package com.kaiodelphino.cobudget.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Re-posts of the same notification usually arrive seconds apart; real repeat purchases rarely do. */
const val DUPLICATE_WINDOW_MS = 2 * 60 * 1000L

/**
 * One notification posted by a monitored app, as it was received.
 * This is the raw input the finance layer will parse spending from.
 */
@Entity(
    tableName = "captured_notifications",
    indices = [Index("packageName"), Index("postedAt"), Index("notificationKey")],
)
data class CapturedNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    /** StatusBarNotification.key — stable across re-posts of the same notification. */
    val notificationKey: String,
    /** StatusBarNotification.postTime, epoch millis. Changes on every re-post. */
    val postedAt: Long,
    /**
     * Notification.when, epoch millis: the event time the app set. Many apps keep it across
     * re-posts (e.g. after a reboot), so it identifies the same message long after [postedAt] moved on.
     * 0 for rows saved before this column existed.
     */
    @ColumnInfo(defaultValue = "0") val notificationWhen: Long,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    /** InboxStyle lines joined with '\n'. */
    val textLines: String?,
    val category: String?,
    val channelId: String?,
    /** JSON object of every serializable extra, for parsers that need fields we didn't anticipate. */
    val extrasJson: String,
    /** Hash of the visible text fields, used for dedupe. */
    val contentHash: String,
    /** True after the user taps dismiss; kept in the table but hidden from the list. */
    @ColumnInfo(defaultValue = "0") val dismissed: Boolean = false,
) {
    /** The most complete human-readable body available. */
    val body: String?
        get() = bigText ?: textLines ?: text
}

/** An app the user chose to monitor. Only notifications from these packages are saved. */
@Entity(tableName = "monitored_apps")
data class MonitoredApp(
    @PrimaryKey val packageName: String,
    val label: String,
    val addedAt: Long,
)

@Dao
abstract class CobudgetDao {

    @Query("SELECT * FROM captured_notifications WHERE dismissed = 0 ORDER BY postedAt DESC")
    abstract fun observeNotifications(): Flow<List<CapturedNotification>>

    @Query("UPDATE captured_notifications SET dismissed = 1 WHERE id = :id")
    abstract suspend fun dismiss(id: Long)

    @Query("SELECT * FROM monitored_apps ORDER BY label COLLATE NOCASE")
    abstract fun observeMonitoredApps(): Flow<List<MonitoredApp>>

    @Query("SELECT * FROM monitored_apps WHERE packageName = :packageName")
    abstract suspend fun monitoredApp(packageName: String): MonitoredApp?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertMonitoredApp(app: MonitoredApp)

    @Query("DELETE FROM monitored_apps WHERE packageName = :packageName")
    abstract suspend fun deleteMonitoredApp(packageName: String)

    @Query("SELECT * FROM captured_notifications WHERE notificationKey = :key ORDER BY postedAt DESC LIMIT 1")
    protected abstract suspend fun latestForKey(key: String): CapturedNotification?

    @Query(
        "SELECT EXISTS(SELECT 1 FROM captured_notifications " +
            "WHERE notificationKey = :key AND contentHash = :contentHash AND notificationWhen = :notificationWhen)"
    )
    protected abstract suspend fun existsWithWhen(key: String, contentHash: String, notificationWhen: Long): Boolean

    @Insert
    protected abstract suspend fun insert(notification: CapturedNotification): Long

    /**
     * Saves the notification unless it is a re-post. Returns true if saved. It's a re-post when:
     * - a row has the same key, content and `when`, no matter how much later this arrives
     *   (apps re-post old notifications after a reboot or a new message, keeping `when`); or
     * - the latest row with the same key has identical content and was posted within
     *   [DUPLICATE_WINDOW_MS] (for apps that reset `when` on every post).
     *
     * This also covers the catch-up on reconnect: a post we already saved comes back unchanged.
     */
    @Transaction
    open suspend fun insertIfNew(notification: CapturedNotification): Boolean {
        // when = 0 means "not set" (or a legacy row); matching on it would merge unrelated posts forever.
        if (notification.notificationWhen != 0L &&
            existsWithWhen(notification.notificationKey, notification.contentHash, notification.notificationWhen)
        ) {
            return false
        }
        val latest = latestForKey(notification.notificationKey)
        if (latest != null &&
            latest.contentHash == notification.contentHash &&
            kotlin.math.abs(notification.postedAt - latest.postedAt) < DUPLICATE_WINDOW_MS
        ) {
            return false
        }
        insert(notification)
        return true
    }
}

@Database(
    entities = [CapturedNotification::class, MonitoredApp::class],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): CobudgetDao

    companion object {
        const val DB_NAME = "cobudget.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME).build()
    }
}
