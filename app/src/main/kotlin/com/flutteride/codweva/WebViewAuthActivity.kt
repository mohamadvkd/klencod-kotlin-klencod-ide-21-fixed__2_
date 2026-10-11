package com.flutteride.codweva

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * نشاط داخلي لفتح صفحة GitHub داخل التطبيق، مع ملء رمز التحقق تلقائياً.
 * GitHub يستخدم حقول OTP منفصلة، لذا نوزع الرمز حرفاً حرفاً عبر JavaScript.
 */
class WebViewAuthActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private var userCode: String? = null

    private fun dp(value: Int): Int {
        return Math.round(value * resources.displayMetrics.density)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        userCode = intent.getStringExtra(EXTRA_USER_CODE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(10, 14, 20))
        }

        // شريط علوي
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.rgb(20, 27, 36))
        }

        val close = Button(this).apply {
            text = "إغلاق"
            setTextColor(Color.WHITE)
            isAllCaps = false
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }
        topBar.addView(close)

        val title = TextView(this).apply {
            text = "تسجيل الدخول إلى GitHub"
            setTextColor(Color.WHITE)
            setTextSize(15f)
            setPadding(dp(16), 0, 0, 0)
        }
        topBar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))

        root.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        statusView = TextView(this).apply {
            text = "جارٍ تحميل صفحة GitHub، والرمز سيُملأ تلقائياً..."
            setTextColor(Color.rgb(157, 171, 187))
            setTextSize(12f)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        root.addView(statusView, LinearLayout.LayoutParams(-1, -2))

        webView = WebView(this).apply {
            val settings: WebSettings = settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    return handleUrl(request?.url?.toString())
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    return handleUrl(url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    statusView.text = "إذا لم يُملأ الرمز تلقائياً، الصقه من الحافظة."
                    userCode?.takeIf { it.isNotEmpty() }?.let {
                        fillOtpFields(view ?: return, it)
                    }
                }
            }
        }

        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)

        val url = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
            ?: GitHubConfig.VERIFICATION_URL
        webView.loadUrl(url)
    }

    /**
     * يوزع رمز OTP على مربعات GitHub المنفصلة.
     * GitHub يستخدم حقول OTP بحرف واحد لكل مربع، لذا نوزع الرمز حرفاً حرفاً.
     */
    private fun fillOtpFields(view: WebView, code: String) {
        val cleanCode = code.replace("-", "").trim()
        if (cleanCode.isEmpty()) return

        val js = """
            (function() {
                var code = '$cleanCode';
                var inputs = document.querySelectorAll('input');
                var otpInputs = [];
                for (var i = 0; i < inputs.length; i++) {
                    var inp = inputs[i];
                    var isOtp = (inp.maxLength === 1) ||
                                (inp.inputMode === 'numeric') ||
                                (inp.type === 'tel') ||
                                (inp.getAttribute('autocomplete') === 'one-time-code');
                    if (isOtp && !inp.disabled && !inp.readOnly) {
                        otpInputs.push(inp);
                    }
                }
                if (otpInputs.length >= code.length) {
                    for (var j = 0; j < code.length; j++) {
                        otpInputs[j].value = code[j];
                        otpInputs[j].dispatchEvent(new Event('input', { bubbles: true }));
                        otpInputs[j].dispatchEvent(new Event('change', { bubbles: true }));
                        otpInputs[j].dispatchEvent(new KeyboardEvent('keyup', { bubbles: true }));
                    }
                    otpInputs[code.length - 1].focus();
                }
            })();
        """.trimIndent()

        view.evaluateJavascript(js, null)
    }

    private fun handleUrl(url: String?): Boolean {
        if (url == null) return false
        if (url.startsWith("flutteride://")) {
            statusView.text = "تم استلام التفويض. جارٍ العودة..."
            finish()
            return true
        }
        return false
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    companion object {
        const val EXTRA_URL = "auth_url"
        const val EXTRA_USER_CODE = "auth_user_code"
    }
}