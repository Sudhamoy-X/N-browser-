package com.example.domain.browser

import com.example.data.local.NyxoraSettingsState
import com.example.data.local.SearchEngine
import com.example.data.model.ExportedBookmark
import com.example.data.model.ExportedSiteSetting
import com.example.data.model.NyxoraBackupPayload
import com.example.data.model.SiteSettingItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NyxoraUnitTests {

    @After
    fun tearDown() {
        UrlAndSearchHandler.clearUserPickedFileUrlsForTest()
    }

    @Test
    fun `default search engine is Google in settings and URL normalizer`() {
        val defaultSettings = NyxoraSettingsState()
        assertEquals(SearchEngine.GOOGLE, defaultSettings.searchEngine)

        val result = UrlAndSearchHandler.normalizeUserInput("kotlin coroutines guide")
        assertEquals(InputClassification.SEARCH_QUERY, result.classification)
        assertTrue(result.finalUrl.startsWith("https://www.google.com/search?q="))
        assertTrue(result.finalUrl.contains("kotlin+coroutines+guide"))
        assertTrue(result.isSecure)
    }

    @Test
    fun `trims input and loads valid explicit http and https URLs`() {
        val httpsRes = UrlAndSearchHandler.normalizeUserInput("   https://developer.android.com/jetpack/compose   ")
        assertEquals(InputClassification.DIRECT_URL, httpsRes.classification)
        assertEquals("https://developer.android.com/jetpack/compose", httpsRes.finalUrl)
        assertTrue(httpsRes.isSecure)
        assertFalse(httpsRes.wasImplicitHttps)
        assertNull(httpsRes.fallbackHttpUrl)

        // Explicit http:// when httpsOnlyMode is false stays http:// and is NEVER marked secure
        val httpRes = UrlAndSearchHandler.normalizeUserInput(
            rawInput = "  http://neverssl.com/online  ",
            searchEngine = SearchEngine.GOOGLE,
            httpsOnlyMode = false
        )
        assertEquals(InputClassification.DIRECT_URL, httpRes.classification)
        assertEquals("http://neverssl.com/online", httpRes.finalUrl)
        assertFalse(httpRes.isSecure)
        assertFalse(UrlAndSearchHandler.isSecureUrl(httpRes.finalUrl))
    }

    @Test
    fun `treats domain localhost IPv4 IPv6 and host port as URL with HTTPS first and HTTP fallback`() {
        // 1. Standard domain (scheme-less) -> tries HTTPS first, provides HTTP fallback
        val domainRes = UrlAndSearchHandler.normalizeUserInput("wikipedia.org/wiki/Main_Page")
        assertEquals(InputClassification.DIRECT_URL, domainRes.classification)
        assertEquals("https://wikipedia.org/wiki/Main_Page", domainRes.finalUrl)
        assertTrue(domainRes.wasImplicitHttps)
        assertEquals("http://wikipedia.org/wiki/Main_Page", domainRes.fallbackHttpUrl)

        // When httpsOnlyMode is true, no silent HTTP fallback is attached
        val strictRes = UrlAndSearchHandler.normalizeUserInput(
            rawInput = "wikipedia.org",
            searchEngine = SearchEngine.GOOGLE,
            httpsOnlyMode = true
        )
        assertEquals("https://wikipedia.org", strictRes.finalUrl)
        assertNull(strictRes.fallbackHttpUrl)

        // 2. localhost and localhost:port
        val localhostRes = UrlAndSearchHandler.normalizeUserInput("localhost:3000/api/status")
        assertEquals(InputClassification.DIRECT_URL, localhostRes.classification)
        assertEquals("https://localhost:3000/api/status", localhostRes.finalUrl)
        assertEquals("http://localhost:3000/api/status", localhostRes.fallbackHttpUrl)

        // 3. IPv4 and IPv4:port
        val ipv4Res = UrlAndSearchHandler.normalizeUserInput("192.168.1.1:8080/admin")
        assertEquals(InputClassification.DIRECT_URL, ipv4Res.classification)
        assertEquals("https://192.168.1.1:8080/admin", ipv4Res.finalUrl)
        assertEquals("http://192.168.1.1:8080/admin", ipv4Res.fallbackHttpUrl)

        // 4. IPv6 and [IPv6]:port
        val ipv6Res = UrlAndSearchHandler.normalizeUserInput("[2001:db8::1]:8443/health")
        assertEquals(InputClassification.DIRECT_URL, ipv6Res.classification)
        assertEquals("https://[2001:db8::1]:8443/health", ipv6Res.finalUrl)
        assertEquals("http://[2001:db8::1]:8443/health", ipv6Res.fallbackHttpUrl)

        // 5. Single-label host:port
        val hostPortRes = UrlAndSearchHandler.normalizeUserInput("devserver:9000/metrics")
        assertEquals(InputClassification.DIRECT_URL, hostPortRes.classification)
        assertEquals("https://devserver:9000/metrics", hostPortRes.finalUrl)
        assertEquals("http://devserver:9000/metrics", hostPortRes.fallbackHttpUrl)
    }

    @Test
    fun `handles spaces extra whitespace and malformed input without crashing by routing to search`() {
        val malformedInputs = listOf(
            "how to build an android app",
            "   multiple   spaces   in   query   ",
            "http://",
            "https://",
            "http://exa mple.com/path",
            "example..com",
            ".example.com",
            "example.com:99999",
            "example.com:0",
            "example.com:abc",
            "999.999.999.999",
            "[::1:bad:ipv6",
            "://missing-scheme.com"
        )

        for (raw in malformedInputs) {
            val res = UrlAndSearchHandler.normalizeUserInput(raw, SearchEngine.GOOGLE, false)
            assertEquals("Expected SEARCH_QUERY for input: '$raw'", InputClassification.SEARCH_QUERY, res.classification)
            assertTrue(res.finalUrl.startsWith("https://www.google.com/search?q="))
        }
    }

    @Test
    fun `never navigates to javascript data vbscript or typed file URLs in address bar`() {
        val dangerousInputs = listOf(
            "javascript:alert(document.cookie)",
            "   JaVaScRiPt:void(0)  ",
            "java\nscript:alert(1)",
            "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
            "vbscript:msgbox(1)",
            "file:///etc/passwd",
            "file:///sdcard/Download/secret.html",
            "content://com.android.providers.media.documents/document/1"
        )

        for (input in dangerousInputs) {
            val res = UrlAndSearchHandler.normalizeUserInput(input, SearchEngine.GOOGLE, false)
            assertEquals(
                "Expected BLOCKED_DANGEROUS_SCHEME for '$input'",
                InputClassification.BLOCKED_DANGEROUS_SCHEME,
                res.classification
            )
            assertFalse(res.isSecure)
        }
    }

    @Test
    fun `allows file scheme only for files explicitly registered via the file picker`() {
        val arbitraryFileUrl = "file:///data/user/0/com.example/files/secret.txt"
        val pickedFileUrl = "file:///data/user/0/com.example/cache/picker_files/picked_1001.html"

        // Arbitrary file:// URL is not allowed
        assertFalse(UrlAndSearchHandler.isUserPickedFileUrl(arbitraryFileUrl))
        assertFalse(UrlAndSearchHandler.isSecureUrl(arbitraryFileUrl))

        // Typing pickedFileUrl into the address bar is STILL blocked
        val typedResult = UrlAndSearchHandler.normalizeUserInput(pickedFileUrl, SearchEngine.GOOGLE, false)
        assertEquals(InputClassification.BLOCKED_DANGEROUS_SCHEME, typedResult.classification)

        // Registering via file picker allowlist permits internal file:// loading
        UrlAndSearchHandler.registerUserPickedFileUrl(pickedFileUrl)
        assertTrue(UrlAndSearchHandler.isUserPickedFileUrl(pickedFileUrl))
        assertTrue(UrlAndSearchHandler.isSecureUrl(pickedFileUrl))
    }

    @Test
    fun `shows IDN and punycode domains in safe punycode form to prevent lookalike spoofing`() {
        // 1. Mixed-script Cyrillic 'а' (\u0430) + Latin "pple.com" -> xn--pple-43d.com
        val mixedScriptApple = "https://\u0430pple.com/login"
        assertTrue(UrlAndSearchHandler.isSpoofableIdnHost(mixedScriptApple))
        assertEquals("xn--pple-43d.com", UrlAndSearchHandler.formatSafeDisplayHost(mixedScriptApple))

        // 2. Whole-script Cyrillic lookalike "аррӏе.com" -> xn--80ak6aa92e.com
        val wholeScriptCyrillic = "https://xn--80ak6aa92e.com"
        assertTrue(UrlAndSearchHandler.isSpoofableIdnHost(wholeScriptCyrillic))
        assertEquals("xn--80ak6aa92e.com", UrlAndSearchHandler.formatSafeDisplayHost(wholeScriptCyrillic))

        // 3. Normalizing IDN domain in address bar produces safe punycode URL and display host
        val normalizedIdn = UrlAndSearchHandler.normalizeUserInput("\u0430pple.com", SearchEngine.GOOGLE, false)
        assertEquals(InputClassification.DIRECT_URL, normalizedIdn.classification)
        assertEquals("https://xn--pple-43d.com", normalizedIdn.finalUrl)
        assertEquals("xn--pple-43d.com", normalizedIdn.safeDisplayHost)

        // 4. Standard ASCII domain displays cleanly without www.
        assertFalse(UrlAndSearchHandler.isSpoofableIdnHost("https://www.wikipedia.org"))
        assertEquals("wikipedia.org", UrlAndSearchHandler.formatSafeDisplayHost("https://www.wikipedia.org"))
    }

    @Test
    fun `never marks http or certificate-error page as secure`() {
        assertTrue(UrlAndSearchHandler.isSecureUrl("https://www.google.com", hasCertificateError = false))
        assertFalse(UrlAndSearchHandler.isSecureUrl("https://www.google.com", hasCertificateError = true))
        assertFalse(UrlAndSearchHandler.isSecureUrl("http://www.google.com", hasCertificateError = false))
        assertFalse(UrlAndSearchHandler.isSecureUrl("http://localhost:8080", hasCertificateError = false))
    }

    @Test
    fun `ad and tracker blocking blocks third-party ad domains and allows first-party resources`() {
        val blocked = ContentBlockingEngine.shouldBlockSubresource(
            requestUrl = "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            pageUrl = "https://news.ycombinator.com",
            globalAdBlockingEnabled = true,
            siteSetting = null
        )
        assertTrue(blocked)

        val allowedFirstParty = ContentBlockingEngine.shouldBlockSubresource(
            requestUrl = "https://news.ycombinator.com/hn.css",
            pageUrl = "https://news.ycombinator.com",
            globalAdBlockingEnabled = true,
            siteSetting = null
        )
        assertFalse(allowedFirstParty)

        val exceptionSite = SiteSettingItem(
            userId = "u1",
            host = "news.ycombinator.com",
            allowAds = true
        )
        val allowedBySiteException = ContentBlockingEngine.shouldBlockSubresource(
            requestUrl = "https://doubleclick.net/pixel.gif",
            pageUrl = "https://news.ycombinator.com",
            globalAdBlockingEnabled = true,
            siteSetting = exceptionSite
        )
        assertFalse(allowedBySiteException)
    }

    @Test
    fun `AES-256-GCM encrypted backup roundtrip preserves payload and rejects wrong passphrase`() {
        val original = NyxoraBackupPayload(
            exportedAtMillis = 1710000000000L,
            defaultSearchEngine = SearchEngine.GOOGLE.name,
            themeMode = "DARK",
            adBlockingEnabled = true,
            popupBlockingEnabled = true,
            redirectBlockingEnabled = true,
            httpsOnlyMode = true,
            dataSaverEnabled = false,
            forceDarkWeb = true,
            hideCookieBanners = true,
            bookmarks = listOf(
                ExportedBookmark("Android Dev", "https://developer.android.com", "Dev", true)
            ),
            siteSettings = listOf(
                ExportedSiteSetting(
                    host = "example.com",
                    allowAds = false,
                    allowPopups = true,
                    allowRedirects = false,
                    desktopMode = true,
                    textZoom = 125,
                    cameraPermission = "ASK",
                    micPermission = "ASK",
                    locationPermission = "ASK",
                    notificationPermission = "ASK"
                )
            )
        )

        val encryptedResult = EncryptedBackupManager.encryptBackup(original, "CorrectHorseBatteryStaple!99")
        assertTrue(encryptedResult.isSuccess)
        val ciphertext = encryptedResult.getOrThrow()

        val decryptedResult = EncryptedBackupManager.decryptBackup(ciphertext, "CorrectHorseBatteryStaple!99")
        assertTrue(decryptedResult.isSuccess)
        val restored = decryptedResult.getOrThrow()
        assertEquals(original.defaultSearchEngine, restored.defaultSearchEngine)
        assertEquals(1, restored.bookmarks.size)
        assertEquals("https://developer.android.com", restored.bookmarks.first().url)

        val wrongKeyResult = EncryptedBackupManager.decryptBackup(ciphertext, "WrongPassphrase123")
        assertNotNull(wrongKeyResult.exceptionOrNull())
    }
}
