package com.example.domain.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NyxoraApkUpdateTest {

    private lateinit var context: Context
    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        NyxoraApkUpdateManager.getUpdatesDirectory(context).deleteRecursively()
    }

    @After
    fun tearDown() {
        testScope.cancel()
        NyxoraApkUpdateManager.getUpdatesDirectory(context).deleteRecursively()
    }

    @Test
    fun `version comparison handles semantic versions tags and rejects pre-releases`() {
        // Equivalent versions
        assertEquals(0, NyxoraApkUpdateManager.compareVersions("1.0", "1.0.0"))
        assertEquals(0, NyxoraApkUpdateManager.compareVersions("v1.2.3", "1.2.3"))
        assertFalse(NyxoraApkUpdateManager.isNewerStableVersion("1.0", "v1.0.0"))
        assertFalse(NyxoraApkUpdateManager.isNewerStableVersion("1.2.0", "v1.1.9"))

        // Strictly newer versions
        assertTrue(NyxoraApkUpdateManager.compareVersions("1.0", "1.0.1") < 0)
        assertTrue(NyxoraApkUpdateManager.compareVersions("1.2.0", "1.10.0") < 0)
        assertTrue(NyxoraApkUpdateManager.isNewerStableVersion("1.0", "v1.1.0"))
        assertTrue(NyxoraApkUpdateManager.isNewerStableVersion("1.0", "release-2.0.0"))

        // Pre-release qualifiers must never be treated as stable updates
        assertTrue(NyxoraApkUpdateManager.isPreReleaseVersionString("v1.1.0-alpha.1"))
        assertTrue(NyxoraApkUpdateManager.isPreReleaseVersionString("v1.1.0-beta"))
        assertTrue(NyxoraApkUpdateManager.isPreReleaseVersionString("1.1.0-rc2"))
        assertTrue(NyxoraApkUpdateManager.isPreReleaseVersionString("2.0.0-nightly"))
        assertFalse(NyxoraApkUpdateManager.isNewerStableVersion("1.0", "v1.1.0-beta.1"))
        assertFalse(NyxoraApkUpdateManager.isNewerStableVersion("1.0", "v2.0.0-rc1"))
    }

    @Test
    fun `release parser ignores drafts prereleases and releases without real apk assets`() {
        // 1. Draft release -> NoValidReleaseOrApkAsset
        val draftJson = """
            {
              "tag_name": "v1.5.0",
              "name": "NYXORA Browser v1.5.0",
              "draft": true,
              "prerelease": false,
              "assets": [
                {
                  "name": "nyxora-browser-v1.5.0.apk",
                  "browser_download_url": "https://github.com/sudhamoy-nyxora/nyxora-browser/releases/download/v1.5.0/nyxora-browser-v1.5.0.apk",
                  "size": 10485760,
                  "state": "uploaded"
                }
              ]
            }
        """.trimIndent()
        val draftResult = NyxoraApkUpdateManager.parseLatestReleaseResponse(
            json = draftJson,
            installedVersionName = "1.0",
            installedVersionCode = 1
        )
        assertTrue(draftResult is ReleaseCheckResult.NoValidReleaseOrApkAsset)

        // 2. Prerelease flag -> NoValidReleaseOrApkAsset
        val prereleaseJson = """
            {
              "tag_name": "v1.5.0",
              "name": "NYXORA Browser v1.5.0",
              "draft": false,
              "prerelease": true,
              "assets": [
                {
                  "name": "nyxora-browser-v1.5.0.apk",
                  "browser_download_url": "https://github.com/sudhamoy-nyxora/nyxora-browser/releases/download/v1.5.0/nyxora-browser-v1.5.0.apk",
                  "size": 10485760,
                  "state": "uploaded"
                }
              ]
            }
        """.trimIndent()
        val prereleaseResult = NyxoraApkUpdateManager.parseLatestReleaseResponse(
            json = prereleaseJson,
            installedVersionName = "1.0",
            installedVersionCode = 1
        )
        assertTrue(prereleaseResult is ReleaseCheckResult.NoValidReleaseOrApkAsset)

        // 3. Release with no APK asset (only source zip or unsigned apk) -> NoValidReleaseOrApkAsset
        val noApkJson = """
            {
              "tag_name": "v1.5.0",
              "name": "NYXORA Browser v1.5.0",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "source-code.zip",
                  "browser_download_url": "https://github.com/sudhamoy-nyxora/nyxora-browser/releases/download/v1.5.0/source-code.zip",
                  "size": 204800,
                  "state": "uploaded"
                },
                {
                  "name": "app-release-unsigned.apk",
                  "browser_download_url": "https://github.com/sudhamoy-nyxora/nyxora-browser/releases/download/v1.5.0/app-release-unsigned.apk",
                  "size": 9048576,
                  "state": "uploaded"
                }
              ]
            }
        """.trimIndent()
        val noApkResult = NyxoraApkUpdateManager.parseLatestReleaseResponse(
            json = noApkJson,
            installedVersionName = "1.0",
            installedVersionCode = 1
        )
        assertTrue(noApkResult is ReleaseCheckResult.NoValidReleaseOrApkAsset)

        // 4. Same version installed -> UpToDate
        val sameVersionJson = """
            {
              "tag_name": "v1.0.0",
              "name": "NYXORA Browser v1.0.0",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "nyxora-browser-v1.0.0.apk",
                  "browser_download_url": "https://github.com/sudhamoy-nyxora/nyxora-browser/releases/download/v1.0.0/nyxora-browser-v1.0.0.apk",
                  "size": 10485760,
                  "state": "uploaded"
                }
              ]
            }
        """.trimIndent()
        val upToDateResult = NyxoraApkUpdateManager.parseLatestReleaseResponse(
            json = sameVersionJson,
            installedVersionName = "1.0",
            installedVersionCode = 1
        )
        assertTrue(upToDateResult is ReleaseCheckResult.UpToDate)
    }

    @Test
    fun `no-update behavior when github returns 404 or release without apk never shows update indicator`() {
        var httpCode = 404
        var responseJson = """{"message":"Not Found"}"""

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(httpCode)
                    .message(if (httpCode == 404) "Not Found" else "OK")
                    .body(responseJson.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val manager = NyxoraApkUpdateManager(
            context = context,
            scope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
            httpClient = client,
            installedVersionNameProvider = { "1.0" },
            installedVersionCodeProvider = { 1 }
        )

        // Case A: GitHub repository has no releases published yet (HTTP 404)
        manager.checkForUpdates(force = true)
        val state404 = manager.uiState.value
        assertTrue("Expected UpToDate on 404, got $state404", state404 is ApkUpdateUiState.UpToDate)
        assertFalse(state404.hasUpdateIndicator)

        // Case B: GitHub returns a release tag v1.2.0 but no APK asset is attached yet
        httpCode = 200
        responseJson = """
            {
              "tag_name": "v1.2.0",
              "name": "NYXORA Browser v1.2.0",
              "draft": false,
              "prerelease": false,
              "assets": []
            }
        """.trimIndent()
        manager.checkForUpdates(force = true)
        val stateNoAsset = manager.uiState.value
        assertTrue("Expected UpToDate when no APK asset is published, got $stateNoAsset", stateNoAsset is ApkUpdateUiState.UpToDate)
        assertFalse(stateNoAsset.hasUpdateIndicator)
    }

    @Test
    fun `full lifecycle update detection one-tap download verification permission and package installer handoff`() {
        val fakeApkBytes = buildValidZipApkBytes("NYXORA-APK-BINARY-PAYLOAD-V1.2.0".repeat(512))
        val expectedSha256 = sha256Hex(fakeApkBytes)
        val releaseApiUrl = "https://api.github.com/repos/sudhamoy-nyxora/nyxora-browser/releases/latest"
        val apkDownloadUrl = "https://github.com/sudhamoy-nyxora/nyxora-browser/releases/download/v1.2.0/nyxora-browser-v1.2.0.apk"

        val releaseJson = """
            {
              "tag_name": "v1.2.0",
              "name": "NYXORA Browser v1.2.0",
              "body": "Faster tab switching and automatic APK updates.\nSHA-256: $expectedSha256",
              "draft": false,
              "prerelease": false,
              "published_at": "2026-10-30T00:00:00Z",
              "assets": [
                {
                  "name": "nyxora-browser-v1.2.0.apk",
                  "browser_download_url": "$apkDownloadUrl",
                  "size": ${fakeApkBytes.size},
                  "state": "uploaded",
                  "digest": "sha256:$expectedSha256"
                }
              ]
            }
        """.trimIndent()

        var canInstallUnknownApps = false
        val observedStates = mutableListOf<ApkUpdateUiState>()
        lateinit var managerRef: NyxoraApkUpdateManager

        val fakeInspector = object : ApkArchiveInspector {
            override fun inspectApkArchive(context: Context, apkFile: File): ParsedApkArchiveMetadata {
                observedStates.add(managerRef.uiState.value)
                return ParsedApkArchiveMetadata(
                    packageName = context.packageName,
                    versionName = "1.2.0",
                    versionCode = 2L,
                    signingCertSha256Fingerprints = setOf("nyxora_signing_cert_sha256_fingerprint")
                )
            }

            override fun getInstalledSigningCertSha256Fingerprints(context: Context): Set<String> {
                return setOf("nyxora_signing_cert_sha256_fingerprint")
            }

            override fun canRequestPackageInstalls(context: Context): Boolean {
                return canInstallUnknownApps
            }
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                when (url) {
                    releaseApiUrl -> Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(releaseJson.toResponseBody("application/json".toMediaType()))
                        .build()

                    apkDownloadUrl -> {
                        observedStates.add(managerRef.uiState.value)
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(fakeApkBytes.toResponseBody("application/vnd.android.package-archive".toMediaType()))
                            .build()
                    }

                    else -> Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("".toResponseBody(null))
                        .build()
                }
            })
            .build()

        val manager = NyxoraApkUpdateManager(
            context = context,
            scope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
            httpClient = client,
            archiveInspector = fakeInspector,
            releaseApiUrlProvider = { releaseApiUrl },
            installedVersionNameProvider = { "1.0" },
            installedVersionCodeProvider = { 1 }
        )
        managerRef = manager

        // 1. Check for updates -> Detects v1.2.0 and sets hasUpdateIndicator = true
        manager.checkForUpdates(force = true)
        val detectedState = manager.uiState.value
        assertTrue("Expected UpdateAvailable, got $detectedState", detectedState is ApkUpdateUiState.UpdateAvailable)
        assertTrue(detectedState.hasUpdateIndicator)
        val updateInfo = (detectedState as ApkUpdateUiState.UpdateAvailable).release
        assertEquals("1.2.0", updateInfo.cleanVersion)
        assertEquals(expectedSha256, updateInfo.expectedSha256Hex)

        // 2. Tap Update Now -> Streams APK directly in-app, tracks progress, verifies APK, and transitions to ReadyToInstall
        manager.downloadUpdateNow()
        observedStates.add(manager.uiState.value)

        assertTrue(
            "Expected Downloading state to be emitted during download, got $observedStates",
            observedStates.any { it is ApkUpdateUiState.Downloading }
        )
        assertTrue(
            "Expected Verifying state to be emitted before ReadyToInstall, got $observedStates",
            observedStates.any { it is ApkUpdateUiState.Verifying }
        )
        val readyState = manager.uiState.value
        assertTrue("Expected ReadyToInstall after verification, got $readyState", readyState is ApkUpdateUiState.ReadyToInstall)
        val verifiedApk = (readyState as ApkUpdateUiState.ReadyToInstall).apkFile
        assertTrue(verifiedApk.exists())
        assertEquals(fakeApkBytes.size.toLong(), verifiedApk.length())

        // 3. Tap Install Update when 'Install unknown apps' permission is NOT granted yet ->
        //    Transitions to PermissionRequired and opens Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
        val shadowApp = shadowOf(context as android.app.Application)
        shadowApp.clearNextStartedActivities()

        val permResult = manager.initiateInstallOrRequestPermission(context)
        assertTrue("Expected PermissionSettingsLaunched, got $permResult", permResult is InstallHandoffResult.PermissionSettingsLaunched)
        assertTrue(manager.uiState.value is ApkUpdateUiState.PermissionRequired)

        val launchedSettingsIntent = shadowApp.nextStartedActivity
        assertNotNull("Expected unknown sources settings intent to be launched", launchedSettingsIntent)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, launchedSettingsIntent.action)
        assertEquals("package:${context.packageName}", launchedSettingsIntent.dataString)

        // 4. User grants 'Install unknown apps' permission and returns to NYXORA Browser ->
        //    refreshInstallPermissionState transitions state back to ReadyToInstall
        canInstallUnknownApps = true
        assertTrue(manager.refreshInstallPermissionState(context))
        assertTrue(manager.uiState.value is ApkUpdateUiState.ReadyToInstall)

        // 5. Tap Install Update -> Launches official Android Package Installer with FileProvider content:// URI
        shadowApp.clearNextStartedActivities()
        val installResult = manager.initiateInstallOrRequestPermission(context)
        assertTrue("Expected InstallerLaunched, got $installResult", installResult is InstallHandoffResult.InstallerLaunched)
        val installerLaunched = installResult as InstallHandoffResult.InstallerLaunched
        assertEquals("content", installerLaunched.contentUri.scheme)
        assertEquals("${context.packageName}.fileprovider", installerLaunched.contentUri.authority)

        val startedInstallerIntent = shadowApp.nextStartedActivity
        assertNotNull("Expected official Android Package Installer intent to be started", startedInstallerIntent)
        assertEquals(Intent.ACTION_VIEW, startedInstallerIntent.action)
        assertEquals(NyxoraApkUpdateManager.APK_MIME_TYPE, startedInstallerIntent.type)
        assertTrue((startedInstallerIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertTrue((startedInstallerIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun `interrupted download cleans up partial file and surfaces retryable error state`() {
        val fullPayload = buildValidZipApkBytes("PARTIAL-STREAM-TEST-PAYLOAD".repeat(256))
        val declaredSize = fullPayload.size.toLong()
        val truncatedBytes = fullPayload.copyOf(fullPayload.size / 3)

        val releaseInfo = GitHubReleaseApkInfo(
            tagName = "v1.3.0",
            cleanVersion = "1.3.0",
            releaseTitle = "NYXORA Browser v1.3.0",
            releaseNotes = "Update test",
            publishedAt = "2026-10-30T00:00:00Z",
            assetName = "nyxora-browser-v1.3.0.apk",
            downloadUrl = "https://github.com/sudhamoy-nyxora/nyxora-browser/releases/download/v1.3.0/nyxora-browser-v1.3.0.apk",
            assetSizeBytes = declaredSize
        )

        // Simulate a response whose stream ends early (fewer bytes than declared assetSizeBytes)
        val truncatedBody = object : okhttp3.ResponseBody() {
            override fun contentType() = "application/vnd.android.package-archive".toMediaType()
            override fun contentLength(): Long = declaredSize
            override fun source(): BufferedSource {
                val buffer = Buffer().write(truncatedBytes)
                val truncatedSource: Source = buffer
                return truncatedSource.buffer()
            }
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(truncatedBody)
                    .build()
            })
            .build()

        val manager = NyxoraApkUpdateManager(
            context = context,
            scope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
            httpClient = client,
            installedVersionNameProvider = { "1.0" },
            installedVersionCodeProvider = { 1 }
        )

        manager.downloadUpdateNow(explicitRelease = releaseInfo)

        val errorState = manager.uiState.value
        assertTrue("Expected Error state on interrupted download, got $errorState", errorState is ApkUpdateUiState.Error)
        val err = errorState as ApkUpdateUiState.Error
        assertTrue(err.message.contains("interrupted", ignoreCase = true))
        assertEquals(releaseInfo, err.recoverableRelease)
        assertTrue("Update indicator must remain visible so user can retry", err.hasUpdateIndicator)

        // Verify no leftover .part or corrupted .apk file remains in updates directory
        val updatesDir = NyxoraApkUpdateManager.getUpdatesDirectory(context)
        val remainingFiles = updatesDir.listFiles().orEmpty()
        assertEquals("Partial files must be cleaned up on interrupted download", 0, remainingFiles.size)
    }

    @Test
    fun `apk verification blocks corrupted files wrong package names version downgrades and signature mismatches`() {
        val updatesDir = NyxoraApkUpdateManager.getUpdatesDirectory(context)

        // 1. Non-ZIP file (HTML error page saved as .apk) -> Invalid
        val fakeHtmlFile = File(updatesDir, "corrupted.apk").apply {
            writeBytes("<html><body>502 Bad Gateway</body></html>".repeat(10).toByteArray())
        }
        val nonZipCheck = NyxoraApkUpdateManager.verifyDownloadedApk(
            context = context,
            apkFile = fakeHtmlFile,
            expectedPackageName = context.packageName,
            installedVersionCode = 1,
            installedVersionName = "1.0"
        )
        assertTrue(nonZipCheck is ApkVerificationResult.Invalid)

        // Valid ZIP file for structural tests
        val validZipBytes = buildValidZipApkBytes("APK-ARCHIVE-TEST-CONTENT".repeat(64))
        val validZipFile = File(updatesDir, "candidate.apk").apply {
            writeBytes(validZipBytes)
        }
        val actualHash = sha256Hex(validZipBytes)

        // 2. SHA-256 mismatch -> Invalid
        val badHashCheck = NyxoraApkUpdateManager.verifyDownloadedApk(
            context = context,
            apkFile = validZipFile,
            expectedPackageName = context.packageName,
            installedVersionCode = 1,
            installedVersionName = "1.0",
            expectedSha256Hex = "0".repeat(64)
        )
        assertTrue(badHashCheck is ApkVerificationResult.Invalid)
        assertTrue((badHashCheck as ApkVerificationResult.Invalid).reason.contains("checksum", ignoreCase = true))

        // 3. Package name mismatch (would install a separate app instead of updating NYXORA Browser) -> Invalid
        val wrongPkgInspector = object : ApkArchiveInspector {
            override fun inspectApkArchive(context: Context, apkFile: File) = ParsedApkArchiveMetadata(
                packageName = "com.some.other.browser",
                versionName = "2.0.0",
                versionCode = 5L,
                signingCertSha256Fingerprints = setOf("cert_a")
            )
            override fun getInstalledSigningCertSha256Fingerprints(context: Context) = setOf("cert_a")
            override fun canRequestPackageInstalls(context: Context) = true
        }
        val wrongPkgCheck = NyxoraApkUpdateManager.verifyDownloadedApk(
            context = context,
            apkFile = validZipFile,
            expectedPackageName = context.packageName,
            installedVersionCode = 1,
            installedVersionName = "1.0",
            expectedSha256Hex = actualHash,
            archiveInspector = wrongPkgInspector
        )
        assertTrue(wrongPkgCheck is ApkVerificationResult.Invalid)
        assertTrue((wrongPkgCheck as ApkVerificationResult.Invalid).reason.contains("package ID mismatch", ignoreCase = true))

        // 4. Version code downgrade (would fail installation or risk data loss) -> Invalid
        val downgradeInspector = object : ApkArchiveInspector {
            override fun inspectApkArchive(context: Context, apkFile: File) = ParsedApkArchiveMetadata(
                packageName = context.packageName,
                versionName = "1.2.0",
                versionCode = 1L,
                signingCertSha256Fingerprints = setOf("cert_a")
            )
            override fun getInstalledSigningCertSha256Fingerprints(context: Context) = setOf("cert_a")
            override fun canRequestPackageInstalls(context: Context) = true
        }
        val downgradeCheck = NyxoraApkUpdateManager.verifyDownloadedApk(
            context = context,
            apkFile = validZipFile,
            expectedPackageName = context.packageName,
            installedVersionCode = 2,
            installedVersionName = "1.1.0",
            expectedSha256Hex = actualHash,
            archiveInspector = downgradeInspector
        )
        assertTrue(downgradeCheck is ApkVerificationResult.Invalid)
        assertTrue((downgradeCheck as ApkVerificationResult.Invalid).reason.contains("version code", ignoreCase = true))

        // 5. Signing certificate mismatch (would require uninstalling and wiping user data) -> Invalid
        val mismatchedCertInspector = object : ApkArchiveInspector {
            override fun inspectApkArchive(context: Context, apkFile: File) = ParsedApkArchiveMetadata(
                packageName = context.packageName,
                versionName = "1.2.0",
                versionCode = 2L,
                signingCertSha256Fingerprints = setOf("different_signing_certificate_hash")
            )
            override fun getInstalledSigningCertSha256Fingerprints(context: Context) = setOf("installed_signing_certificate_hash")
            override fun canRequestPackageInstalls(context: Context) = true
        }
        val certMismatchCheck = NyxoraApkUpdateManager.verifyDownloadedApk(
            context = context,
            apkFile = validZipFile,
            expectedPackageName = context.packageName,
            installedVersionCode = 1,
            installedVersionName = "1.0",
            expectedSha256Hex = actualHash,
            archiveInspector = mismatchedCertInspector
        )
        assertTrue(certMismatchCheck is ApkVerificationResult.Invalid)
        assertTrue((certMismatchCheck as ApkVerificationResult.Invalid).reason.contains("signing certificate", ignoreCase = true))
    }

    private fun buildValidZipApkBytes(payloadText: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(payloadText.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write("DEX_BYTECODE_PLACEHOLDER".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
