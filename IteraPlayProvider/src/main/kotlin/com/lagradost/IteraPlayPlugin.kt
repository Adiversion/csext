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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IteraPlayPlugin : Plugin() {
    override fun load(context: Context) {
        // Initialize saved settings if present
        IteraPlayProvider.getSavedCookie(context)
        TeraBoxExtractor.getSavedNdus(context)
        TeraBoxExtractor.getSavedCustomWorker(context)

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
                    statusView.text = "Status: 🟢 Direct Native TeraBox Active\n(Highest speed 1080p, bypasses all 3rd-party sites)"
                    statusView.setTextColor(Color.parseColor("#4CAF50"))
                }
                !worker.isNullOrBlank() -> {
                    statusView.text = "Status: 🔵 Custom Gateway Worker Active\n($worker)"
                    statusView.setTextColor(Color.parseColor("#2196F3"))
                }
                else -> {
                    statusView.text = "Status: ⚪ Free Dual Upstream Active\n(iTeraPlay.in + TeraVideo concurrent engines)"
                    statusView.setTextColor(Color.parseColor("#B0BEC5"))
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
        val ndusLabel = TextView(act).apply {
            text = "Manual ndus Cookie Token:"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 4)
        }
        container.addView(ndusLabel)

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
            singleLine = true
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
        }

        // Top Action Bar
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(Color.parseColor("#1565C0"))
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(activity).apply {
            text = "Log into TeraBox (Auto-captures session)"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(titleView)

        val closeBtn = Button(activity).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 18f
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dialog.dismiss() }
        }
        topBar.addView(closeBtn)

        rootLayout.addView(topBar)

        // Progress bar
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
        }
        rootLayout.addView(progressBar)

        // WebView
        val webView = WebView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val handler = Handler(Looper.getMainLooper())
        var captured = false

        fun checkCookies() {
            if (captured) return
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie("https://www.terabox.app")
                ?: cookieManager.getCookie("https://www.terabox.com")
                ?: ""

            val ndus = Regex("""\bndus=([^;]+)""").find(cookies)?.groupValues?.get(1)
            if (!ndus.isNullOrBlank()) {
                captured = true
                TeraBoxExtractor.saveNdus(activity, ndus)
                Toast.makeText(activity, "🎉 TeraBox Connected! Direct 1080p active.", Toast.LENGTH_LONG).show()
                onCookieCaptured()
                dialog.dismiss()
            }
        }

        val pollRunnable = object : Runnable {
            override fun run() {
                if (!captured && dialog.isShowing) {
                    checkCookies()
                    handler.postDelayed(this, 1200)
                }
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = TeraBoxExtractor.USER_AGENT
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
                checkCookies()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                checkCookies()
            }
        }

        dialog.setOnDismissListener {
            handler.removeCallbacks(pollRunnable)
            webView.stopLoading()
            webView.destroy()
        }

        webView.loadUrl("https://www.terabox.app/main?category=all")
        handler.postDelayed(pollRunnable, 1500)

        rootLayout.addView(webView)
        dialog.setContentView(rootLayout)
        dialog.show()
    }
}
