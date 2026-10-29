package com.lagradost

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.Base64
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
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Requests
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AnimePaheProvider : MainAPI() {

    companion object {
        const val MAIN_URL = "https://animepahe.pw"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        private const val PREFS_NAME = "animepahe_cf_prefs"
        private const val KEY_CF_COOKIES = "cf_cookies"
        private const val KEY_CF_UA = "cf_user_agent"
        private const val KEY_CF_TS = "cf_timestamp"

        var cfCookies: String? = null
        var cfUserAgent: String? = null

        fun loadSavedSession(context: Context? = null) {
            if (cfCookies != null && cfUserAgent != null) return
            val ctx = context ?: CommonActivity.activity ?: getActivity() ?: return
            runCatching {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val savedCookies = prefs.getString(KEY_CF_COOKIES, null)
                val savedUa = prefs.getString(KEY_CF_UA, null)
                if (!savedCookies.isNullOrBlank() && extractClearanceToken(savedCookies) != null) {
                    cfCookies = savedCookies
                }
                if (!savedUa.isNullOrBlank()) {
                    cfUserAgent = savedUa
                }
            }
        }

        fun saveSession(context: Context? = null, cookies: String, ua: String?) {
            cfCookies = cookies
            if (!ua.isNullOrBlank()) {
                cfUserAgent = ua
            }
            val ctx = context ?: CommonActivity.activity ?: getActivity() ?: return
            runCatching {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_CF_COOKIES, cookies)
                    .putString(KEY_CF_UA, cfUserAgent)
                    .putLong(KEY_CF_TS, System.currentTimeMillis())
                    .apply()
            }
        }

        fun clearSavedSession(context: Context? = null) {
            cfCookies = null
            val ctx = context ?: CommonActivity.activity ?: getActivity() ?: return
            runCatching {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().remove(KEY_CF_COOKIES).remove(KEY_CF_TS).apply()
            }
        }

        private val cfMutex = Mutex()
        private var lastCfDialogAtMs = 0L
        private val isDialogShowing = AtomicBoolean(false)

        val CHALLENGE_TITLES = listOf(
            "one more step",
            "just a moment",
            "checking your browser",
            "attention required",
            "ddos-guard",
            "cloudflare",
            "security check",
            "5 seconds",
            "turnstile",
            "wait a moment",
            "verifying"
        )

        fun isChallengeTitle(title: String?): Boolean {
            val t = (title ?: "").lowercase().trim()
            if (t.isEmpty() || t == "about:blank") return true
            return CHALLENGE_TITLES.any { t.contains(it) }
        }

        fun extractClearanceToken(cookies: String?): String? {
            if (cookies.isNullOrBlank()) return null
            val match = Regex("""cf_clearance=([^;]+)""").find(cookies)
            val token = match?.groupValues?.get(1)?.trim()
            return if (!token.isNullOrBlank() && token != ";") token else null
        }

        fun hasClearance(cookies: String?): Boolean {
            return extractClearanceToken(cookies) != null
        }

        fun isBlocked(response: NiceResponse): Boolean {
            if (response.code == 403 || response.code == 503) return true
            val text = response.text.trimStart()
            if (text.startsWith("<") && text.contains("<html", ignoreCase = true)) {
                val lower = text.lowercase()
                return lower.contains("<title>just a moment") ||
                       lower.contains("<title>attention required") ||
                       lower.contains("cf-browser-verification") ||
                       lower.contains("challenge-running") ||
                       lower.contains("verify you are human") ||
                       lower.contains("cf-turnstile")
            }
            return false
        }

        fun getHeaders(base: String = MAIN_URL): Map<String, String> {
            if (cfCookies == null || cfUserAgent == null) {
                loadSavedSession()
            }
            if (cfUserAgent == null) {
                val act = CommonActivity.activity ?: getActivity()
                if (act != null) {
                    runCatching {
                        val defaultUa = WebSettings.getDefaultUserAgent(act)
                        if (!defaultUa.isNullOrBlank()) {
                            cfUserAgent = defaultUa
                                .replace("; wv", "")
                                .replace(Regex("Version/[0-9.]+\\s*"), "")
                                .replace("Android TV", "Android")
                                .let { ua ->
                                    if (ua.contains("Android") && ua.contains("Safari/") && !ua.contains("Mobile Safari/")) {
                                        ua.replace("Safari/", "Mobile Safari/")
                                    } else {
                                        ua
                                    }
                                }
                        }
                    }
                }
            }
            val ua = cfUserAgent ?: USER_AGENT
            val headers = mutableMapOf(
                "User-Agent" to ua,
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Accept-Language" to "en-US,en;q=0.9",
                "Referer" to "$base/",
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to "\"Android\""
            )
            val cookies = cfCookies ?: runCatching {
                CookieManager.getInstance().getCookie(base)
            }.getOrNull()
            if (!cookies.isNullOrBlank()) {
                headers["Cookie"] = cookies
            }
            return headers
        }

        private val KEY_REGEX = Regex("""^#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:\s*)$""")
        private val SEGMENT_REGEX = Regex("""segment-(\d+)-""")

        fun fixHlsAesSequenceIvs(m3u8: String): String {
            var currentKeyUri: String? = null
            val sb = java.lang.StringBuilder(m3u8.length)

            for (line in m3u8.lines()) {
                val trimmed = line.trim()
                val keyMatch = KEY_REGEX.find(trimmed)
                if (keyMatch != null) {
                    currentKeyUri = keyMatch.groupValues[1]
                    continue
                }
                if (currentKeyUri != null && !trimmed.startsWith("#") && trimmed.isNotBlank()) {
                    val segMatch = SEGMENT_REGEX.find(trimmed)
                    val segNum = segMatch?.groupValues?.getOrNull(1)?.toLongOrNull()
                    if (segNum != null) {
                        val ivHex = segNum.toString(16).padStart(32, '0')
                        sb.append("""#EXT-X-KEY:METHOD=AES-128,URI="$currentKeyUri",IV=0x$ivHex""").append('\n')
                    }
                }
                sb.append(line).append('\n')
            }
            return sb.toString().trimEnd()
        }

        fun getImageHeaders(url: String? = null): Map<String, String> {
            val ua = cfUserAgent ?: USER_AGENT
            val host = url?.toHttpUrlOrNull()?.host ?: "animepahe.pw"
            val isPaheHost = host.contains("animepahe")
            val headers = mutableMapOf(
                "User-Agent" to ua,
                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
            )
            if (isPaheHost) {
                headers["Referer"] = "$MAIN_URL/"
                headers["sec-ch-ua-mobile"] = "?1"
                headers["sec-ch-ua-platform"] = "\"Android\""
                headers["Sec-Fetch-Dest"] = "image"
                headers["Sec-Fetch-Mode"] = "no-cors"
                headers["Sec-Fetch-Site"] = "same-site"
                val cookies = cfCookies ?: runCatching {
                    CookieManager.getInstance().getCookie("https://$host")
                        ?: CookieManager.getInstance().getCookie(MAIN_URL)
                }.getOrNull()
                if (!cookies.isNullOrBlank()) {
                    headers["Cookie"] = cookies
                }
            }
            return headers
        }

        fun fixImageUrl(url: String?, base: String = MAIN_URL): String? {
            if (url.isNullOrBlank()) return null
            val raw = when {
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> "$base$url"
                else -> url
            }.replace("\\/", "/")

            // Route AnimePahe snapshots & uploads through unblocked i.animepahe.com CDN
            // to bypass Cloudflare 403 Forbidden blocks on Coil ImageLoader
            return if (raw.contains("animepahe.")) {
                raw.replace(Regex("""https?://(?:i\.)?animepahe\.[a-z]+"""), "https://i.animepahe.com")
            } else {
                raw
            }
        }


        fun syncCookiesToApp(appInstance: Requests, base: String = MAIN_URL) {
            if (cfCookies == null) {
                loadSavedSession()
            }
            val cookies = cfCookies ?: runCatching {
                CookieManager.getInstance().getCookie(base)
            }.getOrNull() ?: return

            val targetDomains = listOf(
                "animepahe.pw",
                "i.animepahe.pw",
                "animepahe.ru",
                "i.animepahe.ru",
                "animepahe.com",
                "i.animepahe.com",
                "animepahe.org",
                "i.animepahe.org"
            )

            // 1. Sync to Android CookieManager for all subdomains
            val cookieManager = CookieManager.getInstance()
            targetDomains.forEach { domain ->
                cookies.split(";").forEach { part ->
                    val trimmed = part.trim()
                    if (trimmed.isNotBlank()) {
                        runCatching {
                            cookieManager.setCookie("https://$domain", "$trimmed; Domain=.$domain; Path=/")
                            cookieManager.setCookie("https://$domain", "$trimmed; Path=/")
                        }
                    }
                }
            }
            cookieManager.flush()

            // 2. Sync to OkHttp CookieJar for all subdomains
            targetDomains.forEach { domain ->
                val httpUrl = "https://$domain".toHttpUrlOrNull() ?: return@forEach
                val list = ArrayList<Cookie>()
                cookies.split(";").forEach { part ->
                    val trimmed = part.trim()
                    if (trimmed.isNotBlank()) {
                        Cookie.parse(httpUrl, "$trimmed; Domain=$domain; Path=/")?.let { list.add(it) }
                        Cookie.parse(httpUrl, "$trimmed; Path=/")?.let { list.add(it) }
                    }
                }
                if (list.isNotEmpty()) {
                    runCatching {
                        appInstance.baseClient.cookieJar.saveFromResponse(httpUrl, list)
                    }
                }
            }
        }

        fun purgeCfState(appInstance: Requests) {
            clearSavedSession()
            cfCookies = null
            runCatching { appInstance.baseClient.connectionPool.evictAll() }
            val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull()
            val targetDomains = listOf(
                "animepahe.pw",
                "i.animepahe.pw",
                "animepahe.ru",
                "i.animepahe.ru",
                "animepahe.com",
                "i.animepahe.com",
                "animepahe.org",
                "i.animepahe.org",
                "challenges.cloudflare.com",
                "cloudflare.com"
            )
            if (cookieManager != null) {
                targetDomains.forEach { domain ->
                    runCatching {
                        cookieManager.setCookie("https://$domain", "cf_clearance=expired; Domain=.$domain; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
                        cookieManager.setCookie("https://$domain", "cf_clearance=expired; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
                        cookieManager.setCookie("http://$domain", "cf_clearance=expired; Domain=.$domain; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
                        cookieManager.setCookie("http://$domain", "cf_clearance=expired; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
                    }
                }
                cookieManager.flush()
            }
            targetDomains.forEach { domain ->
                val httpUrl = "https://$domain".toHttpUrlOrNull() ?: return@forEach
                val expiredCookies = listOfNotNull(
                    Cookie.Builder().domain(domain).path("/").name("cf_clearance").value("expired").expiresAt(0L).build()
                )
                runCatching {
                    appInstance.baseClient.cookieJar.saveFromResponse(httpUrl, expiredCookies)
                }
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

        private fun extractMergedCookies(currentUrl: String?): String {
            val cookieManager = CookieManager.getInstance()
            runCatching { cookieManager.flush() }
            val map = LinkedHashMap<String, String>()
            val domains = listOfNotNull(
                currentUrl,
                MAIN_URL,
                "https://animepahe.pw/",
                "https://animepahe.org/",
                "https://animepahe.com/",
                "https://challenges.cloudflare.com/",
                "https://cloudflare.com/"
            )
            domains.forEach { d ->
                val cookieStr = runCatching { cookieManager.getCookie(d) }.getOrNull()
                if (!cookieStr.isNullOrBlank()) {
                    cookieStr.split(";").forEach { part ->
                        val trimmed = part.trim()
                        val eq = trimmed.indexOf("=")
                        if (eq > 0) {
                            val k = trimmed.substring(0, eq).trim()
                            val v = trimmed.substring(eq + 1).trim()
                            if (k.isNotEmpty() && v.isNotEmpty()) {
                                map[k] = v
                            }
                        }
                    }
                }
            }
            return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }


        data class SolveResult(val success: Boolean, val content: String?)

        fun unescapeJs(raw: String?): String? {
            if (raw == null || raw == "null" || raw.isBlank()) return null
            return if (raw.startsWith("\"") && raw.endsWith("\"")) {
                try {
                    org.json.JSONTokener(raw).nextValue().toString()
                } catch (_: Throwable) {
                    raw.substring(1, raw.length - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                        .replace("\\n", "\n")
                        .replace("\\r", "\r")
                        .replace("\\t", "\t")
                }
            } else {
                raw
            }
        }

        fun isBlockedText(text: String): Boolean {
            val trimmed = text.trimStart().lowercase()
            return trimmed.contains("<title>just a moment") ||
                   trimmed.contains("<title>attention required") ||
                   trimmed.contains("cf-browser-verification") ||
                   trimmed.contains("challenge-running") ||
                   trimmed.contains("verify you are human") ||
                   trimmed.contains("challenges.cloudflare.com")
        }

        @SuppressLint("SetJavaScriptEnabled")
        fun solveCloudflare(
            appInstance: Requests,
            targetUrl: String = "$MAIN_URL/api?m=airing&page=1",
            allowDialog: Boolean = true
        ): SolveResult {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return SolveResult(false, null)
            }
            val activity = CommonActivity.activity ?: getActivity()
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                Log.e("AnimePahe", "No active Activity found for Cloudflare verification")
                return SolveResult(false, null)
            }

            if (!isDialogShowing.compareAndSet(false, true)) {
                Log.d("AnimePahe", "Verification dialog already in progress, waiting...")
                var waits = 0
                while (isDialogShowing.get() && waits < 65) {
                    try {
                        Thread.sleep(1000L)
                    } catch (_: InterruptedException) {
                        break
                    }
                    waits++
                }
                return SolveResult(cfCookies != null, null)
            }

            val initialCookies = extractMergedCookies(targetUrl)
            val oldClearance = extractClearanceToken(initialCookies)
            Log.d("AnimePahe", "solveCloudflare start: targetUrl=$targetUrl, allowDialog=$allowDialog, oldClearance present=${oldClearance != null}")

            val latch = CountDownLatch(1)
            var success = false
            var capturedContent: String? = null

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

                    // Start dialog window with 0 alpha so non-interactive Turnstile completes invisibly
                    dialog.window?.decorView?.alpha = 0f

                    val revealDialogRunnable = Runnable {
                        if (!cookiesSaved && dialog.isShowing) {
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
                        text = "AnimePahe Cloudflare Verification"
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
                        text = "Please complete the verification checkmark below. The window will close automatically once done."
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

                        val baseUa = userAgentString ?: cfUserAgent ?: USER_AGENT
                        val sanitizedUa = baseUa
                            .replace("; wv", "")
                            .replace(Regex("Version/[0-9.]+\\s*"), "")
                            .replace("Android TV", "Android")
                            .let { ua ->
                                if (ua.contains("Android") && ua.contains("Safari/") && !ua.contains("Mobile Safari/")) {
                                    ua.replace("Safari/", "Mobile Safari/")
                                } else {
                                    ua
                                }
                            }
                        userAgentString = sanitizedUa
                        cfUserAgent = sanitizedUa
                    }

                    var cookiesSaved = false
                    val handler = Handler(Looper.getMainLooper())
                    var pollElapsedMs = 0L

                    fun extractPageContentAndFinish(cookies: String) {
                        if (cookiesSaved) return
                        cookiesSaved = true

                        handler.removeCallbacks(revealDialogRunnable)
                        cookieManager.flush()
                        val ua = webView.settings.userAgentString
                        saveSession(activity, cookies, ua)
                        syncCookiesToApp(appInstance)

                        val extractScript = """
                            (function() {
                                var pre = document.querySelector('pre');
                                if (pre && pre.innerText && pre.innerText.trim().length > 0) return pre.innerText;
                                if (document.body && document.body.innerText && document.body.innerText.trim().length > 0) return document.body.innerText;
                                return document.documentElement ? document.documentElement.outerHTML : '';
                            })();
                        """.trimIndent()

                        webView.evaluateJavascript(extractScript) { rawVal ->
                            val content = unescapeJs(rawVal)
                            capturedContent = content
                            success = true
                            latch.countDown()
                            handler.postDelayed({
                                runCatching {
                                    if (dialog.isShowing) dialog.dismiss()
                                }
                            }, 300L)
                        }
                    }

                    lateinit var cookiePollRunnable: Runnable
                    cookiePollRunnable = Runnable {
                        if (cookiesSaved) return@Runnable
                        pollElapsedMs += 600L
                        val currentTitle = webView.title ?: ""
                        val currentUrl = webView.url ?: ""
                        val isChallenge = isChallengeTitle(currentTitle)

                        val currentCookies = extractMergedCookies(currentUrl.takeIf { it.isNotBlank() } ?: targetUrl)
                        val currentClearance = extractClearanceToken(currentCookies)
                        val hasNewClearance = !currentClearance.isNullOrBlank() && (oldClearance == null || currentClearance != oldClearance)

                        val isRealPageLoaded = !isChallenge &&
                                !currentUrl.contains("__cf_chl") &&
                                !currentUrl.contains("challenges.cloudflare.com")

                        if (!isChallenge && (hasNewClearance || (!currentClearance.isNullOrBlank() && pollElapsedMs >= 2000L) || (isRealPageLoaded && pollElapsedMs >= 3000L))) {
                            Log.d("AnimePahe", "Verification confirmed (title='$currentTitle', clearance=$currentClearance)! Finishing...")
                            extractPageContentAndFinish(currentCookies)
                            return@Runnable
                        }

                        if (pollElapsedMs >= 65_000L) {
                            Log.w("AnimePahe", "Turnstile verification timed out after 65s")
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
                            val currentTitle = view?.title ?: ""
                            val currentUrl = url ?: ""
                            val isChallenge = isChallengeTitle(currentTitle)
                            Log.d("AnimePahe", "onPageFinished title='$currentTitle' url=$currentUrl isChallenge=$isChallenge")

                            if (isChallenge) {
                                // Crucial: do not exit while still on challenge page!
                                return
                            }

                            val currentCookies = extractMergedCookies(url ?: targetUrl)
                            val currentClearance = extractClearanceToken(currentCookies)
                            val hasNewClearance = !currentClearance.isNullOrBlank() && (oldClearance == null || currentClearance != oldClearance)

                            if (hasNewClearance || !currentClearance.isNullOrBlank()) {
                                Log.d("AnimePahe", "onPageFinished: Clearance verified! Saving cookies.")
                                extractPageContentAndFinish(currentCookies)
                            }
                        }
                    }

                    root.addView(webView)
                    dialog.setContentView(root)

                    dialog.setOnDismissListener {
                        handler.removeCallbacks(cookiePollRunnable)
                        handler.removeCallbacks(revealDialogRunnable)
                        isDialogShowing.set(false)
                        if (!cookiesSaved) {
                            val finalTitle = webView.title ?: ""
                            val isChallenge = isChallengeTitle(finalTitle)
                            if (!isChallenge) {
                                val finalCookies = extractMergedCookies(webView.url ?: targetUrl)
                                val finalClearance = extractClearanceToken(finalCookies)
                                if (!finalClearance.isNullOrBlank()) {
                                    extractPageContentAndFinish(finalCookies)
                                    return@setOnDismissListener
                                }
                            }
                            latch.countDown()
                        }
                    }

                    dialog.show()
                    webView.loadUrl(targetUrl)
                    handler.postDelayed(cookiePollRunnable, 600L)
                    handler.postDelayed(revealDialogRunnable, 2500L)

                } catch (e: Throwable) {
                    Log.e("AnimePahe", "Failed in solveCloudflare: ${e.message}")
                    isDialogShowing.set(false)
                    latch.countDown()
                }
            }

            try {
                latch.await(65, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {}

            return SolveResult(success, capturedContent)
        }

        private fun getType(t: String): TvType {
            return when {
                t.contains("OVA", ignoreCase = true) || t.contains("Special", ignoreCase = true) -> TvType.OVA
                t.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }

        val YOUTUBE_VIDEO_LINK =
            Regex("""(^(?:https?:)?(?://)?(?:www\.)?(?:youtu\.be/|youtube(?:-nocookie)?\.(?:[A-Za-z]{2,4}|[A-Za-z]{2,3}\.[A-Za-z]{2})/)(?:watch|embed/|vi?/)*(?:\?[\w=&]*vi?=)?[^#&?/]{11}.*$)""")
    }

    override var mainUrl = MAIN_URL
    override var name = "AnimePahe"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "en"

    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.Anime,
        TvType.OVA
    )

    private suspend fun fetchWithCloudflare(url: String, allowDialog: Boolean = true): String {
        Log.d("AnimePahe", "fetchWithCloudflare: GET $url")
        // 0. Ensure persistent session is loaded and synced to OkHttp
        if (cfCookies == null) {
            loadSavedSession()
            if (cfCookies != null) {
                syncCookiesToApp(app)
            }
        }

        // 1. Fast check with active cookies (OkHttp)
        val initial = runCatching { app.get(url, headers = getHeaders()) }.getOrNull()
        if (initial != null && !isBlocked(initial)) {
            return initial.text
        }

        Log.w("AnimePahe", "Saved CF cookies rejected on $url (code=${initial?.code}) - entering verification lock")

        return cfMutex.withLock {
            // Re-check: another coroutine might have solved Cloudflare while waiting for lock
            val recheck = runCatching { app.get(url, headers = getHeaders()) }.getOrNull()
            if (recheck != null && !isBlocked(recheck)) {
                Log.d("AnimePahe", "CF already solved by sibling coroutine, continuing with code 200")
                return@withLock recheck.text
            }

            // Only purge if the server explicitly blocked us with a Cloudflare challenge
            if (initial != null && isBlocked(initial)) {
                Log.w("AnimePahe", "Purging stale CF state and initiating solve...")
                purgeCfState(app)
            }

            val solveTarget = if (url.contains("animepahe.pw/api")) url else "$MAIN_URL/api?m=airing&page=1"
            val res = solveCloudflare(app, solveTarget, allowDialog = allowDialog)
            if (!res.success && cfCookies == null) {
                throw ErrorLoadingException("AnimePahe requires Cloudflare verification. Please complete the checkmark.")
            }

            // 3. Retry OkHttp request with newly saved clearance cookies
            val retry = runCatching { app.get(url, headers = getHeaders()) }.getOrNull()
            if (retry != null && !isBlocked(retry)) {
                Log.d("AnimePahe", "appGet retried $url -> code=${retry.code}")
                return@withLock retry.text
            }

            Log.e("AnimePahe", "appGet retry failed on $url (code=${retry?.code})")
            throw ErrorLoadingException("Cloudflare challenge encountered. Please pull down to refresh.")
        }
    }

    override val mainPage = listOf(
        MainPageData("Latest Releases", "$mainUrl/api?m=airing&page=", true)
    )

    // ── Data Models ──────────────────────────────────────────────────
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaEpisode(
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
        @JsonProperty("title") val title: Map<String, String?>? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("airDate") val airDate: String? = null,
        @JsonProperty("overview") val overview: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaImage(
        @JsonProperty("coverType") val coverType: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaAnimeData(
        @JsonProperty("images") val images: List<MetaImage>? = null,
        @JsonProperty("episodes") val episodes: Map<String, MetaEpisode>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListCoverImage(
        @JsonProperty("extraLarge") val extraLarge: String? = null,
        @JsonProperty("large") val large: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListMedia(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("idMal") val idMal: Int? = null,
        @JsonProperty("bannerImage") val bannerImage: String? = null,
        @JsonProperty("coverImage") val coverImage: AniListCoverImage? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListData(
        @JsonProperty("Media") val Media: AniListMedia? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListResponse(
        @JsonProperty("data") val data: AniListData? = null
    )

    data class AiringItem(
        @JsonProperty("anime_title") val animeTitle: String,
        @JsonProperty("episode") val episode: Int?,
        @JsonProperty("snapshot") val snapshot: String?,
        @JsonProperty("created_at") val createdAt: String?,
        @JsonProperty("anime_session") val animeSession: String
    )

    data class AnimePaheLatestReleases(
        @JsonProperty("total") val total: Int,
        @JsonProperty("data") val data: List<AiringItem>
    )

    data class AnimePaheSearchData(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("title") val title: String,
        @JsonProperty("type") val type: String?,
        @JsonProperty("episodes") val episodes: Int?,
        @JsonProperty("status") val status: String?,
        @JsonProperty("season") val season: String?,
        @JsonProperty("year") val year: Int?,
        @JsonProperty("score") val score: Double?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("session") val session: String,
        @JsonProperty("relevance") val relevance: String?
    )

    data class AnimePaheSearch(
        @JsonProperty("total") val total: Int,
        @JsonProperty("data") val data: List<AnimePaheSearchData>
    )

    data class AnimeEpisodeData(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("anime_id") val animeId: Int?,
        @JsonProperty("episode") val episode: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("snapshot") val snapshot: String?,
        @JsonProperty("session") val session: String,
        @JsonProperty("filler") val filler: Int?,
        @JsonProperty("created_at") val createdAt: String?
    )

    data class AnimePaheReleaseResponse(
        @JsonProperty("total") val total: Int,
        @JsonProperty("per_page") val perPage: Int,
        @JsonProperty("current_page") val currentPage: Int,
        @JsonProperty("last_page") val lastPage: Int,
        @JsonProperty("data") val data: List<AnimeEpisodeData>
    )

    data class LoadData(val session: String, val sessionDate: Long, val name: String)

    data class LinkLoadData(
        @JsonProperty("mainUrl") val mainUrl: String,
        @JsonProperty("is_play_page") val isPlayPage: Boolean,
        @JsonProperty("episode_num") val episodeNum: Int,
        @JsonProperty("page") val page: Int,
        @JsonProperty("session") val session: String,
        @JsonProperty("episode_session") val episodeSession: String
    )

    private suspend fun resolveEpisodeUrl(data: LinkLoadData): String? {
        if (data.isPlayPage) {
            return "${data.mainUrl}/play/${data.session}/${data.episodeSession}"
        }
        val url = "${data.mainUrl}/api?m=release&id=${data.session}&sort=episode_asc&page=${data.page}"
        val jsonResponse = runCatching {
            val resp = app.get(url, headers = getHeaders(data.mainUrl))
            if (isBlocked(resp)) throw Exception("CF blocked")
            resp.parsedSafe<AnimePaheReleaseResponse>()
        }.getOrNull() ?: runCatching {
            val raw = fetchWithCloudflare(url)
            parseJson<AnimePaheReleaseResponse>(raw)
        }.getOrNull() ?: return null

        val epSession = jsonResponse.data.firstOrNull { it.episode == data.episodeNum }?.session
            ?: return null
        return "${data.mainUrl}/play/${data.session}/$epSession"
    }

    // ── Main Page (Airing Releases - Netflix Style Horizontal Lane) ──
    private suspend fun fetchTopBanners(titles: List<String>): Map<Int, String> {
        if (titles.isEmpty()) return emptyMap()
        return runCatching {
            val queryParts = titles.mapIndexed { idx, title ->
                val clean = title
                    .replace(Regex("""\s*(?:Season|\(TV\)|Part)\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""[^a-zA-Z0-9\s]"""), " ")
                    .trim()
                val escaped = clean.replace("\\", "\\\\").replace("\"", "\\\"")
                "a$idx: Media(search: \"$escaped\", type: ANIME) { bannerImage coverImage { extraLarge } }"
            }.joinToString(" ")
            val resp = app.post(
                "https://graphql.anilist.co",
                json = mapOf("query" to "query { $queryParts }")
            ).text
            val map = mutableMapOf<Int, String>()
            titles.indices.forEach { idx ->
                val banner = Regex(""""a$idx":\s*\{[^}]*?"bannerImage":\s*"([^"]+)"""").find(resp)?.groupValues?.get(1)
                    ?.takeIf { it != "null" && it.isNotBlank() }
                    ?.replace("\\/", "/")
                val poster = Regex(""""a$idx":\s*\{.*?"extraLarge":\s*"([^"]+)"""").find(resp)?.groupValues?.get(1)
                    ?.takeIf { it != "null" && it.isNotBlank() }
                    ?.replace("\\/", "/")
                val best = banner ?: poster
                if (!best.isNullOrBlank()) {
                    map[idx] = best
                }
            }
            map
        }.getOrDefault(emptyMap())
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = "${request.data}$page"
            val responseText = fetchWithCloudflare(url)
            val parsed = parseJson<AnimePaheLatestReleases>(responseText)

            val topBanners = if (page == 1 && parsed.data.isNotEmpty()) {
                fetchTopBanners(parsed.data.take(5).map { it.animeTitle })
            } else {
                emptyMap()
            }

            val items = parsed.data.mapIndexed { idx, item ->
                newAnimeSearchResponse(
                    item.animeTitle,
                    LoadData(item.animeSession, unixTime, item.animeTitle).toJson(),
                    fix = false
                ) {
                    val banner = topBanners[idx]
                    val snap = fixImageUrl(item.snapshot)
                    this.posterUrl = banner ?: snap
                    this.posterHeaders = getImageHeaders(this.posterUrl)
                    addDubStatus(DubStatus.Subbed, item.episode)
                }
            }

            newHomePageResponse(
                HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = true
                ),
                hasNext = items.isNotEmpty()
            )
        } catch (e: Throwable) {
            Log.w("AnimePahe", "Failed to load main page: ${e.message}")
            newHomePageResponse(emptyList(), false)
        }
    }

    // ── Search ────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/api?m=search&l=8&q=$encodedQuery"
        val responseText = fetchWithCloudflare(url)
        val parsed = parseJson<AnimePaheSearch>(responseText)

        return parsed.data.map { item ->
            newAnimeSearchResponse(
                item.title,
                LoadData(item.session, unixTime, item.title).toJson(),
                fix = false
            ) {
                val post = fixImageUrl(item.poster)
                this.posterUrl = post
                this.posterHeaders = getImageHeaders(post)
                addDubStatus(DubStatus.Subbed, item.episodes)
            }
        }
    }

    // ── Episode List Generation ───────────────────────────────────────
    private suspend fun generateListOfEpisodes(session: String, fallbackPoster: String? = null): List<Episode> {
        return try {
            val uri = "$mainUrl/api?m=release&id=$session&sort=episode_asc&page=1"
            val responseText = fetchWithCloudflare(uri)
            val data = parseJson<AnimePaheReleaseResponse>(responseText)

            val lastPage = data.lastPage
            val perPage = data.perPage
            val total = data.total
            val episodes = ArrayList<Episode>()

            fun getTitle(k: AnimeEpisodeData): String {
                val title = k.title
                return if (!title.isNullOrBlank()) title else "Episode ${k.episode ?: 1}"
            }

            // Index page 1 snapshots for instant lookup
            val page1Snapshots = data.data.associate { (it.episode ?: 0) to fixImageUrl(it.snapshot) }

            if (lastPage == 1 && perPage >= total) {
                data.data.forEach { ep ->
                    episodes.add(
                        newEpisode(
                            LinkLoadData(
                                mainUrl = mainUrl,
                                isPlayPage = true,
                                episodeNum = ep.episode ?: 0,
                                page = 1,
                                session = session,
                                episodeSession = ep.session
                            ).toJson()
                        ) {
                            ep.createdAt?.let { dateStr ->
                                runCatching { addDate(dateStr.substringBefore(" ").substringBefore("T")) }
                            }
                            this.name = getTitle(ep)
                            this.posterUrl = fixImageUrl(ep.snapshot) ?: fallbackPoster
                            this.episode = ep.episode
                        }
                    )
                }
            } else {
                var currentEp = 1
                for (page in 1..lastPage) {
                    for (i in 0 until perPage) {
                        if (currentEp <= total) {
                            val epNum = currentEp
                            val snap = page1Snapshots[epNum] ?: fallbackPoster
                            episodes.add(
                                newEpisode(
                                    LinkLoadData(
                                        mainUrl = mainUrl,
                                        isPlayPage = false,
                                        episodeNum = currentEp,
                                        page = page,
                                        session = session,
                                        episodeSession = ""
                                    ).toJson()
                                ) {
                                    this.episode = currentEp
                                    this.name = "Episode $currentEp"
                                    this.posterUrl = snap
                                }
                            )
                            currentEp++
                        }
                    }
                }
            }
            episodes
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private data class AnimeArtwork(
        val fanartUrl: String? = null,
        val bannerUrl: String? = null,
        val highResPosterUrl: String? = null,
        val anilistId: Int? = null,
        val malId: Int? = null,
        val episodeMeta: Map<String, MetaEpisode>? = null
    )

    private suspend fun fetchArtwork(
        animeTitle: String,
        initialAnilistId: Int?,
        initialMalId: Int?
    ): AnimeArtwork = withTimeoutOrNull(2500L) {
        var anilistId = initialAnilistId
        var malId = initialMalId
        var fanartUrl: String? = null
        var bannerUrl: String? = null
        var highResPosterUrl: String? = null

        coroutineScope {
            val aniZipUrl = when {
                anilistId != null -> "https://api.ani.zip/mappings?anilist_id=$anilistId"
                malId != null -> "https://api.ani.zip/mappings?mal_id=$malId"
                else -> null
            }

            val aniZipJob = if (aniZipUrl != null) {
                async {
                    runCatching {
                        app.get(aniZipUrl, headers = mapOf("User-Agent" to USER_AGENT)).parsedSafe<MetaAnimeData>()
                    }.getOrNull()
                }
            } else null

            val aniListJob = async {
                runCatching {
                    val query = when {
                        anilistId != null -> "query{Media(id:$anilistId,type:ANIME){id idMal bannerImage coverImage{extraLarge large}}}"
                        malId != null -> "query{Media(idMal:$malId,type:ANIME){id idMal bannerImage coverImage{extraLarge large}}}"
                        else -> {
                            val cleanTitle = animeTitle
                                .replace(Regex("""\s*(?:Season|\(TV\)|Part)\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
                                .replace(Regex("""[^a-zA-Z0-9\s]"""), " ")
                                .trim()
                            val escapedTitle = cleanTitle.replace("\\", "\\\\").replace("\"", "\\\"")
                            "query{Media(search:\"$escapedTitle\",type:ANIME){id idMal bannerImage coverImage{extraLarge large}}}"
                        }
                    }
                    app.post(
                        "https://graphql.anilist.co",
                        json = mapOf("query" to query)
                    ).parsedSafe<AniListResponse>()
                }.getOrNull()
            }

            val aniZipMeta = aniZipJob?.await()
            val aniListMeta = aniListJob.await()?.data?.Media

            if (aniZipMeta != null) {
                fanartUrl = aniZipMeta.images?.firstOrNull { 
                    it.coverType.equals("Fanart", ignoreCase = true) || 
                    it.coverType.equals("Background", ignoreCase = true)
                }?.url
                bannerUrl = aniZipMeta.images?.firstOrNull {
                    it.coverType.equals("Banner", ignoreCase = true)
                }?.url
                highResPosterUrl = aniZipMeta.images?.firstOrNull { 
                    it.coverType.equals("Poster", ignoreCase = true) 
                }?.url
            }

            if (aniListMeta != null) {
                if (anilistId == null && aniListMeta.id != null) {
                    anilistId = aniListMeta.id
                }
                if (malId == null && aniListMeta.idMal != null) {
                    malId = aniListMeta.idMal
                }
                if (fanartUrl == null && !aniListMeta.bannerImage.isNullOrBlank()) {
                    fanartUrl = aniListMeta.bannerImage
                }
                if (highResPosterUrl == null) {
                    highResPosterUrl = aniListMeta.coverImage?.extraLarge ?: aniListMeta.coverImage?.large
                }
            }

            AnimeArtwork(
                fanartUrl = fanartUrl,
                bannerUrl = bannerUrl,
                highResPosterUrl = highResPosterUrl,
                anilistId = anilistId,
                malId = malId,
                episodeMeta = aniZipMeta?.episodes
            )
        }
    } ?: AnimeArtwork(anilistId = initialAnilistId, malId = initialMalId)

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val session = parseJson<LoadData>(url).session.takeIf { it.isNotBlank() } ?: return null

            val pageHtml = fetchWithCloudflare("$mainUrl/anime/$session")
            val doc = Jsoup.parse(pageHtml)

            val japTitle = doc.selectFirst("h2.japanese")?.text()
            val animeTitle = doc.selectFirst("span.sr-only.unselectable")?.text()
                ?: doc.selectFirst("h1")?.text() ?: ""
            val poster = doc.selectFirst(".anime-poster a")?.attr("href")
                ?: doc.selectFirst(".anime-poster img")?.attr("src")
                ?: doc.selectFirst(".anime-poster img")?.attr("data-src")

            val tvTypeStr = doc.selectFirst("""a[href*="/anime/type/"]""")?.text() ?: ""
            val tvType = getType(tvTypeStr)

            val trailer: String? = if (pageHtml.contains("youtube.com/watch")) {
                YOUTUBE_VIDEO_LINK.find(pageHtml)?.groupValues?.get(1)
            } else null

            val year = Regex("""<strong>Aired:</strong>[^,]*, (\d+)""")
                .find(pageHtml)?.groupValues?.get(1)?.toIntOrNull()

            val status = when {
                doc.selectFirst("a[href='/anime/airing']") != null -> ShowStatus.Ongoing
                doc.selectFirst("a[href='/anime/completed']") != null -> ShowStatus.Completed
                else -> null
            }

            val synopsis = doc.selectFirst(".anime-synopsis")?.text()
            val genres = doc.select(".anime-genre > ul a").map { it.text() }

            var initialAnilistId: Int? = null
            var initialMalId: Int? = null

            doc.select("a[href*='anilist.co'], a[href*='myanimelist.net']").forEach { aTag ->
                val href = aTag.attr("href")
                if (initialAnilistId == null) {
                    initialAnilistId = Regex("""anilist\.co/anime/(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                }
                if (initialMalId == null) {
                    initialMalId = Regex("""myanimelist\.net/anime/(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                }
            }

            val initialPosterFallback = fixImageUrl(poster)
            val (episodes, artwork) = coroutineScope {
                val epJob = async { generateListOfEpisodes(session, initialPosterFallback) }
                val artJob = async { fetchArtwork(animeTitle, initialAnilistId, initialMalId) }
                epJob.await() to artJob.await()
            }

            val bestHeroBanner = artwork.fanartUrl ?: artwork.bannerUrl ?: artwork.highResPosterUrl ?: initialPosterFallback
            val bestPoster = artwork.highResPosterUrl ?: initialPosterFallback
            val epMeta = artwork.episodeMeta

            // Enrich episodes with high-res AniZip screencaps and real episode titles
            episodes.forEach { ep ->
                val epNum = ep.episode
                val meta = epNum?.let { epMeta?.get(it.toString()) }

                // 1. Localized Episode Title enrichment
                val realTitle = meta?.title?.get("en") ?: meta?.title?.get("x-jat") ?: meta?.title?.get("ja")
                if (!realTitle.isNullOrBlank() && (ep.name.isNullOrBlank() || ep.name?.startsWith("Episode ") == true)) {
                    ep.name = realTitle
                }

                // 2. Air Date fallback
                if (ep.date == null) {
                    meta?.airDate?.let { dateStr ->
                        runCatching { ep.addDate(dateStr.substringBefore(" ").substringBefore("T")) }
                    }
                }

                // 3. Episode Overview / Synopsis fallback
                if (ep.description.isNullOrBlank()) {
                    ep.description = meta?.overview
                }

                // 4. Fast, unblocked AniZip screencap (TheTVDB/TMDB) thumbnail
                val metaImage = meta?.image?.takeIf { it.isNotBlank() }
                if (metaImage != null) {
                    ep.posterUrl = metaImage
                } else if (ep.posterUrl.isNullOrBlank() || ep.posterUrl == initialPosterFallback) {
                    ep.posterUrl = bestPoster
                }
            }


            newAnimeLoadResponse(animeTitle, url, tvType) {
                engName = animeTitle
                japName = japTitle
                this.posterUrl = bestPoster
                this.backgroundPosterUrl = bestHeroBanner
                this.posterHeaders = getImageHeaders(poster ?: "$MAIN_URL/")
                this.year = year
                addEpisodes(DubStatus.Subbed, episodes)
                this.showStatus = status
                this.plot = synopsis
                this.tags = if (genres.isNotEmpty()) ArrayList(genres) else null

                addMalId(artwork.malId)
                addAniListId(artwork.anilistId)
                addTrailer(trailer)
            }
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }

    // ── Kwik Stream Unpacker ──────────────────────────────────────────
    private suspend fun getStreamUrlFromKwik(kwikUrl: String): String? {
        val maxRetries = 3
        for (attempt in 0 until maxRetries) {
            val res = runCatching {
                app.get(
                    kwikUrl,
                    headers = mapOf(
                        "User-Agent" to (cfUserAgent ?: USER_AGENT),
                        "Referer" to "$mainUrl/"
                    )
                )
            }.getOrNull()

            if (res != null) {
                if (res.code == 429) {
                    val delayMs = 600L * (1L shl attempt) + (50L..200L).random()
                    Log.w("AnimePahe", "Kwik rate limited (429) on $kwikUrl. Retrying in ${delayMs}ms...")
                    delay(delayMs)
                    continue
                }
                if (res.code in 200..299) {
                    val unpacked = getAndUnpack(res.text)
                    val stream = Regex("""source\s*=\s*['"]([^'"]+)['"]""").find(unpacked)?.groupValues?.get(1)
                        ?: Regex("""(?:https?://[^\s'"<>]+\.(?:mp4|m3u8)[^\s'"<>]*)""").find(unpacked)?.value
                        ?: Regex("""url\s*:\s*['"]([^'"]+\.(?:mp4|m3u8)[^'"]*)['"]""").find(unpacked)?.groupValues?.get(1)
                    if (!stream.isNullOrBlank()) {
                        return stream
                    }
                }
            }
            if (attempt < maxRetries - 1) {
                delay(350L + (50L..150L).random())
            }
        }
        return null
    }

    // ── Load Links ────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = parseJson<LinkLoadData>(data)
        val episodeUrl = resolveEpisodeUrl(parsed) ?: return false

        val html = fetchWithCloudflare(episodeUrl)
        val doc = Jsoup.parse(html)

        val buttons = doc.select("#resolutionMenu > button")
        val downloadAnchors = doc.select("#pickDownload > a")

        coroutineScope {
            // 1. Resolution menu buttons (#resolutionMenu button[data-src])
            val buttonJobs = buttons.map { btn ->
                async {
                    val kwikSrc = btn.attr("data-src").takeIf { it.isNotBlank() } ?: return@async
                    val rawText = btn.text().trim()
                    val audio = btn.attr("data-audio").trim().lowercase()
                    val isAv1 = btn.attr("data-av1") == "1"
                    val quality = Regex("""(\d+)p""").find(rawText)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Qualities.Unknown.value

                    val audioBadge = when {
                        audio == "eng" || rawText.contains("dub", ignoreCase = true) -> " [ENG DUB]"
                        audio == "jpn" || rawText.contains("sub", ignoreCase = true) -> " [SUB]"
                        audio.isNotBlank() -> " [${audio.uppercase()}]"
                        else -> ""
                    }
                    val av1Badge = if (isAv1) " (AV1)" else ""
                    val formattedName = "AnimePahe - $rawText$audioBadge$av1Badge"

                    getStreamUrlFromKwik(kwikSrc)?.let { streamUrl ->
                        val link = newExtractorLink(
                            source = "AnimePahe",
                            name = formattedName,
                            url = streamUrl,
                            type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://kwik.cx/"
                            this.quality = quality
                        }
                        synchronized(callback) {
                            callback(link)
                        }
                    }
                }
            }

            // 2. Embedded player fallback script regex: let url = "https://kwik.cx/e/..."
            val fallbackJob = if (buttons.isEmpty()) {
                async {
                    val urlRegex = Regex("""let url = "(.*?)";""")
                    val iframeSrc = doc.selectFirst("iframe[src*='kwik']")?.attr("src")
                    val embed = urlRegex.find(html)?.groupValues?.getOrNull(1) ?: iframeSrc
                    if (!embed.isNullOrBlank()) {
                        getStreamUrlFromKwik(embed)?.let { linkUrl ->
                            val link = newExtractorLink(
                                source = "AnimePahe",
                                name = "AnimePahe - Default",
                                url = linkUrl,
                                type = if (linkUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://kwik.cx/"
                                this.quality = Qualities.Unknown.value
                            }
                            synchronized(callback) {
                                callback(link)
                            }
                        }
                    }
                }
            } else null

            // 3. Download links from #pickDownload
            val downloadJobs = downloadAnchors.map { a ->
                async {
                    val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@async
                    val label = a.text()
                    val quality = Regex("""(\d+)p""").find(label)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Qualities.Unknown.value

                    if (href.contains("kwik.cx")) {
                        getStreamUrlFromKwik(href)?.let { directUrl ->
                            val link = newExtractorLink(
                                source = "AnimePahe",
                                name = "Download - $label",
                                url = directUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = href
                                this.quality = quality
                            }
                            synchronized(callback) {
                                callback(link)
                            }
                        }
                    }
                }
            }

            buttonJobs.awaitAll()
            fallbackJob?.await()
            downloadJobs.awaitAll()
        }

        return true
    }

    // ── Video Interceptor for HLS AES-128 Stream Decryption ───────────
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        if (extractorLink.type != ExtractorLinkType.M3U8 && !extractorLink.url.contains(".m3u8")) {
            return null
        }
        return Interceptor { chain ->
            val request = chain.request()
            val reqBuilder = request.newBuilder()
            if (request.header("Referer").isNullOrBlank()) {
                reqBuilder.header("Referer", "https://kwik.cx/")
            }
            val response = chain.proceed(reqBuilder.build())
            if (!request.url.encodedPath.endsWith(".m3u8")) {
                return@Interceptor response
            }
            val body = response.body ?: return@Interceptor response
            val contentType = body.contentType()
            val bodyStr = body.string()
            if (bodyStr.contains("#EXT-X-KEY:METHOD=AES-128") && !bodyStr.contains(",IV=0x")) {
                val fixed = fixHlsAesSequenceIvs(bodyStr)
                response.newBuilder()
                    .body(fixed.toByteArray(Charsets.UTF_8).toResponseBody(contentType))
                    .build()
            } else {
                response.newBuilder()
                    .body(bodyStr.toByteArray(Charsets.UTF_8).toResponseBody(contentType))
                    .build()
            }
        }
    }
}

