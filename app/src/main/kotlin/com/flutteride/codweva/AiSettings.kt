package com.flutteride.codweva

import android.content.Context
import android.content.SharedPreferences

/**
 * إعدادات مزودي AI (اسم المزود الافتراضي + النموذج + الحرارة + التوكنات).
 */
class AiSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ============================================================
    // المزود الافتراضي
    // ============================================================
    val defaultProvider: String
        get() = prefs.getString(DEFAULT_PROVIDER, OPENAI) ?: OPENAI

    fun setDefaultProvider(providerId: String?) {
        prefs.edit().putString(DEFAULT_PROVIDER, providerId).apply()
    }

    // ============================================================
    // النموذج (Model) لكل مزود
    // ============================================================
    fun getModel(providerId: String?): String {
        val defaultModel = when (providerId) {
            OPENAI -> "gpt-4o-mini"
            GEMINI -> "gemini-2.0-flash"
            else -> "openai/gpt-4o-mini"
        }
        return prefs.getString(MODEL_PREFIX + providerId, defaultModel) ?: defaultModel
    }

    fun setModel(providerId: String?, model: String?) {
        prefs.edit().putString(
            MODEL_PREFIX + providerId,
            model?.trim() ?: ""
        ).apply()
    }

    // ============================================================
    // درجة الحرارة (Temperature)
    // ============================================================
    val temperature: Double
        get() = java.lang.Double.longBitsToDouble(
            prefs.getLong(TEMPERATURE, java.lang.Double.doubleToLongBits(0.2))
        )

    fun setTemperature(value: Double) {
        val clamped = value.coerceIn(0.0, 2.0)
        prefs.edit().putLong(
            TEMPERATURE,
            java.lang.Double.doubleToLongBits(clamped)
        ).apply()
    }

    // ============================================================
    // الحد الأقصى للتوكنات
    // ============================================================
    val maxTokens: Int
        get() = prefs.getInt(MAX_TOKENS, 4096)

    fun setMaxTokens(value: Int) {
        prefs.edit().putInt(MAX_TOKENS, value.coerceIn(256, 32768)).apply()
    }

    // ============================================================
    // ثوابت
    // ============================================================
    companion object {
        const val OPENAI = "openai"
        const val GEMINI = "gemini"
        const val OPENROUTER = "openrouter"

        private const val PREFS = "ai_settings"
        private const val DEFAULT_PROVIDER = "default_provider"
        private const val MODEL_PREFIX = "model_"
        private const val TEMPERATURE = "temperature"
        private const val MAX_TOKENS = "max_tokens"
    }
}