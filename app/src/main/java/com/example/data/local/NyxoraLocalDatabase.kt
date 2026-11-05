package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.BookmarkItem
import com.example.data.model.CustomFilterRule
import com.example.data.model.DownloadRecord
import com.example.data.model.HistoryItem
import com.example.data.model.SiteSettingItem
import com.example.data.model.SyncedTabItem
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM local_bookmarks ORDER BY createdAtMillis DESC")
    fun observeAllBookmarks(): Flow<List<BookmarkItem>>

    @Query("SELECT * FROM local_bookmarks ORDER BY createdAtMillis DESC")
    suspend fun getAllBookmarks(): List<BookmarkItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookmark(bookmark: BookmarkItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookmarks(bookmarks: List<BookmarkItem>)

    @Query("DELETE FROM local_bookmarks WHERE id = :id")
    suspend fun deleteBookmarkById(id: String)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM local_history ORDER BY visitedAt DESC LIMIT 250")
    fun observeHistory(): Flow<List<HistoryItem>>

    @Query("SELECT * FROM local_history ORDER BY visitedAt DESC LIMIT 250")
    suspend fun getAllHistory(): List<HistoryItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(item: HistoryItem)

    @Query("DELETE FROM local_history WHERE id = :id")
    suspend fun deleteHistoryById(id: String)

    @Query("DELETE FROM local_history")
    suspend fun clearAllHistory()
}

@Dao
interface SavedTabDao {
    @Query("SELECT * FROM local_tabs ORDER BY updatedAtMillis ASC")
    fun observeSavedTabs(): Flow<List<SyncedTabItem>>

    @Query("SELECT * FROM local_tabs ORDER BY updatedAtMillis ASC")
    suspend fun getAllSavedTabs(): List<SyncedTabItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSavedTab(tab: SyncedTabItem)

    @Query("DELETE FROM local_tabs WHERE id = :tabId")
    suspend fun deleteSavedTab(tabId: String)

    @Query("DELETE FROM local_tabs")
    suspend fun clearAllSavedTabs()
}

@Dao
interface SiteSettingDao {
    @Query("SELECT * FROM local_site_settings ORDER BY host ASC")
    fun observeAllSiteSettings(): Flow<List<SiteSettingItem>>

    @Query("SELECT * FROM local_site_settings ORDER BY host ASC")
    suspend fun getAllSiteSettings(): List<SiteSettingItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSiteSetting(setting: SiteSettingItem)

    @Query("DELETE FROM local_site_settings WHERE host = :host")
    suspend fun deleteSiteSettingByHost(host: String)

    @Query("DELETE FROM local_site_settings")
    suspend fun clearAllSiteSettings()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAtMillis DESC")
    fun observeAllDownloads(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getDownloadById(id: String): DownloadRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDownload(record: DownloadRecord)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteDownload(id: String)

    @Query("DELETE FROM downloads")
    suspend fun clearDownloads()
}

@Dao
interface FilterRuleDao {
    @Query("SELECT * FROM custom_filter_rules ORDER BY addedAtMillis DESC")
    fun observeAllRules(): Flow<List<CustomFilterRule>>

    @Query("SELECT * FROM custom_filter_rules")
    suspend fun getAllRules(): List<CustomFilterRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRules(rules: List<CustomFilterRule>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: CustomFilterRule)

    @Query("DELETE FROM custom_filter_rules WHERE pattern = :pattern")
    suspend fun deleteRule(pattern: String)

    @Query("DELETE FROM custom_filter_rules WHERE sourceList = :sourceList")
    suspend fun deleteBySourceList(sourceList: String)
}

@Database(
    entities = [
        BookmarkItem::class,
        HistoryItem::class,
        SyncedTabItem::class,
        SiteSettingItem::class,
        DownloadRecord::class,
        CustomFilterRule::class
    ],
    version = 2,
    exportSchema = false
)
abstract class NyxoraLocalDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun savedTabDao(): SavedTabDao
    abstract fun siteSettingDao(): SiteSettingDao
    abstract fun downloadDao(): DownloadDao
    abstract fun filterRuleDao(): FilterRuleDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `downloads` (
                        `id` TEXT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `totalBytes` INTEGER NOT NULL,
                        `downloadedBytes` INTEGER NOT NULL,
                        `speedBytesPerSec` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `localPath` TEXT NOT NULL,
                        `isRisky` INTEGER NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `custom_filter_rules` (
                        `pattern` TEXT NOT NULL,
                        `isAllowRule` INTEGER NOT NULL,
                        `sourceList` TEXT NOT NULL,
                        `addedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`pattern`)
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var INSTANCE: NyxoraLocalDatabase? = null

        fun getInstance(context: Context): NyxoraLocalDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NyxoraLocalDatabase::class.java,
                    "nyxora_local.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
        }

        fun createInMemoryForTest(context: Context): NyxoraLocalDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                NyxoraLocalDatabase::class.java
            ).allowMainThreadQueries().build()
        }
    }
}
