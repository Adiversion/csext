package com.lagradost

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.URLDecoder
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
        @Volatile var appContext: Context? = null

        val CANDIDATE_DOMAINS = listOf(
            "https://www.1024terabox.com",
            "https://1024terabox.com",
            "https://www.terabox.app",
            "https://terabox.app",
            "https://www.terabox.com",
            "https://terabox.com",
            "https://dm.terabox.com",
            "https://dm.1024tera.com",
            "https://www.1024tera.com",
            "https://1024tera.com",
            "https://www.freeterabox.com",
            "https://freeterabox.com",
            "https://www.teraboxlink.com",
            "https://teraboxlink.com",
            "https://passport.terabox.com",
            "https://passport.1024terabox.com",
            "https://pan.terabox.com",
            "https://pan.1024terabox.com",
            "https://accounts.google.com"
        )

        fun extractNdus(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val match = Regex("""(?i)\bndus=([^;\s"']+)""").find(raw)
            val token = match?.groupValues?.get(1)?.trim() ?: if (!raw.contains("=") && raw.trim().length >= 8) raw.trim() else null
            return if (!token.isNullOrBlank() && token.length >= 8) token else null
        }

        fun scanCookieManagerForNdus(extraUrl: String? = null): String? {
            return runCatching {
                val cm = android.webkit.CookieManager.getInstance()
                runCatching { cm.flush() }
                val targets = mutableListOf<String>()
                if (!extraUrl.isNullOrBlank()) {
                    targets.add(extraUrl)
                }
                for (domain in CANDIDATE_DOMAINS) {
                    targets.add(domain)
                    targets.add("$domain/")
                    targets.add("$domain/main")
                    targets.add("$domain/login")
                }
                for (t in targets) {
                    val c = cm.getCookie(t)
                    val token = extractNdus(c)
                    if (!token.isNullOrBlank()) {
                        return@runCatching token
                    }
                }
                null
            }.getOrNull()
        }

        fun getSavedNdus(context: Context? = null): String? {
            if (!ndusCookie.isNullOrBlank()) return ndusCookie
            val ctx = context ?: appContext ?: CommonActivity.activity

            // 1. Check SharedPreferences
            val fromPrefs = runCatching {
                val prefs = ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs?.getString(KEY_NDUS, null)
            }.getOrNull()

            if (!fromPrefs.isNullOrBlank()) {
                ndusCookie = fromPrefs
                return fromPrefs
            }

            // 2. Automatic Auto-Detection from Android CookieManager across all candidate domains:
            val fromCookieManager = scanCookieManagerForNdus()
            if (!fromCookieManager.isNullOrBlank()) {
                saveNdus(ctx, fromCookieManager)
                return fromCookieManager
            }

            return null
        }

        fun saveNdus(context: Context? = null, cookie: String?) {
            val clean = extractNdus(cookie) ?: cookie?.trim()
            ndusCookie = if (!clean.isNullOrBlank()) clean else null
            val ctx = context ?: appContext ?: CommonActivity.activity
            runCatching {
                if (ctx != null) {
                    val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    if (clean.isNullOrBlank()) {
                        prefs.edit().remove(KEY_NDUS).apply()
                    } else {
                        prefs.edit().putString(KEY_NDUS, clean).apply()
                    }
                }
                val cm = android.webkit.CookieManager.getInstance()
                if (!clean.isNullOrBlank()) {
                    for (domain in CANDIDATE_DOMAINS) {
                        val host = domain.removePrefix("https://").removePrefix("http://")
                        val rootDomain = host.substringAfter("www.")
                        cm.setCookie(domain, "ndus=$clean; domain=.$rootDomain; path=/")
                    }
                    cm.flush()
                }
            }
        }

        fun getSavedCustomWorker(context: Context? = null): String? {
            if (!customWorkerUrl.isNullOrBlank()) return customWorkerUrl
            val ctx = context ?: appContext ?: CommonActivity.activity ?: return null
            return runCatching {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val w = prefs.getString(KEY_CUSTOM_WORKER, null)
                customWorkerUrl = w
                w
            }.getOrNull()
        }

        fun saveCustomWorker(context: Context? = null, worker: String?) {
            customWorkerUrl = worker
            val ctx = context ?: appContext ?: CommonActivity.activity ?: return
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
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.trim()
        val ndus = getSavedNdus()
        if (ndus.isNullOrBlank()) return

        runCatching {
            resolveDirectTeraBox(cleanUrl, ndus, callback)
        }
    }

    private suspend fun resolveDirectTeraBox(
        rawUrl: String,
        ndus: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val surl = extractSurl(rawUrl) ?: return false
            val candidateHosts = listOf("dm.terabox.com", "dm.1024tera.com", "1024tera.com")
            
            var jsToken: String? = null
            var usedHost = "dm.terabox.com"
            var sign: String? = null
            var timestamp: String? = null
            var uk: String? = null
            var shareid: String? = null

            for (host in candidateHosts) {
                val sharePageUrl = "https://$host/sharing/link?surl=$surl"
                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Cookie" to "ndus=$ndus",
                    "Referer" to "https://$host/sharing/link?surl=$surl"
                )
                val html = runCatching {
                    app.get(sharePageUrl, headers = headers, timeout = 5).text
                }.getOrNull()

                if (!html.isNullOrBlank()) {
                    val tokenMatch = Regex("""fn\(['"]([A-F0-9]{64,128})['"]\)""", RegexOption.IGNORE_CASE).find(html)
                        ?: Regex("""%22([A-F0-9]{64,128})%22""", RegexOption.IGNORE_CASE).find(html)
                        ?: Regex("""jsToken["']?\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
                    if (tokenMatch != null) {
                        jsToken = tokenMatch.groupValues[1]
                    }

                    val stateIdx = html.indexOf("window.__INITIAL_STATE__")
                    if (stateIdx != -1) {
                        val start = html.indexOf('{', stateIdx)
                        val end = html.indexOf(";</script>", start).takeIf { it != -1 } ?: html.indexOf("</script>", start)
                        if (start != -1 && end != -1 && end > start) {
                            val jsonStr = html.substring(start, end).trim()
                            runCatching {
                                val state = parseJson<TeraBoxInitialState>(jsonStr)
                                val shareInfo = state.pinia?.share?.shareInfo
                                if (shareInfo != null) {
                                    sign = shareInfo.sign?.takeIf { it.isNotBlank() }
                                    timestamp = shareInfo.timestamp?.toString()?.takeIf { it.isNotBlank() }
                                    uk = shareInfo.uk?.toString()?.takeIf { it.isNotBlank() }
                                    shareid = shareInfo.shareid?.toString()?.takeIf { it.isNotBlank() }
                                }
                            }
                        }
                    }

                    if (sign.isNullOrBlank()) {
                        sign = Regex(""""sign"\s*:\s*"([a-f0-9]{32,64})"""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                    }
                    if (timestamp.isNullOrBlank()) {
                        timestamp = Regex(""""timestamp"\s*:\s*"?(\d{9,12})""""").find(html)?.groupValues?.get(1)
                    }
                    if (uk.isNullOrBlank()) {
                        uk = Regex(""""uk"\s*:\s*"?(\d+)""""").find(html)?.groupValues?.get(1)
                    }
                    if (shareid.isNullOrBlank()) {
                        shareid = Regex(""""shareid"\s*:\s*"?(\d+)""""").find(html)?.groupValues?.get(1)
                    }

                    if (!sign.isNullOrBlank() || !jsToken.isNullOrBlank()) {
                        usedHost = host
                        break
                    }
                }
            }

            val tokenParam = if (!jsToken.isNullOrBlank()) "&jsToken=$jsToken" else ""
            val rootUrl = "https://$usedHost/share/list?app_id=250528${tokenParam}&shorturl=$surl&root=1"
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Cookie" to "ndus=$ndus",
                "Referer" to "https://$usedHost/sharing/link?surl=$surl"
            )

            val rootRes = runCatching {
                app.get(rootUrl, headers = headers, timeout = 5).parsedSafe<TeraBoxShareListResponse>()
            }.getOrNull()

            val initialList = rootRes?.list
            if (initialList.isNullOrEmpty()) return false

            val firstVideo = initialList.firstOrNull { item ->
                val name = item.serverFilename?.lowercase() ?: ""
                name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm") ||
                name.endsWith(".mov") || name.endsWith(".avi") || name.endsWith(".m4v") ||
                name.endsWith(".3gp") || (item.size ?: 0L) > 1_000_000L
            } ?: initialList.firstOrNull() ?: return false

            val name = firstVideo.serverFilename?.removeSuffix(".mp4")?.replace("_", " ")?.trim() ?: "TeraBox Video"
            val fid = firstVideo.fsId?.toString()
            val path = firstVideo.path

            var found = false
            val shareReferer = "https://$usedHost/sharing/link?surl=$surl"
            val hlsHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to shareReferer,
                "Cookie" to "ndus=$ndus"
            )

            if (!sign.isNullOrBlank() && !timestamp.isNullOrBlank() && !uk.isNullOrBlank() && !shareid.isNullOrBlank() && !fid.isNullOrBlank() && !path.isNullOrBlank()) {
                val pathEnc = URLEncoder.encode(path, "UTF-8")
                
                // Probe available resolutions in parallel (1080p, 720p, 480p, 360p)
                // Non-VIP accounts will succeed on 480p and 360p (1080p/720p return errno: 130)
                // Premium/VIP accounts will automatically succeed on 1080p and 720p as well!
                val resConfigs = listOf(
                    Triple("1080p", "M3U8_AUTO_1080", Qualities.P1080.value),
                    Triple("720p", "M3U8_AUTO_720", Qualities.P720.value),
                    Triple("480p", "M3U8_AUTO_480", Qualities.P480.value),
                    Triple("360p", "M3U8_AUTO_360", Qualities.P360.value)
                )

                coroutineScope {
                    val jobs = resConfigs.map { (qName, tParam, qVal) ->
                        async {
                            val streamUrl = "https://$usedHost/share/streaming?app_id=250528&channel=dubox&clienttype=0&shorturl=$surl&root=1&type=$tParam&path=$pathEnc${tokenParam}&uk=$uk&shareid=$shareid&fid=$fid&sign=$sign&timestamp=$timestamp"
                            val res = runCatching {
                                app.get(streamUrl, headers = hlsHeaders, timeout = 5).text
                            }.getOrNull()

                            if (res != null && res.contains("#EXTM3U") && !res.contains("\"errno\":130")) {
                                Triple(qName, streamUrl, qVal)
                            } else {
                                null
                            }
                        }
                    }

                    jobs.mapNotNull { it.await() }.forEach { (qName, streamUrl, qVal) ->
                        callback(
                            newExtractorLink(
                                source = "TeraBox $qName",
                                name = "$name - [$qName Native HLS ⚡]",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = shareReferer
                                this.headers = hlsHeaders
                                this.quality = qVal
                            }
                        )
                        found = true
                    }
                }
            }

            // Direct MP4 fallback for VIP accounts or small files
            val dlink = firstVideo.dlink
            if (!dlink.isNullOrBlank() && dlink.startsWith("http")) {
                val headersMap = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://www.1024terabox.com/",
                    "Cookie" to "ndus=$ndus",
                    "Accept" to "*/*",
                    "Connection" to "keep-alive"
                )

                val finalStreamUrl = runCatching {
                    val step = app.get(dlink, headers = headersMap, allowRedirects = false, timeout = 5)
                    val loc = step.headers["Location"] ?: step.headers["location"]
                    if (!loc.isNullOrBlank() && loc.startsWith("http")) loc else dlink
                }.getOrDefault(dlink)

                callback(
                    newExtractorLink(
                        source = "$name Direct",
                        name = "$name - [Direct MP4 (VIP / Small Files)]",
                        url = finalStreamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://www.1024terabox.com/"
                        this.headers = headersMap
                        this.quality = Qualities.P1080.value
                    }
                )
                found = true
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

