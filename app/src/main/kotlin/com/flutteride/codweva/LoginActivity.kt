package com.flutteride.codweva

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * شاشة تسجيل الدخول إلى GitHub عبر Device Flow.
 */
class LoginActivity : Activity() {

    private val colorBg = Color.rgb(10, 14, 20)
    private val colorSurface = Color.rgb(20, 27, 36)
    private val colorAlt = Color.rgb(27, 36, 47)
    private val colorBorder = Color.rgb(48, 62, 77)
    private val colorText = Color.rgb(245, 247, 250)
    private val colorMuted = Color.rgb(157, 171, 187)
    private val colorBlue = Color.rgb(55, 145, 255)

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var codeView: TextView
    private lateinit var connect: Button
    private lateinit var secureStore: SecureStore

    private var verificationUri: String = GitHubConfig.VERIFICATION_URL
    private var pendingDeviceCode: GitHubApi.DeviceCode? = null

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = colorBg
        window.navigationBarColor = colorBg

        secureStore = SecureStore(this)
        buildUi()
        refreshConnection()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val data = intent?.data
        if (data != null && data.scheme == "flutteride") {
            val codeValue = data.getQueryParameter("code")
            if (!codeValue.isNullOrEmpty()) {
                Toast.makeText(this, "تم استلام الرمز، جارٍ التحقق...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(18))
            setBackgroundColor(colorBg)
        }

        val eyebrow = text("KLENCOD  /  CONNECT", 11f, colorBlue, true).apply {
            letterSpacing = 0.08f
        }
        root.addView(eyebrow)

        val title = text("اربط مساحة العمل بـ GitHub", 27f, colorText, true)
        root.addView(title, params(0, 6, 0, 0))

        root.addView(
            text("تفويض رسمي من دون خادم وسيط", 13f, colorMuted, false),
            params(0, 3, 0, 20)
        )

        val explanation = card()
        explanation.addView(text("كيف يعمل الربط؟", 17f, colorText, true))
        val body = text(
            "يتم طلب رمز مؤقت من GitHub. سيتم ملء الرمز تلقائياً في صفحة التحقق. " +
                    "أنت فقط تضغط Continue ثم توافق. لا يتم حفظ كلمة المرور.",
            14f, colorMuted, false
        ).apply {
            setLineSpacing(0f, 1.15f)
        }
        explanation.addView(body, params(0, 8, 0, 0))
        root.addView(explanation, params(0, 0, 0, 14))

        status = text("جارٍ التحقق من الحالة...", 14f, colorMuted, false)
        root.addView(status, params(2, 0, 0, 10))

        codeView = text("", 30f, colorText, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.14f
            background = round(colorAlt, 20, colorBorder)
        }
        root.addView(codeView, params(0, 0, 0, 10, 74))

        val copy = button("نسخ رمز التحقق", colorAlt, colorText)
        root.addView(copy, params(0, 0, 0, 8, 48))
        copy.setOnClickListener {
            val value = codeView.text.toString()
            if (value.trim().isEmpty()) return@setOnClickListener
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("GitHub code", value))
            Toast.makeText(this, "تم نسخ الرمز", Toast.LENGTH_SHORT).show()
        }

        val open = button("فتح صفحة التحقق داخل التطبيق", colorBlue, Color.WHITE)
        root.addView(open, params(0, 0, 0, 8, 50))
        open.setOnClickListener { openVerificationPage() }

        connect = button("بدء تسجيل الدخول", colorBlue, Color.WHITE)
        root.addView(connect, params(0, 0, 0, 8, 50))
        connect.setOnClickListener { beginLogin() }

        val close = button("إغلاق", Color.TRANSPARENT, colorMuted)
        root.addView(close, params(0, 4, 0, 0, 44))
        close.setOnClickListener { finish() }

        setContentView(root)
    }

    private fun refreshConnection() {
        val token = secureStore.readGitHubToken()

        if (token.isNotEmpty()) {
            val savedUser = secureStore.readGitHubUsername()
            showAuthenticatedState(savedUser)
            enterAppSoon()
            return
        }

        status.text = "لم يتم ربط حساب GitHub"
        connect.text = "بدء تسجيل الدخول"
    }

    private fun beginLogin() {
        if (secureStore.readGitHubToken().isNotEmpty()) {
            secureStore.clearGitHubToken()
            refreshConnection()
            return
        }

        connect.isEnabled = false
        connect.text = "جارٍ طلب رمز مؤقت..."

        GitHubApi.requestDeviceCode(object : GitHubApi.Callback<GitHubApi.DeviceCode> {
            override fun onSuccess(device: GitHubApi.DeviceCode) {
                pendingDeviceCode = device

                runOnUiThread {
                    connect.isEnabled = true
                    connect.text = "انتظر موافقة GitHub"
                    codeView.text = device.userCode
                    verificationUri = if (device.verificationUri.isBlank()) {
                        GitHubConfig.VERIFICATION_URL
                    } else {
                        device.verificationUri
                    }
                    status.text = "سيتم فتح GitHub، والرمز جاهز، اضغط Continue ثم Authorize"
                    showDeviceCodeNotification(device)
                    openVerificationPage()
                }

                GitHubApi.pollAccessToken(device, object : GitHubApi.Callback<String> {
                    override fun onSuccess(token: String) {
                        if (!secureStore.saveGitHubToken(token)) {
                            runOnUiThread {
                                showError("تعذر حفظ التفويض بأمان")
                            }
                            return
                        }

                        GitHubApi.getUser(token, object : GitHubApi.Callback<String> {
                            override fun onSuccess(user: String) {
                                secureStore.saveGitHubUsername(user)
                                runOnUiThread {
                                    clearDeviceCodeNotification()
                                    connect.isEnabled = true
                                    showAuthenticatedState(user)
                                    enterAppSoon()
                                }
                            }

                            override fun onError(message: String) {
                                runOnUiThread {
                                    showError("تم التفويض لكن تعذر قراءة الحساب")
                                }
                            }
                        })
                    }

                    override fun onError(message: String) {
                        runOnUiThread {
                            connect.isEnabled = true
                            connect.text = "بدء تسجيل الدخول"
                            showError(message)
                        }
                    }
                })
            }

            override fun onError(message: String) {
                runOnUiThread {
                    connect.isEnabled = true
                    connect.text = "بدء تسجيل الدخول"
                    showError(message)
                }
            }
        })
    }

