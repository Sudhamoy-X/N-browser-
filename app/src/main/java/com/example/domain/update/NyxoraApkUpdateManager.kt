package com.example.domain.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Metadata for a verified, stable GitHub Release containing a real downloadable `.apk` asset.
 */
data class GitHubReleaseApkInfo(
    val tagName: String,
    val cleanVersion: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val publishedAt: String,
    val assetName: String,
    val downloadUrl: String,
    val assetSizeBytes: Long,
    val expectedSha256Hex: String? = null,
    val releaseHtmlUrl: String = ""
)

/**
 * Structured outcome of querying and parsing the GitHub `/releases/latest` endpoint.
 */
sealed class ReleaseCheckResult {
    data class UpdateAvailable(
        val release: GitHubReleaseApkInfo,
        val installedVersion: String
    ) : ReleaseCheckResult()

    data class UpToDate(
        val installedVersion: String,
        val latestVersion: String,
        val reason: String = "Installed version is up to date"
    ) : ReleaseCheckResult()

    data class NoValidReleaseOrApkAsset(
        val installedVersion: String,
        val reason: String
    ) : ReleaseCheckResult()

    data class Failure(
        val errorMessage: String
    ) : ReleaseCheckResult()
}

/**
 * Extracted archive metadata from a downloaded `.apk` file before handing off to Android's PackageInstaller.
 */
data class ParsedApkArchiveMetadata(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signingCertSha256Fingerprints: Set<String> = emptySet()
)

/**
 * Abstraction for inspecting an `.apk` archive file and installed app signature, enabling deterministic JVM/Robolectric tests.
 */
interface ApkArchiveInspector {
    fun inspectApkArchive(context: Context, apkFile: File): ParsedApkArchiveMetadata?
    fun getInstalledSigningCertSha256Fingerprints(context: Context): Set<String>
    fun canRequestPackageInstalls(context: Context): Boolean
}

