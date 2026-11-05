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
@Config(sdk = [34, 36])
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

    @Test
    fun `github actions workflow and robolectric config support JDK 17 SDK 34 and APK artifact upload`() {
        val releaseWorkflowCandidates = listOf(
            File("../.github/workflows/android-release.yml"),
            File(".github/workflows/android-release.yml")
        )
        val releaseWorkflowFile = releaseWorkflowCandidates.firstOrNull { it.exists() }
        assertNotNull("Expected .github/workflows/android-release.yml to exist", releaseWorkflowFile)
        val releaseWorkflowContent = releaseWorkflowFile!!.readText()

        assertTrue(releaseWorkflowContent.contains("branches:"))
        assertTrue(releaseWorkflowContent.contains("- main"))
        assertTrue(releaseWorkflowContent.contains("workflow_dispatch:"))
        assertTrue(releaseWorkflowContent.contains("java-version: '17'"))
        assertTrue(releaseWorkflowContent.contains("sdkmanager"))
        assertTrue(releaseWorkflowContent.contains("--sdk_root="))
        assertTrue(releaseWorkflowContent.contains("--licenses"))
        assertFalse(releaseWorkflowContent.contains("android-actions/setup-android"))
        assertTrue(releaseWorkflowContent.contains("platforms;android-36"))
        assertTrue(releaseWorkflowContent.contains("platforms;android-36.1"))
        assertTrue(releaseWorkflowContent.contains("build-tools;36.0.0"))
        assertTrue(releaseWorkflowContent.contains(":app:testDebugUnitTest"))
        assertTrue(releaseWorkflowContent.contains("-Drobolectric.enabledSdks=34"))
        assertTrue(releaseWorkflowContent.contains(":app:assembleDebug"))
        assertTrue(releaseWorkflowContent.contains(":app:assembleRelease"))
        assertTrue(releaseWorkflowContent.contains("verify --verbose --print-certs"))
        assertTrue(releaseWorkflowContent.contains("dump badging"))
        assertTrue(releaseWorkflowContent.contains("release-metadata.json"))
        assertTrue(releaseWorkflowContent.contains("should_publish_release"))
        assertTrue(releaseWorkflowContent.contains("app/build/outputs/apk/debug/app-debug.apk"))
        assertTrue(releaseWorkflowContent.contains("actions/upload-artifact@v4"))
        assertTrue(releaseWorkflowContent.contains("softprops/action-gh-release@v2"))

        val androidWorkflowCandidates = listOf(
            File("../.github/workflows/android.yml"),
            File(".github/workflows/android.yml")
        )
        val androidWorkflowFile = androidWorkflowCandidates.firstOrNull { it.exists() }
        assertNotNull("Expected .github/workflows/android.yml to exist", androidWorkflowFile)
        val androidWorkflowContent = androidWorkflowFile!!.readText()

        assertTrue(androidWorkflowContent.contains("branches:"))
        assertTrue(androidWorkflowContent.contains("- main"))
        assertTrue(androidWorkflowContent.contains("workflow_dispatch:"))
        assertTrue(androidWorkflowContent.contains("java-version: '17'"))
        assertTrue(androidWorkflowContent.contains("sdkmanager"))
        assertTrue(androidWorkflowContent.contains("--sdk_root="))
        assertTrue(androidWorkflowContent.contains("--licenses"))
        assertFalse(androidWorkflowContent.contains("android-actions/setup-android"))
        assertFalse(androidWorkflowContent.contains("keytool -genkeypair"))
        val releaseJobWorkflowContent = releaseWorkflowContent.substringAfter("  build-and-release-apk:")
        assertFalse("Release signing must reuse the persistent key, not generate a new one", releaseJobWorkflowContent.contains("keytool -genkeypair"))
        assertTrue(androidWorkflowContent.contains("platforms;android-36"))
        assertTrue(androidWorkflowContent.contains("platforms;android-36.1"))
        assertTrue(androidWorkflowContent.contains("build-tools;36.0.0"))
        assertTrue(androidWorkflowContent.contains(":app:testDebugUnitTest"))
        assertTrue(androidWorkflowContent.contains("-Drobolectric.enabledSdks=34"))
        assertTrue(androidWorkflowContent.contains(":app:assembleDebug"))
        assertTrue(androidWorkflowContent.contains("app/build/outputs/apk/debug/app-debug.apk"))
        assertTrue(androidWorkflowContent.contains("name: NYXORA-Debug-APK"))
        assertTrue(androidWorkflowContent.contains("nyxora-debug-latest"))
        assertTrue(androidWorkflowContent.contains("NYXORA-Browser-debug-latest.apk"))
        assertTrue(androidWorkflowContent.contains("NYXORA_DEBUG_KEYSTORE_BASE64"))
        assertTrue(androidWorkflowContent.contains("NYXORA_DEBUG_STORE_PASSWORD"))
        assertTrue(androidWorkflowContent.contains("NYXORA_DEBUG_KEY_ALIAS"))
        assertTrue(androidWorkflowContent.contains("NYXORA_DEBUG_KEY_PASSWORD"))
        assertTrue(androidWorkflowContent.contains("actions/upload-artifact@v4"))
        assertTrue(androidWorkflowContent.contains("softprops/action-gh-release@v2"))

        val roboPropsStream = javaClass.classLoader?.getResourceAsStream("robolectric.properties")
        assertNotNull("Expected robolectric.properties on test classpath", roboPropsStream)
        val roboPropsText = roboPropsStream!!.bufferedReader().use { it.readText() }
        assertTrue(roboPropsText.contains("sdk=34,36"))
    }

    @Test
    fun `nyxora-debug-latest channel and Sudhamoy-X N-browser- repo support monotonic builds and build-scoped snooze`() {
        assertEquals("Sudhamoy-X/N-browser-", NyxoraApkUpdateManager.DEFAULT_GITHUB_REPO)
        assertEquals("Sudhamoy-X/N-browser-", NyxoraApkUpdateManager.configuredGitHubRepoSlug())
        assertEquals(
            "https://api.github.com/repos/Sudhamoy-X/N-browser-/releases/tags/nyxora-debug-latest",
            NyxoraApkUpdateManager.configuredReleaseApiEndpoint()
        )
        assertEquals(
            "https://github.com/Sudhamoy-X/N-browser-/releases/download/nyxora-debug-latest/NYXORA-Browser-debug-latest.apk",
            NyxoraApkUpdateManager.PREDICTABLE_LATEST_APK_DOWNLOAD_URL
        )

        val expectedSha = "b".repeat(64)
        val debugChannelBuild104Json = """
            {
              "tag_name": "nyxora-debug-latest",
              "name": "NYXORA Browser v2.5.104 (Build 104)",
              "body": "### Release Metadata\n- **Package ID:** `com.aistudio.nyxorabrowser.vqxkpl`\n- **Version Name:** `2.5.104`\n- **Version Code:** `104`\n- **Commit SHA:** `9f8e7d6c5b4a3210`\n- **SHA-256 Checksum:** `$expectedSha`",
              "draft": false,
              "prerelease": false,
              "published_at": "2026-11-02T05:00:00Z",
              "assets": [
                {
                  "name": "NYXORA-Browser-debug-latest.apk",
                  "browser_download_url": "https://github.com/Sudhamoy-X/N-browser-/releases/download/nyxora-debug-latest/NYXORA-Browser-debug-latest.apk",
                  "size": 14680064,
                  "state": "uploaded"
                },
                {
                  "name": "app-debug.apk",
                  "browser_download_url": "https://github.com/Sudhamoy-X/N-browser-/releases/download/nyxora-debug-latest/app-debug.apk",
                  "size": 14680064,
                  "state": "uploaded"
                }
              ]
            }
        """.trimIndent()

        // 1. Installed version is 2.5 (versionCode = 3) -> Detects nyxora-debug-latest Build 104 as UpdateAvailable
        val parsed104 = NyxoraApkUpdateManager.parseLatestReleaseResponse(
            json = debugChannelBuild104Json,
            installedVersionName = "2.5",
            installedVersionCode = 3
        )
        assertTrue("Expected UpdateAvailable for nyxora-debug-latest Build 104, got $parsed104", parsed104 is ReleaseCheckResult.UpdateAvailable)
        val release104 = (parsed104 as ReleaseCheckResult.UpdateAvailable).release
        assertEquals("nyxora-debug-latest", release104.tagName)
        assertEquals("2.5.104", release104.cleanVersion)
        assertEquals(104, release104.versionCode)
        assertEquals("9f8e7d6c5b4a3210", release104.commitSha)
        assertEquals(expectedSha, release104.expectedSha256Hex)
        assertEquals("NYXORA-Browser-debug-latest.apk", release104.assetName)
        assertEquals("nyxora-debug-latest#104", release104.snoozeKey)

        // 2. Installed versionCode is already 104 -> UpToDate
        val upToDate104 = NyxoraApkUpdateManager.parseLatestReleaseResponse(
            json = debugChannelBuild104Json,
            installedVersionName = "2.5.104",
            installedVersionCode = 104
        )
        assertTrue("Expected UpToDate when installed versionCode == 104, got $upToDate104", upToDate104 is ReleaseCheckResult.UpToDate)

        // 3. Snoozing nyxora-debug-latest#104 does NOT suppress nyxora-debug-latest#105
        val manager = NyxoraApkUpdateManager(
            context = context,
            scope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
            installedVersionNameProvider = { "2.5" },
            installedVersionCodeProvider = { 3 }
        )
        val state104 = ApkUpdateUiState.UpdateAvailable(release = release104, installedVersion = "2.5")
        assertTrue(manager.shouldShowStartupUpdatePrompt(state104, manager.dismissedReleaseTag.value))
        manager.dismissUpdatePromptForLater(release104)
        assertFalse(manager.shouldShowStartupUpdatePrompt(state104, manager.dismissedReleaseTag.value))

        val release105 = release104.copy(
            cleanVersion = "2.5.105",
            versionCode = 105,
            releaseTitle = "NYXORA Browser v2.5.105 (Build 105)"
        )
        val state105 = ApkUpdateUiState.UpdateAvailable(release = release105, installedVersion = "2.5")
        assertTrue(
            "Expected startup prompt to reappear when a new build (105) is published to nyxora-debug-latest",
            manager.shouldShowStartupUpdatePrompt(state105, manager.dismissedReleaseTag.value)
        )
    }

    @Test
    fun `release parser extracts versionCode and minSupportedVersionCode and blocks versionCode downgrades`() {
        val releaseWithHigherCodeJson = """
            {
              "tag_name": "v2.6.4",
              "name": "NYXORA Browser v2.6.4",
              "body": "### Release Metadata\n- **Version Name:** `2.6.4`\n- **Version Code:** `4`\n- **Min Supported Version Code:** `1`\n- **SHA-256 Checksum:** `${"a".repeat(64)}`",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "nyxora-browser-v2.6.4-b4.apk",
                  "browser_download_url": "https://github.com/sudhamoy-nyxora/nyxora-browser/releases/download/v2.6.4/nyxora-browser-v2.6.4-b4.apk",
                  "size": 12485760,
                  "state": "uploaded"
                }
              ]
            }
        """.trimIndent()

        // 1. Installed version is 2.5 (versionCode = 3) -> UpdateAvailable with versionCode = 4
        val resultAvailable = NyxoraApkUpdateManager.parseLatestReleaseResponse(
            json = releaseWithHigherCodeJson,
            installedVersionName = "2.5",
            installedVersionCode = 3
        )
        assertTrue("Expected UpdateAvailable, got $resultAvailable", resultAvailable is ReleaseCheckResult.UpdateAvailable)
        val release = (resultAvailable as ReleaseCheckResult.UpdateAvailable).release
        assertEquals("2.6.4", release.cleanVersion)
        assertEquals(4, release.versionCode)
        assertEquals(1, release.minSupportedVersionCode)
        assertEquals("a".repeat(64), release.expectedSha256Hex)

        // 2. Installed versionCode is already 4 or 5 -> Never offer a release with lower or equal versionCode
        val resultBlockedByCode = NyxoraApkUpdateManager.parseLatestReleaseResponse(
            json = releaseWithHigherCodeJson,
            installedVersionName = "2.5",
            installedVersionCode = 4
        )
        assertTrue(
            "Expected UpToDate when release versionCode <= installedVersionCode, got $resultBlockedByCode",
            resultBlockedByCode is ReleaseCheckResult.UpToDate
        )
    }

    @Test
    fun `startup update prompt snooze and one-tap autoTriggerInstall launch package installer automatically`() {
        val fakeApkBytes = buildValidZipApkBytes("NYXORA-ONE-TAP-AUTO-INSTALL-V2.6.4".repeat(256))
        val expectedSha256 = sha256Hex(fakeApkBytes)
        val releaseApiUrl = "https://api.github.com/repos/sudhamoy-nyxora/nyxora-browser/releases/latest"
        val apkDownloadUrl = "https://github.com/sudhamoy-nyxora/nyxora-browser/releases/download/v2.6.4/nyxora-browser-v2.6.4-b4.apk"

        val releaseJson = """
            {
              "tag_name": "v2.6.4",
              "name": "NYXORA Browser v2.6.4",
              "body": "Version Code: 4\nSHA-256: $expectedSha256",
              "draft": false,
              "prerelease": false,
              "published_at": "2026-11-01T00:00:00Z",
              "assets": [
                {
                  "name": "nyxora-browser-v2.6.4-b4.apk",
                  "browser_download_url": "$apkDownloadUrl",
                  "size": ${fakeApkBytes.size},
                  "state": "uploaded",
                  "digest": "sha256:$expectedSha256"
                }
              ]
            }
        """.trimIndent()

        val fakeInspector = object : ApkArchiveInspector {
            override fun inspectApkArchive(context: Context, apkFile: File): ParsedApkArchiveMetadata {
                return ParsedApkArchiveMetadata(
                    packageName = context.packageName,
                    versionName = "2.6.4",
                    versionCode = 4L,
                    signingCertSha256Fingerprints = setOf("nyxora_signing_cert_sha256_fingerprint")
                )
            }

            override fun getInstalledSigningCertSha256Fingerprints(context: Context): Set<String> {
                return setOf("nyxora_signing_cert_sha256_fingerprint")
            }

            override fun canRequestPackageInstalls(context: Context): Boolean = true
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                when (chain.request().url.toString()) {
                    releaseApiUrl -> Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(releaseJson.toResponseBody("application/json".toMediaType()))
                        .build()

                    apkDownloadUrl -> Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(fakeApkBytes.toResponseBody("application/vnd.android.package-archive".toMediaType()))
                        .build()

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
            installedVersionNameProvider = { "2.5" },
            installedVersionCodeProvider = { 3 }
        )

        manager.checkForUpdates(force = true)
        val state = manager.uiState.value
        assertTrue(state is ApkUpdateUiState.UpdateAvailable)
        assertTrue(manager.lastCheckedAtMillis.value > 0L)
        assertTrue(manager.shouldShowStartupUpdatePrompt(state, manager.dismissedReleaseTag.value))

        // Tapping "Later" dismisses the startup prompt for v2.6.4 without hiding Settings update state
        val release = (state as ApkUpdateUiState.UpdateAvailable).release
        manager.dismissUpdatePromptForLater(release)
        assertFalse(manager.shouldShowStartupUpdatePrompt(state, manager.dismissedReleaseTag.value))

        // Manual checkForUpdates(force = true) clears snooze
        manager.checkForUpdates(force = true)
        assertTrue(manager.shouldShowStartupUpdatePrompt(manager.uiState.value, manager.dismissedReleaseTag.value))

        // Tapping "Update Now" with autoTriggerInstall = true downloads, verifies, and launches Package Installer
        val shadowApp = shadowOf(context as android.app.Application)
        shadowApp.clearNextStartedActivities()
        manager.downloadUpdateNow(autoTriggerInstall = true)

        assertTrue(manager.uiState.value is ApkUpdateUiState.ReadyToInstall)
        val startedInstallerIntent = shadowApp.nextStartedActivity
        assertNotNull("Expected Package Installer intent to be started automatically after download", startedInstallerIntent)
        assertEquals(Intent.ACTION_VIEW, startedInstallerIntent.action)
        assertEquals(NyxoraApkUpdateManager.APK_MIME_TYPE, startedInstallerIntent.type)
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
