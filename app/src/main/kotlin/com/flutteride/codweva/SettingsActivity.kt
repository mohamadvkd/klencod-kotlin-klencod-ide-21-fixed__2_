package com.flutteride.codweva

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Locale

/**
 * صفحة الإعدادات الكاملة.
 *
 * v2.1 — حذف خيار "تكبير بإصبعين":
 *   - التكبير/التصغير الآن عبر أزرار CodeToolbar فقط
 *   - تنظيف مفاتيح SharedPreferences القديمة
 *
 * v2.0 — إضافة حد التلوين (line highlight limit)
 */
class SettingsActivity : AppCompatActivity() {

    private val colorBackground = 0xFF0D1117.toInt()
    private val colorSurface = 0xFF161B22.toInt()
    private val colorSurfaceAlt = 0xFF21262D.toInt()
    private val colorPrimary = 0xFF1F6FEB.toInt()
    private val colorPrimaryLight = 0xFF58A6FF.toInt()
    private val colorTextPrimary = 0xFFE6EDF3.toInt()
    private val colorTextSecondary = 0xFF8B949E.toInt()
    private val colorBorder = 0xFF30363D.toInt()
    private val colorDanger = 0xFFCF222E.toInt()

    private lateinit var prefs: SharedPreferences

    private fun dp(value: Int): Int {
        return Math.round(value * resources.displayMetrics.density)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("flutter_ide_prefs", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBackground)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        root.addView(buildTopBar(), LinearLayout.LayoutParams(-1, dp(56)))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(colorBackground)
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }

        // المظهر
        content.addView(buildSectionTitle("المظهر"))
        content.addView(
            buildSettingItem(
                "ثيم الكود",
                "اختر ثيم محرر الكود المفضل لديك مع معاينة مباشرة",
                View.OnClickListener { showThemePicker() }
            )
        )

        // المحرر
        content.addView(buildSectionTitle("المحرر"))
        content.addView(
            buildSettingItem(
                "إعدادات المحرر",
                "حجم الخط، الاختصارات",
                View.OnClickListener { openEditorSettings() }
            )
        )

        content.addView(
            buildSettingItem(
                "حد تلوين الأسطر",
                buildHighlightLimitSubtitle(),
                View.OnClickListener { showHighlightLimitPicker() }
            )
        )

        // التخزين
        content.addView(buildSectionTitle("التخزين"))
        content.addView(
            buildSettingItem(
                "إدارة الذاكرة المؤقتة",
                "حذف الأيقونات والمعاينات المخزنة",
                View.OnClickListener { showCacheManager() }
            )
        )

        // عن التطبيق
        content.addView(buildSectionTitle("عن التطبيق"))
        content.addView(
            buildSettingItem(
                "سياسة الخصوصية",
                "اقرأ سياسة الاستخدام والخصوصية",
                View.OnClickListener { showPrivacyPolicy() }
            )
        )
        content.addView(
            buildSettingItem(
                "تواصل مع المطور",
                "البريد الإلكتروني وقناة تيليجرام",
                View.OnClickListener { showContactInfo() }
            )
        )
        content.addView(
            buildSettingItem(
                "حول التطبيق",
                "معلومات الإصدار والمطور",
                View.OnClickListener { showAboutApp() }
            )
        )

        // الحساب
        content.addView(buildSectionTitle("الحساب"))
        content.addView(
            buildSettingItem(
                "تسجيل الخروج",
                "حذف مفتاح GitHub من هذا الجهاز",
                View.OnClickListener { confirmLogout() },
                true
            )
        )

        scroll.addView(content, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
    }

    // ============================================================
    // Top bar
    // ============================================================
    private fun buildTopBar(): View {
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(colorSurface)
        }

