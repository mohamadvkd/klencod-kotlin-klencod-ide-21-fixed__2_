package com.flutteride.codweva

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * شاشة إعدادات الذكاء الاصطناعي:
 *   - اختيار المزود (OpenAI / Gemini / OpenRouter)
 *   - إدخال المفتاح
 *   - إدخال اسم النموذج
 *   - اختبار الاتصال
 */
class AiSettingsActivity : AppCompatActivity() {

    // ============================================================
    // الألوان
    // ============================================================
    private val colorBg = 0xFF0D1117.toInt()
    private val colorSurface = 0xFF161B22.toInt()
    private val colorPrimary = 0xFF1F6FEB.toInt()
    private val colorText = 0xFFE6EDF3.toInt()
    private val colorMuted = colorText
    private val colorBorder = 0xFF30363D.toInt()

    // ============================================================
    // الحقول
    // ============================================================
    private lateinit var providerSpinner: Spinner
    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var status: TextView
    private lateinit var keyStore: AiKeyStore
    private lateinit var settings: AiSettings

    private val providerIds = listOf(
        AiSettings.OPENAI, AiSettings.GEMINI, AiSettings.OPENROUTER
    )

    private fun dp(value: Int): Int {
        return Math.round(value * resources.displayMetrics.density)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keyStore = AiKeyStore(this)
        settings = AiSettings(this)
        title = "إعدادات الذكاء الاصطناعي"
        buildLayout()
    }

    private fun buildLayout() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(colorBg)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
            setBackgroundColor(colorBg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        // الترويسة
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = UiStyle.rounded(this@AiSettingsActivity, colorSurface, 18, colorBorder, 1)
        }

        val titleView = UiStyle.title(this, "إعدادات الذكاء الاصطناعي", 22f)
        header.addView(titleView, LinearLayout.LayoutParams(-1, -2))

        val note = label(
            "أدخل مفتاحك الشخصي. لا يُرسل إلى خادم البناء ولا يظهر في السجلات.",
            13f, colorText
        ).apply {
            setPadding(0, dp(8), 0, 0)
        }
        header.addView(note, LinearLayout.LayoutParams(-1, -2))

        root.addView(header, params(-1, -2, 0f, 0, 0, 20))

        // المزود
        root.addView(label("المزود", 13f, colorText))

        providerSpinner = Spinner(this).apply {
            adapter = UiStyle.spinnerAdapter(
                this@AiSettingsActivity,
                arrayOf("OpenAI", "Gemini", "OpenRouter")
            )
        }
        UiStyle.styleSpinner(providerSpinner)

        val selected = providerIds.indexOf(settings.defaultProvider)
        providerSpinner.setSelection(if (selected < 0) 0 else selected)
        root.addView(providerSpinner, params(-1, -2, 0f, 0, 0, 14))

        // المفتاح
        root.addView(label("مفتاح API", 13f, colorText))
        keyInput = UiStyle.field(this, "أدخل المفتاح هنا").apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD or
                    // مفاتيح API بيانات سرية: لا تعرض لوحة المفاتيح اقتراحات أو إكمالاً.
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                    android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
        }
        root.addView(keyInput, params(-1, -2, 0f, 0, 0, 14))

        // النموذج
        root.addView(label("اسم النموذج", 13f, colorText))
        modelInput = UiStyle.field(this, "مثال: gpt-4o-mini أو gemini-2.0-flash")
        root.addView(modelInput, params(-1, -2, 0f, 0, 0, 16))

        // الأزرار
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val save = button("حفظ", colorPrimary)
        val test = button("اختبار الاتصال", colorSurface)

        buttons.addView(save, params(0, -2, 1f, 0, 0, 0))
        buttons.addView(test, params(0, -2, 1f, 8, 0, 0))

        root.addView(buttons, params(-1, -2, 0f, 0, 0, 16))

        // حالة
        status = label("", 13f, colorText).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = UiStyle.rounded(this@AiSettingsActivity, colorSurface, 12, colorBorder, 1)
        }
        root.addView(status, params(-1, -2, 0f, 0, 0, 16))

        // زر حذف المفتاح
        val clear = button("حذف مفتاح المزود الحالي", colorSurface)
        root.addView(clear, params(-1, -2, 0f, 0, 0, 12))
        clear.setOnClickListener {
            val id = currentProvider()
            keyStore.clear(id)
            keyInput.setText("")
            status.text = "تم حذف مفتاح ${displayName(id)}"
        }

        // المستمعون
        providerSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    loadProvider(providerIds[position])
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }

        save.setOnClickListener { saveCurrent() }
        test.setOnClickListener { testCurrent() }

        scroll.addView(root)
        setContentView(scroll)
    }

    // ============================================================
    // منطق
    // ============================================================
    private fun loadProvider(providerId: String) {
        val key = keyStore.get(providerId)
        keyInput.setText(key ?: "")
        modelInput.setText(settings.getModel(providerId))
        status.text = "جاهز: ${displayName(providerId)}"
    }

    private fun saveCurrent() {
        if (!keyStore.isAvailable) {
            status.text = "تعذر تهيئة التخزين المشفر، لم يُحفظ المفتاح"
            return
        }
        val id = currentProvider()
        val key = keyInput.text.toString().trim()
        val model = modelInput.text.toString().trim()
        if (key.isEmpty() || model.isEmpty()) {
            status.text = "أدخل المفتاح واسم النموذج"
            return
        }
        keyStore.save(id, key)
        settings.setModel(id, model)
        settings.setDefaultProvider(id)
        status.text = "تم حفظ إعدادات ${displayName(id)} بأمان"
    }

    private fun testCurrent() {
        saveCurrent()
        val id = currentProvider()
        val key = keyStore.get(id)
        val model = modelInput.text.toString().trim()
        if (key.isNullOrEmpty() || model.isEmpty()) return

        status.text = "جارٍ اختبار الاتصال..."

        Thread {
            try {
                val provider = AiProviderFactory.create(this, id)
                val messages = listOf(AiMessage.user("أجب بكلمة واحدة فقط: متصل"))
                val response = provider.send(messages, model, 0.0, 32)
                runOnUiThread {
                    status.text = "نجح الاتصال بـ ${displayName(id)}: ${response.safeText.trim()}"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "فشل الاختبار:\n\n${safeError(e)}"
                }
            }
        }.start()
    }

    private fun currentProvider(): String {
        val position = providerSpinner.selectedItemPosition.coerceIn(
            0, providerIds.size - 1
        )
        return providerIds[position]
    }

    private fun displayName(id: String): String {
        return when (id) {
            AiSettings.GEMINI -> "Gemini"
            AiSettings.OPENROUTER -> "OpenRouter"
            else -> "OpenAI"
        }
    }

    private fun safeError(e: Exception): String = AiErrorFormatter.format(e)

    // ============================================================
    // أدوات
    // ============================================================
    private fun label(text: String, size: Float, color: Int): TextView {
        return TextView(this).apply {
            this.text = text
            setTextSize(size)
            setTextColor(color)
        }
    }

    private fun button(text: String, color: Int): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(colorText)
            isAllCaps = false
            setTextSize(14f)
            minHeight = dp(46)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = UiStyle.rounded(
                this@AiSettingsActivity, color, 12,
                if (color == colorPrimary) 0xFF58A6FF.toInt() else colorBorder, 1
            )
        }
    }

    private fun params(
        w: Int, h: Int, weight: Float,
        left: Int, top: Int, bottom: Int
    ): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(w, h, weight).apply {
            setMargins(dp(left), dp(top), dp(left), dp(bottom))
        }
    }
}
