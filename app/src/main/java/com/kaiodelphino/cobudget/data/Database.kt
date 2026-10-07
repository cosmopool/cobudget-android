package com.kaiodelphino.cobudget.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.Embedded
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
import androidx.room.Update
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kaiodelphino.cobudget.capture.suggest
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
    /**
     * True after the user taps dismiss; kept in the table but out of the inbox. A notification is
     * pending until dismissed or processed (a transaction references it), see [CobudgetDao.observePending].
     */
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
 * Money the user accepted from a notification, with at least one tag. The parser's reading of the
 * notification (capture/Extract.kt) only pre-fills it. Named to avoid Room's @Transaction.
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [ForeignKey(CapturedNotification::class, ["id"], ["notificationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("notificationId", unique = true), Index("date"), Index("refundedBy")],
)
data class BankTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The accepted notification: the purchase's, or the estorno's for a refund whose purchase was never accepted. */
    val notificationId: Long,
    /** "" when no one is named. */
    val merchant: String,
    val date: LocalDate,
    val time: LocalTime,
    /** Centavos: R$ 31,50 = 3150. Never 0 in the table. */
    val cents: Long,
    /** The estorno notification that cancelled this purchase; 0 = not refunded. */
    @ColumnInfo(defaultValue = "0") val refundedBy: Long = 0,
    /** True once the user changed the values the parser suggested. */
    @ColumnInfo(defaultValue = "0") val ratified: Boolean = false,
) {
    val refunded: Boolean
        get() = refundedBy != 0L
}

/** A label for transactions; names are unique ignoring case. Lives until deleted in Settings. */
@Entity(tableName = "tags", indices = [Index("name", unique = true)])
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
)

