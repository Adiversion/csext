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
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

open class TeraBoxExtractor : ExtractorApi() {
    override val name = "TeraBox"
    override val mainUrl = "https://terabox.app"
    override val requiresReferer = false

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val PREFS_NAME = "terabox_prefs"
        private const val KEY_NDUS = "terabox_ndus_cookie"
        private const val KEY_CUSTOM_WORKER = "custom_worker_url"

        @Volatile var ndusCookie: String? = null
        @Volatile var customWorkerUrl: String? = null

        data class CachedStream(val url: String, val name: String, val expiresAt: Long)
        private val linkPlayCache = ConcurrentHashMap<String, CachedStream>()

        fun getSavedNdus(context: Context? = null): String? {
            if (!ndusCookie.isNullOrBlank()) return ndusCookie
            val ctx = context ?: CommonActivity.activity

            // 1. Check SharedPreferences
            val fromPrefs = runCatching {
                val prefs = ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs?.getString(KEY_NDUS, null)
            }.getOrNull()

            if (!fromPrefs.isNullOrBlank()) {
                ndusCookie = fromPrefs
                return fromPrefs
            }

            // 2. Automatic Auto-Detection from Android CookieManager:
            val fromCookieManager = runCatching {
                val cookieManager = android.webkit.CookieManager.getInstance()
                val cookies = cookieManager.getCookie("https://www.terabox.app")
                    ?: cookieManager.getCookie("https://www.terabox.com")
                    ?: ""
                Regex("""\bndus=([^;]+)""").find(cookies)?.groupValues?.get(1)
            }.getOrNull()

            if (!fromCookieManager.isNullOrBlank()) {
                saveNdus(ctx, fromCookieManager)
                return fromCookieManager
            }

            return null
        }

        fun saveNdus(context: Context? = null, cookie: String?) {
            ndusCookie = cookie
            val ctx = context ?: CommonActivity.activity ?: return
            runCatching {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (cookie.isNullOrBlank()) {
                    prefs.edit().remove(KEY_NDUS).apply()
                } else {
                    prefs.edit().putString(KEY_NDUS, cookie).apply()
                }
            }
        }

        fun getSavedCustomWorker(context: Context? = null): String? {
            if (!customWorkerUrl.isNullOrBlank()) return customWorkerUrl
            val ctx = context ?: CommonActivity.activity ?: return null
            return runCatching {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val w = prefs.getString(KEY_CUSTOM_WORKER, null)
                customWorkerUrl = w
                w
            }.getOrNull()
        }

        fun saveCustomWorker(context: Context? = null, worker: String?) {
            customWorkerUrl = worker
            val ctx = context ?: CommonActivity.activity ?: return
            runCatching {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (worker.isNullOrBlank()) {
                    prefs.edit().remove(KEY_CUSTOM_WORKER).apply()
                } else {
                    prefs.edit().putString(KEY_CUSTOM_WORKER, worker).apply()
                }
            }
        }