    private fun openVerificationPage() {
        try {
            pendingDeviceCode?.userCode?.let { code ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("GitHub code", code))
            }

            val intent = Intent(this, WebViewAuthActivity::class.java)
            intent.putExtra(WebViewAuthActivity.EXTRA_URL, verificationUri)
            pendingDeviceCode?.let {
                intent.putExtra(WebViewAuthActivity.EXTRA_USER_CODE, it.userCode)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "تعذر فتح صفحة التحقق داخل التطبيق",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showDeviceCodeNotification(device: GitHubApi.DeviceCode) {
        pendingDeviceCode = device
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
            return
        }
        postDeviceCodeNotification(device)
    }

    // ✅ التصحيح: signature الصحيح بدون nullable
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            pendingDeviceCode?.let { postDeviceCodeNotification(it) }
        }
    }

    private fun postDeviceCodeNotification(device: GitHubApi.DeviceCode) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                DEVICE_CODE_CHANNEL,
                "تسجيل الدخول إلى GitHub",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "رمز تسجيل الدخول المؤقت إلى GitHub"
            }
            manager.createNotificationChannel(channel)
        }

        val verificationUrl = if (device.verificationUri.isBlank()) {
            GitHubConfig.VERIFICATION_URL
        } else {
            device.verificationUri
        }

        val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(verificationUrl))
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE

        val pending = PendingIntent.getActivity(this, DEVICE_CODE_NOTIFICATION, openIntent, flags)

        val message = "الرمز: ${device.userCode}\nسيتم فتح GitHub تلقائياً. اضغط Continue ثم Authorize."

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, DEVICE_CODE_CHANNEL)
        } else {
            Notification.Builder(this)
        }

        builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("رمز تسجيل الدخول إلى GitHub")
            .setContentText("الرمز: ${device.userCode}")
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setAutoCancel(false)
            .setOngoing(false)
            .setOnlyAlertOnce(true)

        manager.notify(DEVICE_CODE_NOTIFICATION, builder.build())
    }

    private fun clearDeviceCodeNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(DEVICE_CODE_NOTIFICATION)
        pendingDeviceCode = null
    }

    private fun showAuthenticatedState(user: String) {
        status.text = "تم الربط بالحساب: $user"
        codeView.text = ""
        connect.isEnabled = true
        connect.text = "دخول إلى التطبيق"
        connect.setTextColor(Color.WHITE)
        connect.background = round(colorBlue, 13, colorBorder)
        connect.setOnClickListener { enterApp() }
    }

    private fun enterAppSoon() {
        root.postDelayed({
            if (!isFinishing) enterApp()
        }, 500L)
    }

    private fun enterApp() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }

    private fun showError(message: String?) {
        val fullText = message ?: "Unknown error"

        val content = TextView(this).apply {
            text = fullText
            setTextColor(Color.rgb(230, 237, 243))
            setTextSize(12f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setTextIsSelectable(true)
            gravity = Gravity.START
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(13, 17, 23))
            // ✅ التصحيح: FrameLayout.LayoutParams بدل ScrollView.LayoutParams
            addView(content, FrameLayout.LayoutParams(-1, -2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.55).toInt()
            )
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("تفاصيل الخطأ")
            .setView(scroll)
            .setPositiveButton("نسخ") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("error", fullText))
                Toast.makeText(this, "تم نسخ التفاصيل", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("إعادة المحاولة") { _, _ ->
                connect.isEnabled = true
                connect.text = "بدء تسجيل الدخول"
                beginLogin()
            }
            .setNegativeButton("إغلاق", null)
            .create()

        dialog.show()

        dialog.window?.setBackgroundDrawable(
            round(Color.rgb(13, 17, 23), 16, Color.rgb(48, 62, 77))
        )
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = round(colorSurface, 22, colorBorder)
            elevation = dp(3).toFloat()
        }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView {
        return TextView(this).apply {
            text = value
            setTextSize(size)
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            typeface = Typeface.DEFAULT
            setTypeface(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun button(value: String, background: Int, color: Int): Button {
        return Button(this).apply {
            text = value
            setTextSize(13f)
            setTextColor(color)
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(14), 0, dp(14), 0)
            gravity = Gravity.CENTER
            this.background = round(
                background,
                15,
                if (background == Color.TRANSPARENT) Color.TRANSPARENT else colorBorder
            )
        }
    }

    private fun round(color: Int, radius: Int, stroke: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }
    }

    private fun params(left: Int, top: Int, right: Int, bottom: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(left), dp(top), dp(right), dp(bottom))
        }
    }

    private fun params(left: Int, top: Int, right: Int, bottom: Int, height: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(-1, dp(height)).apply {
            setMargins(dp(left), dp(top), dp(right), dp(bottom))
        }
    }

    companion object {
        private const val DEVICE_CODE_CHANNEL = "github-device-code"
        private const val DEVICE_CODE_NOTIFICATION = 7710
        private const val NOTIFICATION_PERMISSION_REQUEST = 7711
    }
}