package com.kaiodelphino.cobudget.capture

import android.app.Notification
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaiodelphino.cobudget.CobudgetApp
import com.kaiodelphino.cobudget.data.AppDatabase
import com.kaiodelphino.cobudget.ui.settings.SettingsViewModel
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Capture pipeline + outbox flush against a real local HTTP server standing in for genda. */
@RunWith(RobolectricTestRunner::class)
class GendaTest {

    private val app = ApplicationProvider.getApplicationContext<CobudgetApp>()
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()

    /** Every request genda received: decoded msgpack map plus the Authorization header. */
    private val received = CopyOnWriteArrayList<Pair<Map<String, String>, String?>>()
    @Volatile private var status = 200

    /** Fake genda: a bare HTTP/1.1 server (the JDK HttpServer is not on the Android test classpath). */
    private val genda = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).also { server ->
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching {
                server.accept().use { socket ->
                    // ISO-8859-1 maps bytes 1:1 to chars, so the binary body survives the text reader.
                    val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val request = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    fun header(name: String) = request.firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()
                    assertEquals("POST /ingest HTTP/1.1", request.first())
                    assertEquals("application/msgpack", header("Content-Type"))
                    val body = buildString { repeat(header("Content-Length")!!.toInt()) { append(reader.read().toChar()) } }
                    received += decode(body.toByteArray(Charsets.ISO_8859_1)) to header("Authorization")
                    socket.getOutputStream().write("HTTP/1.1 $status X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
        }
    }
    private val gendaUrl = "http://127.0.0.1:${genda.localPort}/"

    @After
    fun tearDown() {
        genda.close()
        db.close()
    }

    /** What the listener does for each post: capture, then flush the outbox. */
    private fun post(pkg: String = "com.chat", configure: Notification.Builder.() -> Unit = {}): StatusBarNotification = runBlocking {
        val notification = Notification.Builder(app, "chats")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Ana")
            .setContentText("Lunch tomorrow?")
            .apply(configure)
            .build()
        @Suppress("DEPRECATION") // the only public constructor; fine for tests
        val sbn = StatusBarNotification(pkg, pkg, 1, null, Process.myUid(), 0, 0, notification, Process.myUserHandle(), 1_700_000_000_123)
        assertFalse(NotificationCaptureService.capture(db.dao(), sbn, ownPackage = app.packageName, context = app))
        NotificationCaptureService.flushGenda(app, db.dao())
        sbn
    }

    private fun flush() = runBlocking { NotificationCaptureService.flushGenda(app, db.dao()) }

    private fun decode(body: ByteArray): Map<String, String> {
        val input = DataInputStream(body.inputStream())
        val header = input.readUnsignedByte()
        assertEquals("fixmap", 0x80, header and 0xf0)
        fun str(): String {
            val b = input.readUnsignedByte()
            val size = when {
                b and 0xe0 == 0xa0 -> b and 0x1f
                b == 0xd9 -> input.readUnsignedByte()
                b == 0xda -> input.readUnsignedShort()
                b == 0xdb -> input.readInt()
                else -> error("not a msgpack str: $b")
            }
            return String(ByteArray(size).also(input::readFully))
        }
        val map = (1..(header and 0x0f)).associate { str() to str() }
        assertEquals("trailing bytes", -1, input.read())
        return map
    }

    @Test
    fun `posts a notification from an unmonitored app to genda as a msgpack map`() {
        // Pasted from the phone keyboard: stray spaces must not break the URL or the token.
        SettingsViewModel(app).setGenda(" $gendaUrl ", " secret ")
        val sbn = post { setStyle(Notification.BigTextStyle().bigText("Lunch tomorrow? At noon")) }

        val (map, auth) = received.single()
        assertEquals("Bearer secret", auth)
        assertEquals(setOf("source", "app", "title", "text", "time", "ext_id"), map.keys)
        assertEquals("notif", map["source"])
        assertEquals("com.chat", map["app"]) // not installed here, so the label falls back to the package
        assertEquals("Ana", map["title"])
        assertEquals("Lunch tomorrow? At noon", map["text"])
        assertEquals("2023-11-14T22:13:20Z", map["time"])
        assertTrue(map["ext_id"]!!.matches(Regex(Regex.escape(sbn.key) + "\\|[0-9a-f]{64}")))

        flush()
        assertEquals("delivered posts leave the outbox", 1, received.size)
    }

    @Test
    fun `noise is not sent`() {
        SettingsViewModel(app).setGenda(gendaUrl, "")
        post { setGroup("g").setGroupSummary(true) }
        post { setOngoing(true) }
        post { setContentTitle(null).setContentText(null) }
        post(pkg = app.packageName)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `a failed post stays queued and goes out on a later flush`() {
        SettingsViewModel(app).setGenda(gendaUrl, "")
        status = 500
        post()
        assertEquals(1, received.size)

        status = 200
        flush()
        assertEquals(2, received.size)
        assertEquals(received[0].first, received[1].first)
        assertEquals("no Authorization header without a token", null, received[1].second)

        flush()
        assertEquals(2, received.size)
    }

    @Test
    fun `a post genda rejects as malformed is dropped`() {
        SettingsViewModel(app).setGenda(gendaUrl, "")
        status = 400
        post()
        status = 200
        flush()
        assertEquals(1, received.size)
    }

    @Test
    fun `nothing is queued or sent while the URL is empty`() {
        post()
        SettingsViewModel(app).setGenda(gendaUrl, "")
        flush()
        assertTrue(received.isEmpty())
    }
}
