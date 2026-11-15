package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "private_memories")
data class PrivateMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ownerUid: String,
    val topic: String,
    val content: String,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "task_logs")
data class TaskLogEntity(
    @PrimaryKey val taskId: String,
    val ownerUid: String,
    val commandText: String,
    val stepsSummary: String,
    val status: String,
    val confidenceScore: Float,
    val reversible: Boolean,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "ledger_entries")
data class LedgerEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ownerUid: String,
    val customerName: String,
    val entryType: String, // INCOME, EXPENSE, DUE
    val itemDescription: String,
    val amountBdt: Double,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "birthday_reminders")
data class BirthdayReminderEntity(
    @PrimaryKey val id: String,
    val ownerUid: String,
    val personName: String,
    val birthDay: Int,
    val birthMonth: Int,
    val phoneOrContact: String = "",
    val wishMessage: String,
    val lastWishedYear: Int = 0,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

@Dao
interface PrivateVaultDao {
    @Query("SELECT * FROM private_memories WHERE ownerUid = :uid ORDER BY createdAtEpochMs DESC")
    fun observeMemories(uid: String): Flow<List<PrivateMemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(memory: PrivateMemoryEntity)

    @Query("DELETE FROM private_memories WHERE id = :id AND ownerUid = :uid")
    suspend fun deleteMemory(id: Long, uid: String)

    @Query("DELETE FROM private_memories WHERE ownerUid = :uid")
    suspend fun clearAllMemories(uid: String)

    @Query("DELETE FROM private_memories WHERE id = (SELECT id FROM private_memories WHERE ownerUid = :uid ORDER BY createdAtEpochMs DESC LIMIT 1)")
    suspend fun deleteLatestMemory(uid: String)

    @Query("SELECT * FROM task_logs WHERE ownerUid = :uid ORDER BY createdAtEpochMs DESC")
    fun observeTaskLogs(uid: String): Flow<List<TaskLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTaskLog(taskLog: TaskLogEntity)

    @Query("UPDATE task_logs SET status = 'UNDONE' WHERE taskId = :taskId AND ownerUid = :uid AND reversible = 1")
    suspend fun undoTaskLog(taskId: String, uid: String)

    @Query("SELECT * FROM ledger_entries WHERE ownerUid = :uid ORDER BY createdAtEpochMs DESC")
    fun observeLedgerEntries(uid: String): Flow<List<LedgerEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLedgerEntry(entry: LedgerEntryEntity)

    @Query("DELETE FROM ledger_entries WHERE id = :id AND ownerUid = :uid")
    suspend fun deleteLedgerEntry(id: Long, uid: String)

    @Query("DELETE FROM ledger_entries WHERE id = (SELECT id FROM ledger_entries WHERE ownerUid = :uid ORDER BY createdAtEpochMs DESC LIMIT 1)")
    suspend fun deleteLatestLedgerEntry(uid: String)

    @Query("SELECT * FROM birthday_reminders WHERE ownerUid = :uid ORDER BY birthMonth ASC, birthDay ASC")
    fun observeBirthdays(uid: String): Flow<List<BirthdayReminderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBirthday(entry: BirthdayReminderEntity)

    @Query("UPDATE birthday_reminders SET lastWishedYear = :year WHERE id = :id AND ownerUid = :uid")
    suspend fun updateBirthdayWishedYear(id: String, uid: String, year: Int)

    @Query("DELETE FROM birthday_reminders WHERE id = :id AND ownerUid = :uid")
    suspend fun deleteBirthday(id: String, uid: String)
}

@Database(
    entities = [
        PrivateMemoryEntity::class,
        TaskLogEntity::class,
        LedgerEntryEntity::class,
        BirthdayReminderEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class PrivateVaultDatabase : RoomDatabase() {
    abstract fun privateVaultDao(): PrivateVaultDao

    companion object {
        @Volatile
        private var INSTANCE: PrivateVaultDatabase? = null

        fun getInstance(context: Context): PrivateVaultDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PrivateVaultDatabase::class.java,
                    "hey_shuvo_private_vault.db"
                ).fallbackToDestructiveMigration(true).build().also { INSTANCE = it }
            }
        }
    }
}

class PrivateVaultRepository(private val dao: PrivateVaultDao) {
    fun observeMemories(uid: String): Flow<List<PrivateMemoryEntity>> = dao.observeMemories(uid)
    suspend fun saveMemory(memory: PrivateMemoryEntity) = dao.upsertMemory(memory)
    suspend fun deleteMemory(id: Long, uid: String) = dao.deleteMemory(id, uid)
    suspend fun deleteLatestMemory(uid: String) = dao.deleteLatestMemory(uid)
    suspend fun clearMemories(uid: String) = dao.clearAllMemories(uid)

    fun observeTaskLogs(uid: String): Flow<List<TaskLogEntity>> = dao.observeTaskLogs(uid)
    suspend fun recordTaskLog(taskLog: TaskLogEntity) = dao.upsertTaskLog(taskLog)
    suspend fun undoTask(taskId: String, uid: String) = dao.undoTaskLog(taskId, uid)

    fun observeLedger(uid: String): Flow<List<LedgerEntryEntity>> = dao.observeLedgerEntries(uid)
    suspend fun addLedgerEntry(entry: LedgerEntryEntity) = dao.insertLedgerEntry(entry)
    suspend fun deleteLedgerEntry(id: Long, uid: String) = dao.deleteLedgerEntry(id, uid)
    suspend fun deleteLatestLedgerEntry(uid: String) = dao.deleteLatestLedgerEntry(uid)

    fun observeBirthdays(uid: String): Flow<List<BirthdayReminderEntity>> = dao.observeBirthdays(uid)
    suspend fun upsertBirthday(entry: BirthdayReminderEntity) = dao.upsertBirthday(entry)
    suspend fun markBirthdayWished(id: String, uid: String, year: Int) = dao.updateBirthdayWishedYear(id, uid, year)
    suspend fun deleteBirthday(id: String, uid: String) = dao.deleteBirthday(id, uid)
}
