package com.example.domain.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reliable website favicon retrieval and local disk + memory caching manager.
 * - Persists favicons in `context.filesDir/favicons` so homepage shortcuts display real website icons offline.
 * - Avoids repeatedly downloading icons that are already cached and fresh.
 * - Refreshes missing or outdated icons (> 7 days old) when network is available.
 * - Captures live favicons directly from WebView's `onReceivedIcon` callback.
 */
object FaviconCacheManager {

    private const val FAVICON_DIR_NAME = "favicons"
    private const val CACHE_FRESHNESS_TTL_MS = 7L * 24L * 60L * 60L * 1000L // 7 days
    private const val NEGATIVE_LOOKUP_COOLDOWN_MS = 10L * 60L * 1000L // 10 minutes

    private val memoryCache = object : LruCache<String, Bitmap>(64) {}
    private val hostFileNameCache = ConcurrentHashMap<String, String>(64)
    private val failedFetchTimestamps = HashMap<String, Long>()
    private val fetchMutex = Mutex()

    private val _cacheUpdateTick = MutableStateFlow(0L)
    val cacheUpdateTick: StateFlow<Long> = _cacheUpdateTick.asStateFlow()

    fun normalizeHostKey(urlOrHost: String): String {
        val extracted = UrlAndSearchHandler.extractHost(urlOrHost)
            .ifBlank { urlOrHost.trim().lowercase() }
        return extracted
            .lowercase()
            .removePrefix("www.")
            .removePrefix("m.")
            .removePrefix("mobile.")
            .trim()
    }

    private fun hostToFileName(hostKey: String): String {
        hostFileNameCache[hostKey]?.let { return it }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(hostKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
        val fileName = "fav_$digest.png"
        if (hostFileNameCache.size < 512) {
            hostFileNameCache[hostKey] = fileName
        }
        return fileName
    }

    fun getFaviconFile(context: Context, urlOrHost: String): File? {
        val hostKey = normalizeHostKey(urlOrHost)
        if (hostKey.isBlank() || hostKey == "nyxora.home" || !hostKey.contains('.')) return null
        val dir = File(context.applicationContext.filesDir, FAVICON_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, hostToFileName(hostKey))
    }

    /**
     * Synchronously returns a cached favicon from memory or local disk if available.
     * Used on the homepage so cached icons appear immediately even when completely offline.
     */
    fun getCachedFavicon(context: Context, urlOrHost: String): Bitmap? {
        val hostKey = normalizeHostKey(urlOrHost)
        if (hostKey.isBlank() || hostKey == "nyxora.home" || !hostKey.contains('.')) return null

        synchronized(memoryCache) {
            memoryCache.get(hostKey)?.let { cached ->
                if (!cached.isRecycled) return cached
            }
        }

        val file = getFaviconFile(context, hostKey) ?: return null
        if (!file.exists() || file.length() <= 0L) return null

        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath)?.takeIf { it.width >= 4 && it.height >= 4 }?.also { bmp ->
                synchronized(memoryCache) {
                    memoryCache.put(hostKey, bmp)
                }
            }
        }.getOrNull()
    }

    /**
     * Checks whether the cached favicon on disk is missing or older than [CACHE_FRESHNESS_TTL_MS].
     */
    fun isFaviconMissingOrOutdated(context: Context, urlOrHost: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val file = getFaviconFile(context, urlOrHost) ?: return false
        if (!file.exists() || file.length() <= 0L) return true
        val ageMs = nowMillis - file.lastModified()
        return ageMs > CACHE_FRESHNESS_TTL_MS
    }

    /**
     * Saves a valid website favicon bitmap to both memory cache and persistent local storage.
     */
    fun saveFaviconForUrl(context: Context, pageUrl: String, bitmap: Bitmap?): Boolean {
        if (bitmap == null || bitmap.isRecycled || bitmap.width < 4 || bitmap.height < 4) return false
        val hostKey = normalizeHostKey(pageUrl)
        if (hostKey.isBlank() || hostKey == "nyxora.home" || !hostKey.contains('.')) return false

        val scaled = if (bitmap.width > 128 || bitmap.height > 128) {
            runCatching { Bitmap.createScaledBitmap(bitmap, 96, 96, true) }.getOrDefault(bitmap)
        } else {
            bitmap
        }

        synchronized(memoryCache) {
            memoryCache.put(hostKey, scaled)
            failedFetchTimestamps.remove(hostKey)
        }

        val file = getFaviconFile(context, hostKey) ?: return false
        val written = runCatching {
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(tempFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
            if (file.exists()) file.delete()
            tempFile.renameTo(file)
        }.getOrDefault(false)

        if (written) {
            _cacheUpdateTick.value = System.currentTimeMillis()
        }
        return written
    }

    /**
     * Retrieves a website favicon from cache, or fetches and caches it if missing or outdated.
     * Always returns the existing cached icon if offline or if a refresh fails.
     */
    suspend fun getOrFetchFavicon(
        context: Context,
        pageUrl: String,
        forceRefresh: Boolean = false
    ): Bitmap? = withContext(Dispatchers.IO) {
        val hostKey = normalizeHostKey(pageUrl)
        if (hostKey.isBlank() || hostKey == "nyxora.home" || !hostKey.contains('.')) return@withContext null

        val existing = getCachedFavicon(context, hostKey)
        val needsRefresh = forceRefresh || isFaviconMissingOrOutdated(context, hostKey)
        if (existing != null && !needsRefresh) {
            return@withContext existing
        }

        val now = System.currentTimeMillis()
        synchronized(memoryCache) {
            val lastFailed = failedFetchTimestamps[hostKey] ?: 0L
            if (!forceRefresh && now - lastFailed < NEGATIVE_LOOKUP_COOLDOWN_MS) {
                return@withContext existing
            }
        }

        fetchMutex.withLock {
            // Re-check inside lock to avoid duplicate concurrent downloads for the same domain
            val rechecked = getCachedFavicon(context, hostKey)
            if (rechecked != null && !forceRefresh && !isFaviconMissingOrOutdated(context, hostKey)) {
                return@withLock rechecked
            }

            val candidateUrls = listOf(
                "https://$hostKey/apple-touch-icon.png",
                "https://$hostKey/favicon.ico",
                "https://www.$hostKey/favicon.ico"
            )

            for (candidate in candidateUrls) {
                val downloaded = downloadFaviconBitmap(candidate)
                if (downloaded != null) {
                    saveFaviconForUrl(context, hostKey, downloaded)
                    return@withLock downloaded
                }
            }

            synchronized(memoryCache) {
                failedFetchTimestamps[hostKey] = System.currentTimeMillis()
            }
            existing
        }
    }

    private fun downloadFaviconBitmap(iconUrl: String): Bitmap? {
        return runCatching {
            val conn = (URL(iconUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 3500
                instanceFollowRedirects = true
                setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/png,image/*,*/*;q=0.8")
            }
            try {
                if (conn.responseCode !in 200..299) return null
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.isEmpty() || bytes.size > 512 * 1024) return null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.takeIf {
                    it.width >= 8 && it.height >= 8
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    fun trimMemory() {
        synchronized(memoryCache) {
            memoryCache.trimToSize(16)
        }
    }

    fun clearMemoryAndDiskCacheForTest(context: Context) {
        synchronized(memoryCache) {
            memoryCache.evictAll()
            failedFetchTimestamps.clear()
        }
        hostFileNameCache.clear()
        runCatching {
            File(context.applicationContext.filesDir, FAVICON_DIR_NAME).deleteRecursively()
        }
        _cacheUpdateTick.value = System.currentTimeMillis()
    }
}
