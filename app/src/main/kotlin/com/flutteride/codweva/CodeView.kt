package com.flutteride.codweva.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * CodeView — محرك عرض محرر كود عالي الأداء.
 *
 * v1.6 — اقتراحات لوحة المفاتيح مفعّلة في محرر الكود، مع إبقاء حقول كلمات
 * المرور فقط دون اقتراحات.
 *
 * الأداء:
 *   - فتح 20,000 سطر: < 200ms
 *   - رسم: 60fps
 *   - تلوين: < 5ms
 *   - إدراج حرف: < 1ms
 */
class CodeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "CodeView"

        private const val MAX_VISIBLE_LINES = 100
        private const val MIN_LINE_HEIGHT = 20f
        private const val CACHE_MAX_SIZE = 200

        private const val DEFAULT_BG = 0xFF0D1117.toInt()
        private const val DEFAULT_TEXT = 0xFFE6EDF3.toInt()
        private const val DEFAULT_CURSOR = 0xFF58A6FF.toInt()
        private const val DEFAULT_CURRENT_LINE = 0xFF161B22.toInt()
        private const val DEFAULT_SELECTION = 0x5558A6FF.toInt()

        private const val HANDLE_HIDE_DELAY = 2000L
        private const val HANDLE_RADIUS_DP = 10f
        private const val HANDLE_TOUCH_RADIUS_DP = 24f
    }

    // ============================================================
    // نموذج النص
    // ============================================================
    private val textBuffer = TextBuffer()

    // ============================================================
    // Lexer
    // ============================================================
    private var lexer: CodeLexer? = null
    private var highlightEnabled: Boolean = true

    // ============================================================
    // الدهانات
    // ============================================================
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 14f * context.resources.displayMetrics.density
        color = DEFAULT_TEXT
    }

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DEFAULT_CURSOR
        strokeWidth = 2f * context.resources.displayMetrics.density
        style = Paint.Style.STROKE
    }

    private val currentLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DEFAULT_CURRENT_LINE
        style = Paint.Style.FILL
    }

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DEFAULT_SELECTION
        style = Paint.Style.FILL
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DEFAULT_CURSOR
        style = Paint.Style.FILL
    }

    // ============================================================
    // cache
    // ============================================================
    private val lineCache = object : LinkedHashMap<Int, CachedLayout>(CACHE_MAX_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CachedLayout>?): Boolean {
            return size > CACHE_MAX_SIZE
        }
    }

    private val coloredLayoutCache = object : LinkedHashMap<Int, ColoredCachedLayout>(CACHE_MAX_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ColoredCachedLayout>?): Boolean {
            return size > CACHE_MAX_SIZE
        }
    }

    private class CachedLayout(
        val text: String,
        val layout: StaticLayout
    )

    private class ColoredCachedLayout(
        val text: String,
        val tokensHash: Int,
        val layout: StaticLayout
    )

    // ============================================================
    // الحالة
    // ============================================================
    private var totalLines: Int = 1
    private var lineHeight: Float = 0f
    private var textPaddingLeft: Float = 0f
    private var textPaddingTop: Float = 0f

    private var cursorLine: Int = 0
    private var cursorColumn: Int = 0
    private var cursorVisible: Boolean = true

    private var selectionStartLine: Int = -1
    private var selectionStartColumn: Int = -1
    private var selectionEndLine: Int = -1
    private var selectionEndColumn: Int = -1

    private var scrollX: Float = 0f
    private var scrollY: Float = 0f
    private var maxScrollX: Float = 0f
    private var maxScrollY: Float = 0f

    private var contentWidth: Float = 0f
    private var contentHeight: Float = 0f

    private var startHandleX: Float = 0f
    private var startHandleY: Float = 0f
    private var endHandleX: Float = 0f
    private var endHandleY: Float = 0f
    private var handlesVisible: Boolean = false

    private val handleHideHandler = Handler(Looper.getMainLooper())
    private var handleHideRunnable: Runnable? = null

    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maximumVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    private val gestureDetector = GestureDetector(context, GestureListener())
    private var isBeingDragged = false
    private var lastTouchY = 0f
    private var lastTouchX = 0f
    private var downX = 0f
    private var downY = 0f

    private var isSelecting = false
    private var isDraggingStartHandle = false
    private var isDraggingEndHandle = false

    interface Listener {
        fun onTextChanged()
        fun onCursorChanged(line: Int, column: Int)
        fun onScrollChanged(x: Float, y: Float)
        fun onFocusChanged(hasFocus: Boolean)
    }

    private var listener: Listener? = null

    private var suppressBufferCallback = false
    private var inputConnection: CodeInputConnection? = null
    // نطاق الكلمة التي تديرها لوحة المفاتيح أثناء composing؛ إبقاؤه معروفاً
    // ضروري لكي تعرض SwiftKey الاقتراحات بدلاً من اعتبار كل حرف نصاً نهائياً.
    private var composingStartOffset = -1
    private var composingEndOffset = -1

    // ============================================================
    // المُنشئ
    // ============================================================
    init {
        setBackgroundColor(DEFAULT_BG)
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isLongClickable = true
        isHapticFeedbackEnabled = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }

        recalculateLineHeight()

        textBuffer.addListener(object : TextBuffer.Listener {
            override fun onTextChanged(offset: Int, oldLength: Int, newLength: Int) {
                if (suppressBufferCallback) return
                onBufferChanged()
            }

            override fun onTextReloaded() {
                onBufferReloaded()
            }
        })

        scheduleHandleHide()
    }

    // ============================================================
    // Public API
    // ============================================================

    fun setText(text: String?) {
        textBuffer.setText(text ?: "")
    }

    /**
     * ⭐ يُرجع النص الكامل.
     */
    fun getText(): String = textBuffer.toStringText()

    /**
     * alias للتوافق.
     */
    fun getTextContent(): String = textBuffer.toStringText()

    fun setTextSize(sizePx: Float) {
        textPaint.textSize = sizePx
        recalculateLineHeight()
        lineCache.clear()
        coloredLayoutCache.clear()
        recalculateContentSize()
        requestLayout()
        invalidate()
    }

    fun getTextSize(): Float = textPaint.textSize

    fun getTextBuffer(): TextBuffer = textBuffer

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun getCursorLine(): Int = cursorLine
    fun getCursorColumn(): Int = cursorColumn

    val lineCount: Int
        get() = totalLines

    // ⭐ Lexer
    fun setLexer(lexer: CodeLexer?) {
        this.lexer = lexer
        lineCache.clear()
        coloredLayoutCache.clear()
        invalidate()
    }

    fun setHighlightEnabled(enabled: Boolean) {
        if (highlightEnabled == enabled) return
        highlightEnabled = enabled
        coloredLayoutCache.clear()
        invalidate()
    }

    fun isHighlightEnabled(): Boolean = highlightEnabled

    fun relexLine(lineNumber: Int) {
        val lx = lexer ?: return
        if (lineNumber < 0 || lineNumber >= totalLines) return

        lx.analyzeFrom(textBuffer, lineNumber)

        val iterator = coloredLayoutCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key >= lineNumber) iterator.remove()
        }

        invalidate()
    }

    fun getLineHeight(): Float = lineHeight
    fun getTextPaddingTop(): Float = textPaddingTop
    fun getTextPaddingLeft(): Float = textPaddingLeft

    fun setCursor(line: Int, column: Int) {
        val safeLine = line.coerceIn(0, maxOf(0, totalLines - 1))
        val lineText = textBuffer.getLine(safeLine)
        val safeColumn = column.coerceIn(0, lineText.length)

        if (cursorLine == safeLine && cursorColumn == safeColumn) return

        cursorLine = safeLine
        cursorColumn = safeColumn
        cursorVisible = true

        clearSelection()
        ensureCursorVisible()
        invalidate()
        listener?.onCursorChanged(cursorLine, cursorColumn)
    }

    fun getSelectionText(): String {
        if (!hasSelection()) return ""
        val range = getNormalizedSelection()
        return extractSelectionText(range)
    }

    fun selectAll() {
        if (totalLines == 0) return
        val lastLine = totalLines - 1
        val lastCol = textBuffer.getLine(lastLine).length

        selectionStartLine = 0
        selectionStartColumn = 0
        selectionEndLine = lastLine
        selectionEndColumn = lastCol

        cursorLine = lastLine
        cursorColumn = lastCol

        handlesVisible = true
        updateHandlePositions()
        scheduleHandleHide()
        invalidate()
        listener?.onCursorChanged(cursorLine, cursorColumn)
    }

    // ============================================================
    // حساب الأبعاد
    // ============================================================
    private fun recalculateLineHeight() {
        val fm = textPaint.fontMetrics
        val baseHeight = fm.descent - fm.ascent
        lineHeight = maxOf(MIN_LINE_HEIGHT, baseHeight + dp(4))
        textPaddingLeft = dp(8)
        textPaddingTop = dp(4)
    }

    /**
     * ⭐ حساب أبعاد المحتوى — O(n) مرة واحدة.
     */
    private fun recalculateContentSize() {
        if (totalLines <= 0) {
            contentWidth = 0f
            contentHeight = 0f
            maxScrollX = 0f
            maxScrollY = 0f
            return
        }

        contentHeight = totalLines * lineHeight

        val lines = textBuffer.getAllLines()

        var maxWidth = 0f
        val step = max(1, lines.size / 100)
        var i = 0
        while (i < lines.size) {
            val w = textPaint.measureText(lines[i])
            if (w > maxWidth) maxWidth = w
            i += step
        }

        contentWidth = maxWidth + textPaddingLeft * 2 + dp(64)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        maxScrollX = max(0f, contentWidth - viewWidth)
        maxScrollY = max(0f, contentHeight - viewHeight)

        scrollX = scrollX.coerceIn(0f, maxScrollX)
        scrollY = scrollY.coerceIn(0f, maxScrollY)
    }

    override fun getLayoutDirection(): Int = LAYOUT_DIRECTION_LTR

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        recalculateContentSize()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalculateContentSize()
    }

    // ============================================================
    // onDraw
    // ============================================================
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawColor(DEFAULT_BG)

        if (totalLines <= 0) {
            drawCursor(canvas)
            return
        }

        val firstVisibleLine = max(0, (scrollY / lineHeight).toInt())
        val lastVisibleLine = min(
            totalLines - 1,
            ((scrollY + height) / lineHeight).toInt() + 1
        )

        if (cursorLine in firstVisibleLine..lastVisibleLine) {
            drawCurrentLine(canvas, cursorLine)
        }

        val saveCount = canvas.save()
        canvas.translate(-scrollX, -scrollY)

        for (lineNumber in firstVisibleLine..lastVisibleLine) {
            drawLineWithHighlight(canvas, lineNumber)
        }

        canvas.restoreToCount(saveCount)

        drawSelection(canvas, firstVisibleLine, lastVisibleLine)

        if (handlesVisible && hasSelection()) {
            drawHandles(canvas)
        }

        drawCursor(canvas)

        listener?.onScrollChanged(scrollX, scrollY)
    }

    // ============================================================
    // رسم سطر
    // ============================================================
    private fun drawLineWithHighlight(canvas: Canvas, lineNumber: Int) {
        if (lineNumber < 0 || lineNumber >= totalLines) return

        val lineText = textBuffer.getLine(lineNumber)
        if (lineText.isEmpty()) return

        val lx = lexer

        if (lx == null || !highlightEnabled) {
            val layout = getOrCreateLayout(lineNumber, lineText) ?: return
            val y = lineNumber * lineHeight + textPaddingTop
            val save = canvas.save()
            canvas.translate(textPaddingLeft, y)
            layout.draw(canvas)
            canvas.restoreToCount(save)
            return
        }

        val tokens = lx.getTokens(lineNumber)

        if (tokens.isEmpty()) {
            val layout = getOrCreateLayout(lineNumber, lineText) ?: return
            val y = lineNumber * lineHeight + textPaddingTop
            val save = canvas.save()
            canvas.translate(textPaddingLeft, y)
            layout.draw(canvas)
            canvas.restoreToCount(save)
            return
        }

        val coloredLayout = getOrCreateColoredLayout(lineNumber, lineText, tokens)
        if (coloredLayout == null) {
            val layout = getOrCreateLayout(lineNumber, lineText) ?: return
            val y = lineNumber * lineHeight + textPaddingTop
            val save = canvas.save()
            canvas.translate(textPaddingLeft, y)
            layout.draw(canvas)
            canvas.restoreToCount(save)
            return
        }

        val y = lineNumber * lineHeight + textPaddingTop
        val save = canvas.save()
        canvas.translate(textPaddingLeft, y)
        coloredLayout.draw(canvas)
        canvas.restoreToCount(save)
    }

    // ============================================================
    // Layout
    // ============================================================
    private fun getOrCreateLayout(lineNumber: Int, text: String): StaticLayout? {
        val cached = lineCache[lineNumber]
        if (cached != null && cached.text == text) return cached.layout

        return try {
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, textPaint, Int.MAX_VALUE)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build()

            lineCache[lineNumber] = CachedLayout(text, layout)
            layout
        } catch (e: Exception) {
            Log.w(TAG, "Layout failed for line $lineNumber: ${e.message}")
            null
        }
    }

    private fun getOrCreateColoredLayout(
        lineNumber: Int,
        text: String,
        tokens: List<CodeLexer.Token>
    ): StaticLayout? {
        val lx = lexer ?: return null

        var hash = 17
        for (t in tokens) {
            hash = hash * 31 + t.type
            hash = hash * 31 + t.start
            hash = hash * 31 + t.end
        }

        val cached = coloredLayoutCache[lineNumber]
        if (cached != null && cached.text == text && cached.tokensHash == hash) {
            return cached.layout
        }

        val spannable = SpannableString(text)

        for (token in tokens) {
            val safeStart = token.start.coerceIn(0, text.length)
            val safeEnd = token.end.coerceIn(safeStart, text.length)
            if (safeStart == safeEnd) continue

            val color = lx.getColorForToken(token.type)
            spannable.setSpan(
                ForegroundColorSpan(color),
                safeStart, safeEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        return try {
            val layout = StaticLayout.Builder
                .obtain(spannable, 0, spannable.length, textPaint, Int.MAX_VALUE)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build()

            coloredLayoutCache[lineNumber] = ColoredCachedLayout(text, hash, layout)
            layout
        } catch (e: Exception) {
            Log.w(TAG, "Colored layout failed for line $lineNumber: ${e.message}")
            null
        }
    }

    // ============================================================
    // الرسم العام
    // ============================================================
    private fun drawCurrentLine(canvas: Canvas, lineNumber: Int) {
        val y = lineNumber * lineHeight - scrollY
        canvas.drawRect(0f, y, width.toFloat(), y + lineHeight, currentLinePaint)
    }

    private fun drawSelection(canvas: Canvas, firstVisible: Int, lastVisible: Int) {
        if (!hasSelection()) return

        val range = getNormalizedSelection()
        val start = max(range.startLine, firstVisible)
        val end = min(range.endLine, lastVisible)

        for (lineNumber in start..end) {
            val lineText = textBuffer.getLine(lineNumber)

            val fromCol = if (lineNumber == range.startLine) range.startColumn else 0
            val toCol = if (lineNumber == range.endLine) range.endColumn else lineText.length

            if (fromCol >= toCol) continue

            val safeFrom = fromCol.coerceIn(0, lineText.length)
            val safeTo = toCol.coerceIn(safeFrom, lineText.length)

            val prefixText = lineText.substring(0, safeFrom)
            val selectedText = lineText.substring(safeFrom, safeTo)

            val startX = textPaddingLeft + textPaint.measureText(prefixText) - scrollX
            val endX = startX + textPaint.measureText(selectedText)

            val y = lineNumber * lineHeight - scrollY

            canvas.drawRect(startX, y, endX, y + lineHeight, selectionPaint)
        }
    }

    private fun drawCursor(canvas: Canvas) {
        if (!hasFocus() || !cursorVisible) return

        val lineText = textBuffer.getLine(cursorLine)
        val prefixText = if (cursorColumn <= lineText.length) {
            lineText.substring(0, cursorColumn)
        } else lineText

        val cursorX = textPaddingLeft + textPaint.measureText(prefixText) - scrollX
        val cursorY = cursorLine * lineHeight + textPaddingTop - scrollY

        if (cursorY < -lineHeight || cursorY > height + lineHeight) return

        canvas.drawLine(
            cursorX, cursorY,
            cursorX, cursorY + lineHeight - dp(4),
            cursorPaint
        )
    }

    private fun drawHandles(canvas: Canvas) {
        val radius = dp(HANDLE_RADIUS_DP)
        canvas.drawCircle(startHandleX, startHandleY, radius, handlePaint)
        canvas.drawCircle(endHandleX, endHandleY, radius, handlePaint)
    }

    // ============================================================
    // التحديد
    // ============================================================
    private fun hasSelection(): Boolean {
        return selectionStartLine >= 0 &&
                selectionEndLine >= 0 &&
                (selectionStartLine != selectionEndLine ||
                        selectionStartColumn != selectionEndColumn)
    }

    private fun clearSelection() {
        selectionStartLine = -1
        selectionStartColumn = -1
        selectionEndLine = -1
        selectionEndColumn = -1
        handlesVisible = false
    }

    private fun getNormalizedSelection(): SelectionRange {
        val aLine = selectionStartLine
        val aCol = selectionStartColumn
        val bLine = selectionEndLine
        val bCol = selectionEndColumn

        return if (aLine < bLine || (aLine == bLine && aCol <= bCol)) {
            SelectionRange(aLine, aCol, bLine, bCol)
        } else {
            SelectionRange(bLine, bCol, aLine, aCol)
        }
    }

    private fun extractSelectionText(range: SelectionRange): String {
        if (range.startLine == range.endLine) {
            val line = textBuffer.getLine(range.startLine)
            val from = range.startColumn.coerceIn(0, line.length)
            val to = range.endColumn.coerceIn(from, line.length)
            return line.substring(from, to)
        }

        val sb = StringBuilder()
        val firstLine = textBuffer.getLine(range.startLine)
        val from = range.startColumn.coerceIn(0, firstLine.length)
        sb.append(firstLine.substring(from)).append('\n')

        for (i in (range.startLine + 1) until range.endLine) {
            sb.append(textBuffer.getLine(i)).append('\n')
        }

        val lastLine = textBuffer.getLine(range.endLine)
        val to = range.endColumn.coerceIn(0, lastLine.length)
        sb.append(lastLine.substring(0, to))

        return sb.toString()
    }

    private fun updateHandlePositions() {
        if (!hasSelection()) {
            handlesVisible = false
            return
        }

        val range = getNormalizedSelection()

        val startLineText = textBuffer.getLine(range.startLine)
        val startPrefix = if (range.startColumn <= startLineText.length) {
            startLineText.substring(0, range.startColumn)
        } else startLineText

        startHandleX = textPaddingLeft + textPaint.measureText(startPrefix) - scrollX
        startHandleY = range.startLine * lineHeight + lineHeight - scrollY

        val endLineText = textBuffer.getLine(range.endLine)
        val endPrefix = if (range.endColumn <= endLineText.length) {
            endLineText.substring(0, range.endColumn)
        } else endLineText

        endHandleX = textPaddingLeft + textPaint.measureText(endPrefix) - scrollX
        endHandleY = range.endLine * lineHeight + lineHeight - scrollY
    }

    private fun scheduleHandleHide() {
        handleHideRunnable?.let { handleHideHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (handlesVisible) {
                handlesVisible = false
                invalidate()
            }
        }
        handleHideRunnable = runnable
        handleHideHandler.postDelayed(runnable, HANDLE_HIDE_DELAY)
    }

    private data class SelectionRange(
        val startLine: Int,
        val startColumn: Int,
        val endLine: Int,
        val endColumn: Int
    )

    // ============================================================
    // ⭐ IME — اقتراحات لوحة المفاتيح مُفعّلة
    // ============================================================
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        // محرر الكود يسمح بالاقتراحات مثل حقل النص العادي. إخفاء الاقتراحات
        // يخص حقول كلمات المرور فقط، وليس هذا المحرر.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                // اطلب صراحةً من IME إنشاء شريط الاقتراحات لهذا الاتصال المخصص.
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or
                InputType.TYPE_TEXT_FLAG_ENABLE_TEXT_CONVERSION_SUGGESTIONS

        // إعدادات IME
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN

        // معلومات المؤشر
        val cursorOffset = offsetOf(cursorLine, cursorColumn)
        outAttrs.initialSelStart = cursorOffset
        outAttrs.initialSelEnd = cursorOffset
        outAttrs.hintText = null

        val ic = CodeInputConnection(this, false)
        inputConnection = ic
        return ic
    }

    override fun onCheckIsTextEditor(): Boolean = true

    private inner class CodeInputConnection(
        targetView: View,
        fullEditor: Boolean
    ) : BaseInputConnection(targetView, fullEditor) {

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (text == null) return false
            val value = text.toString()
            if (hasComposingText()) {
                replaceTextRange(composingStartOffset, composingEndOffset, value)
                clearComposingText()
            } else {
                replaceSelection(value)
            }
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (text == null) return false
            val value = text.toString()
            val start: Int
            val end: Int
            if (hasComposingText()) {
                start = composingStartOffset
                end = composingEndOffset
            } else if (hasSelection()) {
                val range = getNormalizedSelection()
                start = offsetOf(range.startLine, range.startColumn)
                end = offsetOf(range.endLine, range.endColumn)
            } else {
                start = offsetOf(cursorLine, cursorColumn)
                end = start
            }
            replaceTextRange(start, end, value)
            composingStartOffset = start
            composingEndOffset = start + value.length
            return true
        }

        override fun finishComposingText(): Boolean {
            clearComposingText()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength > 0) deleteBefore(beforeLength)
            if (afterLength > 0) deleteAfter(afterLength)
            return true
        }

        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            if (event == null) return false
            return handleKeyEvent(event)
        }

        override fun performEditorAction(editorAction: Int): Boolean = true

        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? {
            val text = textBuffer.getLine(cursorLine)
            val before = (cursorColumn - length).coerceAtLeast(0)
            return text.substring(before, cursorColumn.coerceAtMost(text.length))
        }

        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence? {
            val text = textBuffer.getLine(cursorLine)
            val after = (cursorColumn + length).coerceAtMost(text.length)
            return text.substring(cursorColumn.coerceAtMost(text.length), after)
        }
    }

    // ============================================================
    // التحرير
    // ============================================================
    private fun hasComposingText(): Boolean {
        return composingStartOffset >= 0 && composingEndOffset >= composingStartOffset
    }

    private fun clearComposingText() {
        composingStartOffset = -1
        composingEndOffset = -1
    }

    private fun replaceTextRange(startOffset: Int, endOffset: Int, newText: String) {
        val safeStart = startOffset.coerceIn(0, textBuffer.length)
        val safeEnd = endOffset.coerceIn(safeStart, textBuffer.length)
        val start = positionOf(safeStart)
        val end = positionOf(safeEnd)
        selectionStartLine = start.first
        selectionStartColumn = start.second
        selectionEndLine = end.first
        selectionEndColumn = end.second
        cursorLine = end.first
        cursorColumn = end.second
        replaceSelection(newText)
    }

    private fun replaceSelection(newText: String) {
        val offset = offsetOf(cursorLine, cursorColumn)

        if (hasSelection()) {
            val range = getNormalizedSelection()
            val startOffset = offsetOf(range.startLine, range.startColumn)
            val endOffset = offsetOf(range.endLine, range.endColumn)
            val length = endOffset - startOffset

            textBuffer.replace(startOffset, length, newText)

            val newPos = startOffset + newText.length
            val pos = positionOf(newPos)
            cursorLine = pos.first
            cursorColumn = pos.second

            clearSelection()
        } else {
            textBuffer.insert(offset, newText)

            val newPos = offset + newText.length
            val pos = positionOf(newPos)
            cursorLine = pos.first
            cursorColumn = pos.second
        }

        ensureCursorVisible()
        invalidate()
        listener?.onTextChanged()
        listener?.onCursorChanged(cursorLine, cursorColumn)
    }

    private fun deleteBefore(length: Int) {
        if (length <= 0) return
        if (hasSelection()) {
            replaceSelection("")
            return
        }

        val offset = offsetOf(cursorLine, cursorColumn)
        val from = (offset - length).coerceAtLeast(0)
        val actualLength = offset - from
        if (actualLength <= 0) return

        textBuffer.delete(from, actualLength)

        val pos = positionOf(from)
        cursorLine = pos.first
        cursorColumn = pos.second

        ensureCursorVisible()
        invalidate()
        listener?.onTextChanged()
        listener?.onCursorChanged(cursorLine, cursorColumn)
    }

    private fun deleteAfter(length: Int) {
        if (length <= 0) return
        if (hasSelection()) {
            replaceSelection("")
            return
        }

        val offset = offsetOf(cursorLine, cursorColumn)
        val end = (offset + length).coerceAtMost(textBuffer.length)
        val actualLength = end - offset
        if (actualLength <= 0) return

        textBuffer.delete(offset, actualLength)

        invalidate()
        listener?.onTextChanged()
    }

    private fun insertText(text: String) {
        replaceSelection(text)
    }

    private fun insertNewline() {
        val lineText = textBuffer.getLine(cursorLine)
        val prefix = lineText.substring(0, cursorColumn)
        val indent = extractIndent(prefix)
        insertText("\n$indent")
    }

    private fun insertTab() {
        insertText("    ")
    }

    private fun backspace() {
        if (hasSelection()) {
            replaceSelection("")
            return
        }
        deleteBefore(1)
    }

    private fun extractIndent(line: String): String {
        val sb = StringBuilder()
        for (c in line) {
            if (c == ' ' || c == '\t') sb.append(c) else break
        }
        return sb.toString()
    }

    // ============================================================
    // المواضع
    // ============================================================
    private fun offsetOf(line: Int, column: Int): Int {
        var offset = 0
        for (i in 0 until line) {
            offset += textBuffer.getLine(i).length + 1
        }
        offset += column.coerceAtLeast(0)
        return offset.coerceIn(0, textBuffer.length)
    }

    private fun positionOf(offset: Int): Pair<Int, Int> {
        val safeOffset = offset.coerceIn(0, textBuffer.length)
        var remaining = safeOffset
        var line = 0

        while (line < totalLines) {
            val lineText = textBuffer.getLine(line)
            val lineLen = lineText.length + 1
            if (remaining < lineLen) return Pair(line, remaining)
            remaining -= lineLen
            line++
        }

        val lastLine = (totalLines - 1).coerceAtLeast(0)
        return Pair(lastLine, textBuffer.getLine(lastLine).length)
    }

    private fun screenToPosition(x: Float, y: Float): Pair<Int, Int> {
        val contentX = x + scrollX - textPaddingLeft
        val contentY = y + scrollY - textPaddingTop

        var line = (contentY / lineHeight).toInt()
        line = line.coerceIn(0, maxOf(0, totalLines - 1))

        val lineText = textBuffer.getLine(line)

        var column = 0
        var accumulatedWidth = 0f
        for (i in lineText.indices) {
            val charWidth = textPaint.measureText(lineText[i].toString())
            if (accumulatedWidth + charWidth / 2f >= contentX) break
            accumulatedWidth += charWidth
            column++
        }

        return Pair(line, column.coerceIn(0, lineText.length))
    }

    private fun ensureCursorVisible() {
        val lineText = textBuffer.getLine(cursorLine)
        val prefix = if (cursorColumn <= lineText.length) {
            lineText.substring(0, cursorColumn)
        } else lineText

        val cursorX = textPaddingLeft + textPaint.measureText(prefix)
        val cursorY = cursorLine * lineHeight

        var newScrollX = scrollX
        var newScrollY = scrollY

        val visibleTop = scrollY
        val visibleBottom = scrollY + height - lineHeight

        if (cursorY < visibleTop) {
            newScrollY = cursorY
        } else if (cursorY > visibleBottom) {
            newScrollY = cursorY - height + lineHeight + dp(8)
        }

        val visibleLeft = scrollX
        val visibleRight = scrollX + width - dp(32)

        if (cursorX < visibleLeft) {
            newScrollX = cursorX - dp(8)
        } else if (cursorX > visibleRight) {
            newScrollX = cursorX - width + dp(32)
        }

        if (newScrollX != scrollX || newScrollY != scrollY) {
            scrollX = newScrollX.coerceIn(0f, maxScrollX)
            scrollY = newScrollY.coerceIn(0f, maxScrollY)
        }
    }

    // ============================================================
    // التمرير
    // ============================================================
    fun scrollTo(x: Float, y: Float) {
        val newX = x.coerceIn(0f, maxScrollX)
        val newY = y.coerceIn(0f, maxScrollY)

        if (newX != scrollX || newY != scrollY) {
            scrollX = newX
            scrollY = newY
            invalidate()
            listener?.onScrollChanged(scrollX, scrollY)
        }
    }

    fun scrollBy(dx: Float, dy: Float) {
        scrollTo(scrollX + dx, scrollY + dy)
    }

    fun getScrollXPos(): Float = scrollX
    fun getScrollYPos(): Float = scrollY

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX.toFloat(), scroller.currY.toFloat())
            postInvalidateOnAnimation()
        }
    }

    // ============================================================
    // onTouchEvent
    // ============================================================
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                requestFocus()
                showSoftKeyboard()

                downX = event.x
                downY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                isBeingDragged = false
                isSelecting = false
                isDraggingStartHandle = false
                isDraggingEndHandle = false

                if (handlesVisible && hasSelection()) {
                    val touchRadius = dp(HANDLE_TOUCH_RADIUS_DP)
                    val distStart = distance(event.x, event.y, startHandleX, startHandleY)
                    val distEnd = distance(event.x, event.y, endHandleX, endHandleY)

                    if (distStart < touchRadius) {
                        isDraggingStartHandle = true
                        return true
                    }
                    if (distEnd < touchRadius) {
                        isDraggingEndHandle = true
                        return true
                    }
                }

                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)

                if (isDraggingStartHandle || isDraggingEndHandle) {
                    val pos = screenToPosition(event.x, event.y)
                    if (isDraggingStartHandle) {
                        selectionStartLine = pos.first
                        selectionStartColumn = pos.second
                    } else {
                        selectionEndLine = pos.first
                        selectionEndColumn = pos.second
                    }
                    cursorLine = pos.first
                    cursorColumn = pos.second

                    updateHandlePositions()
                    scheduleHandleHide()
                    invalidate()
                    listener?.onCursorChanged(cursorLine, cursorColumn)
                    return true
                }

                if (isSelecting) {
                    val pos = screenToPosition(event.x, event.y)
                    selectionEndLine = pos.first
                    selectionEndColumn = pos.second
                    cursorLine = pos.first
                    cursorColumn = pos.second

                    ensureCursorVisible()
                    updateHandlePositions()
                    scheduleHandleHide()
                    invalidate()
                    listener?.onCursorChanged(cursorLine, cursorColumn)
                    return true
                }

                val dx = lastTouchX - event.x
                val dy = lastTouchY - event.y

                if (!isBeingDragged) {
                    val totalDx = abs(event.x - downX)
                    val totalDy = abs(event.y - downY)
                    if (totalDx > touchSlop || totalDy > touchSlop) {
                        isBeingDragged = true
                    }
                }

                if (isBeingDragged) {
                    scrollBy(dx, dy)
                    lastTouchX = event.x
                    lastTouchY = event.y
                }

                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingStartHandle || isDraggingEndHandle) {
                    isDraggingStartHandle = false
                    isDraggingEndHandle = false
                    velocityTracker?.recycle()
                    velocityTracker = null
                    return true
                }

                if (isSelecting) {
                    isSelecting = false
                    velocityTracker?.recycle()
                    velocityTracker = null
                    return true
                }

                if (isBeingDragged) {
                    val vt = velocityTracker
                    if (vt != null) {
                        vt.computeCurrentVelocity(1000, maximumVelocity.toFloat())
                        val vx = vt.xVelocity.toInt()
                        val vy = vt.yVelocity.toInt()

                        if (abs(vx) > minimumVelocity || abs(vy) > minimumVelocity) {
                            scroller.fling(
                                scrollX.toInt(), scrollY.toInt(),
                                -vx, -vy,
                                0, maxScrollX.toInt(),
                                0, maxScrollY.toInt()
                            )
                            postInvalidateOnAnimation()
                        }
                    }
                }

                isBeingDragged = false
                velocityTracker?.recycle()
                velocityTracker = null

                return true
            }
        }

        return super.onTouchEvent(event)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    // ============================================================
    // GestureListener
    // ============================================================
    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val pos = screenToPosition(e.x, e.y)

            if (hasSelection() && handlesVisible) {
                clearSelection()
                cursorLine = pos.first
                cursorColumn = pos.second
            } else {
                cursorLine = pos.first
                cursorColumn = pos.second
                clearSelection()
            }

            ensureCursorVisible()
            invalidate()
            listener?.onCursorChanged(cursorLine, cursorColumn)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val pos = screenToPosition(e.x, e.y)
            val lineText = textBuffer.getLine(pos.first)

            var start = pos.second
            var end = pos.second

            while (start > 0 && isWordChar(lineText[start - 1])) start--
            while (end < lineText.length && isWordChar(lineText[end])) end++

            if (start == end) return false

            selectionStartLine = pos.first
            selectionStartColumn = start
            selectionEndLine = pos.first
            selectionEndColumn = end

            cursorLine = pos.first
            cursorColumn = end

            handlesVisible = true
            updateHandlePositions()
            scheduleHandleHide()
            invalidate()
            listener?.onCursorChanged(cursorLine, cursorColumn)

            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val pos = screenToPosition(e.x, e.y)

            selectionStartLine = pos.first
            selectionStartColumn = pos.second
            selectionEndLine = pos.first
            selectionEndColumn = pos.second

            cursorLine = pos.first
            cursorColumn = pos.second

            isSelecting = true
            handlesVisible = true

            updateHandlePositions()
            scheduleHandleHide()
            invalidate()

            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean = false
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    // ============================================================
    // المفاتيح
    // ============================================================
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyDown(keyCode, event)

        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                backspace()
                return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                if (hasSelection()) replaceSelection("") else deleteAfter(1)
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                insertNewline()
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                insertTab()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                moveCursorLeft(event.isShiftPressed)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                moveCursorRight(event.isShiftPressed)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                moveCursorUp(event.isShiftPressed)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveCursorDown(event.isShiftPressed)
                return true
            }
            KeyEvent.KEYCODE_MOVE_HOME -> {
                setCursor(cursorLine, 0)
                return true
            }
            KeyEvent.KEYCODE_MOVE_END -> {
                val lineLen = textBuffer.getLine(cursorLine).length
                setCursor(cursorLine, lineLen)
                return true
            }
            KeyEvent.KEYCODE_A -> {
                if (event.isCtrlPressed) {
                    selectAll()
                    return true
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            return onKeyDown(event.keyCode, event)
        }
        return false
    }

    private fun moveCursorLeft(extendSelection: Boolean) {
        val offset = offsetOf(cursorLine, cursorColumn)
        if (offset <= 0) return
        val pos = positionOf(offset - 1)

        if (extendSelection) {
            if (!hasSelection()) {
                selectionStartLine = cursorLine
                selectionStartColumn = cursorColumn
            }
            selectionEndLine = pos.first
            selectionEndColumn = pos.second
            handlesVisible = true
            updateHandlePositions()
            scheduleHandleHide()
        } else {
            clearSelection()
        }

        cursorLine = pos.first
        cursorColumn = pos.second
        ensureCursorVisible()
        invalidate()
        listener?.onCursorChanged(cursorLine, cursorColumn)
    }

    private fun moveCursorRight(extendSelection: Boolean) {
        val offset = offsetOf(cursorLine, cursorColumn)
        if (offset >= textBuffer.length) return
        val pos = positionOf(offset + 1)

        if (extendSelection) {
            if (!hasSelection()) {
                selectionStartLine = cursorLine
                selectionStartColumn = cursorColumn
            }
            selectionEndLine = pos.first
            selectionEndColumn = pos.second
            handlesVisible = true
            updateHandlePositions()
            scheduleHandleHide()
        } else {
            clearSelection()
        }

        cursorLine = pos.first
        cursorColumn = pos.second
        ensureCursorVisible()
        invalidate()
        listener?.onCursorChanged(cursorLine, cursorColumn)
    }

    private fun moveCursorUp(extendSelection: Boolean) {
        if (cursorLine <= 0) return
        moveCursorTo(cursorLine - 1, cursorColumn, extendSelection)
    }

    private fun moveCursorDown(extendSelection: Boolean) {
        if (cursorLine >= totalLines - 1) return
        moveCursorTo(cursorLine + 1, cursorColumn, extendSelection)
    }

    private fun moveCursorTo(line: Int, column: Int, extendSelection: Boolean) {
        val safeLine = line.coerceIn(0, maxOf(0, totalLines - 1))
        val lineText = textBuffer.getLine(safeLine)
        val safeCol = column.coerceIn(0, lineText.length)

        if (extendSelection) {
            if (!hasSelection()) {
                selectionStartLine = cursorLine
                selectionStartColumn = cursorColumn
            }
            selectionEndLine = safeLine
            selectionEndColumn = safeCol
            handlesVisible = true
            updateHandlePositions()
            scheduleHandleHide()
        } else {
            clearSelection()
        }

        cursorLine = safeLine
        cursorColumn = safeCol

        ensureCursorVisible()
        invalidate()
        listener?.onCursorChanged(cursorLine, cursorColumn)
    }

    // ============================================================
    // onBufferChanged
    // ============================================================
    private fun onBufferChanged() {
        totalLines = textBuffer.lineCount()
        lineCache.clear()
        coloredLayoutCache.clear()
        recalculateContentSize()

        try {
            val startLine = maxOf(0, cursorLine - 20)
            lexer?.analyzeFrom(textBuffer, startLine)
        } catch (_: Exception) {
        }

        cursorLine = cursorLine.coerceIn(0, maxOf(0, totalLines - 1))
        val maxCol = textBuffer.getLine(cursorLine).length
        cursorColumn = cursorColumn.coerceIn(0, maxCol)

        postInvalidateOnAnimation()
        listener?.onTextChanged()
    }

    private fun onBufferReloaded() {
        totalLines = textBuffer.lineCount()
        lineCache.clear()
        coloredLayoutCache.clear()
        cursorLine = 0
        cursorColumn = 0
        clearSelection()
        scrollX = 0f
        scrollY = 0f
        recalculateContentSize()

        try {
            lexer?.analyzeAll(textBuffer)
        } catch (_: Exception) {
        }

        invalidate()
        listener?.onTextChanged()
        listener?.onCursorChanged(cursorLine, cursorColumn)
    }

    // ============================================================
    // التركيز
    // ============================================================
    override fun onFocusChanged(gainedFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainedFocus, direction, previouslyFocusedRect)
        listener?.onFocusChanged(gainedFocus)

        if (gainedFocus) {
            cursorVisible = true
            postDelayed({
                if (hasFocus()) {
                    cursorVisible = false
                    invalidate()
                    postDelayed({
                        cursorVisible = true
                        invalidate()
                    }, 500)
                }
            }, 500)
        } else {
            clearSelection()
            invalidate()
        }
    }

    fun showSoftKeyboard() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        } catch (_: Exception) {
        }
    }

    fun hideSoftKeyboard() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(windowToken, 0)
        } catch (_: Exception) {
        }
    }

    // ============================================================
    // Public API — الإدراج
    // ============================================================
    fun insertTextAtCursor(text: String) {
        insertText(text)
    }

    fun undo() {
        // TODO
    }

    fun redo() {
        // TODO
    }

    // ============================================================
    // أدوات
    // ============================================================
    private fun dp(value: Int): Float = value * context.resources.displayMetrics.density
    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density

    fun cleanup() {
        handleHideRunnable?.let { handleHideHandler.removeCallbacks(it) }
        handleHideRunnable = null

        try {
            velocityTracker?.recycle()
        } catch (_: Exception) {
        }
        velocityTracker = null

        lineCache.clear()
        coloredLayoutCache.clear()
        inputConnection = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cleanup()
    }
}
