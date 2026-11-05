package com.lagradost

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.app
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

object TeraBoxVerification {
    private const val TAG = "TeraBoxVerification"
    private const val BASE_URL = "https://1024teradl.com"
    private const val PREFS_NAME = "TeraBox1024VerificationPrefs"
    private const val KEY_TDL_COOKIES = "tdl_cookies"
    private const val KEY_TDL_EXP = "tdl_pass_exp"

    private val isDialogShowing = AtomicBoolean(false)
    private var cachedCookies: String? = null
    private var cachedExpSeconds: Long = 0L

    data class Tera1024Item(
        val fsId: String? = null,
        val fileName: String? = null,
        val size: Long = 0L,
        val directLink: String? = null,
        val streamUrl: String? = null,
        val streamDownloadUrl: String? = null,
        val isDir: Boolean = false,
        val path: String? = null
    )

    fun isTokenValid(): Boolean {
        if (cachedCookies == null) {
            loadSavedSession()
        }
        val exp = cachedExpSeconds
        val now = System.currentTimeMillis() / 1000L
        return !cachedCookies.isNullOrBlank() && (exp - now > 60L)
    }

    fun loadSavedSession(context: Context? = null) {
        val ctx = context ?: CommonActivity.activity ?: getActivity() ?: return
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cookies = prefs.getString(KEY_TDL_COOKIES, null)
            val exp = prefs.getLong(KEY_TDL_EXP, 0L)
            if (!cookies.isNullOrBlank() && exp > 0L) {
                cachedCookies = cookies
                cachedExpSeconds = exp
                Log.d(TAG, "Loaded saved Turnstile session (expires in ${exp - System.currentTimeMillis() / 1000L}s)")
            }
        }
    }

    fun saveSession(context: Context? = null, cookies: String, expSeconds: Long) {
        cachedCookies = cookies
        cachedExpSeconds = expSeconds
        val ctx = context ?: CommonActivity.activity ?: getActivity() ?: return
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_TDL_COOKIES, cookies)
                .putLong(KEY_TDL_EXP, expSeconds)
                .apply()
            Log.d(TAG, "Saved Turnstile session cookies to SharedPreferences (exp=$expSeconds)")
        }
    }

    private fun getActivity(): Activity? {
        return CommonActivity.activity ?: runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThreadMethod = activityThreadClass.getMethod("currentActivityThread")
            val activityThread = currentActivityThreadMethod.invoke(null)
            val mActivitiesField = activityThreadClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val activities = mActivitiesField.get(activityThread) as? Map<*, *>
            activities?.values?.firstNotNullOfOrNull { record ->
                if (record == null) return@firstNotNullOfOrNull null
                val pausedField = record.javaClass.getDeclaredField("paused").apply { isAccessible = true }
                val isPaused = pausedField.getBoolean(record)
                if (!isPaused) {
                    val activityField = record.javaClass.getDeclaredField("activity").apply { isAccessible = true }
                    activityField.get(record) as? Activity
                } else null
            }
        }.getOrNull()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun solveTurnstile(targetTeraUrl: String? = null): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.e(TAG, "solveTurnstile must not be called from the main thread")
            return false
        }
        if (isTokenValid()) {
            return true
        }
        val activity = CommonActivity.activity ?: getActivity()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.e(TAG, "No active Activity found for TeraBox Turnstile verification")
            return false
        }

        if (!isDialogShowing.compareAndSet(false, true)) {
            Log.d(TAG, "Verification dialog already in progress, waiting...")
            var waits = 0
            while (isDialogShowing.get() && waits < 65) {
                try {
                    Thread.sleep(1000L)
                } catch (_: InterruptedException) {
                    break
                }
                waits++
            }
            return isTokenValid()
        }

        val latch = CountDownLatch(1)
        var success = false

        activity.runOnUiThread {
            try {
                val density = activity.resources.displayMetrics.density
                val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                dialog.setOnShowListener {
                    dialog.window?.apply {
                        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        setBackgroundDrawable(ColorDrawable(Color.parseColor("#121218")))
                        clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    }
                }

                // Invisible for 2 seconds in case non-interactive challenge completes silently
                dialog.window?.decorView?.alpha = 0f
                var solved = false
                val handler = Handler(Looper.getMainLooper())

                val revealRunnable = Runnable {
                    if (!solved && dialog.isShowing) {
                        runCatching {
                            dialog.window?.decorView?.animate()?.alpha(1f)?.setDuration(250L)?.start()
                        }
                    }
                }

                val root = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#121218"))
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

                // Top Header Bar
                val header = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setBackgroundColor(Color.parseColor("#1E1E2A"))
                    setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                val titleTv = TextView(activity).apply {
                    text = "TeraBox 1080p VIP Verification"
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val closeBtn = Button(activity).apply {
                    text = "✕"
                    setTextColor(Color.LTGRAY)
                    setBackgroundColor(Color.TRANSPARENT)
                    textSize = 18f
                    setOnClickListener {
                        dialog.dismiss()
                    }
                }

                header.addView(titleTv)
                header.addView(closeBtn)
                root.addView(header)

                val statusText = TextView(activity).apply {
                    text = "Please complete the Cloudflare Turnstile verification below to unlock 1080p unthrottled streaming. The window will close automatically once verified."
                    setTextColor(Color.parseColor("#A0A0B0"))
                    textSize = 12f
                    setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
                }
                root.addView(statusText)

                val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (4 * density).toInt()
                    )
                }
                root.addView(progressBar)

                val webView = WebView(activity).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    requestFocus()
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    )
                }

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowContentAccess = true
                    allowFileAccess = true
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    loadsImagesAutomatically = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    val baseUa = userAgentString ?: TeraBoxExtractor.USER_AGENT
                    val sanitizedUa = baseUa
                        .replace("; wv", "")
                        .replace(Regex("Version/[0-9.]+\\s*"), "")
                        .replace("Android TV", "Android")
                    userAgentString = sanitizedUa
                }

                var pollElapsedMs = 0L

                fun finishWithCookies(cookies: String) {
                    if (solved) return
                    solved = true
                    handler.removeCallbacks(revealRunnable)
                    cookieManager.flush()

                    val expMatch = Regex("""tdl_pass_exp=(\d+)""").find(cookies)
                    val expVal = expMatch?.groupValues?.get(1)?.toLongOrNull()
                        ?: (System.currentTimeMillis() / 1000L + 86400L)
                    saveSession(activity, cookies, expVal)
                    success = true
                    latch.countDown()
                    handler.postDelayed({
                        runCatching { if (dialog.isShowing) dialog.dismiss() }
                    }, 300L)
                }

                lateinit var cookiePollRunnable: Runnable
                cookiePollRunnable = Runnable {
                    if (solved) return@Runnable
                    pollElapsedMs += 600L
                    val currentCookies = cookieManager.getCookie(BASE_URL) ?: ""
                    if (currentCookies.contains("tdl_pass=")) {
                        Log.d(TAG, "Turnstile verification successful! Captured cookies: $currentCookies")
                        finishWithCookies(currentCookies)
                        return@Runnable
                    }
                    if (pollElapsedMs >= 65_000L) {
                        Log.w(TAG, "Turnstile verification timed out after 65s")
                        latch.countDown()
                        runCatching { if (dialog.isShowing) dialog.dismiss() }
                        return@Runnable
                    }
                    handler.postDelayed(cookiePollRunnable, 600L)
                }

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        super.onProgressChanged(view, newProgress)
                        progressBar.progress = newProgress
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        progressBar.visibility = View.GONE
                        val currentCookies = cookieManager.getCookie(BASE_URL) ?: ""
                        if (currentCookies.contains("tdl_pass=")) {
                            finishWithCookies(currentCookies)
                            return
                        }

                        // Auto-fill, dispatch paste event, and mount explicit Turnstile widget
                        val fillUrl = targetTeraUrl ?: "https://terabox.app/s/18YFqJz7G_5zGZqT9s6U-pA"
                        val escapedFillUrl = fillUrl.replace("'", "\\'")
                        val script = """
                            (function() {
                                if (window.__tb_verified_injected) return;
                                window.__tb_verified_injected = true;

                                // 1. Direct Turnstile Explicit Renderer (Guarantees Turnstile is always visible on screen)
                                function mountTurnstile() {
                                    if (document.getElementById('__tb_cf_container')) return;
                                    var container = document.createElement('div');
                                    container.id = '__tb_cf_container';
                                    container.style.cssText = 'position:fixed;inset:0;z-index:2147483647;display:flex;flex-direction:column;align-items:center;justify-content:center;background:rgba(18,18,24,0.96);color:#fff;padding:24px;text-align:center;';

                                    var title = document.createElement('h3');
                                    title.innerText = 'TeraBox 1080p Verification';
                                    title.style.cssText = 'margin:0 0 8px 0;font-size:18px;color:#fff;font-weight:bold;';
                                    container.appendChild(title);

                                    var subtitle = document.createElement('p');
                                    subtitle.innerText = 'Please tap the checkmark below to unlock unthrottled 1080p playback:';
                                    subtitle.style.cssText = 'margin:0 0 20px 0;font-size:13px;color:#94a3b8;max-width:320px;';
                                    container.appendChild(subtitle);

                                    var widgetDiv = document.createElement('div');
                                    widgetDiv.id = '__tb_widget';
                                    widgetDiv.style.cssText = 'min-height:65px;';
                                    container.appendChild(widgetDiv);

                                    var statusDiv = document.createElement('div');
                                    statusDiv.id = '__tb_status';
                                    statusDiv.innerText = 'Waiting for verification...';
                                    statusDiv.style.cssText = 'margin-top:16px;font-size:13px;color:#a855f7;font-weight:500;';
                                    container.appendChild(statusDiv);

                                    document.body.appendChild(container);

                                    if (window.turnstile) {
                                        try {
                                            window.turnstile.render('#__tb_widget', {
                                                sitekey: '0x4AAAAAAFZHusiSezRUKq4-',
                                                theme: 'dark',
                                                callback: function(token) {
                                                    statusDiv.innerText = 'Token verified! Unlocking 1080p stream...';
                                                    fetch('/api/verify', {
                                                        method: 'POST',
                                                        headers: { 'Content-Type': 'application/json' },
                                                        body: JSON.stringify({ token: token })
                                                    }).then(function(r) { return r.json(); }).then(function(res) {
                                                        statusDiv.innerText = 'Success! Closing window...';
                                                    }).catch(function(err) {
                                                        statusDiv.innerText = 'Verified! Closing...';
                                                    });
                                                },
                                                'error-callback': function() {
                                                    statusDiv.innerText = 'Verification failed. Retrying...';
                                                }
                                            });
                                        } catch(e) {
                                            console.error('Turnstile render error:', e);
                                        }
                                    }
                                }

                                if (window.turnstile) {
                                    mountTurnstile();
                                } else {
                                    var s = document.createElement('script');
                                    s.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
                                    s.async = true;
                                    s.onload = mountTurnstile;
                                    document.head.appendChild(s);
                                }

                                // 2. React Input setter + Paste Event Trigger (Backup)
                                try {
                                    var inp = document.querySelector('input[placeholder*="Terabox"]');
                                    var btn = document.querySelector('button[type="submit"]');
                                    if (inp) {
                                        var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                                        if (setter && setter.set) {
                                            setter.set.call(inp, '$escapedFillUrl');
                                        } else {
                                            inp.value = '$escapedFillUrl';
                                        }
                                        inp.dispatchEvent(new Event('input', { bubbles: true }));
                                        inp.dispatchEvent(new Event('change', { bubbles: true }));

                                        try {
                                            var dt = new DataTransfer();
                                            dt.setData('text/plain', '$escapedFillUrl');
                                            var pe = new ClipboardEvent('paste', { clipboardData: dt, bubbles: true });
                                            inp.dispatchEvent(pe);
                                        } catch(e) {}

                                        setTimeout(function() {
                                            if (btn) btn.click();
                                        }, 300);
                                    }
                                } catch(e) {
                                    console.error('Auto-paste error:', e);
                                }
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(script, null)
                    }
                }

                root.addView(webView)
                dialog.setContentView(root)

                dialog.setOnDismissListener {
                    handler.removeCallbacks(cookiePollRunnable)
                    handler.removeCallbacks(revealRunnable)
                    isDialogShowing.set(false)
                    if (!solved) {
                        val finalCookies = cookieManager.getCookie(BASE_URL) ?: ""
                        if (finalCookies.contains("tdl_pass=")) {
                            finishWithCookies(finalCookies)
                        } else {
                            latch.countDown()
                        }
                    }
                }

                dialog.show()
                webView.loadUrl(BASE_URL)
                handler.postDelayed(cookiePollRunnable, 600L)
                handler.postDelayed(revealRunnable, 2000L)

            } catch (e: Throwable) {
                Log.e(TAG, "Failed to launch Turnstile dialog", e)
                isDialogShowing.set(false)
                latch.countDown()
            }
        }

        try {
            latch.await(70, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        return success
    }

    suspend fun fetch1024Files(
        shareUrl: String,
        allowVerification: Boolean = true
    ): List<Tera1024Item> {
        return runCatching {
            if (cachedCookies == null) {
                loadSavedSession()
            }

            suspend fun doRequest(cookieStr: String?): String? {
                val headers = mutableMapOf(
                    "User-Agent" to TeraBoxExtractor.USER_AGENT,
                    "Content-Type" to "application/json",
                    "Accept" to "application/json, text/plain, */*",
                    "Origin" to BASE_URL,
                    "Referer" to "$BASE_URL/"
                )
                if (!cookieStr.isNullOrBlank()) {
                    headers["Cookie"] = cookieStr
                }
                return runCatching {
                    app.post(
                        "$BASE_URL/api/proxy",
                        headers = headers,
                        json = mapOf("url" to shareUrl),
                        timeout = 10
                    ).text
                }.getOrNull()
            }

            var responseText = doRequest(cachedCookies)

            // If challenge required or 403 or empty
            val isChallengeRequired = responseText == null ||
                    responseText.contains("CHALLENGE_REQUIRED") ||
                    responseText.contains("Verification required")

            if (isChallengeRequired && allowVerification) {
                val solved = solveTurnstile(shareUrl)
                if (solved && !cachedCookies.isNullOrBlank()) {
                    responseText = doRequest(cachedCookies)
                }
            }

            if (responseText.isNullOrBlank()) return@runCatching emptyList()

            val json = JSONObject(responseText)
            val listArr = json.optJSONArray("list") ?: return@runCatching emptyList()

            val results = mutableListOf<Tera1024Item>()
            for (i in 0 until listArr.length()) {
                val item = listArr.optJSONObject(i) ?: continue
                val fsId = item.optString("fs_id").ifBlank { null }
                val fileName = item.optString("server_filename").ifBlank { item.optString("file_name") }
                val size = item.optLong("size", 0L)
                val directLink = item.optString("direct_link").ifBlank { item.optString("dlink") }
                val streamUrl = item.optString("stream_url").ifBlank { null }
                val streamDownloadUrl = item.optString("stream_download_url").ifBlank { null }
                val isDir = item.optInt("isdir", 0) == 1
                val path = item.optString("path").ifBlank { null }

                results.add(
                    Tera1024Item(
                        fsId = fsId,
                        fileName = fileName,
                        size = size,
                        directLink = directLink.ifBlank { null },
                        streamUrl = streamUrl,
                        streamDownloadUrl = streamDownloadUrl,
                        isDir = isDir,
                        path = path
                    )
                )
            }
            results
        }.getOrDefault(emptyList())
    }
}