/** Links a transaction to a tag; removed with either side. */
@Entity(
    tableName = "transaction_tags",
    primaryKeys = ["transactionId", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(BankTransaction::class, ["id"], ["transactionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Tag::class, ["id"], ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class TransactionTag(val transactionId: Long, val tagId: Long)

/** One line of the Transactions tab. */
data class TransactionRow(
    @Embedded val transaction: BankTransaction,
    val packageName: String,
    val appLabel: String,
    /** Tag names, sorted. */
    val tags: List<String>,
)

/** java.time values as ISO text ("2026-10-06", "11:43"), readable and sortable in SQL. */
class Converters {
    @TypeConverter fun dateToText(date: LocalDate): String = date.toString()
    @TypeConverter fun textToDate(text: String): LocalDate = LocalDate.parse(text)
    @TypeConverter fun timeToText(time: LocalTime): String = time.toString()
    @TypeConverter fun textToTime(text: String): LocalTime = LocalTime.parse(text)
    /** Tag names from a group_concat(name, char(10)); "" is no tags. */
    @TypeConverter fun textToNames(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split('\n').sorted()
    @TypeConverter fun namesToText(names: List<String>): String = names.joinToString("\n")
}

/**
 * v6: transactions come only from the user accepting a notification, with tags. The ones capture
 * created on its own have no tags, so they are dropped and their notifications are pending again.
 */
@DeleteColumn(tableName = "transactions", columnName = "refunded")
class AcceptedTransactionsOnly : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM transactions")
    }
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

    /** Not dismissed and not processed: no transaction was accepted from it, and it cancelled none. */
    @Query(
        "SELECT * FROM captured_notifications n WHERE dismissed = 0 " +
            "AND NOT EXISTS (SELECT 1 FROM transactions t WHERE t.notificationId = n.id) " +
            "AND NOT EXISTS (SELECT 1 FROM transactions t WHERE t.refundedBy = n.id) " +
            "ORDER BY postedAt DESC"
    )
    abstract fun observePending(): Flow<List<CapturedNotification>>

    @Query("SELECT * FROM captured_notifications WHERE id = :id")
    abstract suspend fun notification(id: Long): CapturedNotification

    @Query(
        "SELECT EXISTS(SELECT 1 FROM transactions WHERE notificationId = :notificationId OR refundedBy = :notificationId)"
    )
    protected abstract suspend fun isProcessed(notificationId: Long): Boolean

    @Query(
        "SELECT t.*, n.packageName, n.appLabel, " +
            "COALESCE((SELECT group_concat(g.name, char(10)) FROM transaction_tags l JOIN tags g ON g.id = l.tagId " +
            "WHERE l.transactionId = t.id), '') AS tags " +
            "FROM transactions t JOIN captured_notifications n ON n.id = t.notificationId " +
            "ORDER BY t.date DESC, t.time DESC, t.id DESC"
    )
    abstract fun observeTransactions(): Flow<List<TransactionRow>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    abstract suspend fun transaction(id: Long): BankTransaction

    @Query("SELECT g.name FROM transaction_tags l JOIN tags g ON g.id = l.tagId WHERE l.transactionId = :id ORDER BY g.name COLLATE NOCASE")
    abstract suspend fun tagNames(id: Long): List<String>

    @Insert
    protected abstract suspend fun insertTransaction(transaction: BankTransaction): Long

    @Update
    protected abstract suspend fun updateTransaction(transaction: BankTransaction)

    /** Deletes it; its notification (and the estorno that cancelled it) become pending again. */
    @Query("DELETE FROM transactions WHERE id = :id")
    abstract suspend fun deleteTransaction(id: Long)

    /** The latest accepted, unrefunded purchase, up to [postedAt], that an estorno of [cents] from [packageName] cancels. 0 = none. */
    @Query(
        "SELECT COALESCE((SELECT t.id FROM transactions t JOIN captured_notifications n ON n.id = t.notificationId " +
            "WHERE n.packageName = :packageName AND t.cents = :cents AND t.refundedBy = 0 AND n.postedAt <= :postedAt " +
            "AND (:merchantKey = '' OR REPLACE(t.merchant, ' ', '') = :merchantKey) " +
            "ORDER BY n.postedAt DESC, n.id DESC LIMIT 1), 0)"
    )
    protected abstract suspend fun refundTarget(packageName: String, cents: Long, merchantKey: String, postedAt: Long): Long

    /** For an estorno notification: the accepted purchase it cancels; 0 when it isn't an estorno or nothing matches. */
    open suspend fun refundMatch(estorno: CapturedNotification): Long {
        val s = suggest(estorno)
        if (!s.refunded || s.cents <= 0) return 0
        return refundTarget(estorno.packageName, s.cents, s.merchant.replace(" ", ""), estorno.postedAt)
    }

    @Query("UPDATE transactions SET refundedBy = :estornoId WHERE id = :transactionId AND refundedBy = 0")
    protected abstract suspend fun setRefundedBy(transactionId: Long, estornoId: Long): Int

    /** Accepts an estorno: marks [transactionId] refunded by it, which also processes the estorno. */
    @Transaction
    open suspend fun acceptRefund(estornoId: Long, transactionId: Long): Boolean {
        if (isProcessed(estornoId)) return false
        return setRefundedBy(transactionId, estornoId) == 1
    }

    /**
     * Turns a pending notification into a transaction. Refuses (returns 0) without a value, without a
     * tag, or when the notification is already processed. Ratified when the values differ from the
     * parser's suggestion; an accepted estorno nobody matched is a refunded purchase on its own.
     */
    @Transaction
    open suspend fun accept(notificationId: Long, merchant: String, cents: Long, date: LocalDate, time: LocalTime, tags: List<String>): Long {
        val names = cleanTags(tags)
        if (cents <= 0 || names.isEmpty() || isProcessed(notificationId)) return 0
        val suggested = suggest(notification(notificationId))
        val edited = BankTransaction(notificationId = notificationId, merchant = merchant.trim(), date = date, time = time, cents = cents)
        val id = insertTransaction(
            edited.copy(
                refundedBy = suggested.refundedBy,
                ratified = !sameValues(edited, suggested),
            )
        )
        linkTags(id, names)
        return id
    }

    /** Edits an accepted transaction in place; same rules as [accept]. Once ratified it stays ratified. */
    @Transaction
    open suspend fun editTransaction(id: Long, merchant: String, cents: Long, date: LocalDate, time: LocalTime, tags: List<String>): Boolean {
        val names = cleanTags(tags)
        if (cents <= 0 || names.isEmpty()) return false
        val old = transaction(id)
        val edited = old.copy(merchant = merchant.trim(), cents = cents, date = date, time = time)
        updateTransaction(edited.copy(ratified = old.ratified || !sameValues(edited, old)))
        deleteLinks(id)
        linkTags(id, names)
        return true
    }

    private fun sameValues(a: BankTransaction, b: BankTransaction) =
        a.merchant == b.merchant && a.cents == b.cents && a.date == b.date && a.time == b.time

    /** Trimmed, non-blank, one per name ignoring case (first spelling wins). */
    private fun cleanTags(names: List<String>) = names.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    abstract fun observeTags(): Flow<List<Tag>>

    /** The column is NOCASE, so this matches any case. 0 = none. */
    @Query("SELECT COALESCE((SELECT id FROM tags WHERE name = :name), 0)")
    protected abstract suspend fun tagId(name: String): Long

    @Insert
    protected abstract suspend fun insertTag(tag: Tag): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertLink(link: TransactionTag)

    @Query("DELETE FROM transaction_tags WHERE transactionId = :transactionId")
    protected abstract suspend fun deleteLinks(transactionId: Long)

    private suspend fun linkTags(transactionId: Long, names: List<String>) {
        for (name in names) {
            val tag = tagId(name).takeIf { it != 0L } ?: insertTag(Tag(name = name))
            insertLink(TransactionTag(transactionId, tag))
        }
    }

    @Query("UPDATE tags SET name = :name WHERE id = :id")
    protected abstract suspend fun updateTagName(id: Long, name: String)

    @Query("INSERT OR IGNORE INTO transaction_tags (transactionId, tagId) SELECT transactionId, :into FROM transaction_tags WHERE tagId = :from")
    protected abstract suspend fun moveLinks(from: Long, into: Long)

    @Query("DELETE FROM tags WHERE id = :id")
    protected abstract suspend fun deleteTagRow(id: Long)

    @Query("DELETE FROM transactions WHERE id NOT IN (SELECT transactionId FROM transaction_tags)")
    protected abstract suspend fun deleteUntagged()

    /** Renames; onto another tag's name (any case) it merges into that tag. False for a blank name. */
    @Transaction
    open suspend fun renameTag(id: Long, name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        val existing = tagId(clean)
        if (existing != 0L && existing != id) {
            moveLinks(from = id, into = existing)
            deleteTagRow(id)
        } else {
            updateTagName(id, clean)
        }
        return true
    }

    /** How many transactions have this as their only tag, i.e. go back to pending if it's deleted. */
    @Query(
        "SELECT COUNT(*) FROM transaction_tags l WHERE l.tagId = :id " +
            "AND (SELECT COUNT(*) FROM transaction_tags o WHERE o.transactionId = l.transactionId) = 1"
    )
    abstract suspend fun soleTagCount(id: Long): Int

    /** Deletes the tag; transactions left without tags are deleted, so their notifications are pending again. */
    @Transaction
    open suspend fun deleteTag(id: Long) {
        deleteTagRow(id)
        deleteUntagged()
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
        insert(notification)
        return true
    }
}

@Database(
    entities = [
        CapturedNotification::class, MonitoredApp::class, GendaPost::class,
        BankTransaction::class, Tag::class, TransactionTag::class,
    ],
    version = 6,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6, spec = AcceptedTransactionsOnly::class),
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