        fun extractSurl(rawUrl: String): String? {
            var surl = Regex("""(?:/s/|surl=)([a-zA-Z0-9_\-]+)""").find(rawUrl)?.groupValues?.get(1) ?: return null
            if (surl.startsWith("1") && surl.length == 23) {
                surl = surl.substring(1)
            }
            return surl
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
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.trim()
        val ndus = getSavedNdus()

        // 1. Direct TeraBox Mode: If user has configured an ndus session token (high-speed dlink mp4)
        if (!ndus.isNullOrBlank()) {
            val resolvedDirect = resolveDirectTeraBox(cleanUrl, ndus, callback)
            if (resolvedDirect) return
        }

        // 2. Custom Gateway Mode (e.g. saahiyo/terabox-gateway worker):
        val customWorker = getSavedCustomWorker()
        if (!customWorker.isNullOrBlank()) {
            val resolvedCustom = resolveViaCustomWorker(cleanUrl, customWorker, callback)
            if (resolvedCustom) return
        }

        // 3. TeraVideo Native HLS Resolver (Fast, No Captcha/Turnstile, Full Duration HLS VOD)
        resolveViaTeraVideo(cleanUrl, callback)

        // 4. LinkPlay 1080p Direct MP4 Resolver (Full HD 1080p)
        resolveViaLinkPlay(cleanUrl, callback)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveViaLinkPlay(
        rawUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val surl = extractSurl(rawUrl) ?: return false
        val canonicalKey = if (surl.startsWith("1")) surl else "1$surl"
        val fullTeraBoxUrl = "https://terabox.app/s/$canonicalKey"

        // Check in-memory cache first (prevents redundant Turnstile solves)
        val cached = linkPlayCache[canonicalKey]
        if (cached != null && System.currentTimeMillis() < cached.expiresAt - 60_000L) {
            callback(
                newExtractorLink(
                    source = "LinkPlay Direct",
                    name = "${cached.name} - [LinkPlay 1080p ⚡]",
                    url = cached.url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://linkplay.in/"
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            return false
        }

        val activity = CommonActivity.activity ?: getActivity() ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false

        val latch = CountDownLatch(1)
        var capturedToken: String? = null
        var effectiveUa: String = USER_AGENT

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

                // Start invisible so non-interactive Turnstile completes with zero flicker
                dialog.window?.decorView?.alpha = 0f

                val handler = Handler(Looper.getMainLooper())
                var tokenSaved = false

                val root = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#121218"))
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

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
                    text = "LinkPlay 1080p Verification"
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val closeBtn = Button(activity).apply {
                    text = "✕"
                    setTextColor(Color.LTGRAY)
                    setBackgroundColor(Color.TRANSPARENT)
                    textSize = 18f
                    setOnClickListener { dialog.dismiss() }
                }

                header.addView(titleTv)
                header.addView(closeBtn)
                root.addView(header)

                val statusTv = TextView(activity).apply {
                    text = "Verifying browser integrity for 1080p playback..."
                    setTextColor(Color.parseColor("#A0A0B0"))
                    textSize = 12f
                    setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
                }
                root.addView(statusTv)

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
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    )
                }

                val cookieManager = android.webkit.CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowContentAccess = true
                    allowFileAccess = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    useWideViewPort = true
                    loadWithOverviewMode = true

                    val baseUa = userAgentString ?: USER_AGENT
                    val sanitizedUa = baseUa
                        .replace("; wv", "")
                        .replace(Regex("Version/[0-9.]+\\s*"), "")
                        .replace("Android TV", "Android")
                        .let { ua ->
                            if (ua.contains("Android") && ua.contains("Safari/") && !ua.contains("Mobile Safari/")) {
                                ua.replace("Safari/", "Mobile Safari/")
                            } else ua
                        }
                    userAgentString = sanitizedUa
                    effectiveUa = sanitizedUa
                }

                // Reveal dialog if user interaction (checkbox click) is needed after 2.2 seconds
                val revealRunnable = Runnable {
                    if (!tokenSaved && dialog.isShowing) {
                        statusTv.text = "Please complete the verification checkmark below to start video."
                        runCatching {
                            dialog.window?.decorView?.animate()?.alpha(1f)?.setDuration(250L)?.start()
                        }
                    }
                }

                var pollElapsedMs = 0L
                lateinit var pollRunnable: Runnable
                pollRunnable = Runnable {
                    if (tokenSaved) return@Runnable
                    pollElapsedMs += 500L

                    val checkJs = """
                        (function() {
                            try {
                                if (typeof turnstile !== 'undefined') {
                                    var t = turnstile.getResponse();
                                    if (t && t.length > 20) return t;
                                }
                                var inp = document.querySelector('[name="cf-turnstile-response"]');
                                if (inp && inp.value && inp.value.length > 20) return inp.value;
                            } catch(e) {}
                            return '';
                        })();
                    """.trimIndent()

                    webView.evaluateJavascript(checkJs) { rawVal ->
                        val token = rawVal?.trim('"', '\'')?.replace("\\", "")
                        if (!token.isNullOrBlank() && token.length > 20) {
                            tokenSaved = true
                            capturedToken = token
                            handler.removeCallbacks(revealRunnable)
                            handler.postDelayed({
                                runCatching { if (dialog.isShowing) dialog.dismiss() }
                            }, 200L)
                            latch.countDown()
                            return@evaluateJavascript
                        }

                        if (pollElapsedMs >= 40_000L) {
                            Log.w("TeraBox", "LinkPlay Turnstile timed out after 40s")
                            latch.countDown()
                            runCatching { if (dialog.isShowing) dialog.dismiss() }
                            return@evaluateJavascript
                        }

                        handler.postDelayed(pollRunnable, 500L)
                    }
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
                    }
                }