data class TeraBoxInitialState(
    @JsonProperty("pinia") val pinia: TeraBoxPiniaState? = null
)

data class TeraBoxPiniaState(
    @JsonProperty("share") val share: TeraBoxPiniaShare? = null
)

data class TeraBoxPiniaShare(
    @JsonProperty("shareInfo") val shareInfo: TeraBoxPiniaShareInfo? = null
)

data class TeraBoxPiniaShareInfo(
    @JsonProperty("uk") val uk: Any? = null,
    @JsonProperty("shareid") val shareid: Any? = null,
    @JsonProperty("sign") val sign: String? = null,
    @JsonProperty("timestamp") val timestamp: Any? = null,
    @JsonProperty("fileInfo") val fileInfo: TeraBoxFileInfo? = null
)

data class TeraBoxShareListResponse(
    @JsonProperty("errno") val errno: Int? = null,
    @JsonProperty("list") val list: List<TeraBoxFileInfo>? = null
)

data class TeraBoxFileInfo(
    @JsonProperty("server_filename") val serverFilename: String? = null,
    @JsonProperty("fs_id") val fsId: Any? = null,
    @JsonProperty("dlink") val dlink: String? = null,
    @JsonProperty("size") val size: Long? = null,
    @JsonProperty("isdir") val isdir: Any? = null,
    @JsonProperty("path") val path: String? = null
)
