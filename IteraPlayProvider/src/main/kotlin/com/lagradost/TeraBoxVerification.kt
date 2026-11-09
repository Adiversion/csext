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
import android.webkit.JavascriptInterface
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
import kotlinx.coroutines.delay
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

    @Volatile
    private var cachedFetchedItems: List<Tera1024Item>? = null

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

    class TeraBoxBridge(private val onResult: (cookies: String?, dataJson: String?) -> Unit) {
        @JavascriptInterface
        fun onVerified(cookieStr: String?, dataJson: String?) {
            onResult(cookieStr, dataJson)
        }
    }

    fun getCookieExp(cookieStr: String?): Long {
        if (cookieStr.isNullOrBlank()) return 0L
        val expMatch = Regex("""(?:^|;\s*)tdl_pass_exp=(\d+)""").find(cookieStr) ?: return 0L
        return expMatch.groupValues.getOrNull(1)?.toLongOrNull() ?: 0L
    }

    fun isCookieExpValid(cookieStr: String?): Boolean {
        val expVal = getCookieExp(cookieStr)
        if (expVal <= 0L) return false
        val nowSec = System.currentTimeMillis() / 1000L
        return (expVal - nowSec) > 60L
    }

    fun isTokenValid(): Boolean {
        if (cachedCookies == null) {
            loadSavedSession()
        }
        val exp = cachedExpSeconds
        val now = System.currentTimeMillis() / 1000L
        return !cachedCookies.isNullOrBlank() && (exp - now > 60L)
    }

    fun clearSession(context: Context? = null) {
        cachedCookies = null
        cachedExpSeconds = 0L
        cachedFetchedItems = null
        val ctx = context ?: CommonActivity.activity ?: getActivity()
        ctx?.let {
            runCatching {
                it.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
            }
        }
        runCatching {
            val cm = CookieManager.getInstance()
            cm.setCookie("https://1024teradl.com", "tdl_pass=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/")
            cm.setCookie("https://1024teradl.com", "tdl_pass_exp=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/")
            cm.setCookie("https://1024teradl.com/", "tdl_pass=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/")
            cm.setCookie("https://1024teradl.com/", "tdl_pass_exp=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/")
            cm.flush()
        }
        Log.d(TAG, "Cleared stale Turnstile session from cache and CookieManager")
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
                .commit()
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

    fun parse1024Response(responseText: String): List<Tera1024Item>? {
        return runCatching {
            val json = JSONObject(responseText)
            val listArr = json.optJSONArray("list") ?: return@runCatching null
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
        }.getOrNull()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun solveTurnstile(targetTeraUrl: String? = null, force: Boolean = false): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.e(TAG, "solveTurnstile must not be called from the main thread")
            return false
        }
        if (!force && isTokenValid()) {
            return true
        }
        if (force) {
            clearSession()
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
        val initialExp = cachedExpSeconds

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

                fun finishWithVerification(cookieStr: String?, dataJson: String?) {
                    val cm1 = cookieManager.getCookie("https://1024teradl.com/") ?: ""
                    val cm2 = cookieManager.getCookie("https://1024teradl.com") ?: ""
                    val cm3 = cookieManager.getCookie("https://player.1024teradl.com/") ?: ""
                    val cm4 = cookieManager.getCookie(".1024teradl.com") ?: ""
                    val cJs = cookieStr ?: ""

                    val allRaw = listOf(cm1, cm2, cm3, cm4, cJs)
                    val mergedCookies = allRaw.flatMap { it.split(";") }
                        .map { it.trim() }
                        .filter { it.isNotBlank() && it.contains("=") }
                        .distinct()
                        .joinToString("; ")

                    val expVal = getCookieExp(mergedCookies).takeIf { it > 0L }
                        ?: (System.currentTimeMillis() / 1000L + 86400L)

                    saveSession(activity, mergedCookies, expVal)

                    if (!dataJson.isNullOrBlank() && dataJson.contains("\"list\"")) {
                        parse1024Response(dataJson)?.let { items ->
                            if (items.isNotEmpty()) {
                                cachedFetchedItems = items
                                Log.d(TAG, "Turnstile WebView pre-fetched ${items.size} 1080p stream items!")
                            }
                        }
                    }

                    if (solved) return
                    solved = true
                    handler.removeCallbacks(revealRunnable)
                    cookieManager.flush()
                    Log.d(TAG, "Turnstile verification finished! Captured merged cookies: $mergedCookies")

                    success = true
                    latch.countDown()
                    handler.postDelayed({
                        runCatching { if (dialog.isShowing) dialog.dismiss() }
                    }, 400L)
                }

                webView.addJavascriptInterface(
                    TeraBoxBridge { cookies, dataJson ->
                        activity.runOnUiThread {
                            Log.d(TAG, "Turnstile verified via JavaScript Bridge! cookies='$cookies', hasData=${!dataJson.isNullOrBlank()}")
                            finishWithVerification(cookies, dataJson)
                        }
                    },
                    "AndroidBridge"
                )

                lateinit var cookiePollRunnable: Runnable
                var verifiedTimeMs = 0L

                cookiePollRunnable = Runnable {
                    if (solved && !cachedFetchedItems.isNullOrEmpty()) return@Runnable
                    pollElapsedMs += 600L

                    val cm1 = cookieManager.getCookie("https://1024teradl.com/") ?: ""
                    val cm2 = cookieManager.getCookie("https://1024teradl.com") ?: ""
                    val combined = "$cm1; $cm2"
                    val combinedExp = getCookieExp(combined)

                    val isNewValidExp = combinedExp > (System.currentTimeMillis() / 1000L + 60L) &&
                            (initialExp == 0L || combinedExp != initialExp)

                    if (isNewValidExp) {
                        if (cachedFetchedItems.isNullOrEmpty() && !targetTeraUrl.isNullOrBlank() && verifiedTimeMs == 0L) {
                            verifiedTimeMs = System.currentTimeMillis()
                            Log.d(TAG, "Detected new tdl_pass_exp via CookieManager poll, waiting for in-page /api/proxy fetch...")
                        }

                        val waitedAfterVerify = if (verifiedTimeMs > 0L) System.currentTimeMillis() - verifiedTimeMs else 0L
                        if (!cachedFetchedItems.isNullOrEmpty() || waitedAfterVerify >= 25000L || targetTeraUrl.isNullOrBlank()) {
                            Log.d(TAG, "Turnstile verified via CookieManager poll! itemsReady=${!cachedFetchedItems.isNullOrEmpty()}")
                            finishWithVerification(combined, null)
                            return@Runnable
                        }
                    }

                    webView.evaluateJavascript("(function(){ return document.cookie || ''; })()") { jsVal ->
                        val cleaned = jsVal?.replace("\"", "")?.trim() ?: ""
                        val jsExp = getCookieExp(cleaned)
                        val isNewJsValidExp = jsExp > (System.currentTimeMillis() / 1000L + 60L) &&
                                (initialExp == 0L || jsExp != initialExp)

                        if (isNewJsValidExp) {
                            if (cachedFetchedItems.isNullOrEmpty() && !targetTeraUrl.isNullOrBlank() && verifiedTimeMs == 0L) {
                                verifiedTimeMs = System.currentTimeMillis()
                                Log.d(TAG, "Detected new tdl_pass_exp via document.cookie poll, waiting for in-page /api/proxy fetch...")
                            }

                            val waitedAfterVerify = if (verifiedTimeMs > 0L) System.currentTimeMillis() - verifiedTimeMs else 0L
                            if (!cachedFetchedItems.isNullOrEmpty() || waitedAfterVerify >= 25000L || targetTeraUrl.isNullOrBlank()) {
                                Log.d(TAG, "Turnstile verified via document.cookie poll! itemsReady=${!cachedFetchedItems.isNullOrEmpty()}")
                                finishWithVerification(cleaned, null)
                            }
                        }
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
                        val cm1 = cookieManager.getCookie("https://1024teradl.com/") ?: ""
                        val cm2 = cookieManager.getCookie("https://1024teradl.com") ?: ""
                        val currentCookies = "$cm1; $cm2"
                        if (isCookieExpValid(currentCookies) && !cachedFetchedItems.isNullOrEmpty()) {
                            finishWithVerification(currentCookies, null)
                            return
                        }

                        // Auto-fill, dispatch paste event, and mount explicit Turnstile widget
                        val fillUrl = targetTeraUrl ?: "https://terabox.app/s/18YFqJz7G_5zGZqT9s6U-pA"
                        val escapedFillUrl = fillUrl.replace("'", "\\'")
                        val script = """
                            (function() {
                                if (window.__tb_verified_injected) return;
                                window.__tb_verified_injected = true;

                                function notifyAndroid(dataText) {
                                    try {
                                        var c = document.cookie || '';
                                        if (window.AndroidBridge && window.AndroidBridge.onVerified) {
                                            window.AndroidBridge.onVerified(c, dataText || '');
                                        }
                                    } catch(e) {
                                        console.error('AndroidBridge call error:', e);
                                    }
                                }

                                // Hook fetch so any successful /api/proxy immediately signals Android
                                var _origFetch = window.fetch;
                                window.fetch = function(url, opts) {
                                    var p = _origFetch.apply(this, arguments);
                                    if (typeof url === 'string') {
                                        if (url.indexOf('/api/proxy') !== -1) {
                                            p.then(function(r) {
                                                if (r && r.ok) {
                                                    r.clone().text().then(function(t) {
                                                        if (t && t.indexOf('list') !== -1) {
                                                            notifyAndroid(t);
                                                        }
                                                    }).catch(function() {});
                                                }
                                            }).catch(function() {});
                                        }
                                    }
                                    return p;
                                };

                                // Hook XMLHttpRequest as well
                                try {
                                    var _origOpen = XMLHttpRequest.prototype.open;
                                    var _origSend = XMLHttpRequest.prototype.send;
                                    XMLHttpRequest.prototype.open = function(method, url) {
                                        this._url = url;
                                        return _origOpen.apply(this, arguments);
                                    };
                                    XMLHttpRequest.prototype.send = function() {
                                        if (this._url && typeof this._url === 'string' && this._url.indexOf('/api/proxy') !== -1) {
                                            this.addEventListener('load', function() {
                                                if (this.responseText && this.responseText.indexOf('list') !== -1) {
                                                    notifyAndroid(this.responseText);
                                                }
                                            });
                                        }
                                        return _origSend.apply(this, arguments);
                                    };
                                } catch(e) {}

                                function handleTokenSuccess(token) {
                                    var statusDiv = document.getElementById('__tb_status');
                                    if (statusDiv) statusDiv.innerText = 'Token verified! Unlocking 1080p stream...';

                                    fetch('/api/verify', {
                                        method: 'POST',
                                        credentials: 'include',
                                        headers: { 'Content-Type': 'application/json' },
                                        body: JSON.stringify({ token: token })
                                    }).then(function(r) {
                                        return r.json().catch(function() { return {}; });
                                    }).then(function(res) {
                                        if (statusDiv) statusDiv.innerText = 'Extracting 1080p stream links...';

                                        var targetUrl = '$escapedFillUrl';
                                        if (targetUrl && targetUrl.length > 5) {
                                            fetch('/api/proxy', {
                                                method: 'POST',
                                                credentials: 'include',
                                                headers: { 'Content-Type': 'application/json' },
                                                body: JSON.stringify({ url: targetUrl })
                                            }).then(function(pr) {
                                                return pr.text();
                                            }).then(function(dataText) {
                                                if (statusDiv) statusDiv.innerText = 'Success! 1080p stream ready.';
                                                notifyAndroid(dataText);
                                            }).catch(function(err) {
                                                console.error('Proxy fetch error:', err);
                                                notifyAndroid('');
                                            });
                                        } else {
                                            notifyAndroid('');
                                        }
                                    }).catch(function(err) {
                                        console.error('Verify fetch error:', err);
                                        notifyAndroid('');
                                    });
                                }

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
                                                callback: handleTokenSuccess,
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
                        val cm1 = cookieManager.getCookie("https://1024teradl.com/") ?: ""
                        val cm2 = cookieManager.getCookie("https://1024teradl.com") ?: ""
                        val finalCookies = "$cm1; $cm2"
                        val finalExp = getCookieExp(finalCookies)
                        if (finalExp > (System.currentTimeMillis() / 1000L + 60L) && (initialExp == 0L || finalExp != initialExp)) {
                            finishWithVerification(finalCookies, null)
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
            if (!cachedFetchedItems.isNullOrEmpty()) {
                val items = cachedFetchedItems!!
                cachedFetchedItems = null
                Log.d(TAG, "Using pre-fetched items from Turnstile session (${items.size} files)")
                return@runCatching items
            }

            if (cachedCookies == null) {
                loadSavedSession()
            }

            suspend fun doRequest(cookieStr: String?): Pair<Int, String?> {
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
                    val res = app.post(
                        "$BASE_URL/api/proxy",
                        headers = headers,
                        json = mapOf("url" to shareUrl),
                        timeout = 15
                    )
                    Log.d(TAG, "doRequest status=${res.code}")
                    Pair(res.code, res.text)
                }.getOrElse {
                    Log.w(TAG, "doRequest network failure: ${it.message}")
                    Pair(0, null)
                }
            }

            var (statusCode, responseText) = if (!cachedCookies.isNullOrBlank()) {
                doRequest(cachedCookies)
            } else {
                Pair(0, null)
            }

            val isChallengeRequired = statusCode == 403 ||
                    statusCode == 401 ||
                    responseText == null ||
                    responseText.contains("CHALLENGE_REQUIRED") ||
                    responseText.contains("Verification required")

            if (isChallengeRequired && allowVerification) {
                Log.d(TAG, "Challenge required by 1024teradl (status=$statusCode), forcing Turnstile resolution...")
                val solved = solveTurnstile(shareUrl, force = true)
                var waitCount = 0
                while (cachedFetchedItems.isNullOrEmpty() && waitCount < 20 && isDialogShowing.get()) {
                    try { kotlinx.coroutines.delay(250L) } catch (_: Exception) { break }
                    waitCount++
                }
                if (!cachedFetchedItems.isNullOrEmpty()) {
                    val items = cachedFetchedItems!!
                    cachedFetchedItems = null
                    Log.d(TAG, "Using pre-fetched items after solveTurnstile (${items.size} files)")
                    return@runCatching items
                }
                if (solved && !cachedCookies.isNullOrBlank()) {
                    val retry = doRequest(cachedCookies)
                    responseText = retry.second
                }
            }

            if (responseText.isNullOrBlank()) return@runCatching emptyList()

            parse1024Response(responseText) ?: emptyList()
        }.getOrDefault(emptyList())
    }
}