        val backBtn = Button(this).apply {
            text = "←"
            setTextColor(colorTextPrimary)
            setTextSize(18f)
            isAllCaps = false
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            background = UiStyle.rounded(this@SettingsActivity, colorSurfaceAlt, 10, colorBorder, 1)
            setOnClickListener { finish() }
        }
        topBar.addView(backBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        val title = TextView(this).apply {
            text = "الإعدادات"
            setTextColor(colorTextPrimary)
            setTextSize(18f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, 0, 0)
        }
        topBar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))

        return topBar
    }

    private fun buildSectionTitle(text: String): View {
        return TextView(this).apply {
            this.text = text
            setTextColor(colorPrimaryLight)
            setTextSize(13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(16), dp(4), dp(8))
        }
    }

    private fun buildSettingItem(
        title: String,
        subtitle: String,
        onClick: View.OnClickListener,
        danger: Boolean = false
    ): View {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = UiStyle.rounded(this@SettingsActivity, colorSurface, 12, colorBorder, 1)
            isClickable = true
            isFocusable = true
            setOnClickListener(onClick)

            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, dp(4), 0, dp(4))
            }
        }

        val tvTitle = TextView(this).apply {
            text = title
            setTextColor(if (danger) colorDanger else colorTextPrimary)
            setTextSize(14f)
            typeface = Typeface.DEFAULT_BOLD
        }
        item.addView(tvTitle)

        val tvSubtitle = TextView(this).apply {
            text = subtitle
            setTextColor(colorTextSecondary)
            setTextSize(11f)
        }
        val subParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
        }
        item.addView(tvSubtitle, subParams)

        return item
    }

    // ============================================================
    // حد التلوين
    // ============================================================
    private fun buildHighlightLimitSubtitle(): String {
        val limit = prefs.getInt(KEY_HIGHLIGHT_LIMIT, DEFAULT_HIGHLIGHT_LIMIT)
        return if (limit <= 0) {
            "الملفات الحالية: تلوين غير محدود (قد يكون بطيئاً في الملفات الضخمة)"
        } else {
            "الملفات الحالية: تلوين الملفات حتى $limit سطر فقط"
        }
    }

    private fun showHighlightLimitPicker() {
        val currentLimit = prefs.getInt(KEY_HIGHLIGHT_LIMIT, DEFAULT_HIGHLIGHT_LIMIT)

        val options = arrayOf(
            "1,000 سطر",
            "3,000 سطر",
            "5,000 سطر (موصى به)",
            "10,000 سطر",
            "20,000 سطر",
            "بلا حد (تلوين كل الملفات)"
        )

        val values = intArrayOf(1000, 3000, 5000, 10000, 20000, 0)

        var selectedIndex = 2
        for (i in values.indices) {
            if (values[i] == currentLimit) {
                selectedIndex = i
                break
            }
        }

        val headerView = TextView(this).apply {
            text = "الملفات التي تتجاوز هذا الحد سيتم عرضها بدون تلوين (لكن يمكن تعديلها).\n\n" +
                    "الفائدة: أداء أفضل مع الملفات الضخمة.\n\n" +
                    "الوضع الحالي: " + (if (currentLimit <= 0) "بلا حد" else "$currentLimit سطر")
            setTextColor(colorTextSecondary)
            setTextSize(12f)
            setLineSpacing(0f, 1.2f)
            setPadding(dp(20), dp(16), dp(20), dp(12))
        }

        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(buildDialogTitle("حد تلوين الأسطر"))
            .setView(headerView)
            .setSingleChoiceItems(
                UiStyle.dialogChoiceAdapter(this, options),
                selectedIndex
            ) { d, which ->
                val newLimit = values[which]
                prefs.edit().putInt(KEY_HIGHLIGHT_LIMIT, newLimit).apply()

                val label = if (newLimit <= 0) "بلا حد" else "$newLimit سطر"
                Toast.makeText(
                    this,
                    "تم التحديث: $label",
                    Toast.LENGTH_SHORT
                ).show()

                d.dismiss()
                recreate()
            }
            .setNegativeButton("إلغاء", null)
            .create()

        dialog.show()
        styleDialog(dialog)

        dialog.window?.let { window ->
            val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun buildDialogTitle(title: String): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(8))
        }

        val tv = TextView(this).apply {
            text = title
            setTextColor(colorTextPrimary)
            setTextSize(18f)
            typeface = Typeface.DEFAULT_BOLD
        }
        container.addView(tv)

        return container
    }

    // ============================================================
    // الثيمات
    // ============================================================
    private fun showThemePicker() {
        val themes = EditorThemes.getAll()
        val currentId = EditorThemes.getSelectedId(this)
        val selectedIndex = EditorThemes.indexOf(currentId)

        val title = TextView(this).apply {
            text = "ثيم الكود"
            setTextColor(colorTextPrimary)
            setTextSize(18f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(20), dp(18), dp(20), dp(12))
        }

        val subtitle = TextView(this).apply {
            text = "انقر على أي ثيم لتطبيقه فوراً"
            setTextColor(colorTextSecondary)
            setTextSize(12f)
            setPadding(dp(20), 0, dp(20), dp(12))
        }

        val headerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        headerBox.addView(title)
        headerBox.addView(subtitle)

        val listView = ListView(this).apply {
            setBackgroundColor(colorBackground)
            divider = null
            dividerHeight = dp(8)
            setPadding(dp(12), dp(8), dp(12), dp(12))
            clipToPadding = false
        }

        val currentSelection = intArrayOf(selectedIndex)

        val adapter = object : android.widget.ArrayAdapter<EditorThemes.Theme>(
            this, 0, themes
        ) {
            override fun getView(
                position: Int,
                convertView: View?,
                parent: ViewGroup
            ): View {
                return buildThemeRow(
                    themes[position],
                    position == currentSelection[0]
                )
            }
        }
        listView.adapter = adapter

        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(headerBox)
            .setView(listView)
            .setNegativeButton("إلغاء", null)
            .create()

        listView.onItemClickListener =
            android.widget.AdapterView.OnItemClickListener { _, _, position, _ ->
                val theme = themes[position]
                EditorThemes.setSelectedId(this, theme.id)
                currentSelection[0] = position
                adapter.notifyDataSetChanged()
                Toast.makeText(
                    this,
                    "تم اختيار: ${theme.displayName}",
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }

        dialog.show()
        styleDialog(dialog)

        dialog.window?.let { window ->
            val width = (resources.displayMetrics.widthPixels * 0.95).toInt()
            val height = (resources.displayMetrics.heightPixels * 0.88).toInt()
            window.setLayout(width, height)
        }
    }

    private fun buildThemeRow(theme: EditorThemes.Theme, isSelected: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = android.widget.AbsListView.LayoutParams(-1, -2)
        }

        val rowBg = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            if (isSelected) {
                setColor(0xFF1F2D3F.toInt())
                setStroke(dp(2), colorPrimaryLight)
            } else {
                setColor(colorSurface)
                setStroke(dp(1), colorBorder)
            }
        }
        row.background = rowBg

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val radio = RadioButton(this).apply {
            isChecked = isSelected
            isClickable = false
            isFocusable = false
            buttonTintList = ColorStateList.valueOf(
                if (isSelected) colorPrimaryLight else colorTextSecondary
            )
        }
        header.addView(radio, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
            marginEnd = dp(6)
        })

        val nameView = TextView(this).apply {
            text = theme.displayName
            setTextColor(if (isSelected) colorPrimaryLight else colorTextPrimary)
            setTextSize(15f)
            typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        header.addView(nameView, LinearLayout.LayoutParams(0, -2, 1f))

        if (isSelected) {
            val currentBadge = TextView(this).apply {
                text = "الحالي"
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(10f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(colorPrimary)
                }
            }
            header.addView(currentBadge)
        }

        row.addView(header)

        val preview = buildCodePreview(theme)
        val previewParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
        }
        row.addView(preview, previewParams)

        return row
    }

    private fun buildCodePreview(theme: EditorThemes.Theme): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(theme.background)
                setStroke(dp(1), 0x33FFFFFF)
            }
        }

        container.addView(
            buildPreviewLine(
                theme, arrayOf(
                    PreviewToken("// main.dart", theme.comment)
                )
            )
        )

        container.addView(
            buildPreviewLine(
                theme, arrayOf(
                    PreviewToken("class ", theme.keyword),
                    PreviewToken("Hello", theme.function),
                    PreviewToken(" extends ", theme.keyword),
                    PreviewToken("StatelessWidget", theme.function),
                    PreviewToken(" {", theme.operator)
                )
            )
        )

        container.addView(
            buildPreviewLine(
                theme, arrayOf(
                    PreviewToken("  @override", theme.tag)
                )
            )
        )

        container.addView(
            buildPreviewLine(
                theme, arrayOf(
                    PreviewToken("  ", theme.defaultText),
                    PreviewToken("Widget", theme.function),
                    PreviewToken(" ", theme.defaultText),
                    PreviewToken("build", theme.function),
                    PreviewToken("(", theme.operator),
                    PreviewToken("BuildContext", theme.function),
                    PreviewToken(" context", theme.defaultText),
                    PreviewToken(") {", theme.operator)
                )
            )
        )

        container.addView(
            buildPreviewLine(
                theme, arrayOf(
                    PreviewToken("    ", theme.defaultText),
                    PreviewToken("return", theme.keyword),
                    PreviewToken(" ", theme.defaultText),
                    PreviewToken("Text", theme.function),
                    PreviewToken("(", theme.operator),
                    PreviewToken("'Hello'", theme.string),
                    PreviewToken(", ", theme.operator),
                    PreviewToken("fontSize", theme.function),
                    PreviewToken(": ", theme.operator),
                    PreviewToken("20", theme.number),
                    PreviewToken(");", theme.operator)
                )
            )
        )

        container.addView(
            buildPreviewLine(theme, arrayOf(PreviewToken("  }", theme.operator)))
        )

        container.addView(
            buildPreviewLine(theme, arrayOf(PreviewToken("}", theme.operator)))
        )

        return container
    }

    private fun buildPreviewLine(
        theme: EditorThemes.Theme,
        tokens: Array<PreviewToken>
    ): TextView {
        val line = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(11f)
            includeFontPadding = false
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val sb = SpannableStringBuilder()
        var cursor = 0
        for (token in tokens) {
            sb.append(token.text)
            sb.setSpan(
                ForegroundColorSpan(token.color),
                cursor, cursor + token.text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            cursor += token.text.length
        }

        line.text = sb
        return line
    }

    private class PreviewToken(val text: String, val color: Int)

    // ============================================================
    // إعدادات المحرر — بعد حذف pinch zoom
    // ============================================================
    private fun openEditorSettings() {
        val currentSize = prefs.getInt("editor_font_size", 14)
        val snippets = prefs.getBoolean("editor_snippets_enabled", true)

        val options = arrayOf(
            "حجم الخط: $currentSize",
            "اختصارات الكتابة (stless + مسافة): ${if (snippets) "مفعل" else "غير مفعل"}"
        )

        val builder = AlertDialog.Builder(this)
        builder.setTitle("إعدادات المحرر")
        builder.setAdapter(
            UiStyle.dialogListAdapter(this, options),
            DialogInterface.OnClickListener { _, which ->
                when (which) {
                    0 -> showFontSizeDialog()
                    1 -> {
                        prefs.edit().putBoolean(
                            "editor_snippets_enabled",
                            !prefs.getBoolean("editor_snippets_enabled", true)
                        ).apply()
                        Toast.makeText(this, "تم التغيير", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
        builder.setNegativeButton("إغلاق", null)
        val dialog = builder.create()
        dialog.show()
        styleDialog(dialog)
    }

    private fun showFontSizeDialog() {
        val currentSize = prefs.getInt("editor_font_size", 14)
        val options = arrayOf("صغير (10)", "افتراضي (14)", "كبير (18)", "كبير جداً (24)")
        val sizes = intArrayOf(10, 14, 18, 24)

        var selectedIndex = 1
        for (i in sizes.indices) {
            if (sizes[i] == currentSize) {
                selectedIndex = i
                break
            }
        }

        val builder = AlertDialog.Builder(this)
        builder.setTitle("حجم الخط")
        builder.setSingleChoiceItems(
            UiStyle.dialogChoiceAdapter(this, options),
            selectedIndex,
            DialogInterface.OnClickListener { dialog, which ->
                prefs.edit().putInt("editor_font_size", sizes[which]).apply()
                Toast.makeText(
                    this,
                    "تم التغيير إلى ${sizes[which]}",
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
        )
        builder.setNegativeButton("إلغاء", null)
        val dialog = builder.create()
        dialog.show()
        styleDialog(dialog)
    }

    // ============================================================
    // إدارة الذاكرة المؤقتة
    // ============================================================
    private fun showCacheManager() {
        val apkIconsSize = getDirSize(File(cacheDir, "apk_icons"))
        val templateIconsSize = getDirSize(File(cacheDir, "template_icons"))
        val templatePreviewsSize = getDirSize(File(cacheDir, "template_previews"))
        val showcaseSize = getDirSize(File(cacheDir, "template_showcase"))
        val totalSize = apkIconsSize + templateIconsSize +
                templatePreviewsSize + showcaseSize

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(colorBackground)
        }

        val title = TextView(this).apply {
            text = "إدارة الذاكرة المؤقتة"
            setTextSize(17f)
            setTextColor(colorTextPrimary)
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(title)

        val desc = TextView(this).apply {
            text = "التطبيق يخزّن أيقونات ومعاينات لتسريع الفتح. " +
                    "يمكنك حذفها لتحرير مساحة، وسيُعاد إنشاؤها عند الحاجة."
            setTextSize(12f)
            setTextColor(colorTextSecondary)
            setPadding(0, dp(8), 0, dp(16))
            setLineSpacing(0f, 1.15f)
        }
        root.addView(desc)

        val totalView = TextView(this).apply {
            text = "الإجمالي: ${formatBytes(totalSize)}"
            setTextSize(15f)
            setTextColor(colorPrimary)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(totalView)

        root.addView(buildCacheRow("أيقونات التطبيقات المبنية", apkIconsSize))
        root.addView(buildCacheRow("أيقونات القوالب", templateIconsSize))
        root.addView(buildCacheRow("معاينات القوالب", templatePreviewsSize))
        root.addView(buildCacheRow("ملفات مؤقتة للقوالب", showcaseSize))

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(12))
        }
        root.addView(spacer)

        val dialog = AlertDialog.Builder(this)
            .setView(root)
            .setPositiveButton("حذف الكل", null)
            .setNegativeButton("إغلاق", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                confirmClearCache(dialog, totalSize)
            }
        }

        dialog.show()
        styleDialog(dialog)
    }

    private fun buildCacheRow(label: String, size: Long): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))

            addView(
                TextView(this@SettingsActivity).apply {
                    text = label
                    setTextSize(13f)
                    setTextColor(colorTextPrimary)
                },
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            addView(
                TextView(this@SettingsActivity).apply {
                    text = formatBytes(size)
                    setTextSize(13f)
                    setTextColor(if (size > 0) colorPrimary else colorTextSecondary)
                    typeface = Typeface.MONOSPACE
                }
            )
        }
    }

    private fun confirmClearCache(parentDialog: AlertDialog, size: Long) {
        val sizeText = formatBytes(size)
        AlertDialog.Builder(this)
            .setTitle("تأكيد الحذف")
            .setMessage(
                "سيتم حذف $sizeText من الذاكرة المؤقتة.\n\n" +
                        "الأيقونات والمعاينات ستُعاد إنشاؤها عند فتح التطبيقات والقوالب مرة أخرى.\n\n" +
                        "لا يتم حذف أي مشاريع أو ملفات أو APK."
            )
            .setPositiveButton("حذف") { _, _ ->
                performClearCache()
                if (parentDialog.isShowing) parentDialog.dismiss()
                Toast.makeText(
                    this,
                    "تم حذف الذاكرة المؤقتة ($sizeText)",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun performClearCache() {
        try {
            deleteRecursive(File(cacheDir, "apk_icons"))
            deleteRecursive(File(cacheDir, "template_icons"))
            deleteRecursive(File(cacheDir, "template_previews"))
            deleteRecursive(File(cacheDir, "template_showcase"))
        } catch (_: Exception) {
        }
    }

    // ============================================================
    // سياسة الخصوصية
    // ============================================================
    private fun showPrivacyPolicy() {
        val privacyText = buildPrivacyPolicyText()

        val builder = AlertDialog.Builder(this)
        builder.setTitle("سياسة الخصوصية")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(colorBackground)
        }

        val textView = TextView(this).apply {
            text = privacyText
            setTextColor(colorTextPrimary)
            setTextSize(13f)
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = UiStyle.rounded(this@SettingsActivity, colorSurface, 12, colorBorder, 1)
        }

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(colorBackground)
            addView(textView, FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))

        builder.setView(root)
        builder.setPositiveButton("موافق", null)
        builder.setNeutralButton("نسخ النص") { _, _ ->
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(
                android.content.ClipData.newPlainText(
                    "privacy", buildPrivacyPolicyText()
                )
            )
            Toast.makeText(this, "تم نسخ سياسة الخصوصية", Toast.LENGTH_SHORT).show()
        }

        val dialog = builder.create()
        dialog.show()
        styleDialog(dialog)

        dialog.window?.let { window ->
            val width = (resources.displayMetrics.widthPixels * 0.95).toInt()
            val height = (resources.displayMetrics.heightPixels * 0.85).toInt()
            window.setLayout(width, height)
        }
    }

    private fun buildPrivacyPolicyText(): String {
        return """
سياسة الخصوصية — Klencod IDE
آخر تحديث: 2026


تنبيه هام: هذا التطبيق مخصص للاستخدام في الخير فقط.
أي سوء استخدام يقع على مسؤولية المستخدم وحده أمام الله.


مقدمة وقبول

نحن في Klencod IDE نحترم خصوصيتك ونأخذها على محمل الجدية. توضّح هذه السياسة بالتفصيل:
• ما البيانات التي نجمعها (وما لا نجمعها)
• كيف نستخدمها ونحميها
• مع من نشاركها (إن لزم)
• حقوقك الكاملة تجاه بياناتك

باستخدامك للتطبيق، أنت توافق على هذه السياسة.


١. البيانات التي نجمعها

التطبيق مصمّم ليعمل محليًا بالكامل. لا نجمع أيًا من البيانات التالية:
• الاسم أو البريد الإلكتروني
• رقم الهاتف أو العنوان
• جهات الاتصال أو الصور أو الملفات الشخصية
• الموقع الجغرافي
• معلومات الجهاز الحساسة (IMEI, MAC)
• سجل الاستخدام أو التحليلات (Analytics)

كل بياناتك تُخزَّن على جهازك فقط.


٢. البيانات التي لا نجمعها أبدًا

نؤكد بشكل قاطع أننا لا نجمع ولا نحتفظ بـ:
• كلمات المرور
• بيانات بطاقات الائتمان
• سجل التصفح
• تفاصيل الاستخدام
• إحصائيات فتح/إغلاق التطبيق


٣. البيانات المرسلة لأطراف ثالثة

أ) GitHub (لبناء APK)
عند استخدامك ميزة "بناء APK":
• تُرفَع ملفات مشروعك إلى مستودع GitHub خاص بك
• يُشغَّل GitHub Actions لبناء التطبيق
• البيانات تخضع لسياسة خصوصية GitHub:
  https://docs.github.com/site-policy/privacy-policies

ب) مزودو الذكاء الاصطناعي (AI)
عند استخدامك مساعد AI، يُرسَل:
• نص طلبك (Prompt)
• محتوى الملفات المرفقة أو المفتوحة
• سياق المحادثة

إلى المزود الذي اخترته:
• OpenAI (https://openai.com/privacy)
• Google Gemini (https://policies.google.com/privacy)
• OpenRouter (https://openrouter.ai/privacy)


٤. تخزين البيانات وأمنها

أ) ما يُخزَّن محليًا على جهازك:
• المشاريع والملفات: في مساحة التطبيق الخاصة
• الإعدادات: SharedPreferences عادية
• المفاتيح الحساسة (GitHub Token، API Keys): مشفّرة باستخدام Android Keystore + AES-256-GCM

ب) ما لا يُخزَّن على الإطلاق:
• كلمات مرور GitHub (نستخدم Device Flow فقط)
• بيانات شخصية
• أي شيء على خوادمنا (لا نملك خوادم)


٥. الذكاء الاصطناعي

أ) كيف يعمل:
• أنت تختار المزود (OpenAI/Gemini/OpenRouter)
• أنت تُدخل مفتاح API الخاص بك
• الطلبات تُرسَل مباشرة من جهازك للمزود

ب) مسؤوليتك:
• لا ترسل أسرارًا (كلمات مرور، مفاتيح، رموز)
• لا ترسل بيانات شخصية للآخرين

ج) اقتراحات AI:
• قد يقدّم AI اقتراحات خاطئة أو مضللة
• راجع كل تعديل قبل تطبيقه


٦. GitHub Actions

عند استخدام ميزة البناء:

أ) ما يحدث:
• ربط آمن بحسابك عبر OAuth Device Flow
• لا نحتفظ بكلمة مرور GitHub أبدًا
• الـ Token محفوظ مشفّرًا على جهازك
• ملفات المشروع تُرفَع لمستودعك الخاص

ب) تحذير:
• البناء العام (Public) يعني أن الكود مرئي للجميع
• إذا كان مشروعك يحتوي أسرارًا، استخدم Private Repo


٧. القوالب وحقوق الملكية

أ) الملكية:
• جميع القوالب المتوفرة داخل التطبيق ملك حصري لـ Klencod IDE.
• القوالب محمية بحقوق النشر © 2026 Klencod IDE.

ب) الاستخدام المسموح:
• استخدام القوالب لإنشاء مشاريع شخصية أو تجارية.
• تعديل القالب أو إضافة خصائص وميزات جديدة عليه.

ج) الاستخدام غير المسموح:
• نشر القالب كما هو تمامًا في أي متجر تطبيقات.
• إعادة بيع القالب نفسه بشكل منفصل.
• نسب القالب إلى مطور آخر.

د) شرط التعديل الجوهري:
يجب إضافة تعديل جوهري واحد على الأقل عند استخدام أي قالب.


٨. حقوقك

لديك الحق الكامل في:
• الوصول لبياناتك في أي وقت (موجودة على جهازك).
• تعديل أي ملف يدويًا من داخل التطبيق.
• حذف أي مشروع في أي وقت.
• حذف كل البيانات بإلغاء تثبيت التطبيق.
• تصدير مشاريعك كملفات ZIP.


٩. حدود المسؤولية

التطبيق يُقدَّم "كما هو" (As-Is) دون أي ضمانات صريحة أو ضمنية.


١٠. حذف البيانات

يمكنك حذف بياناتك عبر:
• حذف مشروع فردي: الضغط الطويل > حذف.
• حذف محادثة AI: زر "مسح".
• حذف مفتاح: الإعدادات > حذف مفتاح.
• حذف كل شيء: إلغاء تثبيت التطبيق.


١١. الأذونات المطلوبة

نطلب فقط الأذونات الضرورية للعمل:
• INTERNET
• ACCESS_NETWORK_STATE
• POST_NOTIFICATIONS
• READ/WRITE_EXTERNAL_STORAGE
• REQUEST_INSTALL_PACKAGES
• MANAGE_EXTERNAL_STORAGE


١٢. مجاني، بلا إعلانات

التطبيق مجاني بالكامل، ولا نعرض أي إعلانات.


١٣. تغييرات السياسة

قد نحدّث هذه السياسة من وقت لآخر.


١٤. تواصل معنا

البريد الإلكتروني: mm2007mm19h@gmail.comشكرًا لثقتك واستخدامك Klencod IDE
        """.trimIndent()
    }

    private fun showContactInfo() {
        val contactText = """
للتواصل مع المطور:

البريد الإلكتروني:
mm2007mm19h@gmail.com

تيليجرام:
https://t.me/Klencod

شكرًا لك على استخدام التطبيق!
        """.trimIndent()

        val builder = AlertDialog.Builder(this)
        builder.setTitle("تواصل مع المطور")

        val textView = TextView(this).apply {
            text = contactText
            setTextColor(colorTextPrimary)
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setTextSize(14f)
            setLineSpacing(0f, 1.2f)
            setTextIsSelectable(true)
            Linkify.addLinks(this, Linkify.EMAIL_ADDRESSES or Linkify.WEB_URLS)
            movementMethod = LinkMovementMethod.getInstance()
        }

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(colorBackground)
            addView(textView)
        }
        builder.setView(scrollView)

        builder.setPositiveButton("موافق", null)
        val dialog = builder.create()
        dialog.show()
        styleDialog(dialog)
    }

    private fun showAboutApp() {
        var version = "2.0"
        try {
            version = packageManager.getPackageInfo(packageName, 0).versionName ?: "2.0"
        } catch (_: PackageManager.NameNotFoundException) {
        }

        val aboutText = """
Klencod IDE

الإصدار: $version

تطبيق لإنشاء وتحرير وبناء مشاريع Flutter و Java مباشرة من هاتفك.
        """.trimIndent()

        val builder = AlertDialog.Builder(this)
        builder.setTitle("حول التطبيق")

        val textView = TextView(this).apply {
            text = aboutText
            setTextColor(colorTextPrimary)
            setPadding(32, 24, 32, 24)
            setTextSize(14f)
            movementMethod = ScrollingMovementMethod()
        }

        val scrollView = ScrollView(this).apply {
            addView(textView)
        }
        builder.setView(scrollView)

        builder.setPositiveButton("موافق", null)
        val dialog = builder.create()
        dialog.show()
        styleDialog(dialog)
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("تسجيل الخروج")
            .setMessage(
                "هل أنت متأكد أنك تريد تسجيل الخروج؟\n\n" +
                        "ملاحظات:\n" +
                        "• سيتم حذف مفتاح GitHub المحفوظ من هذا الجهاز\n" +
                        "• ستبقى مشاريعك وملفاتك كما هي\n" +
                        "• ستحتاج تسجيل الدخول من جديد للبناء على GitHub\n" +
                        "• لن يتم حذف أي شيء من حساب GitHub الخاص بك"
            )
            .setPositiveButton("تسجيل الخروج") { _, _ -> performLogout() }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun performLogout() {
        try {
            SecureStore(this).clearGitHubToken()
            AuthManager.getInstance(this).logout()

            Toast.makeText(this, "تم تسجيل الخروج بنجاح", Toast.LENGTH_SHORT).show()

            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "خطأ أثناء تسجيل الخروج: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun getDirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0
        if (dir.isFile) return dir.length()

        var size = 0L
        dir.listFiles()?.forEach { size += getDirSize(it) }
        return size
    }

    private fun deleteRecursive(file: File?) {
        if (file == null || !file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursive(it) }
        }
        file.delete()
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 ->
                String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L ->
                String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
            else ->
                String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private fun styleDialog(dialog: AlertDialog?) {
        if (dialog == null) return

        dialog.window?.let { window ->
            window.setBackgroundDrawable(
                UiStyle.rounded(this, colorBackground, 16, colorBorder, 1)
            )
        }

        val titleId = dialog.context.resources.getIdentifier(
            "android:id/alertTitle", null, null
        )
        if (titleId != 0) {
            dialog.findViewById<TextView>(titleId)?.let {
                it.setTextColor(colorTextPrimary)
                it.typeface = Typeface.DEFAULT_BOLD
            }
        }

        dialog.findViewById<TextView>(android.R.id.message)?.let {
            it.setTextColor(colorTextSecondary)
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(colorPrimaryLight)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(colorTextPrimary)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(colorTextPrimary)

        UiStyle.styleDialog(dialog)
    }

    // ============================================================
    // Companion
    // ============================================================
    companion object {
        const val KEY_HIGHLIGHT_LIMIT = "editor_highlight_line_limit"
        const val DEFAULT_HIGHLIGHT_LIMIT = 5000
    }
}