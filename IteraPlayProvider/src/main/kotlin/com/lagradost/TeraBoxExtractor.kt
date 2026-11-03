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

object MasterPlaylistManager {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun set(key: String, m3u8: String) {
        if (cache.size > 100) {
            cache.clear()
        }
        cache[key] = m3u8
    }

    fun get(key: String): String? {
        return cache[key]
    }
}

data class HlsVariant(
    val name: String,
    val resolution: String,
    val bandwidth: Long,
    val url: String
)

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

        fun getMasterPlaylist(url: String): String? {
            val streamId = Regex("""virtual_master_([a-zA-Z0-9_\-]+)""").find(url)?.groupValues?.get(1)
            return MasterPlaylistManager.get(url)
                ?: (if (streamId != null) MasterPlaylistManager.get(streamId) else null)
        }

        fun saveMasterPlaylist(id: String, content: String) {
            MasterPlaylistManager.set(id, content)
            MasterPlaylistManager.set("https://iteraplay.in/virtual_master_$id.m3u8", content)
        }

        fun buildMasterM3u8(variants: List<HlsVariant>): String {
            val sorted = variants.sortedByDescending { it.bandwidth }
            val sb = StringBuilder()
            sb.append("#EXTM3U\n")
            sb.append("#EXT-X-VERSION:3\n")
            sb.append("#EXT-X-INDEPENDENT-SEGMENTS\n")
            for (v in sorted) {
                sb.append("#EXT-X-STREAM-INF:BANDWIDTH=${v.bandwidth},RESOLUTION=${v.resolution},NAME=\"${v.name}\"\n")
                sb.append("${v.url}\n")
            }
            return sb.toString()
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
        val surl = extractSurl(cleanUrl) ?: return
        val streamId = surl.replace(Regex("""[^a-zA-Z0-9_\-]"""), "")

        // 1. Prioritize iTeraPlay.in Multi-Quality Adaptive Streams (High-speed edge CDN, no login required)
        val inSuccess = runCatching {
            resolveViaIteraPlayInVariants(cleanUrl, streamId, callback)
        }.getOrDefault(false)

        if (inSuccess) return

        // 2. Fallback to Direct TeraBox unthrottled HLS (Uses ndus cookie if saved)
        if (!ndus.isNullOrBlank()) {
            runCatching {
                resolveDirectTeraBoxVariants(cleanUrl, ndus, streamId, callback)
            }.getOrDefault(false)
        }
    }

    private suspend fun resolveViaIteraPlayInVariants(
        rawUrl: String,
        streamId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val surl = extractSurl(rawUrl) ?: return false
            val canonicalKey = if (surl.startsWith("1")) surl else "1$surl"
            val fullTeraBoxUrl = "https://terabox.app/s/$canonicalKey"
            val target = "https://iteraplay.in/api/videos/import"
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Content-Type" to "application/json",
                "Origin" to "https://iteraplay.in",
                "Referer" to "https://iteraplay.in/"
            )
            val res = app.post(
                target,
                headers = headers,
                json = mapOf("url" to fullTeraBoxUrl),
                timeout = 20
            ).parsedSafe<IteraPlayInImportResponse>()

            val videos = res?.videos
            if (res?.success == true && !videos.isNullOrEmpty()) {
                val video = videos.firstOrNull() ?: return false
                val title = video.title?.removeSuffix(".mp4")?.replace("_", " ")?.trim() ?: "TeraBox Video"

                val fastStreams = video.fastStreamUrl
                val validVariants = if (fastStreams != null && fastStreams.isNotEmpty()) {
                    val orderedKeys = listOf("1080p", "720p", "480p", "360p")
                    val sortedEntries = fastStreams.entries.sortedBy { entry ->
                        val idx = orderedKeys.indexOf(entry.key.lowercase())
                        if (idx != -1) idx else 99
                    }

                    coroutineScope {
                        sortedEntries.map { (qName, rawHlsUrl) ->
                            async {
                                resolveIteraHlsVariant(qName, rawHlsUrl)
                            }
                        }.mapNotNull { it.await() }
                    }
                } else emptyList()

                // If valid HLS variants found, emit ONE single Adaptive Master Playlist link
                if (validVariants.isNotEmpty()) {
                    val masterM3u8 = buildMasterM3u8(validVariants)
                    val virtualUrl = "https://iteraplay.in/virtual_master_$streamId.m3u8"
                    saveMasterPlaylist(streamId, masterM3u8)
                    saveMasterPlaylist(virtualUrl, masterM3u8)

                    callback(
                        newExtractorLink(
                            source = "iTeraPlay Adaptive",
                            name = "$title - [Adaptive Multi-Quality ⚡]",
                            url = virtualUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://iteraplay.in/"
                            this.headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "https://iteraplay.in/",
                                "Origin" to "https://iteraplay.in"
                            )
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }

                // If no HLS streams found, fallback to emitting 1 single Direct MP4 link
                val streamUrl = video.streamUrl
                if (!streamUrl.isNullOrBlank()) {
                    callback(
                        newExtractorLink(
                            source = "iTeraPlay Direct",
                            name = "$title - [Direct MP4 ⚡]",
                            url = streamUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://iteraplay.in/"
                            this.headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "https://iteraplay.in/",
                                "Origin" to "https://iteraplay.in"
                            )
                            this.quality = Qualities.P1080.value
                        }
                    )
                    return true
                }
                false
            } else {
                false
            }
        }.getOrDefault(false)
    }

    private suspend fun resolveIteraHlsVariant(qName: String, rawHlsUrl: String): HlsVariant? {
        if (rawHlsUrl.isBlank()) return null
        return runCatching {
            val res = app.get(
                rawHlsUrl,
                headers = mapOf("Referer" to "https://iteraplay.in/"),
                timeout = 3
            ).text.trim()

            var effectiveUrl = rawHlsUrl
            if (res.contains("#EXTM3U") && !res.contains("\"errno\":130")) {
                effectiveUrl = rawHlsUrl
            } else if (res.startsWith("http")) {
                val res2 = app.get(
                    res,
                    headers = mapOf("Referer" to "https://iteraplay.in/"),
                    timeout = 3
                ).text.trim()
                if (res2.contains("#EXTM3U") && !res2.contains("\"errno\":130")) {
                    effectiveUrl = res
                } else {
                    return null
                }
            } else {
                return null
            }

            val (bw, resStr) = when (qName.lowercase()) {
                "1080p" -> 5000000L to "1920x1080"
                "720p"  -> 2800000L to "1280x720"
                "480p"  -> 1400000L to "854x480"
                "360p"  -> 800000L to "640x360"
                else    -> 1000000L to "854x480"
            }

            HlsVariant(
                name = qName,
                resolution = resStr,
                bandwidth = bw,
                url = effectiveUrl
            )
        }.getOrNull()
    }

    private suspend fun resolveDirectTeraBoxVariants(
        rawUrl: String,
        ndus: String,
        streamId: String,
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

            val directVariants = mutableListOf<HlsVariant>()
            val shareReferer = "https://$usedHost/sharing/link?surl=$surl"
            val hlsHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to shareReferer,
                "Cookie" to "ndus=$ndus"
            )

            if (!sign.isNullOrBlank() && !timestamp.isNullOrBlank() && !uk.isNullOrBlank() && !shareid.isNullOrBlank() && !fid.isNullOrBlank() && !path.isNullOrBlank()) {
                val pathEnc = URLEncoder.encode(path, "UTF-8")
                
                // Probe available resolutions in parallel
                coroutineScope {
                    val p1080 = async {
                        val url1080 = "https://$usedHost/share/streaming?app_id=250528&channel=dubox&clienttype=0&shorturl=$surl&root=1&type=M3U8_AUTO_1080&path=$pathEnc${tokenParam}&uk=$uk&shareid=$shareid&fid=$fid&sign=$sign&timestamp=$timestamp"
                        val res = runCatching { app.get(url1080, headers = hlsHeaders, timeout = 2).text }.getOrNull()
                        if (res != null && res.contains("#EXTM3U") && !res.contains("\"errno\":130")) {
                            HlsVariant("1080p", "1920x1080", 5000000L, url1080)
                        } else null
                    }
                    val p720 = async {
                        val url720 = "https://$usedHost/share/streaming?app_id=250528&channel=dubox&clienttype=0&shorturl=$surl&root=1&type=M3U8_AUTO_720&path=$pathEnc${tokenParam}&uk=$uk&shareid=$shareid&fid=$fid&sign=$sign&timestamp=$timestamp"
                        val res = runCatching { app.get(url720, headers = hlsHeaders, timeout = 2).text }.getOrNull()
                        if (res != null && res.contains("#EXTM3U") && !res.contains("\"errno\":130")) {
                            HlsVariant("720p", "1280x720", 2800000L, url720)
                        } else null
                    }
                    val p480 = async {
                        val url480 = "https://$usedHost/share/streaming?app_id=250528&channel=dubox&clienttype=0&shorturl=$surl&root=1&type=M3U8_AUTO_480&path=$pathEnc${tokenParam}&uk=$uk&shareid=$shareid&fid=$fid&sign=$sign&timestamp=$timestamp"
                        val res = runCatching { app.get(url480, headers = hlsHeaders, timeout = 2).text }.getOrNull()
                        if (res != null && res.contains("#EXTM3U") && !res.contains("\"errno\":130")) {
                            HlsVariant("480p", "854x480", 1400000L, url480)
                        } else null
                    }
                    val p360 = async {
                        val url360 = "https://$usedHost/share/streaming?app_id=250528&channel=dubox&clienttype=0&shorturl=$surl&root=1&type=M3U8_AUTO_360&path=$pathEnc${tokenParam}&uk=$uk&shareid=$shareid&fid=$fid&sign=$sign&timestamp=$timestamp"
                        val res = runCatching { app.get(url360, headers = hlsHeaders, timeout = 2).text }.getOrNull()
                        if (res != null && res.contains("#EXTM3U") && !res.contains("\"errno\":130")) {
                            HlsVariant("360p", "640x360", 800000L, url360)
                        } else null
                    }

                    listOfNotNull(p1080.await(), p720.await(), p480.await(), p360.await()).forEach {
                        directVariants.add(it)
                    }
                }
            }

            if (directVariants.isNotEmpty()) {
                val masterM3u8 = buildMasterM3u8(directVariants)
                val virtualUrl = "https://iteraplay.in/virtual_master_$streamId.m3u8"
                saveMasterPlaylist(streamId, masterM3u8)
                saveMasterPlaylist(virtualUrl, masterM3u8)

                callback(
                    newExtractorLink(
                        source = "$name Adaptive",
                        name = "$name - [Adaptive Multi-Quality ⚡ (Unthrottled)]",
                        url = virtualUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = shareReferer
                        this.headers = hlsHeaders
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            // Direct MP4 fallback if no HLS variants were found
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
                return true
            }
            false
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

data class IteraPlayInImportResponse(
    @JsonProperty("success") val success: Boolean? = null,
    @JsonProperty("videos") val videos: List<IteraPlayInVideoItem>? = null,
    @JsonProperty("message") val message: String? = null,
    @JsonProperty("error") val error: String? = null
)

data class IteraPlayInVideoItem(
    @JsonProperty("_id") val id: String? = null,
    @JsonProperty("fileId") val fileId: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("size") val size: Long? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("duration") val duration: String? = null,
    @JsonProperty("thumbnailUrl") val thumbnailUrl: String? = null,
    @JsonProperty("stream_url") val streamUrl: String? = null,
    @JsonProperty("fast_stream_url") val fastStreamUrl: Map<String, String>? = null,
    @JsonProperty("resolutions") val resolutions: List<String>? = null
)

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