                root.addView(webView)
                dialog.setContentView(root)

                dialog.setOnDismissListener {
                    handler.removeCallbacks(pollRunnable)
                    handler.removeCallbacks(revealRunnable)
                    latch.countDown()
                }

                dialog.show()
                webView.loadUrl("https://linkplay.in/")
                handler.postDelayed(pollRunnable, 700L)
                handler.postDelayed(revealRunnable, 2200L)

            } catch (e: Throwable) {
                Log.e("TeraBox", "resolveViaLinkPlay failed: ${e.message}")
                latch.countDown()
            }
        }

        try {
            latch.await(45, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {}

        val token = capturedToken
        if (token.isNullOrBlank()) return false

        return runCatching {
            val postBody = mapOf(
                "url" to fullTeraBoxUrl,
                "turnstileToken" to token
            )
            val res = app.post(
                "https://linkplay.in/api/fetch-video",
                json = postBody,
                headers = mapOf(
                    "User-Agent" to effectiveUa,
                    "Referer" to "https://linkplay.in/",
                    "Origin" to "https://linkplay.in",
                    "Content-Type" to "application/json"
                ),
                timeout = 25
            ).parsedSafe<LinkPlayResponse>()

            val video = res?.video
            val streamUrl = video?.secure_stream ?: video?.secure_download
            if (res?.success == true && !streamUrl.isNullOrBlank()) {
                val videoName = video?.name ?: "TeraBox Video"
                val expires = Regex("""expires=(\d+)""").find(streamUrl)?.groupValues?.get(1)?.toLongOrNull()
                    ?: (System.currentTimeMillis() + 900_000L)
                linkPlayCache[canonicalKey] = CachedStream(streamUrl, videoName, expires)

                val q = when {
                    videoName.contains("1080", ignoreCase = true) || videoName.contains("4k", ignoreCase = true) -> Qualities.P1080.value
                    videoName.contains("720", ignoreCase = true) -> Qualities.P720.value
                    videoName.contains("480", ignoreCase = true) -> Qualities.P480.value
                    else -> Qualities.P1080.value
                }

                callback(
                    newExtractorLink(
                        source = "LinkPlay Direct",
                        name = "$videoName - [LinkPlay Direct MP4 ⚡]",
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://linkplay.in/"
                        this.quality = q
                    }
                )
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    private suspend fun resolveViaCustomWorker(
        rawUrl: String,
        workerUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val surl = extractSurl(rawUrl) ?: return false
            val baseWorker = workerUrl.trimEnd('/')
            val streamUrl = "$baseWorker/?mode=stream&surl=$surl&type=M3U8_AUTO_720"
            callback(
                newExtractorLink(
                    source = "$name Gateway",
                    name = "TeraBox HD - [Private Gateway ⚡]",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://www.terabox.app/"
                    this.quality = Qualities.P720.value
                }
            )
            true
        }.getOrDefault(false)
    }

    private suspend fun resolveDirectTeraBox(
        rawUrl: String,
        ndus: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val surl = extractSurl(rawUrl) ?: return false
            val listUrl = "https://www.terabox.app/share/list?app_id=250528&shorturl=$surl&root=1"
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Cookie" to "ndus=$ndus",
                "Referer" to "https://www.terabox.app/sharing/link?surl=$surl"
            )
            val res = app.get(listUrl, headers = headers, timeout = 10).parsedSafe<TeraBoxShareListResponse>() ?: return false
            val file = res.list?.firstOrNull() ?: return false
            val dlink = file.dlink

            if (!dlink.isNullOrBlank() && dlink.startsWith("http")) {
                callback(
                    newExtractorLink(
                        source = "$name Direct",
                        name = "${file.serverFilename ?: "TeraBox Video"} - [Direct 🚀]",
                        url = dlink,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://www.terabox.app/"
                        this.quality = Qualities.P1080.value
                    }
                )
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    private suspend fun resolveViaTeraVideo(
        rawUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val surl = extractSurl(rawUrl) ?: return false
            val canonicalKey = if (surl.startsWith("1")) surl else "1$surl"
            val fullTeraBoxUrl = "https://terabox.app/s/$canonicalKey"
            val encodedUrl = URLEncoder.encode(fullTeraBoxUrl, "UTF-8")
            val target = "https://teravideo.in/terabox-api/api.php?action=resolve&url=$encodedUrl"
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "https://teravideo.in/terabox-player-online/",
                "Origin" to "https://teravideo.in"
            )
            val res = app.get(target, headers = headers, timeout = 12).parsedSafe<TeraVideoResponse>()
            val active = res?.data?.activeFile ?: res?.data?.file
            if (res?.success == true && active != null) {
                val videoName = active.name ?: res.data?.title ?: "TeraBox Video"
                var found = false

                val masterUrl = active.masterUrl
                if (!masterUrl.isNullOrBlank()) {
                    val fullMaster = if (masterUrl.startsWith("http")) masterUrl else "https://teravideo.in/terabox-api/$masterUrl"
                    callback(
                        newExtractorLink(
                            source = "TeraVideo HLS",
                            name = "$videoName - [TeraVideo Adaptive HLS ⚡]",
                            url = fullMaster,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://teravideo.in/terabox-player-online/"
                            this.quality = Qualities.P720.value
                        }
                    )
                    found = true
                }

                val hlsUrl = active.hlsUrl
                if (!hlsUrl.isNullOrBlank() && hlsUrl != masterUrl) {
                    val fullHls = if (hlsUrl.startsWith("http")) hlsUrl else "https://teravideo.in/terabox-api/$hlsUrl"
                    callback(
                        newExtractorLink(
                            source = "TeraVideo HLS",
                            name = "$videoName - [TeraVideo 480p HLS]",
                            url = fullHls,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://teravideo.in/terabox-player-online/"
                            this.quality = Qualities.P480.value
                        }
                    )
                    found = true
                }

                found
            } else {
                false
            }
        }.getOrDefault(false)
    }
}

class TeraBoxComExtractor : TeraBoxExtractor() {
    override val mainUrl = "https://terabox.com"
}

class TeraBox1024Extractor : TeraBoxExtractor() {
    override val mainUrl = "https://1024tera.com"
}

class TeraBox1024BoxExtractor : TeraBoxExtractor() {
    override val mainUrl = "https://1024terabox.com"
}

class TeraBoxLinkExtractor : TeraBoxExtractor() {
    override val mainUrl = "https://teraboxlink.com"
}

data class TeraVideoResponse(
    @JsonProperty("success") val success: Boolean? = null,
    @JsonProperty("data") val data: TeraVideoData? = null
)

data class TeraVideoData(
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("active_file") val activeFile: TeraVideoFile? = null,
    @JsonProperty("file") val file: TeraVideoFile? = null
)

data class TeraVideoFile(
    @JsonProperty("fs_id") val fsId: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("size") val size: Long? = null,
    @JsonProperty("duration") val duration: Long? = null,
    @JsonProperty("masterUrl") val masterUrl: String? = null,
    @JsonProperty("hlsUrl") val hlsUrl: String? = null,
    @JsonProperty("streamUrl") val streamUrl: String? = null
)

data class LinkPlayResponse(
    @JsonProperty("success") val success: Boolean = false,
    @JsonProperty("cached") val cached: Boolean? = null,
    @JsonProperty("downloading") val downloading: Boolean? = null,
    @JsonProperty("video") val video: LinkPlayVideo? = null,
    @JsonProperty("error") val error: String? = null
)

data class LinkPlayVideo(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("size") val size: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null,
    @JsonProperty("secure_stream") val secure_stream: String? = null,
    @JsonProperty("secure_download") val secure_download: String? = null
)

data class TeraBoxShareListResponse(
    @JsonProperty("errno") val errno: Int? = null,
    @JsonProperty("list") val list: List<TeraBoxFileInfo>? = null
)

data class TeraBoxFileInfo(
    @JsonProperty("server_filename") val serverFilename: String? = null,
    @JsonProperty("fs_id") val fsId: String? = null,
    @JsonProperty("dlink") val dlink: String? = null,
    @JsonProperty("size") val size: Long? = null
)
