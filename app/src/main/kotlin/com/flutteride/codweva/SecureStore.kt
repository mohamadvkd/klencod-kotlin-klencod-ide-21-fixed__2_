package com.flutteride.codweva

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * تخزين محلي مشفّر لتوكن GitHub واسم المستخدم باستخدام Android Keystore.
 *
 * ملاحظة أمنية:
 *   - المفتاح (Key) محفوظ في Keystore النظام — لا يمكن استخراجه.
 *   - القيم مشفّرة بـ AES-256-GCM.
 *   - كل تشفير يولّد IV جديد (آمن).
 */
class SecureStore(context: Context) {

    private val appContext: Context = context.applicationContext

    // ============================================================
    // GitHub Token
    // ============================================================

    fun saveGitHubToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getKey())
            val encrypted = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
            prefs().edit()
                .putString(VALUE, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    fun readGitHubToken(): String {
        return try {
            val encoded = prefs().getString(VALUE, null) ?: return ""
            val encodedIv = prefs().getString(IV, null) ?: return ""
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE, getKey(),
                GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    // ============================================================
    // GitHub Username
    // ============================================================

    /**
     * يحفظ اسم مستخدم GitHub بشكل مشفّر لاستخدامه في واجهة الدخول بدون إنترنت.
     */
    fun saveGitHubUsername(username: String?): Boolean {
        if (username.isNullOrBlank()) return false
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getKey())
            val encrypted = cipher.doFinal(username.toByteArray(StandardCharsets.UTF_8))
            prefs().edit()
                .putString(USERNAME, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(USERNAME_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * يقرأ اسم المستخدم المحفوظ، ويُرجع قيمة افتراضية إن لم يوجد.
     */
    fun readGitHubUsername(): String {
        return try {
            val encoded = prefs().getString(USERNAME, null) ?: return "GitHub user"
            val encodedIv = prefs().getString(USERNAME_IV, null) ?: return "GitHub user"
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE, getKey(),
                GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            "GitHub user"
        }
    }

    // ============================================================
    // حذف كل شيء
    // ============================================================

    fun clearGitHubToken() {
        prefs().edit()
            .remove(VALUE)
            .remove(IV)
            .remove(USERNAME)
            .remove(USERNAME_IV)
            .apply()
    }

    // ============================================================
    // أدوات داخلية
    // ============================================================

    private fun prefs(): SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE)
        keyStore.load(null)

        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, KEYSTORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    // ============================================================
    // ثوابت
    // ============================================================
    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "klencod_ide_secure_store"
        private const val PREFS = "klencod_secure_store"
        private const val VALUE = "github_access_token"
        private const val IV = "github_access_token_iv"
        private const val USERNAME = "github_username"
        private const val USERNAME_IV = "github_username_iv"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}