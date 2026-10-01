package com.kaiodelphino.cobudget.capture

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.kaiodelphino.cobudget.CobudgetApp
import com.kaiodelphino.cobudget.data.CapturedNotification
import com.kaiodelphino.cobudget.data.CobudgetDao
import com.kaiodelphino.cobudget.data.GendaPost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant

/**
 * Receives every notification posted on the device once the user grants Notification access,
 * and saves the ones coming from apps the user chose to monitor. Every non-noise post, from any
 * app, is also queued for genda when its URL is set in Settings.
 */
class NotificationCaptureService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val dao = (application as CobudgetApp).db.dao()
        scope.launch {
            runCatching {
                capture(dao, sbn, ownPackage = packageName, context = application)
                flushGenda(application, dao)
            }.onFailure { Log.e(TAG, "Failed to capture notification from ${sbn.packageName}", it) }
        }
    }

    /**
     * Catch-up: anything posted while we were disconnected (access toggled off, OEM battery killer,
     * before first unlock) is lost unless it's still in the shade. Feed what's there through the
     * normal pipeline; posts we already saved are skipped by the dedupe in insertIfNew.
     */
    override fun onListenerConnected() {
        val dao = (application as CobudgetApp).db.dao()
        scope.launch {
            val active = runCatching { activeNotifications }
                .onFailure { Log.e(TAG, "Failed to read active notifications", it) }
                .getOrNull().orEmpty()
            for (sbn in active.sortedBy { it.postTime }) {
                runCatching { capture(dao, sbn, ownPackage = packageName, context = application) }
                    .onFailure { Log.e(TAG, "Failed to catch up notification from ${sbn.packageName}", it) }
            }
            runCatching { flushGenda(application, dao) }.onFailure { Log.e(TAG, "Failed to flush genda outbox", it) }
        }
    }

    override fun onListenerDisconnected() {
        // The system can unbind us (e.g. after an app update); ask to be bound again.
        requestRebind(ComponentName(this, NotificationCaptureService::class.java))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NotificationCapture"

        /** SharedPreferences file holding genda's "url" and "token". An empty url turns sending off. */
        const val GENDA_PREFS = "genda"

        private val gendaLock = Mutex()

        /**
         * The whole capture pipeline: filter noise, extract the text and extras, save it unless the
         * app is not monitored or it's a re-post, then queue it for genda (any app; needs [context]).
         * Returns true if a row was saved.
         */
        suspend fun capture(dao: CobudgetDao, sbn: StatusBarNotification, ownPackage: String, context: Context? = null): Boolean {
            if (sbn.packageName == ownPackage) return false

            // Group summaries and ongoing notifications (media, downloads, foreground services) are noise.
            val n = sbn.notification
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
            if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
            if (sbn.isOngoing) return false

            val extras = n.extras
            fun textOf(key: String) = extras.getCharSequence(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val title = textOf(Notification.EXTRA_TITLE_BIG) ?: textOf(Notification.EXTRA_TITLE)
            val text = textOf(Notification.EXTRA_TEXT)
            val bigText = textOf(Notification.EXTRA_BIG_TEXT)
            val subText = textOf(Notification.EXTRA_SUB_TEXT)
            val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString("\n")
            if (title == null && text == null && bigText == null && subText == null && textLines == null) return false

            val digest = MessageDigest.getInstance("SHA-256")
            for (part in listOf(title, text, bigText, subText, textLines)) {
                digest.update((part ?: "").toByteArray())
                digest.update(0)
            }
            val contentHash = digest.digest().joinToString("") { "%02x".format(it) }

            val app = dao.monitoredApp(sbn.packageName)
            val saved = app != null && dao.insertIfNew(
                CapturedNotification(
                    packageName = sbn.packageName,
                    appLabel = app.label,
                    notificationKey = sbn.key,
                    postedAt = sbn.postTime,
                    notificationWhen = n.`when`,
                    title = title,
                    text = text,
                    bigText = bigText,
                    subText = subText,
                    textLines = textLines,
                    category = n.category,
                    channelId = n.channelId,
                    extrasJson = bundleToJson(extras).toString(),
                    contentHash = contentHash,
                )
            )

            if (context != null && context.getSharedPreferences(GENDA_PREFS, Context.MODE_PRIVATE).getString("url", "")!!.isNotBlank()) {
                val pm = context.packageManager
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }
                    .getOrDefault(sbn.packageName)
                var extId = "${sbn.key}|$contentHash"
                if (extId.toByteArray().size > 128) {
                    extId = MessageDigest.getInstance("SHA-256").digest(extId.toByteArray()).joinToString("") { "%02x".format(it) }
                }
                val body = ByteArrayOutputStream()
                DataOutputStream(body).apply {
                    writeByte(0x86) // msgpack fixmap of 6 entries; every key and value is a str32
                    for (s in listOf(
                        "source", "notif",
                        "app", label,
                        "title", title ?: "",
                        "text", bigText ?: textLines ?: text ?: subText ?: "",
                        "time", Instant.ofEpochSecond(sbn.postTime / 1000).toString(),
                        "ext_id", extId,
                    )) {
                        val bytes = s.toByteArray()
                        writeByte(0xdb)
                        writeInt(bytes.size)
                        write(bytes)
                    }
                }
                dao.enqueueGenda(GendaPost(body = body.toByteArray()))
            }
            return saved
        }

        /**
         * POSTs queued posts to genda oldest-first. 2xx and 400 (never succeeds) delete the row;
         * anything else (401, 5xx, network error) keeps it and stops until the next flush.
         */
        suspend fun flushGenda(context: Context, dao: CobudgetDao) {
            gendaLock.withLock {
                val prefs = context.getSharedPreferences(GENDA_PREFS, Context.MODE_PRIVATE)
                val url = prefs.getString("url", "")!!.trim().trimEnd('/')
                val token = prefs.getString("token", "")!!.trim()
                if (url.isEmpty()) return
                while (true) {
                    val post = dao.oldestGenda() ?: return
                    val code = runCatching {
                        val conn = URL("$url/ingest").openConnection() as HttpURLConnection
                        try {
                            conn.connectTimeout = 10_000
                            conn.readTimeout = 30_000 // genda may wait up to 30 s on its classifier
                            conn.requestMethod = "POST"
                            conn.doOutput = true
                            conn.setRequestProperty("Content-Type", "application/msgpack")
                            if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
                            conn.outputStream.use { it.write(post.body) }
                            conn.responseCode
                        } finally {
                            conn.disconnect()
                        }
                    }.getOrElse { Log.w(TAG, "genda unreachable", it); return }
                    if (code == 400) Log.w(TAG, "genda rejected a post (400), dropping it")
                    else if (code !in 200..299) return
                    dao.deleteGenda(post.id)
                }
            }
        }

        /** Serializes text, primitives and nested bundles (e.g. MessagingStyle messages); skips bitmaps etc. */
        private fun bundleToJson(bundle: Bundle): JSONObject {
            val json = JSONObject()
            for (key in bundle.keySet()) {
                @Suppress("DEPRECATION")
                val value = runCatching { bundle.get(key) }.getOrNull() ?: continue
                toJsonValue(value)?.let { json.put(key, it) }
            }
            return json
        }

        private fun toJsonValue(value: Any): Any? = when (value) {
            is CharSequence -> value.toString()
            is Boolean, is Int, is Long, is Double, is Float -> value
            is Bundle -> bundleToJson(value)
            is Array<*> -> JSONArray().apply { value.forEach { item -> item?.let(::toJsonValue)?.let(::put) } }
                .takeIf { it.length() > 0 }
            else -> null
        }

        fun isAccessGranted(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        /** Opens the toggle for this app directly where supported, otherwise the listener list. */
        fun accessSettingsIntent(context: Context): Intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, NotificationCaptureService::class.java).flattenToString(),
                )
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
    }
}
