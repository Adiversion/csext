package com.lagradost

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class HlsVariant(
    val name: String,
    val resolution: String,
    val bandwidth: Long,
    val url: String
)

private data class VariantProbeConfig(
    val name: String,
    val resolution: String,
    val bandwidth: Long,
    val typeParam: String,
    val qualityVal: Int
)

object LocalM3u8Server {
    private var serverSocket: ServerSocket? = null
    private val playlists = ConcurrentHashMap<String, String>()
    private val isRunning = AtomicBoolean(false)

    val port: Int
        get() = serverSocket?.localPort ?: 0

    @Synchronized
    fun start(): Int {
        if (serverSocket != null && !serverSocket!!.isClosed) {
            return serverSocket!!.localPort
        }
        return try {
            val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = s
            isRunning.set(true)
            thread(isDaemon = true, name = "LocalM3u8Server") {
                while (isRunning.get() && !s.isClosed) {
                    try {
                        val client = s.accept()
                        thread(isDaemon = true) {
                            handleClient(client)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            s.localPort
        } catch (e: Exception) {
            0
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 4000
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val firstLine = reader.readLine() ?: return
                // Expected format: GET /master_<id>.m3u8 HTTP/1.1
                val rawPath = firstLine.split(" ").getOrNull(1) ?: return
                val cleanPath = rawPath.substringBefore("?")
                val id = cleanPath.substringAfterLast("/").removeSuffix(".m3u8")
                val content = playlists[cleanPath] ?: playlists[id] ?: playlists["master_$id"] ?: playlists["/master_$id.m3u8"] ?: playlists["$id.m3u8"]

                val out = s.getOutputStream()
                if (content != null) {
                    val bytes = content.toByteArray(Charsets.UTF_8)
                    val response = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/vnd.apple.mpegurl\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n" +
                            "Access-Control-Allow-Origin: *\r\n\r\n"
                    out.write(response.toByteArray(Charsets.UTF_8))
                    out.write(bytes)
                    out.flush()
                } else {
                    val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    out.write(notFound.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun serve(id: String, m3u8Content: String): String {
        val p = start()
        playlists[id] = m3u8Content
        playlists["master_$id"] = m3u8Content
        playlists["/master_$id.m3u8"] = m3u8Content
        return "http://127.0.0.1:$p/master_$id.m3u8"
    }

    fun set(key: String, m3u8Content: String): String {
        val p = start()
        val cleanKey = key.removePrefix("/").removeSuffix(".m3u8")
        playlists[cleanKey] = m3u8Content
        playlists["/$cleanKey.m3u8"] = m3u8Content
        playlists["$cleanKey.m3u8"] = m3u8Content
        return "http://127.0.0.1:$p/$cleanKey.m3u8"
    }

    fun get(key: String): String? {
        val clean = key.substringBefore("?").substringAfterLast("/").removeSuffix(".m3u8")
        return playlists[key] ?: playlists[clean] ?: playlists["master_$clean"]
    }
}

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

        suspend fun fetchShareContents(shareUrl: String): TeraBoxFolderResult? {
            return runCatching {
                val surl = extractSurl(shareUrl) ?: return@runCatching null
                val ndus = getSavedNdus()
                val candidateHosts = listOf("dm.terabox.com", "www.1024terabox.com", "1024tera.com", "terabox.app")

                for (host in candidateHosts) {
                    val sharePageUrl = "https://$host/sharing/link?surl=$surl"
                    val headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Cookie" to "ndus=$ndus",
                        "Referer" to sharePageUrl
                    )
                    val html = runCatching { app.get(sharePageUrl, headers = headers, timeout = 5).text }.getOrNull()
                    if (html.isNullOrBlank()) continue

                    val tokenMatch = Regex("""fn\(['"]([A-F0-9]{64,128})['"]\)""", RegexOption.IGNORE_CASE).find(html)
                        ?: Regex("""%22([A-F0-9]{64,128})%22""", RegexOption.IGNORE_CASE).find(html)
                        ?: Regex("""jsToken["']?\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
                    val jsToken = tokenMatch?.groupValues?.get(1)

                    var sign: String? = null
                    var timestamp: String? = null
                    var uk: String? = null
                    var shareid: String? = null
                    var initialFile: TeraBoxFileInfo? = null

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
                                    initialFile = shareInfo.fileInfo
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

                    if (sign.isNullOrBlank() && jsToken.isNullOrBlank()) continue

                    val tokenParam = if (!jsToken.isNullOrBlank()) "&jsToken=$jsToken" else ""
                    val rootUrl = "https://$host/share/list?app_id=250528${tokenParam}&shorturl=$surl&root=1"
                    val rootRes = runCatching {
                        app.get(rootUrl, headers = headers, timeout = 5).parsedSafe<TeraBoxShareListResponse>()
                    }.getOrNull()

                    fun isVideo(item: TeraBoxFileInfo): Boolean {
                        val name = item.serverFilename?.lowercase() ?: item.path?.lowercase() ?: ""
                        return item.category?.toString() == "1" ||
                                name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm") ||
                                name.endsWith(".mov") || name.endsWith(".avi") || name.endsWith(".m4v") ||
                                name.endsWith(".3gp") || (item.size ?: 0L) > 1_000_000L
                    }

                    val videos = mutableListOf<TeraBoxFileInfo>()
                    val rawList = rootRes?.list ?: listOfNotNull(initialFile)
                    for (item in rawList) {
                        val isDir = item.isdir?.toString() == "1"
                        if (isDir && !item.path.isNullOrBlank()) {
                            val subUrl = "https://$host/share/list?app_id=250528${tokenParam}&shorturl=$surl&dir=${URLEncoder.encode(item.path, "UTF-8")}&by=name&order=asc&num=20000"
                            val subRes = runCatching {
                                app.get(subUrl, headers = headers, timeout = 5).parsedSafe<TeraBoxShareListResponse>()
                            }.getOrNull()
                            subRes?.list?.filter { isVideo(it) }?.let { videos.addAll(it) }
                        } else if (isVideo(item)) {
                            videos.add(item)
                        }
                    }

                    if (videos.isEmpty() && initialFile != null && isVideo(initialFile)) {
                        videos.add(initialFile)
                    }

                    return@runCatching TeraBoxFolderResult(
                        title = rootRes?.title ?: initialFile?.serverFilename,
                        videos = videos,
                        uk = uk,
                        shareid = shareid,
                        sign = sign,
                        timestamp = timestamp,
                        jsToken = jsToken,
                        host = host,
                        surl = surl
                    )
                }
                null
            }.getOrNull()
        }

        private fun extractResponseCookies(res: NiceResponse, existing: Map<String, String> = emptyMap()): Map<String, String> {
            val map = existing.toMutableMap()
            runCatching { map.putAll(res.cookies) }
            val rawList = runCatching {
                res.headers.values("Set-Cookie").ifEmpty { res.headers.values("set-cookie") }
            }.getOrNull() ?: emptyList()
            for (line in rawList) {
                val pair = line.substringBefore(";").trim()
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val k = pair.substring(0, eq).trim()
                    val v = pair.substring(eq + 1).trim()
                    if (k.isNotBlank() && v.isNotBlank()) {
                        map[k] = v
                    }
                }
            }
            return map
        }

        suspend fun resolveFlowVideoPlayer(shareUrl: String): FlowResolvedStream? {
            return runCatching {
                val baseUrl = "https://flowvideoplayer.com"
                val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
                val secChUa = """"Chromium";v="148", "Google Chrome";v="148", "Not/A)Brand";v="99""""

                val browserHeaders = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                    "Accept-Language" to "en-IN,en-GB;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Sec-Ch-Ua" to secChUa,
                    "Sec-Ch-Ua-Mobile" to "?0",
                    "Sec-Ch-Ua-Platform" to """"Windows"""",
                    "Sec-Fetch-Dest" to "document",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "none",
                    "Sec-Fetch-User" to "?1",
                    "Upgrade-Insecure-Requests" to "1"
                )

                // 1. GET /
                val homeRes = app.get(baseUrl, headers = browserHeaders, timeout = 10)
                val homeHtml = homeRes.text
                val csrfToken = Regex("""name=["']csrf-token["']\s+content=["']([^"']+)["']""").find(homeHtml)?.groupValues?.get(1)
                    ?: return@runCatching null

                var cookies = extractResponseCookies(homeRes)
                var cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

                // 2. POST /device/init
                val devicePayload = mapOf(
                    "cpu" to 4,
                    "memory" to 8,
                    "touch" to 0,
                    "platform" to "Win32",
                    "lang" to "en-IN",
                    "vendor" to "Google Inc.",
                    "webgl_vendor" to "Google Inc. (NVIDIA)",
                    "webgl_renderer" to "ANGLE (NVIDIA, NVIDIA GeForce GT 710 (0x0000128B) Direct3D11 vs_5_0 ps_5_0, D3D11)",
                    "ua" to userAgent,
                    "backup_token" to null,
                    "os" to "windows",
                    "browser" to "chrome",
                    "pwa_installed" to false
                )

                val initHeaders = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                    "X-Requested-With" to "XMLHttpRequest",
                    "X-CSRF-TOKEN" to csrfToken,
                    "Origin" to baseUrl,
                    "Referer" to "$baseUrl/",
                    "Sec-Ch-Ua" to secChUa,
                    "Sec-Ch-Ua-Mobile" to "?0",
                    "Sec-Ch-Ua-Platform" to """"Windows"""",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin",
                    "Cookie" to cookieHeader
                )

                val initRes = app.post(
                    "$baseUrl/device/init",
                    headers = initHeaders,
                    json = devicePayload,
                    timeout = 10
                )

                cookies = extractResponseCookies(initRes, cookies)
                cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

                // 3. POST /search/video
                val searchHeaders = initHeaders.toMutableMap()
                searchHeaders["Cookie"] = cookieHeader

                val searchRes = app.post(
                    "$baseUrl/search/video",
                    headers = searchHeaders,
                    json = mapOf("url" to shareUrl),
                    timeout = 15
                )

                val searchJson = runCatching { parseJson<FlowSearchResponse>(searchRes.text) }.getOrNull()
                val firstVid = searchJson?.response?.firstOrNull() ?: return@runCatching null

                // 4. Resolve 302 location from download_url
                var direct1080Url: String? = null
                val dlApiUrl = firstVid.downloadUrl
                if (!dlApiUrl.isNullOrBlank()) {
                    val dirStep = app.get(
                        dlApiUrl,
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Cookie" to cookieHeader,
                            "Referer" to "$baseUrl/"
                        ),
                        allowRedirects = false,
                        timeout = 10
                    )
                    val loc = dirStep.headers["Location"] ?: dirStep.headers["location"]
                    if (!loc.isNullOrBlank() && loc.startsWith("http")) {
                        direct1080Url = loc
                    }
                }

                FlowResolvedStream(
                    fileName = firstVid.fileName,
                    direct1080Url = direct1080Url,
                    fast720Url = firstVid.fastStreamUrl,
                    fileSize = firstVid.fileSize
                )
            }.getOrNull()
        }

        suspend fun resolveDirectTeraBox(
            rawUrl: String,
            callback: (ExtractorLink) -> Unit,
            targetFsId: String? = null,
            targetPath: String? = null
        ): Boolean {
            return runCatching {
                val surl = extractSurl(rawUrl) ?: return false
                val streamId = (surl + "_" + (targetFsId ?: "0")).replace(Regex("[^a-zA-Z0-9_-]"), "")

                coroutineScope {
                    val flowDeferred = async { resolveFlowVideoPlayer(rawUrl) }
                    val nativeDeferred = async { runCatching { fetchShareContents(rawUrl) }.getOrNull() }

                    val flowStream = flowDeferred.await()
                    val folderResult = nativeDeferred.await()

                    val variants = mutableListOf<HlsVariant>()

                    // 1. Fast Unthrottled HLS Stream from Flow Video Player (Direct Cloudflare Worker HLS with .ts chunks)
                    if (!flowStream?.fast720Url.isNullOrBlank()) {
                        // Priority #1: 5 Mbps ensures ExoPlayer chooses this unthrottled HD stream first
                        variants.add(HlsVariant("Fast Stream ⚡ (720p HD)", "1280x720", 5000000L, flowStream.fast720Url))
                    }

                    // 2. Native Multi-Resolution HLS Streams from TeraBox
                    if (folderResult != null) {
                        val usedHost = folderResult.host
                        val jsToken = folderResult.jsToken
                        val sign = folderResult.sign
                        val timestamp = folderResult.timestamp
                        val uk = folderResult.uk
                        val shareid = folderResult.shareid
                        val ndus = getSavedNdus()
                        val tokenParam = if (!jsToken.isNullOrBlank()) "&jsToken=$jsToken" else ""

                        val targetVideo = if (!targetFsId.isNullOrBlank()) {
                            folderResult.videos.firstOrNull { it.fsId?.toString() == targetFsId }
                                ?: folderResult.videos.firstOrNull { it.path == targetPath }
                                ?: folderResult.videos.firstOrNull()
                        } else {
                            folderResult.videos.firstOrNull()
                        }

                        val fid = targetFsId ?: targetVideo?.fsId?.toString()
                        val path = targetPath ?: targetVideo?.path

                        if (!sign.isNullOrBlank() && !timestamp.isNullOrBlank() && !uk.isNullOrBlank() && !shareid.isNullOrBlank() && !fid.isNullOrBlank() && !path.isNullOrBlank()) {
                            val pathEnc = URLEncoder.encode(path, "UTF-8")
                            val hlsHeaders = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "https://$usedHost/sharing/link?surl=$surl",
                                "Cookie" to "ndus=$ndus"
                            )

                            // Only probe VIP 1080p/720p if user has configured an official ndus account in Settings.
                            // For free/guest users, TeraBox blocks 720p/1080p segments, which causes ExoPlayer to fail and fall back ("throw back") to 480p!
                            val nativeConfigs = mutableListOf<VariantProbeConfig>()
                            if (!ndus.isNullOrBlank()) {
                                nativeConfigs.add(VariantProbeConfig("1080p (VIP)", "1920x1080", 6000000L, "M3U8_AUTO_1080", Qualities.P1080.value))
                                nativeConfigs.add(VariantProbeConfig("720p (VIP)", "1280x720", 3000000L, "M3U8_AUTO_720", Qualities.P720.value))
                            }
                            nativeConfigs.add(VariantProbeConfig("480p (Native)", "854x480", 1400000L, "M3U8_AUTO_480", Qualities.P480.value))
                            nativeConfigs.add(VariantProbeConfig("360p (Native)", "640x360", 800000L, "M3U8_AUTO_360", Qualities.P360.value))

                            val nativeJobs = nativeConfigs.map { cfg ->
                                async {
                                    val streamUrl = "https://$usedHost/share/streaming?app_id=250528&channel=dubox&clienttype=0&shorturl=$surl&root=1&type=${cfg.typeParam}&path=$pathEnc${tokenParam}&uk=$uk&shareid=$shareid&fid=$fid&sign=$sign&timestamp=$timestamp"
                                    val res = runCatching {
                                        app.get(streamUrl, headers = hlsHeaders, timeout = 5).text
                                    }.getOrNull()

                                    if (res != null && res.contains("#EXTM3U") && !res.contains("\"errno\":130")) {
                                        HlsVariant(cfg.name, cfg.resolution, cfg.bandwidth, streamUrl)
                                    } else {
                                        null
                                    }
                                }
                            }
                            val validNative = nativeJobs.mapNotNull { it.await() }
                            variants.addAll(validNative)
                        }
                    }

                    if (variants.isEmpty()) {
                        return@coroutineScope false
                    }

                    // 1. Standalone Direct Fast HD Link (Plays exclusively in 720p HD with no risk of falling back to 480p)
                    if (!flowStream?.fast720Url.isNullOrBlank()) {
                        callback(
                            newExtractorLink(
                                source = "TeraBox Fast HD",
                                name = "TeraBox - Fast HD ⚡ [720p] (Direct Stream)",
                                url = flowStream.fast720Url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://flowvideoplayer.com/"
                                this.quality = Qualities.P720.value
                            }
                        )
                    }

                    // 2. Multi-Resolution Adaptive Master Link
                    val sortedVariants = variants.sortedByDescending { it.bandwidth }
                    val masterContent = buildString {
                        append("#EXTM3U\n")
                        append("#EXT-X-VERSION:3\n")
                        append("#EXT-X-INDEPENDENT-SEGMENTS\n")
                        for (v in sortedVariants) {
                            append("#EXT-X-STREAM-INF:BANDWIDTH=${v.bandwidth},RESOLUTION=${v.resolution},NAME=\"${v.name}\"\n")
                            append("${v.url}\n")
                        }
                    }

                    val masterUrl = LocalM3u8Server.serve(streamId, masterContent)

                    callback(
                        newExtractorLink(
                            source = "TeraBox Adaptive",
                            name = "TeraBox - [Adaptive Fast HD ⚡ / 480p / 360p]",
                            url = masterUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://flowvideoplayer.com/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    true
                }
            }.getOrDefault(false)
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.trim()
        runCatching {
            resolveDirectTeraBox(cleanUrl, callback)
        }
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
    @JsonProperty("list") val list: List<TeraBoxFileInfo>? = null,
    @JsonProperty("title") val title: String? = null
)

data class TeraBoxFileInfo(
    @JsonProperty("server_filename") val serverFilename: String? = null,
    @JsonProperty("fs_id") val fsId: Any? = null,
    @JsonProperty("dlink") val dlink: String? = null,
    @JsonProperty("size") val size: Long? = null,
    @JsonProperty("isdir") val isdir: Any? = null,
    @JsonProperty("category") val category: Any? = null,
    @JsonProperty("path") val path: String? = null
)

data class TeraBoxFolderResult(
    val title: String?,
    val videos: List<TeraBoxFileInfo>,
    val uk: String?,
    val shareid: String?,
    val sign: String?,
    val timestamp: String?,
    val jsToken: String?,
    val host: String,
    val surl: String
)

data class FlowSearchResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("status") val status: Boolean? = null,
    @JsonProperty("message") val message: String? = null,
    @JsonProperty("response") val response: List<FlowVideoItem>? = null
)

data class FlowVideoItem(
    @JsonProperty("file_name") val fileName: String? = null,
    @JsonProperty("download_url") val downloadUrl: String? = null,
    @JsonProperty("fast_stream_url") val fastStreamUrl: String? = null,
    @JsonProperty("file_size") val fileSize: String? = null,
    @JsonProperty("file_size_bytes") val fileSizeBytes: Long? = null
)

data class FlowResolvedStream(
    val fileName: String?,
    val direct1080Url: String?,
    val fast720Url: String?,
    val fileSize: String?
)
