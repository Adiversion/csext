package com.lagradost

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URLEncoder

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
        @Volatile private var cachedSessionCookies: String? = null

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
            // If user ever logged into TeraBox on their Android device, grab ndus automatically with 0 manual typing
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

        suspend fun getSessionCookies(): String {
            if (!cachedSessionCookies.isNullOrBlank()) return cachedSessionCookies!!
            return runCatching {
                val res = app.get("https://iteraplay.com/", headers = mapOf("User-Agent" to USER_AGENT), timeout = 8)
                val cookies = res.headers
                    .filter { it.first.equals("set-cookie", ignoreCase = true) }
                    .map { it.second.substringBefore(";").trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("; ")
                if (cookies.isNotBlank()) {
                    cachedSessionCookies = cookies
                }
                cookies
            }.getOrDefault("")
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

        // 2. Direct TeraBox Native HLS Streaming (100% Free, NO Rate Limits, NO Login)
        resolveDirectTeraBoxHls(cleanUrl, callback)

        // 3. Custom Gateway Mode (e.g. saahiyo/terabox-gateway worker):
        val customWorker = getSavedCustomWorker()
        if (!customWorker.isNullOrBlank()) {
            val resolvedCustom = resolveViaCustomWorker(cleanUrl, customWorker, callback)
            if (resolvedCustom) return
        }

        // 4. Bonus Transcode Worker for 720p/1080p HD (subject to guest limit)
        resolveViaTranscodeWorker(cleanUrl, subtitleCallback, callback)
    }

    private suspend fun resolveDirectTeraBoxHls(
        rawUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val surl = extractSurl(rawUrl) ?: return false
            val pageUrl = "https://www.terabox.app/sharing/link?surl=$surl"
            val pageRes = app.get(
                pageUrl,
                headers = mapOf("User-Agent" to USER_AGENT),
                timeout = 10
            )
            val html = pageRes.text
            val cookies = pageRes.headers
                .filter { it.first.equals("set-cookie", ignoreCase = true) }
                .map { it.second.substringBefore(";").trim() }
                .filter { it.isNotBlank() }
                .joinToString("; ")

            val stateJson = Regex("""window\.__INITIAL_STATE__\s*=\s*(\{.*?\});\s*<\/script>""").find(html)?.groupValues?.get(1)
                ?: return false
            val root = parseJson<Map<String, Any?>>(stateJson)
            val pinia = root["pinia"] as? Map<*, *> ?: return false
            val share = pinia["share"] as? Map<*, *> ?: return false
            val sInfo = share["shareInfo"] as? Map<*, *> ?: return false
            val fileInfo = sInfo["fileInfo"] as? Map<*, *> ?: return false

            val sign = sInfo["sign"]?.toString() ?: return false
            val timestamp = sInfo["timestamp"]?.toString() ?: return false
            val shareid = sInfo["shareid"]?.toString() ?: return false
            val uk = sInfo["uk"]?.toString() ?: return false
            val path = fileInfo["path"]?.toString() ?: return false
            val fid = fileInfo["fs_id"]?.toString() ?: return false
            val filename = fileInfo["server_filename"]?.toString() ?: "TeraBox Video"

            var found = false
            val qualities = listOf("M3U8_AUTO_480" to Qualities.P480, "M3U8_AUTO_360" to Qualities.P360)
            for (q in qualities) {
                val encodedPath = URLEncoder.encode(path, "UTF-8")
                val streamUrl = "https://www.terabox.app/share/streaming?app_id=250528&channel=dubox&clienttype=0&web=1&type=${q.first}&path=$encodedPath&shareid=$shareid&uk=$uk&fid=$fid&sign=$sign&timestamp=$timestamp"
                val probe = app.get(
                    streamUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to pageUrl,
                        "Cookie" to cookies
                    ),
                    timeout = 6
                )
                if (probe.text.contains("#EXTM3U")) {
                    found = true
                    val qNum = q.first.replace("M3U8_AUTO_", "")
                    callback(
                        newExtractorLink(
                            source = "TeraBox Direct",
                            name = "$filename - [TeraBox Direct ${qNum}p 🟢]",
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://www.terabox.app/"
                            this.quality = q.second.value
                            this.headers = mapOf(
                                "Cookie" to cookies,
                                "User-Agent" to USER_AGENT,
                                "Referer" to pageUrl
                            )
                        }
                    )
                }
            }
            found
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

    private suspend fun resolveViaTranscodeWorker(
        rawUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            var cookies = getSessionCookies()
            val streamHeaders = mutableMapOf(
                "Content-Type" to "application/json",
                "User-Agent" to USER_AGENT,
                "Origin" to "https://iteraplay.com",
                "Referer" to "https://iteraplay.com/"
            )
            if (cookies.isNotBlank()) {
                streamHeaders["Cookie"] = cookies
            }

            var streamRes = app.post(
                "https://iteraplay.com/api/stream",
                headers = streamHeaders,
                json = mapOf("url" to rawUrl),
                timeout = 15
            ).parsedSafe<IteraStreamResponse>()

            // Retry once if token expired
            if (streamRes?.status != "success") {
                if (streamRes?.status == "error") {
                    val errMsg = streamRes?.list?.firstOrNull()?.name
                    CommonActivity.activity?.let { act ->
                        act.runOnUiThread {
                            android.widget.Toast.makeText(
                                act,
                                "iTeraPlay: Guest limit reached (5 videos/6h). Switch mobile data/IP or login in settings.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                cachedSessionCookies = null
                cookies = getSessionCookies()
                if (cookies.isNotBlank()) streamHeaders["Cookie"] = cookies
                streamRes = app.post(
                    "https://iteraplay.com/api/stream",
                    headers = streamHeaders,
                    json = mapOf("url" to rawUrl),
                    timeout = 15
                ).parsedSafe<IteraStreamResponse>()
            }

            var found = false
            streamRes?.list?.forEach { file ->
                val fastStreams = file.fastStreamUrl ?: return@forEach
                fastStreams.forEach { (qualityKey, m3u8Url) ->
                    if (m3u8Url.isNotBlank() && m3u8Url.startsWith("http")) {
                        found = true
                        val qValue = when {
                            qualityKey.contains("1080") -> Qualities.P1080.value
                            qualityKey.contains("720")  -> Qualities.P720.value
                            qualityKey.contains("480")  -> Qualities.P480.value
                            qualityKey.contains("360")  -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "${file.name ?: "TeraBox"} - [$qualityKey ⚡]",
                                url = m3u8Url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://iteraplay.com/"
                                this.quality = qValue
                            }
                        )
                    }
                }

                if (!file.subtitleUrl.isNullOrBlank() && file.subtitleUrl.startsWith("http")) {
                    subtitleCallback(
                        SubtitleFile(
                            lang = "English (AI)",
                            url = file.subtitleUrl
                        )
                    )
                }
            }
            found
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
