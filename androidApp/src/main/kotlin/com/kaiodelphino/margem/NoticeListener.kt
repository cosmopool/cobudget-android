package com.kaiodelphino.margem

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "NoticeListener"
private const val OWN_PACKAGE = "com.kaiodelphino.margem"

/**
 * Anotação automática (D25): every notification posted on the device reaches here once the person
 * grants notification access; [rawNotice] parses it and `capture()` keeps the ones from sources.
 */
class NoticeListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db get() = (application as MargemApp).db

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val raw = rawNotice(sbn, appLabel(sbn.packageName)) ?: return
        scope.launch {
            runCatching {
                val saved = capture(db, raw)
                if (BuildConfig.DEBUG && saved) Log.d(TAG, "notice from ${raw.packageName}; pending=${db.pendingCount()}")
            }.onFailure { Log.e(TAG, "capture failed for ${sbn.packageName}", it) }
        }
    }

    /**
     * Catch-up: whatever was posted while disconnected (access toggled off, OEM battery killer, before
     * the first unlock) is lost unless it's still in the shade. The dedupe in `capture()` skips the
     * posts already saved.
     */
    override fun onListenerConnected() {
        scope.launch {
            val active = runCatching { activeNotifications }.onFailure { Log.e(TAG, "can't read active notifications", it) }.getOrNull().orEmpty()
            for (sbn in active.sortedBy { it.postTime }) {
                val raw = rawNotice(sbn, appLabel(sbn.packageName)) ?: continue
                runCatching { capture(db, raw) }.onFailure { Log.e(TAG, "catch-up failed for ${sbn.packageName}", it) }
            }
        }
    }

    // The system can unbind the listener (e.g. after an app update); ask to be bound again.
    override fun onListenerDisconnected() = requestRebind(ComponentName(this, NoticeListener::class.java))

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}

/**
 * The capture boundary: a StatusBarNotification as a [RawNotice], or null for noise — our own
 * package, group summaries, foreground services, ongoing notifications (media, downloads) and
 * notifications without any text. Texts are trimmed; extras become JSON.
 */
fun rawNotice(sbn: StatusBarNotification, appLabel: String): RawNotice? {
    if (sbn.packageName == OWN_PACKAGE) return null
    val n = sbn.notification
    if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
    if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return null
    if (sbn.isOngoing) return null

    val extras = n.extras
    fun textOf(key: String) = extras.getCharSequence(key)?.toString()?.trim().orEmpty()
    val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
    val raw = RawNotice(
        packageName = sbn.packageName,
        appLabel = appLabel,
        key = sbn.key,
        postedAt = sbn.postTime,
        whenMs = n.`when`,
        title = textOf(Notification.EXTRA_TITLE_BIG).ifEmpty { textOf(Notification.EXTRA_TITLE) },
        text = textOf(Notification.EXTRA_TEXT),
        bigText = textOf(Notification.EXTRA_BIG_TEXT),
        subText = textOf(Notification.EXTRA_SUB_TEXT),
        textLines = lines.joinToString("\n"),
        category = n.category.orEmpty(),
        channelId = n.channelId.orEmpty(),
        extrasJson = bundleToJson(extras).toString(),
    )
    if (raw.title.isEmpty() && raw.text.isEmpty() && raw.bigText.isEmpty() && raw.subText.isEmpty() && raw.textLines.isEmpty()) return null
    return raw
}

/** Text, primitives and nested bundles (e.g. MessagingStyle messages); bitmaps and the like are skipped. */
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
    is Array<*> -> JSONArray().apply { value.forEach { item -> item?.let(::toJsonValue)?.let(::put) } }.takeIf { it.length() > 0 }
    else -> null
}

/** Notification access is on (glossary: otherwise, Anotação pausada). */
fun isAccessGranted(context: Context): Boolean = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

/** Opens this app's toggle directly where supported, otherwise the list of listeners. */
fun accessSettingsIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context, NoticeListener::class.java).flattenToString(),
        )
    } else {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
