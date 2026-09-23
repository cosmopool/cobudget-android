package com.kaiodelphino.cobudget.data

import android.content.Context
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
    /** StatusBarNotification.postTime, epoch millis. */
    val postedAt: Long,
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
abstract class CoBudgetDao {

    @Query("SELECT * FROM captured_notifications ORDER BY postedAt DESC")
    abstract fun observeNotifications(): Flow<List<CapturedNotification>>

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

    @Insert
    protected abstract suspend fun insert(notification: CapturedNotification): Long

    /**
     * Saves the notification unless it is a re-post: the latest row with the same key has identical
     * content and was posted within [DUPLICATE_WINDOW_MS]. Returns true if saved.
     */
    @Transaction
    open suspend fun insertIfNew(notification: CapturedNotification): Boolean {
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

@Database(entities = [CapturedNotification::class, MonitoredApp::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): CoBudgetDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "cobudget.db").build()
    }
}
