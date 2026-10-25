package com.example.domain.browser

import android.util.Base64
import com.example.data.model.ExportedBookmark
import com.example.data.model.ExportedSiteSetting
import com.example.data.model.NyxoraBackupPayload
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

object EncryptedBackupManager {
    private const val PBKDF2_ALGO = "PBKDF2WithHmacSHA256"
    private const val CIPHER_ALGO = "AES/GCM/NoPadding"
    private const val ITERATIONS = 65_536
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12

    /**
     * Encrypts a NyxoraBackupPayload using AES-256-GCM derived via PBKDF2 from the user's passphrase.
     * Uses built-in org.json to avoid heavy reflection libraries.
     * Format: NYXORA_ENC_V1:<base64(salt)>:<base64(iv)>:<base64(ciphertext)>
     */
    fun encryptBackup(payload: NyxoraBackupPayload, passphrase: String): Result<String> = runCatching {
        require(passphrase.length >= 4) { "Passphrase must be at least 4 characters." }
        val jsonBytes = serializePayloadToJson(payload).toByteArray(Charsets.UTF_8)

        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }

        val keySpec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        val secretKeyBytes = SecretKeyFactory.getInstance(PBKDF2_ALGO).generateSecret(keySpec).encoded
        val secretKey = SecretKeySpec(secretKeyBytes, "AES")

        val cipher = Cipher.getInstance(CIPHER_ALGO)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherBytes = cipher.doFinal(jsonBytes)

        val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val cipherB64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
        "NYXORA_ENC_V1:$saltB64:$ivB64:$cipherB64"
    }

    /**
     * Decrypts and parses an encrypted NYXORA backup string using the user's passphrase.
     */
    fun decryptBackup(encryptedText: String, passphrase: String): Result<NyxoraBackupPayload> = runCatching {
        val trimmed = encryptedText.trim()
        require(trimmed.startsWith("NYXORA_ENC_V1:")) { "Invalid NYXORA encrypted backup header." }
        val parts = trimmed.split(":")
        require(parts.size == 4) { "Malformed encrypted backup envelope." }

        val salt = Base64.decode(parts[1], Base64.NO_WRAP)
        val iv = Base64.decode(parts[2], Base64.NO_WRAP)
        val cipherBytes = Base64.decode(parts[3], Base64.NO_WRAP)

        val keySpec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        val secretKeyBytes = SecretKeyFactory.getInstance(PBKDF2_ALGO).generateSecret(keySpec).encoded
        val secretKey = SecretKeySpec(secretKeyBytes, "AES")

        val cipher = Cipher.getInstance(CIPHER_ALGO)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val plainBytes = cipher.doFinal(cipherBytes)
        val json = String(plainBytes, Charsets.UTF_8)
        deserializePayloadFromJson(json)
    }

    private fun serializePayloadToJson(payload: NyxoraBackupPayload): String {
        val root = JSONObject()
        root.put("version", payload.version)
        root.put("exportedAtMillis", payload.exportedAtMillis)
        root.put("defaultSearchEngine", payload.defaultSearchEngine)
        root.put("themeMode", payload.themeMode)
        root.put("adBlockingEnabled", payload.adBlockingEnabled)
        root.put("popupBlockingEnabled", payload.popupBlockingEnabled)
        root.put("redirectBlockingEnabled", payload.redirectBlockingEnabled)
        root.put("httpsOnlyMode", payload.httpsOnlyMode)
        root.put("dataSaverEnabled", payload.dataSaverEnabled)
        root.put("forceDarkWeb", payload.forceDarkWeb)
        root.put("hideCookieBanners", payload.hideCookieBanners)

        val bmArray = JSONArray()
        for (bm in payload.bookmarks) {
            val obj = JSONObject()
            obj.put("title", bm.title)
            obj.put("url", bm.url)
            obj.put("folder", bm.folder)
            obj.put("isShortcut", bm.isShortcut)
            bmArray.put(obj)
        }
        root.put("bookmarks", bmArray)

        val siteArray = JSONArray()
        for (site in payload.siteSettings) {
            val obj = JSONObject()
            obj.put("host", site.host)
            obj.put("allowAds", site.allowAds)
            obj.put("allowPopups", site.allowPopups)
            obj.put("allowRedirects", site.allowRedirects)
            obj.put("desktopMode", site.desktopMode)
            obj.put("textZoom", site.textZoom)
            obj.put("cameraPermission", site.cameraPermission)
            obj.put("micPermission", site.micPermission)
            obj.put("locationPermission", site.locationPermission)
            obj.put("notificationPermission", site.notificationPermission)
            siteArray.put(obj)
        }
        root.put("siteSettings", siteArray)
        return root.toString()
    }

    private fun deserializePayloadFromJson(json: String): NyxoraBackupPayload {
        val root = JSONObject(json)
        val bmArray = root.optJSONArray("bookmarks") ?: JSONArray()
        val bookmarks = buildList {
            for (i in 0 until bmArray.length()) {
                val obj = bmArray.getJSONObject(i)
                add(
                    ExportedBookmark(
                        title = obj.optString("title", ""),
                        url = obj.optString("url", ""),
                        folder = obj.optString("folder", "General"),
                        isShortcut = obj.optBoolean("isShortcut", false)
                    )
                )
            }
        }

        val siteArray = root.optJSONArray("siteSettings") ?: JSONArray()
        val siteSettings = buildList {
            for (i in 0 until siteArray.length()) {
                val obj = siteArray.getJSONObject(i)
                add(
                    ExportedSiteSetting(
                        host = obj.optString("host", ""),
                        allowAds = obj.optBoolean("allowAds", false),
                        allowPopups = obj.optBoolean("allowPopups", false),
                        allowRedirects = obj.optBoolean("allowRedirects", false),
                        desktopMode = obj.optBoolean("desktopMode", false),
                        textZoom = obj.optInt("textZoom", 100),
                        cameraPermission = obj.optString("cameraPermission", "ASK"),
                        micPermission = obj.optString("micPermission", "ASK"),
                        locationPermission = obj.optString("locationPermission", "ASK"),
                        notificationPermission = obj.optString("notificationPermission", "ASK")
                    )
                )
            }
        }

        return NyxoraBackupPayload(
            version = root.optInt("version", 1),
            exportedAtMillis = root.optLong("exportedAtMillis", 0L),
            defaultSearchEngine = root.optString("defaultSearchEngine", "DUCKDUCKGO"),
            themeMode = root.optString("themeMode", "DARK"),
            adBlockingEnabled = root.optBoolean("adBlockingEnabled", true),
            popupBlockingEnabled = root.optBoolean("popupBlockingEnabled", true),
            redirectBlockingEnabled = root.optBoolean("redirectBlockingEnabled", true),
            httpsOnlyMode = root.optBoolean("httpsOnlyMode", false),
            dataSaverEnabled = root.optBoolean("dataSaverEnabled", false),
            forceDarkWeb = root.optBoolean("forceDarkWeb", false),
            hideCookieBanners = root.optBoolean("hideCookieBanners", true),
            bookmarks = bookmarks,
            siteSettings = siteSettings
        )
    }
}
