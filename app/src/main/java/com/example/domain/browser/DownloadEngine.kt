package com.example.domain.browser

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import com.example.data.local.DownloadDao
import com.example.data.model.DownloadRecord
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Native Android download engine backed by [android.app.DownloadManager] with cookie forwarding,
 * duplicate filename resolution, risky-file detection, and local Room progress tracking.
 */
class DownloadEngine(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val scope: CoroutineScope
) {
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeUrlsToId = ConcurrentHashMap<String, String>()
    private val systemDownloadIds = ConcurrentHashMap<String, Long>()
    private val downloadsDir: File by lazy {
        (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")).apply { if (!exists()) mkdirs() }
    }

    fun enqueueDownload(
        url: String,
        suggestedFileName: String?,
        mimeType: String?,
        notificationsEnabled: Boolean = true
    ): String {
        val cleanUrl = url.trim()
        val existingActiveId = activeUrlsToId[cleanUrl]
        if (existingActiveId != null && activeJobs[existingActiveId]?.isActive == true) {
            return existingActiveId
        }

        val id = UUID.randomUUID().toString()
        val rawName = suggestedFileName?.takeIf { it.isNotBlank() }
            ?: URLUtil.guessFileName(cleanUrl, null, mimeType)
            ?: "download_${System.currentTimeMillis()}.bin"
        val uniqueFile = resolveDuplicateFile(downloadsDir, sanitizeFileName(rawName))
        val effectiveMime = mimeType?.takeIf { it.isNotBlank() } ?: guessMimeFromName(uniqueFile.name)
        val isRisky = UrlAndSearchHandler.isRiskyDownloadFile(uniqueFile.name, effectiveMime)

        val record = DownloadRecord(
            id = id,
            fileName = uniqueFile.name,
            url = cleanUrl,
            mimeType = effectiveMime,
            totalBytes = -1L,
            downloadedBytes = 0L,
            speedBytesPerSec = 0L,
            status = "RUNNING",
            localPath = uniqueFile.absolutePath,
            isRisky = isRisky,
            createdAtMillis = System.currentTimeMillis()
        )

        activeUrlsToId[cleanUrl] = id
        scope.launch(Dispatchers.IO) {
            downloadDao.upsertDownload(record)
            startOrResumeInternal(record, notificationsEnabled)
        }
        return id
    }

    fun pauseDownload(id: String) {
        activeJobs.remove(id)?.cancel()
        systemDownloadIds.remove(id)?.let { sysId ->
            runCatching {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                dm?.remove(sysId)
            }
        }
        scope.launch(Dispatchers.IO) {
            val existing = downloadDao.getDownloadById(id) ?: return@launch
            activeUrlsToId.remove(existing.url, id)
            if (existing.status == "RUNNING") {
                downloadDao.upsertDownload(existing.copy(status = "PAUSED", speedBytesPerSec = 0L))
            }
        }
    }

    fun resumeOrRetryDownload(id: String, notificationsEnabled: Boolean = true) {
        if (activeJobs[id]?.isActive == true) return
        scope.launch(Dispatchers.IO) {
            val existing = downloadDao.getDownloadById(id) ?: return@launch
            startOrResumeInternal(existing, notificationsEnabled)
        }
    }

    fun cancelDownload(id: String) {
        activeJobs.remove(id)?.cancel()
        systemDownloadIds.remove(id)?.let { sysId ->
            runCatching {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                dm?.remove(sysId)
            }
        }
        scope.launch(Dispatchers.IO) {
            val existing = downloadDao.getDownloadById(id) ?: return@launch
            File(existing.localPath).delete()
            downloadDao.upsertDownload(
                existing.copy(status = "CANCELED", downloadedBytes = 0L, speedBytesPerSec = 0L)
            )
        }
    }

    fun deleteDownloadRecord(id: String, deleteFile: Boolean = true) {
        activeJobs.remove(id)?.cancel()
        systemDownloadIds.remove(id)?.let { sysId ->
            runCatching {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                dm?.remove(sysId)
            }
        }
        scope.launch(Dispatchers.IO) {
            val existing = downloadDao.getDownloadById(id)
            if (deleteFile && existing != null) {
                File(existing.localPath).delete()
            }
            downloadDao.deleteDownload(id)
        }
    }

    private fun startOrResumeInternal(
        initialRecord: DownloadRecord,
        notificationsEnabled: Boolean = true
    ) {
        if (activeJobs[initialRecord.id]?.isActive == true) return
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val targetFile = File(initialRecord.localPath)
            targetFile.parentFile?.mkdirs()
            val existingBytes = if (targetFile.exists() && initialRecord.status == "PAUSED") targetFile.length() else 0L

            var currentRecord = initialRecord.copy(
                status = "RUNNING",
                downloadedBytes = existingBytes,
                speedBytesPerSec = 0L
            )
            downloadDao.upsertDownload(currentRecord)

            try {
                // First try Android System DownloadManager when starting a fresh HTTP/HTTPS download
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                val canUseSystemDownloadManager = dm != null &&
                    existingBytes == 0L &&
                    (initialRecord.url.startsWith("http://", ignoreCase = true) ||
                        initialRecord.url.startsWith("https://", ignoreCase = true))

                if (canUseSystemDownloadManager) {
                    val enqueued = tryEnqueueWithSystemDownloadManager(dm, currentRecord, targetFile, notificationsEnabled)
                    if (enqueued != null) {
                        systemDownloadIds[initialRecord.id] = enqueued
                        monitorSystemDownload(dm, enqueued, currentRecord)
                        return@launch
                    }
                }

                // Fallback / Range-resume via standard Android HttpURLConnection
                downloadWithHttpUrlConnection(currentRecord, targetFile, existingBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                downloadDao.upsertDownload(
                    currentRecord.copy(status = "FAILED", speedBytesPerSec = 0L)
                )
            } finally {
                activeJobs.remove(initialRecord.id)
                activeUrlsToId.remove(initialRecord.url, initialRecord.id)
                systemDownloadIds.remove(initialRecord.id)
            }
        }
        activeJobs[initialRecord.id] = job
        activeUrlsToId[initialRecord.url] = initialRecord.id
        job.start()
    }

    private fun tryEnqueueWithSystemDownloadManager(
        dm: DownloadManager,
        record: DownloadRecord,
        targetFile: File,
        notificationsEnabled: Boolean
    ): Long? {
        return runCatching {
            val uri = Uri.parse(record.url)
            val notifVisibility = if (notificationsEnabled) {
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            } else {
                DownloadManager.Request.VISIBILITY_VISIBLE
            }
            val request = DownloadManager.Request(uri)
                .setTitle(record.fileName)
                .setDescription("Downloading via NYXORA")
                .setMimeType(record.mimeType)
                .setNotificationVisibility(notifVisibility)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            runCatching {
                val cookies = CookieManager.getInstance().getCookie(record.url)
                if (!cookies.isNullOrBlank()) {
                    request.addRequestHeader("Cookie", cookies)
                }
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            request.setDestinationUri(Uri.fromFile(targetFile))
            val downloadId = dm.enqueue(request)
            if (downloadId <= 0L) null else downloadId
        }.getOrNull()
    }

    private suspend fun monitorSystemDownload(
        dm: DownloadManager,
        systemDownloadId: Long,
        initialRecord: DownloadRecord
    ) {
        var currentRecord = initialRecord
        var lastBytes = 0L
        var lastTime = System.currentTimeMillis()

        while (currentCoroutineContext().isActive) {
            val query = DownloadManager.Query().setFilterById(systemDownloadId)
            val cursor: Cursor? = runCatching { dm.query(query) }.getOrNull()
            if (cursor == null) {
                downloadDao.upsertDownload(currentRecord.copy(status = "FAILED", speedBytesPerSec = 0L))
                return
            }
            cursor.use { c ->
                if (!c.moveToFirst()) {
                    downloadDao.upsertDownload(currentRecord.copy(status = "CANCELED", speedBytesPerSec = 0L))
                    return
                }
                val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val soFarIdx = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                val status = if (statusIdx >= 0) c.getInt(statusIdx) else DownloadManager.STATUS_RUNNING
                val bytesSoFar = if (soFarIdx >= 0) c.getLong(soFarIdx).coerceAtLeast(0L) else 0L
                val totalBytes = if (totalIdx >= 0) c.getLong(totalIdx) else -1L

                val now = System.currentTimeMillis()
                val elapsedMs = (now - lastTime).coerceAtLeast(1L)
                val speed = if (bytesSoFar > lastBytes) {
                    ((bytesSoFar - lastBytes) * 1000L) / elapsedMs
                } else {
                    0L
                }
                lastBytes = bytesSoFar
                lastTime = now

                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val finalTotal = if (totalBytes > 0) totalBytes else bytesSoFar
                        downloadDao.upsertDownload(
                            currentRecord.copy(
                                downloadedBytes = finalTotal,
                                totalBytes = finalTotal,
                                speedBytesPerSec = 0L,
                                status = "COMPLETED"
                            )
                        )
                        return
                    }
                    DownloadManager.STATUS_FAILED -> {
                        downloadDao.upsertDownload(
                            currentRecord.copy(status = "FAILED", speedBytesPerSec = 0L)
                        )
                        return
                    }
                    DownloadManager.STATUS_PAUSED -> {
                        currentRecord = currentRecord.copy(
                            downloadedBytes = bytesSoFar,
                            totalBytes = totalBytes,
                            speedBytesPerSec = 0L,
                            status = "PAUSED"
                        )
                        downloadDao.upsertDownload(currentRecord)
                    }
                    else -> {
                        currentRecord = currentRecord.copy(
                            downloadedBytes = bytesSoFar,
                            totalBytes = totalBytes,
                            speedBytesPerSec = speed,
                            status = "RUNNING"
                        )
                        downloadDao.upsertDownload(currentRecord)
                    }
                }
            }
            delay(350L)
        }
    }

    private suspend fun downloadWithHttpUrlConnection(
        initialRecord: DownloadRecord,
        targetFile: File,
        existingBytes: Long
    ) {
        var currentRecord = initialRecord
        val conn = (URL(initialRecord.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (existingBytes > 0L) {
                setRequestProperty("Range", "bytes=$existingBytes-")
            }
            runCatching {
                val cookies = CookieManager.getInstance().getCookie(initialRecord.url)
                if (!cookies.isNullOrBlank()) {
                    setRequestProperty("Cookie", cookies)
                }
            }
        }

        try {
            val code = conn.responseCode
            if (code !in 200..299 && code != HttpURLConnection.HTTP_PARTIAL) {
                downloadDao.upsertDownload(
                    currentRecord.copy(status = "FAILED", speedBytesPerSec = 0L)
                )
                return
            }

            val isPartial = code == HttpURLConnection.HTTP_PARTIAL
            val appendMode = isPartial && existingBytes > 0L
            val startOffset = if (appendMode) existingBytes else 0L
            val contentLength = conn.contentLengthLong
            val totalBytes = if (contentLength > 0) startOffset + contentLength else -1L

            currentRecord = currentRecord.copy(
                totalBytes = totalBytes,
                downloadedBytes = startOffset
            )
            downloadDao.upsertDownload(currentRecord)

            var bytesReadTotal = startOffset
            var windowBytes = 0L
            var lastReportTime = System.currentTimeMillis()

            conn.inputStream.use { input ->
                FileOutputStream(targetFile, appendMode).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (currentCoroutineContext().isActive) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        bytesReadTotal += read
                        windowBytes += read

                        val now = System.currentTimeMillis()
                        val elapsedMs = now - lastReportTime
                        if (elapsedMs >= 350L) {
                            val speed = (windowBytes * 1000L) / elapsedMs.coerceAtLeast(1L)
                            currentRecord = currentRecord.copy(
                                downloadedBytes = bytesReadTotal,
                                speedBytesPerSec = speed,
                                status = "RUNNING"
                            )
                            downloadDao.upsertDownload(currentRecord)
                            windowBytes = 0L
                            lastReportTime = now
                        }
                    }
                    output.flush()
                }
            }

            if (currentCoroutineContext().isActive) {
                downloadDao.upsertDownload(
                    currentRecord.copy(
                        downloadedBytes = bytesReadTotal,
                        totalBytes = if (totalBytes > 0) totalBytes else bytesReadTotal,
                        speedBytesPerSec = 0L,
                        status = "COMPLETED"
                    )
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    fun buildOpenFileIntent(record: DownloadRecord): Intent? {
        if (record.localPath.isBlank()) return null
        val file = File(record.localPath)
        if (!file.exists()) return null
        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }.getOrNull() ?: return null
        val resolvedMime = resolveEffectiveMimeType(file.name, record.mimeType)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, resolvedMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildShareFileIntent(record: DownloadRecord): Intent? {
        if (record.localPath.isBlank()) return null
        val file = File(record.localPath)
        if (!file.exists()) return null
        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }.getOrNull() ?: return null
        val resolvedMime = resolveEffectiveMimeType(file.name, record.mimeType)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = resolvedMime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(shareIntent, "Share ${record.fileName}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun resolveEffectiveMimeType(fileName: String, rawMimeType: String): String {
        val cleaned = rawMimeType.substringBefore(';').trim().lowercase()
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext == "apk") {
            return "application/vnd.android.package-archive"
        }
        if (cleaned.isNotBlank() && cleaned != "*/*" && cleaned != "application/octet-stream") {
            return cleaned
        }
        if (ext.isNotEmpty()) {
            val fromMap = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            if (!fromMap.isNullOrBlank()) return fromMap
        }
        return cleaned.ifBlank { "*/*" }
    }

    companion object {
        fun sanitizeFileName(raw: String): String {
            return raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "download.bin" }
        }

        fun resolveDuplicateFile(dir: File, fileName: String): File {
            var candidate = File(dir, fileName)
            if (!candidate.exists()) return candidate
            val baseName = fileName.substringBeforeLast('.', fileName)
            val ext = fileName.substringAfterLast('.', "")
            var index = 1
            while (candidate.exists()) {
                val numbered = if (ext.isNotEmpty()) "$baseName ($index).$ext" else "$baseName ($index)"
                candidate = File(dir, numbered)
                index++
            }
            return candidate
        }

        fun guessMimeFromName(name: String): String {
            return when (name.substringAfterLast('.', "").lowercase()) {
                "pdf" -> "application/pdf"
                "html", "htm" -> "text/html"
                "txt", "md", "log", "json" -> "text/plain"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                "mp4" -> "video/mp4"
                "mp3" -> "audio/mpeg"
                "apk" -> "application/vnd.android.package-archive"
                else -> "application/octet-stream"
            }
        }
    }
}
