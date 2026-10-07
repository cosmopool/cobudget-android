package com.kaiodelphino.cobudget.data

import android.content.Context
import android.util.Log
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.kaiodelphino.cobudget.capture.extractorFor
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalTime

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

/**
 * Money read from a notification by its app's extractor (capture/Extract.kt). Rebuilt from
 * captured_notifications by [CobudgetDao.rebuildTransactions]. Named to avoid Room's @Transaction.
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [ForeignKey(CapturedNotification::class, ["id"], ["notificationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("notificationId", unique = true), Index("date")],
)
data class BankTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The purchase's notification, or the refund's for a refund whose purchase we never saw. */
    val notificationId: Long,
    /** "" when the text names no one. */
    val merchant: String,
    /** From the text when it has one, else the notification's time. */
    val date: LocalDate,
    val time: LocalTime,
    /** Centavos: R$ 31,50 = 3150. Never 0 in the table. */
    val cents: Long,
    /** Set when an estorno for this purchase arrived. */
    val refunded: Boolean = false,
)

/** java.time values as ISO text ("2026-10-06", "11:43"), readable and sortable in SQL. */
class Converters {
    @TypeConverter fun dateToText(date: LocalDate): String = date.toString()
    @TypeConverter fun textToDate(text: String): LocalDate = LocalDate.parse(text)
    @TypeConverter fun timeToText(time: LocalTime): String = time.toString()
    @TypeConverter fun textToTime(text: String): LocalTime = LocalTime.parse(text)
}

/** A msgpack body waiting to be POSTed to genda's /ingest. Deleted once genda accepts or rejects it. */
@Entity(tableName = "genda_outbox")
class GendaPost(@PrimaryKey(autoGenerate = true) val id: Long = 0, val body: ByteArray)

@Dao
abstract class CobudgetDao {

    @Insert
    abstract suspend fun enqueueGenda(post: GendaPost)

    @Query("SELECT * FROM genda_outbox ORDER BY id LIMIT 1")
    abstract suspend fun oldestGenda(): GendaPost?

    @Query("DELETE FROM genda_outbox WHERE id = :id")
    abstract suspend fun deleteGenda(id: Long)

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

    @Query("SELECT * FROM transactions ORDER BY date DESC, time DESC, id DESC")
    abstract fun observeTransactions(): Flow<List<BankTransaction>>

    @Insert
    protected abstract suspend fun insertTransaction(transaction: BankTransaction)

    @Query("DELETE FROM transactions")
    protected abstract suspend fun deleteAllTransactions()

    @Query("SELECT * FROM captured_notifications ORDER BY postedAt, id")
    protected abstract suspend fun allNotifications(): List<CapturedNotification>

    @Query("SELECT COUNT(*) FROM transactions")
    protected abstract suspend fun transactionCount(): Int

    @Query("UPDATE transactions SET refunded = 1 WHERE id = :id")
    protected abstract suspend fun markRefunded(id: Long)

    /** The latest unrefunded purchase, up to [postedAt], that a refund of [cents] from [packageName] cancels. */
    @Query(
        "SELECT t.id FROM transactions t JOIN captured_notifications n ON n.id = t.notificationId " +
            "WHERE n.packageName = :packageName AND t.cents = :cents AND t.refunded = 0 AND n.postedAt <= :postedAt " +
            "AND (:merchantKey = '' OR REPLACE(t.merchant, ' ', '') = :merchantKey) " +
            "ORDER BY n.postedAt DESC, n.id DESC LIMIT 1"
    )
    protected abstract suspend fun refundTarget(packageName: String, cents: Long, merchantKey: String, postedAt: Long): Long?

    /**
     * The only code that writes transactions: extract, then insert, or for a refund flag the purchase it
     * cancels (inserting it already refunded when we never saw it). A parser bug only loses the transaction.
     */
    private suspend fun saveTransaction(n: CapturedNotification) {
        val t = runCatching { extractorFor(n.packageName).extract(n) }
            .onFailure { Log.e("CobudgetDao", "extract failed for notification ${n.id}", it) }
            .getOrNull()
        if (t == null || t.cents <= 0) return
        val target = if (t.refunded) refundTarget(n.packageName, t.cents, t.merchant.replace(" ", ""), n.postedAt) else null
        if (target != null) markRefunded(target) else insertTransaction(t)
    }

    /**
     * Re-parses every saved notification (dismissed too) oldest-first, so refunds meet their purchases.
     * Returns the number of transactions. This is the canonical result: capture only differs when a
     * purchase arrives after its refund.
     */
    @Transaction
    open suspend fun rebuildTransactions(): Int {
        deleteAllTransactions()
        for (n in allNotifications()) saveTransaction(n)
        return transactionCount()
    }

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
        val id = insert(notification)
        saveTransaction(notification.copy(id = id))
        return true
    }
}

@Database(
    entities = [CapturedNotification::class, MonitoredApp::class, GendaPost::class, BankTransaction::class],
    version = 5,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
    ],
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): CobudgetDao

    companion object {
        const val DB_NAME = "cobudget.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME).build()
    }
}
