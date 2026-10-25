package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_bookmarks")
data class BookmarkItem(
    @PrimaryKey
    val id: String = "",
    val userId: String = "local_user",
    val title: String = "",
    val url: String = "",
    val folder: String = "General",
    val isShortcut: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "local_history")
data class HistoryItem(
    @PrimaryKey
    val id: String = "",
    val userId: String = "local_user",
    val title: String = "",
    val url: String = "",
    val visitedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "local_tabs")
data class SyncedTabItem(
    @PrimaryKey
    val id: String = "",
    val userId: String = "local_user",
    val title: String = "New Tab",
    val url: String = "nyxora://home",
    val groupName: String = "Default",
    val isActive: Boolean = false,
    val desktopMode: Boolean = false,
    val textZoom: Int = 100,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "local_site_settings")
data class SiteSettingItem(
    val id: String = "",
    val userId: String = "local_user",
    @PrimaryKey
    val host: String = "",
    val allowAds: Boolean = false,
    val allowPopups: Boolean = false,
    val allowRedirects: Boolean = false,
    val desktopMode: Boolean = false,
    val textZoom: Int = 100,
    val cameraPermission: String = "ASK",
    val micPermission: String = "ASK",
    val locationPermission: String = "ASK",
    val notificationPermission: String = "ASK"
)

@Entity(tableName = "downloads")
data class DownloadRecord(
    @PrimaryKey val id: String,
    val fileName: String,
    val url: String,
    val mimeType: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val speedBytesPerSec: Long,
    val status: String, // RUNNING, PAUSED, COMPLETED, FAILED, CANCELED
    val localPath: String,
    val isRisky: Boolean,
    val createdAtMillis: Long
)

@Entity(tableName = "custom_filter_rules")
data class CustomFilterRule(
    @PrimaryKey val pattern: String,
    val isAllowRule: Boolean = false,
    val sourceList: String = "NYXORA Built-in",
    val addedAtMillis: Long = System.currentTimeMillis()
)

data class ExportedBookmark(
    val title: String,
    val url: String,
    val folder: String,
    val isShortcut: Boolean
)

data class ExportedSiteSetting(
    val host: String,
    val allowAds: Boolean,
    val allowPopups: Boolean,
    val allowRedirects: Boolean,
    val desktopMode: Boolean,
    val textZoom: Int,
    val cameraPermission: String,
    val micPermission: String,
    val locationPermission: String,
    val notificationPermission: String
)

data class NyxoraBackupPayload(
    val version: Int = 1,
    val exportedAtMillis: Long,
    val defaultSearchEngine: String,
    val themeMode: String,
    val adBlockingEnabled: Boolean,
    val popupBlockingEnabled: Boolean,
    val redirectBlockingEnabled: Boolean,
    val httpsOnlyMode: Boolean,
    val dataSaverEnabled: Boolean,
    val forceDarkWeb: Boolean,
    val hideCookieBanners: Boolean,
    val bookmarks: List<ExportedBookmark>,
    val siteSettings: List<ExportedSiteSetting>
)