object AndroidPackageManagerInspector : ApkArchiveInspector {
    override fun inspectApkArchive(context: Context, apkFile: File): ParsedApkArchiveMetadata? {
        return runCatching {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val info: PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(flags.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            }
            val pkgInfo = info ?: return null
            val pkgName = pkgInfo.packageName?.trim().orEmpty()
            if (pkgName.isBlank()) return null
            val vName = pkgInfo.versionName?.trim().orEmpty().ifBlank { "1.0" }
            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            }
            val certHashes = extractCertificateSha256Set(pkgInfo)
            ParsedApkArchiveMetadata(
                packageName = pkgName,
                versionName = vName,
                versionCode = vCode,
                signingCertSha256Fingerprints = certHashes
            )
        }.getOrNull()
    }

    override fun getInstalledSigningCertSha256Fingerprints(context: Context): Set<String> {
        return runCatching {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(flags.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, flags)
            }
            extractCertificateSha256Set(pkgInfo)
        }.getOrDefault(emptySet())
    }

    override fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(true)
        } else {
            true
        }
    }

    private fun extractCertificateSha256Set(packageInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo
            if (signingInfo != null) {
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        } ?: return emptySet()

        return signatures.mapNotNull { sig ->
            val bytes = sig?.toByteArray() ?: return@mapNotNull null
            if (bytes.isEmpty()) return@mapNotNull null
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            digest.joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}

/**
 * Result of verifying a downloaded `.apk` file prior to installation.
 */
sealed class ApkVerificationResult {
    data class Valid(
        val apkFile: File,
        val sha256Hex: String,
        val archiveMetadata: ParsedApkArchiveMetadata
    ) : ApkVerificationResult()

    data class Invalid(
        val reason: String
    ) : ApkVerificationResult()
}

/**
 * Result of initiating the official Android Package Installer or unknown-sources permission flow.
 */
sealed class InstallHandoffResult {
    data class InstallerLaunched(val intent: Intent, val contentUri: Uri) : InstallHandoffResult()
    data class PermissionSettingsLaunched(val settingsIntent: Intent) : InstallHandoffResult()
    data class Failed(val errorMessage: String) : InstallHandoffResult()
}

/**
 * Reactive UI state for the Automatic APK Update System.
 */
sealed class ApkUpdateUiState {
    data object Idle : ApkUpdateUiState()
    data object Checking : ApkUpdateUiState()

    data class UpToDate(
        val installedVersion: String,
        val lastCheckedMillis: Long = System.currentTimeMillis(),
        val statusNote: String = "NYXORA Browser is up to date"
    ) : ApkUpdateUiState()

    data class UpdateAvailable(
        val release: GitHubReleaseApkInfo,
        val installedVersion: String
    ) : ApkUpdateUiState()

    data class Downloading(
        val release: GitHubReleaseApkInfo,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progressPercent: Int
    ) : ApkUpdateUiState()

    data class Verifying(
        val release: GitHubReleaseApkInfo
    ) : ApkUpdateUiState()

    data class ReadyToInstall(
        val release: GitHubReleaseApkInfo,
        val apkFile: File,
        val sha256Hex: String
    ) : ApkUpdateUiState()

    data class PermissionRequired(
        val release: GitHubReleaseApkInfo,
        val apkFile: File,
        val sha256Hex: String
    ) : ApkUpdateUiState()

    data class Error(
        val message: String,
        val recoverableRelease: GitHubReleaseApkInfo? = null,
        val verifiedApkFile: File? = null
    ) : ApkUpdateUiState()

    /**
     * True whenever a verified newer release has been detected or is in the download/install pipeline.
     * Used to display the compact update indicator badge in the browser menu and Settings > About NYXORA.
     */
    val hasUpdateIndicator: Boolean
        get() = when (this) {
            is UpdateAvailable,
            is Downloading,
            is Verifying,
            is ReadyToInstall,
            is PermissionRequired -> true
            is Error -> recoverableRelease != null
            is Idle,
            is Checking,
            is UpToDate -> false
        }

    val activeReleaseInfo: GitHubReleaseApkInfo?
        get() = when (this) {
            is UpdateAvailable -> release
            is Downloading -> release
            is Verifying -> release
            is ReadyToInstall -> release
            is PermissionRequired -> release
            is Error -> recoverableRelease
            else -> null
        }
}

/**
 * Complete Automatic APK Update Manager for NYXORA Browser:
 * 1. Automatic & manual version checks against GitHub Releases (`/releases/latest`).
 * 2. Strict filtering: ignores drafts, prereleases, and releases without a real `.apk` asset.
 * 3. Direct in-app streaming APK download with real-time progress and interruption recovery.
 * 4. Pre-install APK verification (ZIP magic header, SHA-256 checksum, `PackageManager` archive parsing,
 *    exact `applicationId` match, version compatibility, and signing certificate consistency).
 * 5. Official Android Package Installer handoff via `FileProvider` and `REQUEST_INSTALL_PACKAGES` permission handling.
 */
class NyxoraApkUpdateManager(
    context: Context,
    private val scope: CoroutineScope,
    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private var httpClient: OkHttpClient = defaultUpdateHttpClient(),
    private var archiveInspector: ApkArchiveInspector = AndroidPackageManagerInspector,
    private var releaseApiUrlProvider: () -> String = { configuredReleaseApiEndpoint() },
    private var installedVersionNameProvider: () -> String = { BuildConfig.VERSION_NAME },
    private var installedVersionCodeProvider: () -> Int = { BuildConfig.VERSION_CODE },
    private var allowHttpForLocalTesting: Boolean = false
) {
    private val appContext: Context = context.applicationContext

    private val _uiState = MutableStateFlow<ApkUpdateUiState>(ApkUpdateUiState.Idle)
    val uiState: StateFlow<ApkUpdateUiState> = _uiState.asStateFlow()

    @Volatile
    private var lastCheckTimestampMillis: Long = 0L

    @Volatile
    private var configGeneration: Int = 0

    private var activeCheckJob: Job? = null
    private var activeDownloadJob: Job? = null

    val installedVersionName: String
        get() = installedVersionNameProvider().trim().ifBlank { "1.0" }

    val installedVersionCode: Int
        get() = installedVersionCodeProvider().coerceAtLeast(1)

    internal fun configureForTesting(
        httpClient: OkHttpClient = this.httpClient,
        archiveInspector: ApkArchiveInspector = this.archiveInspector,
        releaseApiUrlProvider: () -> String = this.releaseApiUrlProvider,
        installedVersionNameProvider: () -> String = this.installedVersionNameProvider,
        installedVersionCodeProvider: () -> Int = this.installedVersionCodeProvider,
        ioDispatcher: CoroutineDispatcher = this.ioDispatcher,
        allowHttpForLocalTesting: Boolean = true
    ) {
        configGeneration += 1
        activeCheckJob?.cancel()
        activeDownloadJob?.cancel()
        this.ioDispatcher = ioDispatcher
        this.httpClient = httpClient
        this.archiveInspector = archiveInspector
        this.releaseApiUrlProvider = releaseApiUrlProvider
        this.installedVersionNameProvider = installedVersionNameProvider
        this.installedVersionCodeProvider = installedVersionCodeProvider
        this.allowHttpForLocalTesting = allowHttpForLocalTesting
        this.lastCheckTimestampMillis = 0L
        this._uiState.value = ApkUpdateUiState.Idle
    }

    /**
     * Checks GitHub Releases for a newer stable APK version.
     * Safe to invoke on browser startup and when opening Settings > About NYXORA.
     */
    fun checkForUpdates(force: Boolean = false) {
        val currentState = _uiState.value
        if (currentState is ApkUpdateUiState.Downloading || currentState is ApkUpdateUiState.Verifying) {
            return
        }
        if (!force &&
            (currentState is ApkUpdateUiState.ReadyToInstall || currentState is ApkUpdateUiState.PermissionRequired)
        ) {
            return
        }
        val now = System.currentTimeMillis()
        if (!force &&
            lastCheckTimestampMillis > 0L &&
            (now - lastCheckTimestampMillis) < MIN_AUTO_CHECK_INTERVAL_MS &&
            (currentState is ApkUpdateUiState.UpToDate || currentState is ApkUpdateUiState.UpdateAvailable)
        ) {
            return
        }

        val myGeneration = configGeneration
        activeCheckJob?.cancel()
        activeCheckJob = scope.launch(ioDispatcher) {
            if (!isActive || myGeneration != configGeneration) return@launch
            _uiState.value = ApkUpdateUiState.Checking
            val endpoint = releaseApiUrlProvider().trim()
            if (endpoint.isBlank() ||
                (!endpoint.startsWith("https://", ignoreCase = true) &&
                    !(allowHttpForLocalTesting && endpoint.startsWith("http://", ignoreCase = true)))
            ) {
                if (isActive && myGeneration == configGeneration) {
                    _uiState.value = ApkUpdateUiState.Error(
                        message = "Invalid release check endpoint configuration."
                    )
                }
                return@launch
            }

            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "NYXORA-Browser-Android/$installedVersionName")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!isActive || myGeneration != configGeneration) return@use
                    val checkedAt = System.currentTimeMillis()
                    lastCheckTimestampMillis = checkedAt

                    if (response.code == 404) {
                        // Repository has no published stable releases yet — never claim an update is available
                        _uiState.value = ApkUpdateUiState.UpToDate(
                            installedVersion = installedVersionName,
                            lastCheckedMillis = checkedAt,
                            statusNote = "NYXORA Browser v$installedVersionName is the latest available version"
                        )
                        return@use
                    }

                    if (!response.isSuccessful) {
                        _uiState.value = ApkUpdateUiState.Error(
                            message = "Unable to check for updates (HTTP ${response.code}). Please try again later."
                        )
                        return@use
                    }

                    val bodyText = response.body?.string().orEmpty()
                    val parsed = parseLatestReleaseResponse(
                        json = bodyText,
                        installedVersionName = installedVersionName,
                        installedVersionCode = installedVersionCode,
                        supportedAbis = Build.SUPPORTED_ABIS.toList(),
                        allowHttpForLocalTesting = allowHttpForLocalTesting
                    )

                    when (parsed) {
                        is ReleaseCheckResult.UpdateAvailable -> {
                            // Check if this release APK was already downloaded and verified in cache
                            val cachedFile = getTargetApkFile(parsed.release)
                            if (cachedFile.exists() && cachedFile.length() > 0L) {
                                val verification = verifyDownloadedApk(
                                    context = appContext,
                                    apkFile = cachedFile,
                                    expectedPackageName = appContext.packageName,
                                    installedVersionCode = installedVersionCode,
                                    installedVersionName = installedVersionName,
                                    expectedSha256Hex = parsed.release.expectedSha256Hex,
                                    archiveInspector = archiveInspector
                                )
                                if (verification is ApkVerificationResult.Valid) {
                                    _uiState.value = ApkUpdateUiState.ReadyToInstall(
                                        release = parsed.release,
                                        apkFile = verification.apkFile,
                                        sha256Hex = verification.sha256Hex
                                    )
                                    return@use
                                } else {
                                    cachedFile.delete()
                                }
                            }
                            _uiState.value = ApkUpdateUiState.UpdateAvailable(
                                release = parsed.release,
                                installedVersion = parsed.installedVersion
                            )
                        }
                        is ReleaseCheckResult.UpToDate -> {
                            _uiState.value = ApkUpdateUiState.UpToDate(
                                installedVersion = parsed.installedVersion,
                                lastCheckedMillis = checkedAt,
                                statusNote = "NYXORA Browser v${parsed.installedVersion} is up to date"
                            )
                        }
                        is ReleaseCheckResult.NoValidReleaseOrApkAsset -> {
                            // Do not claim a release is available until a real APK asset is published
                            _uiState.value = ApkUpdateUiState.UpToDate(
                                installedVersion = parsed.installedVersion,
                                lastCheckedMillis = checkedAt,
                                statusNote = "NYXORA Browser v${parsed.installedVersion} is up to date"
                            )
                        }
                        is ReleaseCheckResult.Failure -> {
                            _uiState.value = ApkUpdateUiState.Error(
                                message = parsed.errorMessage
                            )
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (isActive && myGeneration == configGeneration) {
                    _uiState.value = ApkUpdateUiState.Error(
                        message = "Update check failed: ${e.localizedMessage ?: "Network unavailable"}",
                        recoverableRelease = currentState.activeReleaseInfo
                    )
                }
            }
        }
    }

    /**
     * Downloads the latest compatible `.apk` directly inside the app without opening any browser page,
     * tracks progress in real time, and verifies the APK archive before enabling installation.
     */
    fun downloadUpdateNow(explicitRelease: GitHubReleaseApkInfo? = null) {
        val currentState = _uiState.value
        if (currentState is ApkUpdateUiState.Downloading || currentState is ApkUpdateUiState.Verifying) {
            return
        }
        val targetRelease = explicitRelease ?: currentState.activeReleaseInfo ?: return

        activeDownloadJob?.cancel()
        activeDownloadJob = scope.launch(ioDispatcher) {
            val updatesDir = getUpdatesDirectory(appContext)
            val partFile = File(updatesDir, "nyxora-update-${sanitizeVersionToken(targetRelease.cleanVersion)}.apk.part")
            val finalApkFile = getTargetApkFile(targetRelease)

            try {
                if (partFile.exists()) {
                    partFile.delete()
                }
                _uiState.value = ApkUpdateUiState.Downloading(
                    release = targetRelease,
                    bytesDownloaded = 0L,
                    totalBytes = targetRelease.assetSizeBytes.coerceAtLeast(0L),
                    progressPercent = 0
                )

                val request = Request.Builder()
                    .url(targetRelease.downloadUrl)
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "NYXORA-Browser-Android/$installedVersionName")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        partFile.delete()
                        _uiState.value = ApkUpdateUiState.Error(
                            message = "APK download failed (HTTP ${response.code}). Tap Update Now to retry.",
                            recoverableRelease = targetRelease
                        )
                        return@use
                    }

                    val body = response.body
                    if (body == null) {
                        partFile.delete()
                        _uiState.value = ApkUpdateUiState.Error(
                            message = "Empty response received while downloading update APK.",
                            recoverableRelease = targetRelease
                        )
                        return@use
                    }

                    val contentLength = body.contentLength()
                    val expectedTotalBytes = when {
                        contentLength > 0L -> contentLength
                        targetRelease.assetSizeBytes > 0L -> targetRelease.assetSizeBytes
                        else -> -1L
                    }

                    var totalRead = 0L
                    var lastEmittedPercent = -1

                    body.byteStream().use { input ->
                        FileOutputStream(partFile).use { output ->
                            val buffer = ByteArray(DEFAULT_DOWNLOAD_BUFFER_SIZE)
                            while (isActive) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                totalRead += read

                                val percent = if (expectedTotalBytes > 0L) {
                                    ((totalRead * 100L) / expectedTotalBytes).toInt().coerceIn(0, 100)
                                } else {
                                    0
                                }
                                if (percent != lastEmittedPercent || expectedTotalBytes <= 0L) {
                                    lastEmittedPercent = percent
                                    _uiState.value = ApkUpdateUiState.Downloading(
                                        release = targetRelease,
                                        bytesDownloaded = totalRead,
                                        totalBytes = expectedTotalBytes.coerceAtLeast(0L),
                                        progressPercent = percent
                                    )
                                }
                            }
                            output.flush()
                        }
                    }

                    if (!isActive) {
                        partFile.delete()
                        return@use
                    }

                    if (totalRead <= 0L || (expectedTotalBytes > 0L && totalRead < expectedTotalBytes)) {
                        partFile.delete()
                        _uiState.value = ApkUpdateUiState.Error(
                            message = "APK download was interrupted before completion ($totalRead / $expectedTotalBytes bytes).",
                            recoverableRelease = targetRelease
                        )
                        return@use
                    }

                    _uiState.value = ApkUpdateUiState.Verifying(release = targetRelease)

                    if (finalApkFile.exists()) {
                        finalApkFile.delete()
                    }
                    val renamed = partFile.renameTo(finalApkFile)
                    if (!renamed) {
                        partFile.copyTo(finalApkFile, overwrite = true)
                        partFile.delete()
                    }

                    val verification = verifyDownloadedApk(
                        context = appContext,
                        apkFile = finalApkFile,
                        expectedPackageName = appContext.packageName,
                        installedVersionCode = installedVersionCode,
                        installedVersionName = installedVersionName,
                        expectedSha256Hex = targetRelease.expectedSha256Hex,
                        archiveInspector = archiveInspector
                    )

                    when (verification) {
                        is ApkVerificationResult.Valid -> {
                            _uiState.value = ApkUpdateUiState.ReadyToInstall(
                                release = targetRelease,
                                apkFile = verification.apkFile,
                                sha256Hex = verification.sha256Hex
                            )
                        }
                        is ApkVerificationResult.Invalid -> {
                            finalApkFile.delete()
                            _uiState.value = ApkUpdateUiState.Error(
                                message = verification.reason,
                                recoverableRelease = targetRelease
                            )
                        }
                    }
                }
            } catch (ce: CancellationException) {
                partFile.delete()
                throw ce
            } catch (e: Exception) {
                partFile.delete()
                _uiState.value = ApkUpdateUiState.Error(
                    message = "Download interrupted: ${e.localizedMessage ?: "Network connection lost"}. Tap retry to download again.",
                    recoverableRelease = targetRelease
                )
            }
        }
    }

    /**
     * Cancels an in-progress APK download and restores the `UpdateAvailable` state.
     */
    fun cancelActiveDownload() {
        val current = _uiState.value
        val rel = current.activeReleaseInfo
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        if (rel != null) {
            _uiState.value = ApkUpdateUiState.UpdateAvailable(
                release = rel,
                installedVersion = installedVersionName
            )
        } else {
            _uiState.value = ApkUpdateUiState.Idle
        }
    }

    /**
     * Re-checks `canRequestPackageInstalls()` when returning from Android Settings.
     * If permission was granted while in `PermissionRequired`, transitions back to `ReadyToInstall`.
     */
    fun refreshInstallPermissionState(context: Context = appContext): Boolean {
        val allowed = archiveInspector.canRequestPackageInstalls(context)
        val current = _uiState.value
        if (allowed && current is ApkUpdateUiState.PermissionRequired) {
            if (current.apkFile.exists() && current.apkFile.length() > 0L) {
                _uiState.value = ApkUpdateUiState.ReadyToInstall(
                    release = current.release,
                    apkFile = current.apkFile,
                    sha256Hex = current.sha256Hex
                )
            }
        }
        return allowed
    }

    /**
     * Launches Android's official Package Installer for a verified APK, or opens Android's
     * "Install unknown apps" permission screen if the user has not granted it yet.
     * Never attempts silent installation or bypasses Android security restrictions.
     */
    fun initiateInstallOrRequestPermission(context: Context = appContext): InstallHandoffResult {
        val current = _uiState.value
        val release: GitHubReleaseApkInfo
        val apkFile: File
        val sha256Hex: String

        when (current) {
            is ApkUpdateUiState.ReadyToInstall -> {
                release = current.release
                apkFile = current.apkFile
                sha256Hex = current.sha256Hex
            }
            is ApkUpdateUiState.PermissionRequired -> {
                release = current.release
                apkFile = current.apkFile
                sha256Hex = current.sha256Hex
            }
            else -> {
                return InstallHandoffResult.Failed("No verified update APK is ready to install.")
            }
        }

        if (!apkFile.exists() || apkFile.length() <= 0L) {
            _uiState.value = ApkUpdateUiState.Error(
                message = "Downloaded APK file is missing. Please download the update again.",
                recoverableRelease = release
            )
            return InstallHandoffResult.Failed("Downloaded APK file is missing.")
        }

        // Re-verify APK integrity immediately before launching system package installer
        val recheck = verifyDownloadedApk(
            context = context,
            apkFile = apkFile,
            expectedPackageName = context.packageName,
            installedVersionCode = installedVersionCode,
            installedVersionName = installedVersionName,
            expectedSha256Hex = release.expectedSha256Hex,
            archiveInspector = archiveInspector
        )
        if (recheck is ApkVerificationResult.Invalid) {
            apkFile.delete()
            _uiState.value = ApkUpdateUiState.Error(
                message = recheck.reason,
                recoverableRelease = release
            )
            return InstallHandoffResult.Failed(recheck.reason)
        }

        // Check Android O+ "Install unknown apps" permission
        if (!archiveInspector.canRequestPackageInstalls(context)) {
            _uiState.value = ApkUpdateUiState.PermissionRequired(
                release = release,
                apkFile = apkFile,
                sha256Hex = sha256Hex
            )
            val settingsIntent = buildUnknownSourcesSettingsIntent(context)
            return runCatching {
                context.startActivity(settingsIntent)
                InstallHandoffResult.PermissionSettingsLaunched(settingsIntent)
            }.getOrElse { err ->
                val message = "Please enable 'Install unknown apps' for NYXORA Browser in Android Settings (${err.localizedMessage ?: "Settings unavailable"})."
                InstallHandoffResult.Failed(message)
            }
        }

        // Permission is granted — launch Android's official Package Installer via FileProvider URI
        return try {
            val contentUri = resolveApkFileProviderUri(context, apkFile)
            val installerIntent = buildOfficialPackageInstallerIntent(context, contentUri)
            context.startActivity(installerIntent)
            _uiState.value = ApkUpdateUiState.ReadyToInstall(
                release = release,
                apkFile = apkFile,
                sha256Hex = sha256Hex
            )
            InstallHandoffResult.InstallerLaunched(installerIntent, contentUri)
        } catch (e: Exception) {
            val errMsg = "Could not open Android Package Installer: ${e.localizedMessage ?: "Installer error"}"
            _uiState.value = ApkUpdateUiState.Error(
                message = errMsg,
                recoverableRelease = release,
                verifiedApkFile = apkFile
            )
            InstallHandoffResult.Failed(errMsg)
        }
    }

    fun getTargetApkFile(release: GitHubReleaseApkInfo): File {
        val updatesDir = getUpdatesDirectory(appContext)
        val cleanVer = sanitizeVersionToken(release.cleanVersion)
        return File(updatesDir, "nyxora-browser-v$cleanVer.apk")
    }

    companion object {
        const val DEFAULT_GITHUB_REPO = "sudhamoy-nyxora/nyxora-browser"
        const val DEFAULT_GITHUB_RELEASES_LATEST_URL =
            "https://api.github.com/repos/$DEFAULT_GITHUB_REPO/releases/latest"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val MIN_AUTO_CHECK_INTERVAL_MS = 60_000L
        private const val DEFAULT_DOWNLOAD_BUFFER_SIZE = 16 * 1024
        private const val MIN_VALID_APK_BYTES = 64L

        private val PRE_RELEASE_KEYWORDS = listOf(
            "alpha", "beta", "rc", "dev", "snapshot", "nightly", "canary", "preview"
        )
        private val SHA256_HEX_REGEX = Regex("\\b([a-fA-F0-9]{64})\\b")

        fun configuredReleaseApiEndpoint(): String {
            val explicitUrl = runCatching { BuildConfig.NYXORA_GITHUB_RELEASE_API_URL }.getOrDefault("").trim()
            if (explicitUrl.startsWith("https://", ignoreCase = true)) {
                return explicitUrl
            }
            val repoSlug = runCatching { BuildConfig.NYXORA_GITHUB_REPO }.getOrDefault("").trim()
                .removePrefix("https://github.com/")
                .removeSuffix("/")
                .removeSuffix(".git")
                .ifBlank { DEFAULT_GITHUB_REPO }
            return "https://api.github.com/repos/$repoSlug/releases/latest"
        }

        fun defaultUpdateHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        fun getUpdatesDirectory(context: Context): File {
            return File(context.cacheDir, "updates").apply { mkdirs() }
        }

        private fun sanitizeVersionToken(raw: String): String {
            return raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "latest" }
        }

        /**
         * Normalizes a version string or Git tag (e.g., `"v1.2.3"` -> `"1.2.3"`).
         */
        fun normalizeVersionString(rawVersionOrTag: String): String {
            return rawVersionOrTag.trim()
                .removePrefix("refs/tags/")
                .removePrefix("release-")
                .removePrefix("release/")
                .removePrefix("v")
                .removePrefix("V")
                .trim()
        }

        /**
         * Returns true if the version string contains a pre-release qualifier such as `-alpha`, `-beta`, or `-rc`.
         */
        fun isPreReleaseVersionString(rawVersionOrTag: String): Boolean {
            val cleaned = normalizeVersionString(rawVersionOrTag).substringBefore('+').lowercase()
            if ('-' in cleaned) {
                val suffix = cleaned.substringAfter('-')
                if (PRE_RELEASE_KEYWORDS.any { suffix.contains(it) }) {
                    return true
                }
            }
            return PRE_RELEASE_KEYWORDS.any { keyword ->
                Regex("(^|[._-])$keyword([0-9._-]|$)").containsMatchIn(cleaned)
            }
        }

        /**
         * Compares two semantic version strings (e.g. `"1.0"` vs `"1.1.0"`).
         * Returns:
         * - `< 0` when [installedVersion] is older than [candidateVersion]
         * - `0` when both versions are equivalent (e.g. `"1.0"` and `"1.0.0"`)
         * - `> 0` when [installedVersion] is newer than [candidateVersion]
         */
        fun compareVersions(installedVersion: String, candidateVersion: String): Int {
            val v1Parts = parseNumericVersionParts(installedVersion)
            val v2Parts = parseNumericVersionParts(candidateVersion)
            if (v1Parts.isEmpty() && v2Parts.isEmpty()) return 0
            if (v1Parts.isEmpty()) return -1
            if (v2Parts.isEmpty()) return 1

            val maxLen = maxOf(v1Parts.size, v2Parts.size)
            for (i in 0 until maxLen) {
                val a = v1Parts.getOrElse(i) { 0 }
                val b = v2Parts.getOrElse(i) { 0 }
                if (a != b) {
                    return a.compareTo(b)
                }
            }
            return 0
        }

        /**
         * Returns true only if [latestTagOrVersion] is a valid stable version strictly newer than [installedVersion].
         */
        fun isNewerStableVersion(installedVersion: String, latestTagOrVersion: String): Boolean {
            val normalizedLatest = normalizeVersionString(latestTagOrVersion)
            if (normalizedLatest.isBlank()) return false
            if (isPreReleaseVersionString(normalizedLatest)) return false
            val latestParts = parseNumericVersionParts(normalizedLatest)
            if (latestParts.isEmpty()) return false
            return compareVersions(installedVersion, normalizedLatest) < 0
        }

        private fun parseNumericVersionParts(rawVersionOrTag: String): List<Int> {
            val core = normalizeVersionString(rawVersionOrTag)
                .substringBefore('+')
                .substringBefore('-')
                .trim()
            if (core.isBlank()) return emptyList()
            val segments = core.split('.')
            val result = mutableListOf<Int>()
            for (seg in segments) {
                val digitsOnly = seg.takeWhile { it.isDigit() }
                val parsed = digitsOnly.toIntOrNull() ?: return emptyList()
                result.add(parsed)
            }
            return result
        }

        /**
         * Parses a GitHub `/releases/latest` JSON payload, enforces stable release & real `.apk` asset rules,
         * and compares the release version against [installedVersionName].
         */
        fun parseLatestReleaseResponse(
            json: String,
            installedVersionName: String,
            installedVersionCode: Int,
            supportedAbis: List<String> = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"),
            allowHttpForLocalTesting: Boolean = false
        ): ReleaseCheckResult {
            if (json.isBlank()) {
                return ReleaseCheckResult.Failure("Empty release response from server.")
            }
            return try {
                val root = JSONObject(json)
                val isDraft = root.optBoolean("draft", false)
                val isPrerelease = root.optBoolean("prerelease", false)
                if (isDraft || isPrerelease) {
                    return ReleaseCheckResult.NoValidReleaseOrApkAsset(
                        installedVersion = installedVersionName,
                        reason = "Latest release is a draft or pre-release."
                    )
                }

                val rawTag = root.optString("tag_name", "").trim()
                val releaseName = root.optString("name", "").trim().ifBlank { rawTag }
                val versionCandidate = rawTag.ifBlank { releaseName }
                val cleanVersion = normalizeVersionString(versionCandidate)

                if (cleanVersion.isBlank() || isPreReleaseVersionString(versionCandidate)) {
                    return ReleaseCheckResult.NoValidReleaseOrApkAsset(
                        installedVersion = installedVersionName,
                        reason = "Release tag '$versionCandidate' is not a valid stable version."
                    )
                }

                val assetsArray = root.optJSONArray("assets") ?: JSONArray()
                val releaseBody = root.optString("body", "").trim()
                val publishedAt = root.optString("published_at", "").trim()
                val htmlUrl = root.optString("html_url", "").trim()

                val selectedAsset = selectBestApkAsset(
                    assets = assetsArray,
                    releaseBody = releaseBody,
                    supportedAbis = supportedAbis,
                    allowHttpForLocalTesting = allowHttpForLocalTesting
                ) ?: return ReleaseCheckResult.NoValidReleaseOrApkAsset(
                    installedVersion = installedVersionName,
                    reason = "Release $rawTag does not contain a published APK asset."
                )

                if (!isNewerStableVersion(installedVersionName, cleanVersion)) {
                    return ReleaseCheckResult.UpToDate(
                        installedVersion = installedVersionName,
                        latestVersion = cleanVersion
                    )
                }

                ReleaseCheckResult.UpdateAvailable(
                    release = GitHubReleaseApkInfo(
                        tagName = rawTag.ifBlank { "v$cleanVersion" },
                        cleanVersion = cleanVersion,
                        releaseTitle = releaseName.ifBlank { "NYXORA Browser v$cleanVersion" },
                        releaseNotes = releaseBody,
                        publishedAt = publishedAt,
                        assetName = selectedAsset.name,
                        downloadUrl = selectedAsset.downloadUrl,
                        assetSizeBytes = selectedAsset.sizeBytes,
                        expectedSha256Hex = selectedAsset.sha256Hex,
                        releaseHtmlUrl = htmlUrl
                    ),
                    installedVersion = installedVersionName
                )
            } catch (e: Exception) {
                ReleaseCheckResult.Failure("Failed to parse release metadata: ${e.localizedMessage ?: "Invalid JSON"}")
            }
        }

        private data class CandidateApkAsset(
            val name: String,
            val downloadUrl: String,
            val sizeBytes: Long,
            val sha256Hex: String?,
            val score: Int
        )

        private fun selectBestApkAsset(
            assets: JSONArray,
            releaseBody: String,
            supportedAbis: List<String>,
            allowHttpForLocalTesting: Boolean
        ): CandidateApkAsset? {
            val candidates = mutableListOf<CandidateApkAsset>()
            for (i in 0 until assets.length()) {
                val obj = assets.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                val downloadUrl = obj.optString("browser_download_url", "").trim()
                val sizeBytes = obj.optLong("size", 0L)
                val state = obj.optString("state", "uploaded").trim().lowercase()

                if (!name.endsWith(".apk", ignoreCase = true)) continue
                if (sizeBytes <= 0L) continue
                if (state.isNotBlank() && state != "uploaded") continue

                val isValidScheme = downloadUrl.startsWith("https://", ignoreCase = true) ||
                    (allowHttpForLocalTesting && downloadUrl.startsWith("http://", ignoreCase = true))
                if (!isValidScheme) continue

                val lowerName = name.lowercase()
                // Ignore unsigned APKs since Android cannot install unsigned packages
                if (lowerName.contains("unsigned")) continue

                var score = 100
                if (lowerName.contains("universal")) {
                    score += 25
                }
                if (lowerName.contains("release")) {
                    score += 20
                }
                supportedAbis.forEachIndexed { index, abi ->
                    if (lowerName.contains(abi.lowercase())) {
                        score += (50 - index * 10).coerceAtLeast(10)
                    }
                }

                // Extract SHA-256 from GitHub asset `digest` field ("sha256:...") or release notes
                val rawDigest = obj.optString("digest", "").trim()
                val assetDigestHex = if (rawDigest.startsWith("sha256:", ignoreCase = true)) {
                    rawDigest.substringAfter(':').trim().lowercase().takeIf { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' } }
                } else {
                    null
                }
                val resolvedSha256 = assetDigestHex ?: extractSha256FromReleaseNotes(releaseBody, name)

                candidates.add(
                    CandidateApkAsset(
                        name = name,
                        downloadUrl = downloadUrl,
                        sizeBytes = sizeBytes,
                        sha256Hex = resolvedSha256,
                        score = score
                    )
                )
            }
            return candidates.maxByOrNull { it.score }
        }

        internal fun extractSha256FromReleaseNotes(releaseBody: String, assetName: String): String? {
            if (releaseBody.isBlank()) return null
            val lines = releaseBody.lines()
            // First look for a line referencing the specific APK asset name alongside a 64-char hex SHA-256
            for (line in lines) {
                if (line.contains(assetName, ignoreCase = true) || line.contains("sha256", ignoreCase = true) || line.contains("sha-256", ignoreCase = true)) {
                    val match = SHA256_HEX_REGEX.find(line)
                    if (match != null) {
                        return match.groupValues[1].lowercase()
                    }
                }
            }
            return null
        }

        /**
         * Verifies a downloaded `.apk` file before launching Android's Package Installer:
         * 1. Non-empty file size and valid ZIP/APK local file header (`PK\x03\x04`).
         * 2. SHA-256 checksum verification (when provided by the release).
         * 3. Android `PackageManager.getPackageArchiveInfo` verification:
         *    - Confirms valid parseable APK archive
         *    - Confirms `packageName == expectedPackageName` (`com.aistudio.nyxorabrowser.vqxkpl`)
         *    - Confirms `versionCode >= installedVersionCode` (prevents downgrade data loss)
         *    - Confirms signing certificate compatibility when available (prevents signature mismatch data loss)
         */
        fun verifyDownloadedApk(
            context: Context,
            apkFile: File,
            expectedPackageName: String = context.packageName,
            installedVersionCode: Int = BuildConfig.VERSION_CODE,
            installedVersionName: String = BuildConfig.VERSION_NAME,
            expectedSha256Hex: String? = null,
            archiveInspector: ApkArchiveInspector = AndroidPackageManagerInspector
        ): ApkVerificationResult {
            if (!apkFile.exists() || !apkFile.isFile) {
                return ApkVerificationResult.Invalid("Downloaded APK file does not exist.")
            }
            if (apkFile.length() < MIN_VALID_APK_BYTES) {
                return ApkVerificationResult.Invalid("Downloaded APK file is incomplete or empty.")
            }

            if (!hasValidZipHeader(apkFile)) {
                return ApkVerificationResult.Invalid("Downloaded file is not a valid APK package archive.")
            }

            val actualSha256 = computeFileSha256Hex(apkFile)
            val expectedNormalized = expectedSha256Hex?.trim()?.lowercase().orEmpty()
            if (expectedNormalized.isNotEmpty() && !actualSha256.equals(expectedNormalized, ignoreCase = true)) {
                return ApkVerificationResult.Invalid(
                    "APK checksum verification failed (expected ${expectedNormalized.take(12)}…, got ${actualSha256.take(12)}…)."
                )
            }

            val archiveMeta = archiveInspector.inspectApkArchive(context, apkFile)
                ?: return ApkVerificationResult.Invalid("Downloaded APK archive is corrupted or unreadable by Android PackageManager.")

            if (archiveMeta.packageName != expectedPackageName) {
                return ApkVerificationResult.Invalid(
                    "APK package ID mismatch: expected '$expectedPackageName', found '${archiveMeta.packageName}'."
                )
            }

            if (archiveMeta.versionCode < installedVersionCode.toLong()) {
                return ApkVerificationResult.Invalid(
                    "APK version code (${archiveMeta.versionCode}) is lower than installed version code ($installedVersionCode)."
                )
            }

            if (compareVersions(installedVersionName, archiveMeta.versionName) > 0) {
                return ApkVerificationResult.Invalid(
                    "APK version (${archiveMeta.versionName}) is older than installed version ($installedVersionName)."
                )
            }

            val installedCerts = archiveInspector.getInstalledSigningCertSha256Fingerprints(context)
            val archiveCerts = archiveMeta.signingCertSha256Fingerprints
            if (installedCerts.isNotEmpty() && archiveCerts.isNotEmpty() && installedCerts.intersect(archiveCerts).isEmpty()) {
                return ApkVerificationResult.Invalid(
                    "APK signing certificate does not match the installed NYXORA Browser signature. Update blocked to protect your bookmarks, history, and tabs."
                )
            }

            return ApkVerificationResult.Valid(
                apkFile = apkFile,
                sha256Hex = actualSha256,
                archiveMetadata = archiveMeta
            )
        }

        internal fun hasValidZipHeader(file: File): Boolean {
            return runCatching {
                FileInputStream(file).use { fis ->
                    val magic = ByteArray(4)
                    val read = fis.read(magic)
                    read == 4 &&
                        magic[0] == 0x50.toByte() && // 'P'
                        magic[1] == 0x4B.toByte() && // 'K'
                        magic[2] == 0x03.toByte() &&
                        magic[3] == 0x04.toByte()
                }
            }.getOrDefault(false)
        }

        fun computeFileSha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = fis.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun buildUnknownSourcesSettingsIntent(context: Context): Intent {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }

        @Suppress("DEPRECATION")
        fun buildOfficialPackageInstallerIntent(context: Context, apkContentUri: Uri): Intent {
            return Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkContentUri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
            }
        }

        internal fun resolveApkFileProviderUri(context: Context, apkFile: File): Uri {
            val authority = "${context.packageName}.fileprovider"
            return try {
                FileProvider.getUriForFile(context, authority, apkFile)
            } catch (iae: IllegalArgumentException) {
                // If FileProvider's static sCache holds a PathStrategy from an earlier context dir, reset and retry
                runCatching {
                    for (field in FileProvider::class.java.declaredFields) {
                        if (Map::class.java.isAssignableFrom(field.type)) {
                            field.isAccessible = true
                            val map = field.get(null) as? MutableMap<*, *>
                            map?.clear()
                        }
                    }
                }
                FileProvider.getUriForFile(context, authority, apkFile)
            }
        }
    }
}
