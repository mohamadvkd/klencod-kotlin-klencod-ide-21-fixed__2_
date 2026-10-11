package com.flutteride.codweva

import android.content.Context
import android.content.SharedPreferences

/**
 * واجهة توافقية قديمة تستخدمها MainActivity، دون أي خادم وسيط.
 *
 * Singleton — نسخة واحدة لكل تطبيق.
 */
class AuthManager private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val secureStore: SecureStore = SecureStore(appContext)
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("github_profile", Context.MODE_PRIVATE)

    // ============================================================
    // GitHub Token
    // ============================================================

    fun saveAccessToken(token: String?) {
        secureStore.saveGitHubToken(token)
    }

    fun getAccessToken(): String {
        return secureStore.readGitHubToken()
    }

    // ============================================================
    // GitHub Username
    // ============================================================

    fun saveGithubUsername(username: String?) {
        prefs.edit().putString("github_username", username ?: "").apply()
    }

    fun getGithubUsername(): String {
        return prefs.getString("github_username", "") ?: ""
    }

    // ============================================================
    // هل المستخدم مسجّل دخول؟
    // ============================================================

    val isLoggedIn: Boolean
        get() {
            val token = getAccessToken()
            return token.trim().isNotEmpty()
        }

    // ============================================================
    // تسجيل الخروج
    // ============================================================

    fun logout() {
        secureStore.clearGitHubToken()
        prefs.edit().clear().apply()
    }

    // ============================================================
    // رأس المصادقة (Authorization Header)
    // ============================================================

    fun getAuthHeader(): String? {
        val token = getAccessToken()
        return if (token.trim().isEmpty()) null else "Bearer $token"
    }

    /**
     * لم يعد هناك refresh token؛ Device Flow يمنح توكنًا محفوظًا محليًا.
     */
    fun refreshAccessToken(): Boolean {
        return isLoggedIn
    }

    // ============================================================
    // Singleton
    // ============================================================
    companion object {
        @Volatile
        private var instance: AuthManager? = null

        @JvmStatic
        fun getInstance(context: Context): AuthManager {
            return instance ?: synchronized(this) {
                instance ?: AuthManager(context).also { instance = it }
            }
        }
    }
}