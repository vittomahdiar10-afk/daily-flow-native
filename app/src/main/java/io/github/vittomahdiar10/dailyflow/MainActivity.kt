package io.github.vittomahdiar10.dailyflow

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            val results = if (uri != null) arrayOf(uri) else null
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (::webView.isInitialized) {
                webView.evaluateJavascript(
                    "if(typeof window.__onNativeNotificationPermission==='function'){window.__onNativeNotificationPermission(${if (granted) "true" else "false"});}",
                    null
                )
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ثبت کانال‌های اعلان برنامه و درخواست مجوز نوتیفیکیشن در اندروید ۱۳ به بالا
        ReminderScheduler.createNotificationChannels(this)
        requestNotificationPermissionIfNeeded()

        // فعال‌سازی تمام‌صفحه شفاف واقعی (True Edge-to-Edge) بدون هیچ نوار سفید یا مشکی
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0b1012"))
            overScrollMode = WebView.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
        setContentView(webView)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK

        webView.addJavascriptInterface(NativeBridge(), "AndroidNative")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("https://vittomahdiar10-afk.github.io/")) {
                    return false
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                return try {
                    val acceptTypes = fileChooserParams?.acceptTypes ?: emptyArray()
                    val wantsJson = acceptTypes.any {
                        it.contains("json", ignoreCase = true) || it.contains("text", ignoreCase = true)
                    }
                    filePickerLauncher.launch(if (wantsJson) "*/*" else "image/*")
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(function(){ return typeof window.__handleNativeBack === 'function' ? window.__handleNativeBack() : false; })();"
                ) { result ->
                    if (result != "true") {
                        finish()
                    }
                }
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("https://vittomahdiar10-afk.github.io/daily-flow-apk/")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val channelId = intent.getStringExtra(ReminderScheduler.EXTRA_CHANNEL_ID) ?: return
        if (::webView.isInitialized && channelId == ReminderScheduler.CHANNEL_FOCUS) {
            webView.evaluateJavascript(
                "if(typeof window.__onNativeNotificationOpen==='function'){window.__onNativeNotificationOpen('focus');}",
                null
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun clearWebCache() {
            runOnUiThread {
                try {
                    webView.clearCache(true)
                } catch (e: Exception) {}
            }
        }

        @JavascriptInterface
        fun setSystemBarTheme(isLight: Boolean) {
            runOnUiThread {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.isAppearanceLightStatusBars = isLight
                controller.isAppearanceLightNavigationBars = isLight
                webView.setBackgroundColor(
                    Color.parseColor(if (isLight) "#eef3f5" else "#0b1012")
                )
            }
        }

        @JavascriptInterface
        fun shareBackupText(fileName: String, jsonContent: String) {
            runOnUiThread {
                try {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_SUBJECT, fileName)
                        putExtra(Intent.EXTRA_TEXT, jsonContent)
                    }
                    startActivity(Intent.createChooser(sendIntent, "ذخیره یا ارسال پشتیبان"))
                } catch (e: Exception) {}
            }
        }

        @JavascriptInterface
        fun hasNotificationPermission(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        @JavascriptInterface
        fun requestNotificationPermission() {
            runOnUiThread {
                requestNotificationPermissionIfNeeded()
            }
        }

        @JavascriptInterface
        fun canScheduleExactAlarms(): Boolean {
            return ReminderScheduler.canScheduleExactAlarms(this@MainActivity)
        }

        @JavascriptInterface
        fun openNotificationSettings() {
            runOnUiThread {
                try {
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        }
                    } else {
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    }
                    startActivity(intent)
                } catch (e: Exception) {}
            }
        }

        @JavascriptInterface
        fun openExactAlarmSettings() {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    } catch (e: Exception) {}
                }
            }
        }

        @JavascriptInterface
        fun showInstantNotification(idKey: String, title: String, body: String, channelId: String): Boolean {
            return ReminderScheduler.showNotificationNow(
                context = this@MainActivity,
                idKey = idKey,
                title = title,
                body = body,
                channelId = channelId
            )
        }

        @JavascriptInterface
        fun scheduleNotification(
            idKey: String,
            triggerAtMillis: Long,
            title: String,
            body: String,
            channelId: String,
            repeatDaily: Boolean
        ): Boolean {
            return ReminderScheduler.scheduleAlarm(
                context = this@MainActivity,
                idKey = idKey,
                triggerAtMillis = triggerAtMillis,
                title = title,
                body = body,
                channelId = channelId,
                repeatDaily = repeatDaily
            )
        }

        @JavascriptInterface
        fun cancelNotification(idKey: String) {
            ReminderScheduler.cancelAlarm(this@MainActivity, idKey)
        }

        @JavascriptInterface
        fun cancelNotificationsByPrefix(prefix: String) {
            ReminderScheduler.cancelByPrefix(this@MainActivity, prefix)
        }
    }
}