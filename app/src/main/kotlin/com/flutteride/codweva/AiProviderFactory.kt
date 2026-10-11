package com.flutteride.codweva

import android.content.Context

/**
 * مصنع المزودين — ينشئ AiProvider حسب المعرّف.
 *
 * يقوm بتحميل المفتاح من AiKeyStore ويمرره للمزود المناسب.
 */
object AiProviderFactory {

    /**
     * ينشئ مزود AI حسب المعرّف.
     *
     * @param context سياق التطبيق (لقراءة المفتاح من AiKeyStore)
     * @param providerId معرّف المزود (openai/gemini/openrouter)
     * @return AiProvider جاهز للاستخدام
     * @throws IllegalArgumentException إذا كان المزود غير معروف
     */
    @JvmStatic
    fun create(context: Context, providerId: String?): AiProvider {
        val keyStore = AiKeyStore(context)
        val key = keyStore.get(providerId)

        return when (providerId) {
            AiSettings.OPENAI -> OpenAiProvider(key)
            AiSettings.GEMINI -> GeminiProvider(key)
            AiSettings.OPENROUTER -> OpenRouterProvider(key)
            else -> throw IllegalArgumentException("مزود غير معروف: $providerId")
        }
    }
}