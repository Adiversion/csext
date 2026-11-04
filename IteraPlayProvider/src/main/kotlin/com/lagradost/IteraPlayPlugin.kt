package com.lagradost

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IteraPlayPlugin : Plugin() {
    override fun load(context: Context) {
        // Initialize persistent application context
        val appCtx = context.applicationContext ?: context
        TeraBoxExtractor.appContext = appCtx
        IteraPlayProvider.appContext = appCtx

        // Initialize saved settings if present
        IteraPlayProvider.getSavedCookie(appCtx)
        TeraBoxExtractor.getSavedNdus(appCtx)
        TeraBoxExtractor.getSavedCustomWorker(appCtx)

        // Register the IteraPlay MainAPI provider
        registerMainAPI(IteraPlayProvider())

        // Register TeraBox extractors for direct and proxy streaming
        registerExtractorAPI(TeraBoxExtractor())
        registerExtractorAPI(TeraBoxComExtractor())
        registerExtractorAPI(TeraBox1024Extractor())
        registerExtractorAPI(TeraBox1024BoxExtractor())
        registerExtractorAPI(TeraBoxLinkExtractor())

        // Register Settings UI for TeraBox cookie & worker gateway management
        openSettings = { ctx ->
            val act = findActivity(ctx) ?: CommonActivity.activity
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                act.runOnUiThread {
                    showSettingsDialog(act)
                }
            }
        }
    }

    private fun findActivity(context: Context): Activity? {
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun showSettingsDialog(activity: Activity) {
        val act = activity
        val currentNdus = TeraBoxExtractor.getSavedNdus(act)
        val currentWorker = TeraBoxExtractor.getSavedCustomWorker(act)

        val scrollView = ScrollView(act)
        val container = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 36, 48, 36)
        }

        // Title View
        val titleView = TextView(act).apply {
            text = "TeraBox & iTeraPlay Settings"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }
        container.addView(titleView)

        // Status Indicator
        val statusView = TextView(act).apply {
            textSize = 13.5f
            setPadding(0, 0, 0, 24)
        }
        fun updateStatusDisplay() {
            val ndus = TeraBoxExtractor.getSavedNdus(act)
            val worker = TeraBoxExtractor.getSavedCustomWorker(act)
            when {
                !ndus.isNullOrBlank() -> {
                    statusView.text = "Status: 🟢 Native TeraBox Active [ndus: ${ndus.take(6)}...]\n(Unthrottled HLS & Direct MP4 via official TeraBox CDN)"
                    statusView.setTextColor(Color.parseColor("#4CAF50"))
                }
                else -> {
                    statusView.text = "Status: ⚪ No ndus Cookie Saved\n(Connect via login or paste ndus cookie below)"
                    statusView.setTextColor(Color.parseColor("#FFA000"))
                }
            }
        }
        updateStatusDisplay()
        container.addView(statusView)

        // Section 1: In-App Browser Auto Login
        val loginBtn = Button(act).apply {
            text = "🌐 Connect via TeraBox Web Login"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setOnClickListener {
                showLoginWebView(act) {
                    updateStatusDisplay()
                }
            }
        }
        container.addView(loginBtn)

        val dividerText = TextView(act).apply {
            text = "— OR Manual Configuration —"
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.parseColor("#78909C"))
            setPadding(0, 24, 0, 12)
        }
        container.addView(dividerText)

        // Section 2: Manual ndus Cookie Input
        val ndusHeaderLayout = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 4)
        }
        val ndusLabel = TextView(act).apply {
            text = "Manual ndus Cookie Token:"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        ndusHeaderLayout.addView(ndusLabel)

        val syncBtn = Button(act).apply {
            text = "🔄 Sync Session"
            textSize = 11.5f
            setOnClickListener {
                val found = TeraBoxExtractor.scanCookieManagerForNdus()
                if (!found.isNullOrBlank()) {
                    TeraBoxExtractor.saveNdus(act, found)
                    updateStatusDisplay()
                    Toast.makeText(act, "✅ Synced ndus: ${found.take(8)}...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(act, "⚠️ No browser session found yet. Try Web Login.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        ndusHeaderLayout.addView(syncBtn)
        container.addView(ndusHeaderLayout)

        val ndusInput = EditText(act).apply {
            hint = "Paste ndus token from browser (valid ~1 yr)"
            setText(currentNdus ?: "")
            textSize = 12.5f
            maxLines = 3
        }
        container.addView(ndusInput)

        // Section 3: Custom Worker Gateway Input (Optional)
        val workerLabel = TextView(act).apply {
            text = "Custom Gateway URL (Optional):"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 20, 0, 4)
        }
        container.addView(workerLabel)

        val workerInput = EditText(act).apply {
            hint = "e.g. https://my-terabox-worker.dev"
            setText(currentWorker ?: "")
            textSize = 12.5f
            maxLines = 1
            setSingleLine(true)
        }
        container.addView(workerInput)

        scrollView.addView(container)

        AlertDialog.Builder(act)
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                val newNdus = ndusInput.text.toString().trim()
                val newWorker = workerInput.text.toString().trim()

                TeraBoxExtractor.saveNdus(act, if (newNdus.isNotEmpty()) newNdus else null)
                TeraBoxExtractor.saveCustomWorker(act, if (newWorker.isNotEmpty()) newWorker else null)

                Toast.makeText(act, "✅ Settings saved successfully!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Clear Session") { _, _ ->
                TeraBoxExtractor.saveNdus(act, null)
                TeraBoxExtractor.saveCustomWorker(act, null)
                runCatching {
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.removeAllCookies(null)
                    cookieManager.flush()
                }
                ndusInput.setText("")
                workerInput.setText("")
                updateStatusDisplay()
                Toast.makeText(act, "Cleared TeraBox session. Reverted to free engines.", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Close", null)
            .show()
    }

    private fun showLoginWebView(activity: Activity, onCookieCaptured: () -> Unit) {
        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
        val rootLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        // Top Action Bar
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(28, 20, 28, 20)
            setBackgroundColor(Color.parseColor("#1E293B"))
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(activity).apply {
            text = "🌐 TeraBox Desktop Login"
            setTextColor(Color.WHITE)
            textSize = 15.5f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(titleView)

        val closeBtn = Button(activity).apply {
            text = "✕ Close"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13.5f
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dialog.dismiss() }
        }
        topBar.addView(closeBtn)

        rootLayout.addView(topBar)

        // Progress bar
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            visibility = View.VISIBLE
        }
        rootLayout.addView(progressBar)

        // WebView
        val webView = WebView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val handler = Handler(Looper.getMainLooper())
        var captured = false

        fun onSuccessCaptured(token: String) {
            if (captured) return
            captured = true
            TeraBoxExtractor.saveNdus(activity, token)
            Toast.makeText(activity, "🎉 TeraBox Connected! [ndus: ${token.take(8)}...]", Toast.LENGTH_LONG).show()
            onCookieCaptured()
            dialog.dismiss()
        }

        fun checkCookies(manualClick: Boolean = false) {
            if (captured) return

            runCatching {
                CookieManager.getInstance().flush()
            }

            // 1. Scan CookieManager across candidate domains and webView.url
            val tokenFromCm = TeraBoxExtractor.scanCookieManagerForNdus(webView.url)
            if (!tokenFromCm.isNullOrBlank()) {
                onSuccessCaptured(tokenFromCm)
                return
            }

            // 2. Scan JavaScript document.cookie, localStorage, and sessionStorage
            val extractScript = """
                (function() {
                    try {
                        var c = document.cookie || '';
                        var m = c.match(/\bndus=([^;\s"']+)/i);
                        if (m && m[1]) return m[1].trim();

                        for (var i = 0; i < localStorage.length; i++) {
                            var k = localStorage.key(i) || '';
                            var v = localStorage.getItem(k) || '';
                            if (k.toLowerCase().indexOf('ndus') !== -1 && v.length >= 8) {
                                var subK = v.match(/\bndus=([^;\s"']+)/i) || v.match(/"ndus"\s*:\s*"([^"]+)"/i);
                                if (subK && subK[1]) return subK[1].trim();
                                return v.trim();
                            }
                            if (v && v.indexOf('ndus') !== -1) {
                                var sub = v.match(/"ndus"\s*:\s*"([^"]+)"/i) || v.match(/\bndus=([^;\s"']+)/i);
                                if (sub && sub[1]) return sub[1].trim();
                            }
                        }

                        for (var j = 0; j < sessionStorage.length; j++) {
                            var sk = sessionStorage.key(j) || '';
                            var sv = sessionStorage.getItem(sk) || '';
                            if (sk.toLowerCase().indexOf('ndus') !== -1 && sv.length >= 8) {
                                return sv.trim();
                            }
                            if (sv && sv.indexOf('ndus') !== -1) {
                                var ssub = sv.match(/"ndus"\s*:\s*"([^"]+)"/i) || sv.match(/\bndus=([^;\s"']+)/i);
                                if (ssub && ssub[1]) return ssub[1].trim();
                            }
                        }
                    } catch(e) {}
                    return '';
                })()
            """.trimIndent()

            webView.evaluateJavascript(extractScript) { result ->
                val token = result?.trim('"', ' ', '\n', '\r')
                if (!token.isNullOrBlank() && token != "null" && token != "undefined" && token.length >= 8) {
                    onSuccessCaptured(token)
                } else if (manualClick) {
                    val currentUrl = webView.url ?: ""
                    if (currentUrl.contains("/main") || currentUrl.contains("/sharing") || currentUrl.contains("category=all")) {
                        Toast.makeText(activity, "Session detected! Retrying capture...", Toast.LENGTH_SHORT).show()
                        handler.postDelayed({ checkCookies(manualClick = false) }, 1500)
                    } else {
                        Toast.makeText(
                            activity,
                            "⚠️ Please log in to TeraBox first! (ndus session cookie not found yet)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        // Bottom Action Bar
        val bottomBar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 16, 28, 20)
            setBackgroundColor(Color.parseColor("#1E293B"))
            gravity = Gravity.CENTER
        }

        val hintView = TextView(activity).apply {
            text = "Log in via Google, Email, or QR Code above, then tap:"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        bottomBar.addView(hintView)

        val saveSessionBtn = Button(activity).apply {
            text = "💾 Save Session & Connect Native"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2563EB"))
            setPadding(24, 20, 24, 20)
            setOnClickListener {
                checkCookies(manualClick = true)
            }
        }
        bottomBar.addView(saveSessionBtn)

        val pollRunnable = object : Runnable {
            override fun run() {
                if (!captured && dialog.isShowing) {
                    checkCookies(manualClick = false)
                    handler.postDelayed(this, 1500)
                }
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = TeraBoxExtractor.USER_AGENT
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        fun injectDesktopScript(wv: WebView) {
            val js = """
                (function() {
                    try {
                        window.open = function(u) { if (u) window.location.href = u; return window; };
                        if (Object.defineProperty) {
                            Object.defineProperty(navigator, 'platform', { get: function() { return 'Win32'; } });
                        }
                    } catch(e) {}
                })();
            """.trimIndent()
            wv.evaluateJavascript(js, null)
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                } else {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
                injectDesktopScript(webView)
                checkCookies(manualClick = false)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                injectDesktopScript(webView)
                checkCookies(manualClick = false)
            }
        }

        dialog.setOnDismissListener {
            handler.removeCallbacks(pollRunnable)
            webView.stopLoading()
            webView.destroy()
        }

        webView.loadUrl("https://www.1024terabox.com/login")
        handler.postDelayed(pollRunnable, 2000)

        rootLayout.addView(webView)
        rootLayout.addView(bottomBar)
        dialog.setContentView(rootLayout)
        dialog.show()
    }
}
