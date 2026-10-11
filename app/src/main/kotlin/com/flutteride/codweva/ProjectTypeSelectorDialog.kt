package com.flutteride.codweva.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.flutteride.codweva.ProjectType
import com.flutteride.codweva.UiStyle

/**
 * ProjectTypeSelectorDialog — شاشة اختيار نوع المشروع الجديد.
 *
 * v2.2 — تحسينات:
 *   - إصلاح: النافذة تختفي بعد الاختيار
 *   - استبدال الإيموجي برسم برمجي لكل لغة
 *   - إزالة التلميح السفلي
 */
object ProjectTypeSelectorDialog {

    /**
     * يعرض الحوار ويستدعي onSelected عند الاختيار.
     */
    @JvmStatic
    fun show(activity: Activity?, onSelected: (ProjectType) -> Unit) {
        if (activity == null || activity.isFinishing) return

        val types = ProjectType.available()
        if (types.isEmpty()) return

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(activity, 16),
                dp(activity, 8),
                dp(activity, 16),
                dp(activity, 14)
            )
            setBackgroundColor(0xFF0D1117.toInt())
        }

        // عنوان فرعي
        val subtitle = TextView(activity).apply {
            text = "اختر نوع المشروع الذي تريد إنشاءه:"
            setTextColor(0xFF8B949E.toInt())
            setTextSize(13f)
            setPadding(dp(activity, 4), 0, dp(activity, 4), dp(activity, 12))
        }
        container.addView(subtitle)

        // الحوار
        val dialog = AlertDialog.Builder(activity)
            .setTitle("نوع المشروع")
            .setView(container)
            .setNegativeButton("إلغاء", null)
            .create()

        // بطاقات الأنواع
        for (type in types) {
            val card = buildTypeCard(activity, type) {
                // ✅ إغلاق النافذة أولاً، ثم استدعاء onSelected
                dialog.dismiss()
                onSelected(type)
            }
            container.addView(
                card,
                LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(activity, 10)
                }
            )
        }

        dialog.show()
        UiStyle.styleDialog(dialog)
    }

    // ============================================================
    // بطاقة نوع واحد
    // ============================================================
    private fun buildTypeCard(
        activity: Activity,
        type: ProjectType,
        onClick: () -> Unit
    ): View {

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(activity, 16),
                dp(activity, 14),
                dp(activity, 16),
                dp(activity, 14)
            )
            background = UiStyle.rounded(
                activity,
                0xFF161B22.toInt(),
                16,
                0xFF30363D.toInt(),
                1
            )
            isClickable = true
            isFocusable = true
        }

        // ============================================================
        // أيقونة النوع (رسم برمجي)
        // ============================================================
        val iconBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ProjectType.colorFor(type))
            }
        }

        val iconView = LanguageIconView(activity, type)
        iconBox.addView(iconView, LinearLayout.LayoutParams(-1, -1))

        card.addView(
            iconBox,
            LinearLayout.LayoutParams(dp(activity, 52), dp(activity, 52))
        )

        // ============================================================
        // عمود النصوص
        // ============================================================
        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        card.addView(
            textCol,
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = dp(activity, 14)
            }
        )

        // اسم النوع
        val title = TextView(activity).apply {
            text = type.displayName
            setTextColor(0xFFE6EDF3.toInt())
            setTextSize(16f)
            typeface = Typeface.DEFAULT_BOLD
        }
        textCol.addView(title)

        // وصف النوع
        val description = TextView(activity).apply {
            text = type.description
            setTextColor(0xFF8B949E.toInt())
            setTextSize(12f)
            setPadding(0, dp(activity, 3), 0, 0)
            setLineSpacing(0f, 1.15f)
        }
        textCol.addView(description)

        // شارات
        val badgesRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 7), 0, 0)
        }

        val langBadge = makeBadge(
            activity,
            ProjectType.colorFor(type),
            type.primaryLanguage
        )
        badgesRow.addView(langBadge)

        val extBadge = makeBadge(
            activity,
            0xFF30363D.toInt(),
            ".${type.primaryExtension}"
        )
        val extParams = LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = dp(activity, 6)
        }
        badgesRow.addView(extBadge, extParams)

        val wfBadge = makeBadge(
            activity,
            0xFF1F6FEB.toInt(),
            "GitHub Actions"
        )
        val wfParams = LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = dp(activity, 6)
        }
        badgesRow.addView(wfBadge, wfParams)

        textCol.addView(badgesRow)

        // سهم
        val arrow = TextView(activity).apply {
            text = "‹"
            setTextColor(0xFF8B949E.toInt())
            setTextSize(22f)
            gravity = Gravity.CENTER
        }
        card.addView(
            arrow,
            LinearLayout.LayoutParams(dp(activity, 22), dp(activity, 22))
        )

        card.setOnClickListener { onClick() }

        return card
    }

    // ============================================================
    // شارة صغيرة
    // ============================================================
    private fun makeBadge(
        activity: Activity,
        color: Int,
        text: String
    ): TextView {
        return TextView(activity).apply {
            this.text = text
            setTextColor(0xFFE6EDF3.toInt())
            setTextSize(10f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(
                dp(activity, 7),
                dp(activity, 2),
                dp(activity, 7),
                dp(activity, 2)
            )
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 6).toFloat()
                setColor(withAlpha(color, 0.20f))
                setStroke(dp(activity, 1), color)
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private fun dp(activity: Activity, value: Int): Int {
        return Math.round(value * activity.resources.displayMetrics.density)
    }

    // ============================================================
    // View مخصص لرسم رمز كل لغة
    // ============================================================
    private class LanguageIconView(
        context: Context,
        private val type: ProjectType
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cx = w / 2f
            val cy = h / 2f
            val size = minOf(w, h)

            when (type) {
                ProjectType.FLUTTER -> drawFlutter(canvas, cx, cy, size)
                ProjectType.JAVA -> drawJava(canvas, cx, cy, size)
                ProjectType.KOTLIN -> drawKotlin(canvas, cx, cy, size)
                ProjectType.REACT_NATIVE -> drawReactNative(canvas, cx, cy, size)
            }
        }

        // ------------------------------------------------------------
        // Flutter — شكل الماسة
        // ------------------------------------------------------------
        private fun drawFlutter(canvas: Canvas, cx: Float, cy: Float, size: Float) {
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt()

            val s = size * 0.36f

            // المثلث العلوي
            val path = Path()
            path.moveTo(cx - s * 0.75f, cy - s * 0.55f)
            path.lineTo(cx + s * 0.35f, cy - s * 0.55f)
            path.lineTo(cx + s * 0.95f, cy + s * 0.05f)
            path.lineTo(cx - s * 0.15f, cy + s * 0.05f)
            path.close()
            canvas.drawPath(path, paint)

            // المثلث الأوسط
            val path2 = Path()
            path2.moveTo(cx - s * 0.15f, cy + s * 0.05f)
            path2.lineTo(cx + s * 0.95f, cy + s * 0.05f)
            path2.lineTo(cx + s * 0.4f, cy + s * 0.6f)
            path2.lineTo(cx - s * 0.5f, cy + s * 0.6f)
            path2.close()
            canvas.drawPath(path2, paint)

            // المثلث الأخير
            val path3 = Path()
            path3.moveTo(cx + s * 0.4f, cy + s * 0.6f)
            path3.lineTo(cx + s * 0.95f, cy + s * 0.05f)
            path3.lineTo(cx + s * 0.95f, cy + s * 0.6f)
            path3.close()
            canvas.drawPath(path3, paint)
        }

        // ------------------------------------------------------------
        // Java — فنجان قهوة
        // ------------------------------------------------------------
        private fun drawJava(canvas: Canvas, cx: Float, cy: Float, size: Float) {
            paint.style = Paint.Style.STROKE
            paint.color = 0xFFFFFFFF.toInt()
            paint.strokeWidth = size * 0.055f
            paint.strokeCap = Paint.Cap.ROUND

            val cupW = size * 0.42f
            val cupH = size * 0.32f
            val left = cx - cupW / 2f
            val top = cy - cupH * 0.15f
            val right = cx + cupW / 2f
            val bottom = top + cupH

            // جسم الفنجان
            val cupRect = RectF(left, top, right, bottom)
            canvas.drawRoundRect(cupRect, size * 0.06f, size * 0.06f, paint)

            // المقبض
            val handleRect = RectF(
                right - size * 0.02f,
                top + cupH * 0.2f,
                right + size * 0.18f,
                bottom - cupH * 0.2f
            )
            canvas.drawArc(handleRect, -90f, 180f, false, paint)

            // البخار
            paint.strokeWidth = size * 0.045f
            val steamTop = top - size * 0.28f
            val steamBottom = top - size * 0.08f

            val steam1 = Path()
            steam1.moveTo(cx - size * 0.13f, steamBottom)
            steam1.quadTo(
                cx - size * 0.20f, (steamTop + steamBottom) / 2,
                cx - size * 0.13f, steamTop
            )
            canvas.drawPath(steam1, paint)

            val steam2 = Path()
            steam2.moveTo(cx + size * 0.05f, steamBottom)
            steam2.quadTo(
                cx - size * 0.02f, (steamTop + steamBottom) / 2,
                cx + size * 0.05f, steamTop
            )
            canvas.drawPath(steam2, paint)
        }

        // ------------------------------------------------------------
        // Kotlin — حرف K هندسي
        // ------------------------------------------------------------
        private fun drawKotlin(canvas: Canvas, cx: Float, cy: Float, size: Float) {
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt()

            val s = size * 0.36f

            // مثلث علوي
            val path = Path()
            path.moveTo(cx - s * 0.6f, cy - s * 0.7f)
            path.lineTo(cx - s * 0.6f, cy + s * 0.7f)
            path.lineTo(cx + s * 0.2f, cy)
            path.close()
            canvas.drawPath(path, paint)

            // خط مائل علوي
            val path2 = Path()
            path2.moveTo(cx - s * 0.1f, cy)
            path2.lineTo(cx + s * 0.7f, cy - s * 0.7f)
            path2.lineTo(cx + s * 0.7f, cy)
            path2.close()
            canvas.drawPath(path2, paint)

            // خط مائل سفلي
            val path3 = Path()
            path3.moveTo(cx + s * 0.2f, cy)
            path3.lineTo(cx + s * 0.7f, cy + s * 0.7f)
            path3.lineTo(cx - s * 0.1f, cy)
            path3.close()
            canvas.drawPath(path3, paint)
        }

        // ------------------------------------------------------------
        // React Native — ذرة React
        // ------------------------------------------------------------
        private fun drawReactNative(canvas: Canvas, cx: Float, cy: Float, size: Float) {
            val orbitRadius = size * 0.30f
            val stroke = size * 0.032f

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = 0xFF61DAFB.toInt()

            canvas.save()
            canvas.rotate(0f, cx, cy)
            canvas.drawOval(
                cx - orbitRadius, cy - orbitRadius * 0.36f,
                cx + orbitRadius, cy + orbitRadius * 0.36f,
                paint
            )
            canvas.restore()

            canvas.save()
            canvas.rotate(60f, cx, cy)
            canvas.drawOval(
                cx - orbitRadius, cy - orbitRadius * 0.36f,
                cx + orbitRadius, cy + orbitRadius * 0.36f,
                paint
            )
            canvas.restore()

            canvas.save()
            canvas.rotate(-60f, cx, cy)
            canvas.drawOval(
                cx - orbitRadius, cy - orbitRadius * 0.36f,
                cx + orbitRadius, cy + orbitRadius * 0.36f,
                paint
            )
            canvas.restore()

            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(cx, cy, size * 0.055f, paint)
        }
    }
}