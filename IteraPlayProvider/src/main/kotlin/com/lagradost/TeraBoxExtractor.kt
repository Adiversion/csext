package com.lagradost

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
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

data class ProxyConfig(
    val targetUrl: String,
    val referer: String? = null,
    val customHeaders: Map<String, String> = emptyMap()
)

object LocalM3u8Server {
    private var serverSocket: ServerSocket? = null
    private val playlists = ConcurrentHashMap<String, String>()
    private val proxies = ConcurrentHashMap<String, ProxyConfig>()
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
                s.soTimeout = 10000
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val firstLine = reader.readLine() ?: return
                val parts = firstLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val rawPath = parts.getOrNull(1) ?: return
                val cleanPath = rawPath.substringBefore("?")
                val id = cleanPath.substringAfterLast("/").substringBeforeLast(".")

                val clientHeaders = mutableMapOf<String, String>()
                var headerLine = reader.readLine()
                while (!headerLine.isNullOrBlank()) {
                    val colonIdx = headerLine.indexOf(':')
                    if (colonIdx > 0) {
                        val k = headerLine.substring(0, colonIdx).trim().lowercase()
                        val v = headerLine.substring(colonIdx + 1).trim()
                        clientHeaders[k] = v
                    }
                    headerLine = reader.readLine()
                }

                val out = s.getOutputStream()

                // 1. In-memory M3U8 string
                val m3u8Content = playlists[cleanPath] ?: playlists[id] ?: playlists["master_$id"] ?: playlists["/master_$id.m3u8"] ?: playlists["$id.m3u8"]
                if (m3u8Content != null) {
                    val bytes = m3u8Content.toByteArray(Charsets.UTF_8)
                    val response = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/vnd.apple.mpegurl\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n" +
                            "Access-Control-Allow-Origin: *\r\n\r\n"
                    out.write(response.toByteArray(Charsets.UTF_8))
                    if (!method.equals("HEAD", ignoreCase = true)) {
                        out.write(bytes)
                    }
                    out.flush()
                    return
                }

                // 2. Proxied range stream (MKV / MP4 / etc.)
                val proxyConfig = proxies[cleanPath] ?: proxies[id] ?: proxies[cleanPath.removePrefix("/")] ?: proxies["stream_$id"] ?: proxies["/stream_$id.mkv"]
                if (proxyConfig != null) {
                    handleProxyStream(s, method, cleanPath, clientHeaders, proxyConfig, out)
                    return
                }

                val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                out.write(notFound.toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleProxyStream(
        socket: Socket,
        method: String,
        cleanPath: String,
        clientHeaders: Map<String, String>,
        config: ProxyConfig,
        out: OutputStream
    ) {
        var conn: HttpURLConnection? = null
        try {
            conn = openConnectionWithRedirects(config.targetUrl, method, clientHeaders, config)
            val responseCode = conn.responseCode
            val responseMessage = conn.responseMessage ?: (if (responseCode == 206) "Partial Content" else "OK")
            val contentType = conn.contentType ?: (if (cleanPath.endsWith(".mp4")) "video/mp4" else "video/x-matroska")
            val contentRange = conn.getHeaderField("Content-Range")
            val contentLength = conn.getHeaderField("Content-Length")

            val headerBuilder = StringBuilder()
            headerBuilder.append("HTTP/1.1 $responseCode $responseMessage\r\n")
            headerBuilder.append("Content-Type: $contentType\r\n")
            headerBuilder.append("Accept-Ranges: bytes\r\n")
            headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
            headerBuilder.append("Access-Control-Allow-Headers: Range, Accept, Origin, Content-Type\r\n")
            headerBuilder.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
            headerBuilder.append("Connection: close\r\n")
            if (!contentRange.isNullOrBlank()) {
                headerBuilder.append("Content-Range: $contentRange\r\n")
            }
            if (!contentLength.isNullOrBlank()) {
                headerBuilder.append("Content-Length: $contentLength\r\n")
            }
            headerBuilder.append("\r\n")

            out.write(headerBuilder.toString().toByteArray(Charsets.UTF_8))
            out.flush()

            if (method.equals("HEAD", ignoreCase = true)) {
                return
            }

            // High-speed parallel chunk pipeline for large streaming ranges (> 512 KB)
            var handledByParallel = false
            if (responseCode == 206 && !contentRange.isNullOrBlank()) {
                val rangeMatch = Regex("""bytes\s+(\d+)-(\d+)/(?:\d+|\*)""", RegexOption.IGNORE_CASE).find(contentRange)
                if (rangeMatch != null) {
                    val startByte = rangeMatch.groupValues[1].toLongOrNull() ?: 0L
                    val endByte = rangeMatch.groupValues[2].toLongOrNull() ?: 0L
                    val bytesToDeliver = endByte - startByte + 1L

                    if (bytesToDeliver > 512 * 1024L) {
                        conn.disconnect()
                        conn = null
                        handleParallelRangeStream(socket, startByte, endByte, clientHeaders, config, out)
                        handledByParallel = true
                    }
                }
            }

            if (!handledByParallel) {
                val inStream = if (responseCode in 200..299) conn?.inputStream else conn?.errorStream
                if (inStream != null) {
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    try {
                        socket.soTimeout = 0
                        while (inStream.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                        }
                        out.flush()
                    } catch (_: SocketException) {
                    } catch (_: IOException) {
                    } finally {
                        runCatching { inStream.close() }
                    }
                }
            }
        } catch (_: Exception) {
            val errorResponse = "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            runCatching { out.write(errorResponse.toByteArray(Charsets.UTF_8)); out.flush() }
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun fetchChunk(
        config: ProxyConfig,
        clientHeaders: Map<String, String>,
        chunkStart: Long,
        chunkEnd: Long
    ): ByteArray? {
        for (attempt in 1..2) {
            var chunkConn: HttpURLConnection? = null
            try {
                val headers = clientHeaders.toMutableMap()
                headers["range"] = "bytes=$chunkStart-$chunkEnd"
                chunkConn = openConnectionWithRedirects(config.targetUrl, "GET", headers, config)
                val code = chunkConn.responseCode
                val stream = if (code in 200..299) chunkConn.inputStream else null
                if (stream != null) {
                    val expectedBytes = (chunkEnd - chunkStart + 1L).toInt()
                    val byteBuf = ByteArray(expectedBytes)
                    var totalRead = 0
                    while (totalRead < expectedBytes) {
                        val r = stream.read(byteBuf, totalRead, expectedBytes - totalRead)
                        if (r == -1) break
                        totalRead += r
                    }
                    stream.close()
                    if (totalRead == expectedBytes) {
                        return byteBuf
                    } else if (totalRead > 0) {
                        return byteBuf.copyOf(totalRead)
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { chunkConn?.disconnect() }
            }
        }
        return null
    }

    private fun handleParallelRangeStream(
        socket: Socket,
        startByte: Long,
        endByte: Long,
        clientHeaders: Map<String, String>,
        config: ProxyConfig,
        out: OutputStream
    ) {
        val chunkSize = 384 * 1024L // 384 KB chunks
        val concurrency = 5 // 5 parallel connections for unthrottled aggregate throughput
        val totalBytes = endByte - startByte + 1L
        val totalChunks = ((totalBytes + chunkSize - 1L) / chunkSize).toInt()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(concurrency) { r ->
            Thread(r).apply { isDaemon = true }
        }
        val futures = ConcurrentHashMap<Int, java.util.concurrent.Future<ByteArray?>>()

        fun scheduleChunk(idx: Int): java.util.concurrent.Future<ByteArray?> {
            val cStart = startByte + (idx.toLong() * chunkSize)
            val cEnd = minOf(endByte, cStart + chunkSize - 1L)
            return pool.submit<ByteArray?> {
                fetchChunk(config, clientHeaders, cStart, cEnd)
            }
        }

        try {
            socket.soTimeout = 0
            val initialSeed = minOf(concurrency, totalChunks)
            for (i in 0 until initialSeed) {
                futures[i] = scheduleChunk(i)
            }

            for (i in 0 until totalChunks) {
                val nextIdx = i + concurrency
                if (nextIdx < totalChunks) {
                    futures[nextIdx] = scheduleChunk(nextIdx)
                }

                val future = futures.remove(i)
                val chunkData = runCatching { future?.get(25, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
                if (chunkData != null && chunkData.isNotEmpty()) {
                    out.write(chunkData)
                    out.flush()
                } else {
                    val cStart = startByte + (i.toLong() * chunkSize)
                    val cEnd = minOf(endByte, cStart + chunkSize - 1L)
                    val fallback = fetchChunk(config, clientHeaders, cStart, cEnd)
                    if (fallback != null && fallback.isNotEmpty()) {
                        out.write(fallback)
                        out.flush()
                    }
                }
            }
        } catch (_: SocketException) {
            // Client closed connection / seeked
        } catch (_: IOException) {
        } finally {
            pool.shutdownNow()
        }
    }

    private fun openConnectionWithRedirects(
        targetUrl: String,
        method: String,
        clientHeaders: Map<String, String>,
        config: ProxyConfig,
        maxRedirects: Int = 5
    ): HttpURLConnection {
        var curUrl = targetUrl
        var redirectCount = 0
        while (redirectCount < maxRedirects) {
            val conn = (URL(curUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = if (method.equals("HEAD", ignoreCase = true)) "HEAD" else "GET"
                connectTimeout = 10000
                readTimeout = 25000
                instanceFollowRedirects = false

                setRequestProperty("User-Agent", config.customHeaders["User-Agent"] ?: TeraBoxExtractor.USER_AGENT)
                if (!config.referer.isNullOrBlank()) {
                    setRequestProperty("Referer", config.referer)
                }
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity;q=1, *;q=0")
                setRequestProperty("Sec-Fetch-Site", "cross-site")
                setRequestProperty("Sec-Fetch-Mode", "no-cors")
                setRequestProperty("Sec-Fetch-Dest", "video")

                config.customHeaders.forEach { (k, v) ->
                    if (!k.equals("User-Agent", ignoreCase = true) && !k.equals("Referer", ignoreCase = true)) {
                        setRequestProperty(k, v)
                    }
                }

                clientHeaders["range"]?.let {
                    setRequestProperty("Range", it)
                }
            }

            val code = conn.responseCode
            if (code in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (!location.isNullOrBlank()) {
                    curUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                        location
                    } else {
                        URL(URL(curUrl), location).toString()
                    }
                    redirectCount++
                    continue
                }
            }
            return conn
        }
        throw IOException("Too many redirects: $redirectCount")
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

    fun proxy(id: String, targetUrl: String, referer: String? = null, headers: Map<String, String> = emptyMap()): String {
        val p = start()
        val cleanId = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val config = ProxyConfig(targetUrl, referer, headers)
        proxies[cleanId] = config
        proxies["stream_$cleanId"] = config
        proxies["/stream_$cleanId.mkv"] = config
        proxies["/stream_$cleanId.mp4"] = config
        proxies["stream_$cleanId.mkv"] = config
        return "http://127.0.0.1:$p/stream_$cleanId.mkv"
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

        // In-memory caches to speed up repeated queries and folder parsing (10-min TTL)
        private val shareContentsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, TeraBoxFolderResult>>()
        private val gidCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
        private val flowCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, FlowResolvedStream>>()
        private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes

        fun extractSurl(rawUrl: String): String? {
            val clean = runCatching { URLDecoder.decode(rawUrl.trim(), "UTF-8") }.getOrDefault(rawUrl.trim())
            var surl = Regex("""(?:/s/|surl=)([a-zA-Z0-9_\-]+)""").find(clean)?.groupValues?.get(1)
                ?: if (clean.length in 20..30 && !clean.contains("/") && !clean.contains(".")) clean else null
                ?: return null

            // Defensive: if a delimiter like '_' or filename prefix leaked into the surl (e.g. 1ZGGaFIqFNCnCkiaP1Lp6Ow_C)
            if (surl.contains("_")) {
                val before = surl.substringBefore("_")
                if (before.length in 21..24) {
                    surl = before
                }
            }

            if (surl.startsWith("1") && surl.length == 23) {
                surl = surl.substring(1)
            }
            return surl
        }

        suspend fun fetchShareContents(shareUrl: String): TeraBoxFolderResult? {
            return runCatching {
                val surl = extractSurl(shareUrl) ?: return@runCatching null
                val cached = shareContentsCache[surl]
                val now = System.currentTimeMillis()
                if (cached != null && (now - cached.first) < CACHE_TTL_MS) {
                    return@runCatching cached.second
                }
                val ndus = getSavedNdus()
                val candidateHosts = listOf("www.1024terabox.com", "1024tera.com", "terabox.app")

                for (host in candidateHosts) {
                    val sharePageUrl = "https://$host/sharing/link?surl=$surl"
                    val headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Cookie" to "ndus=$ndus",
                        "Referer" to sharePageUrl
                    )
                    val html = runCatching { app.get(sharePageUrl, headers = headers, timeout = 4).text }.getOrNull()
                    if (html.isNullOrBlank()) continue

                    val tokenMatch = Regex("""fn\(['"]([A-F0-9]{32,512})['"]\)""", RegexOption.IGNORE_CASE).find(html)
                        ?: Regex("""%22([A-F0-9]{32,512})%22""", RegexOption.IGNORE_CASE).find(html)
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

                    val resolvedShareId = shareid ?: rootRes?.shareId?.toString() ?: rootRes?.shareid?.toString()
                    val resolvedUk = uk ?: rootRes?.uk?.toString()
                    val resolvedTime = timestamp ?: rootRes?.serverTime?.toString() ?: rootRes?.timestamp?.toString()
                    val resolvedSign = sign ?: rootRes?.sign

                    val folderRes = TeraBoxFolderResult(
                        title = rootRes?.title ?: initialFile?.serverFilename,
                        videos = videos,
                        uk = resolvedUk,
                        shareid = resolvedShareId,
                        sign = resolvedSign,
                        timestamp = resolvedTime,
                        jsToken = jsToken,
                        host = host,
                        surl = surl
                    )
                    shareContentsCache[surl] = System.currentTimeMillis() to folderRes
                    return@runCatching folderRes
                }
                null
            }.getOrNull()
        }

        private fun extractResponseCookies(res: NiceResponse, existing: Map<String, String> = emptyMap()): Map<String, String> {
            val map = existing.toMutableMap()
            runCatching { map.putAll(res.cookies) }
            val rawList = runCatching {
                res.okhttpResponse.headers("Set-Cookie")
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

        suspend fun fetchFlowVideos(shareUrl: String): List<FlowVideoItem> {
            return runCatching {
                val baseUrl = "https://flowvideoplayer.com"
                val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
                val secChUa = """"Chromium";v="148", "Google Chrome";v="148", "Not/A)Brand";v="99""""

                val browserHeaders = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to "$baseUrl/"
                )

                val homeRes = app.get(baseUrl, headers = browserHeaders, timeout = 4)
                val csrfToken = Regex("""name=["']csrf-token["']\s+content=["']([^"']+)["']""").find(homeRes.text)?.groupValues?.get(1)
                    ?: return@runCatching emptyList()

                var cookies = extractResponseCookies(homeRes)
                var cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

                val initHeaders = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                    "X-Requested-With" to "XMLHttpRequest",
                    "X-CSRF-TOKEN" to csrfToken,
                    "Origin" to baseUrl,
                    "Referer" to "$baseUrl/",
                    "Sec-Ch-Ua" to secChUa,
                    "Cookie" to cookieHeader
                )

                val initRes = app.post(
                    "$baseUrl/device/init",
                    headers = initHeaders,
                    json = mapOf(
                        "cpu" to 4,
                        "memory" to 8,
                        "touch" to 0,
                        "platform" to "Win32",
                        "lang" to "en-IN",
                        "vendor" to "Google Inc.",
                        "ua" to userAgent,
                        "backup_token" to null,
                        "os" to "windows",
                        "browser" to "chrome",
                        "pwa_installed" to false
                    ),
                    timeout = 4
                )

                cookies = extractResponseCookies(initRes, cookies)
                cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

                val searchHeaders = initHeaders.toMutableMap()
                searchHeaders["Cookie"] = cookieHeader

                val searchRes = app.post(
                    "$baseUrl/search/video",
                    headers = searchHeaders,
                    json = mapOf("url" to shareUrl),
                    timeout = 4
                )

                val searchJson = runCatching { parseJson<FlowSearchResponse>(searchRes.text) }.getOrNull()
                if (searchJson?.status == false) return@runCatching emptyList()
                searchJson?.response ?: emptyList()
            }.getOrDefault(emptyList())
        }

        suspend fun resolveFlowVideoPlayer(
            shareUrl: String,
            targetFsId: String? = null,
            targetPath: String? = null,
            targetTitle: String? = null,
            targetIndex: Int? = null
        ): FlowResolvedStream? {
            return runCatching {
                val cacheKey = "${shareUrl}_${targetFsId}_${targetIndex}"
                val cached = flowCache[cacheKey]
                val now = System.currentTimeMillis()
                if (cached != null && (now - cached.first) < CACHE_TTL_MS) {
                    return@runCatching cached.second
                }

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
                val homeRes = app.get(baseUrl, headers = browserHeaders, timeout = 4)
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
                    timeout = 4
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
                    timeout = 4
                )

                val searchJson = runCatching { parseJson<FlowSearchResponse>(searchRes.text) }.getOrNull()
                if (searchJson?.status == false) return@runCatching null
                val vidList = searchJson?.response ?: return@runCatching null
                if (vidList.isEmpty()) return@runCatching null

                // Pick the exact requested video from the folder list:
                val targetVid = when {
                    // Match by file name / path
                    !targetPath.isNullOrBlank() -> {
                        val targetName = targetPath.substringAfterLast('/').lowercase().trim()
                        vidList.firstOrNull {
                            val fn = it.fileName?.lowercase()?.trim() ?: ""
                            fn == targetName ||
                            fn.contains(targetName.removeSuffix(".mp4").removeSuffix(".mkv")) ||
                            targetName.contains(fn.removeSuffix(".mp4").removeSuffix(".mkv"))
                        }
                    }
                    // Match by clean title
                    !targetTitle.isNullOrBlank() -> {
                        val cleanTitle = targetTitle.lowercase().replace("_", " ").trim()
                        vidList.firstOrNull {
                            val fn = it.fileName?.lowercase()?.replace("_", " ")?.trim() ?: ""
                            fn.contains(cleanTitle) || cleanTitle.contains(fn.removeSuffix(".mp4").removeSuffix(".mkv"))
                        }
                    }
                    else -> null
                } ?: run {
                    // Match by episode index (0-based)
                    if (targetIndex != null && targetIndex in 0 until vidList.size) {
                        vidList[targetIndex]
                    } else {
                        // Default to first video
                        vidList.first()
                    }
                }

                // 4. Resolve 302 location from download_url of target video
                var direct1080Url: String? = null
                val dlApiUrl = targetVid.downloadUrl
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

                val flowRes = FlowResolvedStream(
                    fileName = targetVid.fileName,
                    direct1080Url = direct1080Url,
                    fast720Url = targetVid.fastStreamUrl,
                    fileSize = targetVid.fileSize
                )
                flowCache[cacheKey] = System.currentTimeMillis() to flowRes
                flowRes
            }.getOrNull()
        }

        suspend fun resolveGetInDevice(
            rawUrl: String,
            targetFsId: String? = null,
            targetIndex: Int? = null
        ): String? {
            return runCatching {
                val cacheKey = "${rawUrl}_${targetFsId}_${targetIndex}"
                val cached = gidCache[cacheKey]
                val now = System.currentTimeMillis()
                if (cached != null && (now - cached.first) < CACHE_TTL_MS) {
                    return@runCatching cached.second
                }
                val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
                val tokenHeaders = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "application/json, text/plain, */*",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Referer" to "https://getindevice.com/terabox-downloader/",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin"
                )
                val tokenRes = app.get(
                    "https://getindevice.com/api/token/?_t=${System.currentTimeMillis()}",
                    headers = tokenHeaders,
                    timeout = 4
                )
                val token = parseJson<Map<String, Any>>(tokenRes.text)["token"]?.toString()
                    ?: return@runCatching null

                val dlHeaders = tokenHeaders.toMutableMap()
                dlHeaders["Content-Type"] = "application/json"
                dlHeaders["x-request-token"] = token

                val dlRes = app.post(
                    "https://getindevice.com/api/download/",
                    headers = dlHeaders,
                    json = mapOf(
                        "url" to rawUrl,
                        "tool" to "terabox-downloader"
                    ),
                    timeout = 6
                )
                val dlData = parseJson<Map<String, Any>>(dlRes.text)
                @Suppress("UNCHECKED_CAST")
                val videos = dlData["videos"] as? List<Map<String, Any>>
                if (videos.isNullOrEmpty()) return@runCatching null

                val targetVid = when {
                    !targetFsId.isNullOrBlank() -> videos.find { 
                        it["hlsStreamUrl"]?.toString()?.contains("fid=$targetFsId") == true 
                    } ?: videos.firstOrNull()
                    targetIndex != null && targetIndex in videos.indices -> videos[targetIndex]
                    else -> videos.firstOrNull()
                }

                val remoteHlsUrl = targetVid?.get("hlsStreamUrl")?.toString()
                if (remoteHlsUrl.isNullOrBlank()) return@runCatching null
                gidCache[cacheKey] = System.currentTimeMillis() to remoteHlsUrl
                remoteHlsUrl
            }.getOrNull()
        }

        suspend fun resolveDirectTeraBox(
            rawUrl: String,
            callback: (ExtractorLink) -> Unit,
            targetFsId: String? = null,
            targetPath: String? = null,
            targetTitle: String? = null,
            targetIndex: Int? = null
        ): Boolean {
            return runCatching {
                var emittedAny = false

                // A. Direct worker stream (dl-worker.teraboxdl.site)
                if (rawUrl.contains("dl-worker.teraboxdl.site")) {
                    val directStreamId = "tb_direct_" + (targetFsId ?: System.currentTimeMillis().toString())
                    val proxiedUrl = LocalM3u8Server.proxy(
                        directStreamId,
                        rawUrl,
                        referer = "https://player.1024teradl.com/",
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "https://player.1024teradl.com/",
                            "Connection" to "keep-alive"
                        )
                    )

                    // Local Proxy Link (Smooth Seeking, external player / casting safe)
                    callback(
                        newExtractorLink(
                            source = "TeraBox Direct 1080p",
                            name = "TeraBox - Direct HD [1080p] (Local Proxy - Smooth Seeking)",
                            url = proxiedUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://player.1024teradl.com/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                    emittedAny = true
                }

                // B. Fast preview HLS stream (get_m3u8_stream_fast)
                if (rawUrl.contains("get_m3u8_stream_fast")) {
                    callback(
                        newExtractorLink(
                            source = "TeraBox Fast Stream ⚡",
                            name = "TeraBox - Fast Stream ⚡ [480p] (1024TeraDL)",
                            url = rawUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://player.1024teradl.com/"
                            this.headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "https://player.1024teradl.com/"
                            )
                            this.quality = Qualities.P480.value
                        }
                    )
                    emittedAny = true
                }

                // C. Player URL containing start, direct, and/or terabox_url query parameters
                var effectiveUrl = rawUrl
                if (rawUrl.contains("player.1024teradl.com") || (rawUrl.contains("start=") && rawUrl.contains("direct="))) {
                    val queryPart = rawUrl.substringAfter("?")
                    var directUrl: String? = null
                    var startUrl: String? = null
                    var embeddedTeraboxUrl: String? = null
                    for (param in queryPart.split("&")) {
                        val key = param.substringBefore("=")
                        val rawVal = param.substringAfter("=", "")
                        val decodedVal = runCatching { URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal)
                        when (key) {
                            "direct" -> directUrl = decodedVal
                            "start" -> startUrl = decodedVal
                            "terabox_url" -> embeddedTeraboxUrl = decodedVal
                        }
                    }

                    if (!startUrl.isNullOrBlank()) {
                        callback(
                            newExtractorLink(
                                source = "TeraBox Fast Stream ⚡",
                                name = "TeraBox - Fast Stream ⚡ [480p] (1024TeraDL)",
                                url = startUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://player.1024teradl.com/"
                                this.headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to "https://player.1024teradl.com/"
                                )
                                this.quality = Qualities.P480.value
                            }
                        )
                        emittedAny = true
                    }

                    if (!directUrl.isNullOrBlank()) {
                        val streamId = "tb_1024_" + (targetFsId ?: System.currentTimeMillis().toString())
                        val proxiedUrl = LocalM3u8Server.proxy(
                            streamId,
                            directUrl,
                            referer = "https://player.1024teradl.com/",
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "https://player.1024teradl.com/",
                                "Connection" to "keep-alive"
                            )
                        )

                        // Local Proxy Link (Smooth Seeking, external player / casting safe)
                        callback(
                            newExtractorLink(
                                source = "TeraBox Direct 1080p",
                                name = "TeraBox - Direct HD [1080p] (Local Proxy - Smooth Seeking)",
                                url = proxiedUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://player.1024teradl.com/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                        emittedAny = true
                    }

                    if (!embeddedTeraboxUrl.isNullOrBlank()) {
                        effectiveUrl = embeddedTeraboxUrl
                    }
                }

                val surl = extractSurl(effectiveUrl) ?: return@runCatching emittedAny
                val streamId = (surl + "_" + (targetFsId ?: targetIndex?.toString() ?: "0")).replace(Regex("[^a-zA-Z0-9_-]"), "")

                coroutineScope {
                    val teradlDeferred = async {
                        runCatching {
                            TeraBoxVerification.fetch1024Files(effectiveUrl, allowVerification = true)
                        }.getOrNull() ?: emptyList()
                    }
                    val gidDeferred = async { resolveGetInDevice(effectiveUrl, targetFsId, targetIndex) }
                    val flowDeferred = async { resolveFlowVideoPlayer(effectiveUrl, targetFsId, targetPath, targetTitle, targetIndex) }
                    val nativeDeferred = async { runCatching { fetchShareContents(effectiveUrl) }.getOrNull() }

                    // 1. Emit Unthrottled 1080p Stream from 1024TeraDL (dl-worker.teraboxdl.site)
                    val teradlFiles = teradlDeferred.await()
                    val targetTeraFile = if (teradlFiles.isNotEmpty()) {
                        if (!targetFsId.isNullOrBlank()) {
                            teradlFiles.firstOrNull { it.fsId == targetFsId }
                        } else if (targetIndex != null && targetIndex in 0 until teradlFiles.size) {
                            teradlFiles[targetIndex]
                        } else {
                            teradlFiles.firstOrNull { !it.isDir }
                        }
                    } else null

                    if (targetTeraFile != null) {
                        val highSpeedDirect = targetTeraFile.directLink ?: targetTeraFile.streamDownloadUrl
                        if (!highSpeedDirect.isNullOrBlank()) {
                            val directStreamId = "tb_direct_" + (targetTeraFile.fsId ?: targetFsId ?: System.currentTimeMillis().toString())
                            val proxiedUrl = LocalM3u8Server.proxy(
                                directStreamId,
                                highSpeedDirect,
                                referer = "https://player.1024teradl.com/",
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to "https://player.1024teradl.com/",
                                    "Connection" to "keep-alive"
                                )
                            )

                            callback(
                                newExtractorLink(
                                    source = "TeraBox Direct 1080p",
                                    name = "TeraBox - Direct HD [1080p] (Local Proxy - Smooth Seeking)",
                                    url = proxiedUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://player.1024teradl.com/"
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            emittedAny = true
                        }

                        val fastHls = targetTeraFile.streamUrl
                        if (!fastHls.isNullOrBlank()) {
                            callback(
                                newExtractorLink(
                                    source = "TeraBox Fast Stream ⚡",
                                    name = "TeraBox - Fast Stream ⚡ [480p] (1024TeraDL)",
                                    url = fastHls,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "https://player.1024teradl.com/"
                                    this.headers = mapOf(
                                        "User-Agent" to USER_AGENT,
                                        "Referer" to "https://player.1024teradl.com/"
                                    )
                                    this.quality = Qualities.P480.value
                                }
                            )
                            emittedAny = true
                        }
                    }

                    // 2. Emit GetInDevice Unthrottled HLS Stream FIRST (Smooth, instant seeking, zero buffer)
                    val gidHlsUrl = gidDeferred.await()
                    if (!gidHlsUrl.isNullOrBlank()) {
                        callback(
                            newExtractorLink(
                                source = "TeraBox Fast Stream ⚡",
                                name = "TeraBox - Fast Stream ⚡ [480p] (Smooth Instant Playback)",
                                url = gidHlsUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://getindevice.com/"
                                this.headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to "https://getindevice.com/"
                                )
                                this.quality = Qualities.P480.value
                            }
                        )
                        emittedAny = true
                    }

                    // 2. Emit FlowVideoPlayer Fast Preview Stream (HLS preview transcode)
                    val flowStream = flowDeferred.await()
                    if (!flowStream?.fast720Url.isNullOrBlank()) {
                        callback(
                            newExtractorLink(
                                source = "TeraBox Fast Stream",
                                name = "TeraBox - Fast Stream [480p] (Flow Worker)",
                                url = flowStream.fast720Url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://flowvideoplayer.com/"
                                this.headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to "https://flowvideoplayer.com/"
                                )
                                this.quality = Qualities.P480.value
                            }
                        )
                        emittedAny = true
                    }

                    // 3. Emit FlowVideoPlayer Original File (Direct MKV proxied by worker ~150KB/s)
                    // Set to Qualities.Unknown so CloudStream defaults to the fast unbuffered stream,
                    // but leaves the genuine 1080p MKV selectable for downloads (1DM / ADM) or patient viewers.
                    if (!flowStream?.direct1080Url.isNullOrBlank()) {
                        val fNameLower = flowStream.fileName?.lowercase() ?: ""
                        val is4k = fNameLower.contains("4k") || fNameLower.contains("2160")
                        val is720 = fNameLower.contains("720")
                        val qualityLabel = when {
                            is4k -> "4K"
                            is720 -> "720p"
                            else -> "1080p"
                        }
                        val flowStreamId = "tb_flow_" + (targetFsId ?: System.currentTimeMillis().toString())
                        val proxiedUrl = LocalM3u8Server.proxy(
                            flowStreamId,
                            flowStream.direct1080Url,
                            referer = "https://flowvideoplayer.com/",
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "https://flowvideoplayer.com/",
                                "Connection" to "keep-alive"
                            )
                        )
                        // Accelerated Local Proxy Link (Smooth Seeking + 5-Connection Multi-Thread Range Pipe)
                        callback(
                            newExtractorLink(
                                source = "TeraBox Direct $qualityLabel",
                                name = "TeraBox - Original [$qualityLabel] (Local Proxy - Accelerated Playback)",
                                url = proxiedUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://flowvideoplayer.com/"
                                this.quality = if (qualityLabel == "1080p") Qualities.P1080.value else if (qualityLabel == "720p") Qualities.P720.value else Qualities.Unknown.value
                            }
                        )
                        emittedAny = true
                    }

                    val folderResult = nativeDeferred.await()
                    val variants = mutableListOf<HlsVariant>()

                    // 2. Native Multi-Resolution HLS Streams from TeraBox (Only 1080p & 720p HD)
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
                                ?: (if (targetIndex != null && targetIndex in 0 until folderResult.videos.size) folderResult.videos[targetIndex] else null)
                                ?: folderResult.videos.firstOrNull()
                        } else if (!targetPath.isNullOrBlank()) {
                            folderResult.videos.firstOrNull { it.path == targetPath }
                                ?: (if (targetIndex != null && targetIndex in 0 until folderResult.videos.size) folderResult.videos[targetIndex] else null)
                                ?: folderResult.videos.firstOrNull()
                        } else if (targetIndex != null && targetIndex in 0 until folderResult.videos.size) {
                            folderResult.videos[targetIndex]
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

                            // Strict 720p & 1080p only: probe exclusively HD VIP profiles when VIP ndus is present
                            val nativeConfigs = mutableListOf<VariantProbeConfig>()
                            if (!ndus.isNullOrBlank()) {
                                nativeConfigs.add(VariantProbeConfig("1080p (VIP)", "1920x1080", 6000000L, "M3U8_AUTO_1080", Qualities.P1080.value))
                                nativeConfigs.add(VariantProbeConfig("720p (VIP)", "1280x720", 3000000L, "M3U8_AUTO_720", Qualities.P720.value))
                            } else if (!emittedAny) {
                                // Absolute fallback if no direct stream
                                nativeConfigs.add(VariantProbeConfig("480p (Native)", "854x480", 1400000L, "M3U8_AUTO_480", Qualities.P480.value))
                            }

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

                    // 3. Multi-Resolution Adaptive Master Link (Strict 1080p / 720p HD Master)
                    if (variants.isNotEmpty()) {
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
                                source = "TeraBox Adaptive HD",
                                name = "TeraBox - [Adaptive HD 1080p / 720p Master (Paid VIP)]",
                                url = masterUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://flowvideoplayer.com/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                        emittedAny = true
                    }

                    emittedAny
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

class TeraBox1024DlExtractor : TeraBoxExtractor() {
    override val mainUrl = "https://1024teradl.com"
}

class TeraBoxDlSiteExtractor : TeraBoxExtractor() {
    override val mainUrl = "https://teraboxdl.site"
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
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("share_id") val shareId: Any? = null,
    @JsonProperty("shareid") val shareid: Any? = null,
    @JsonProperty("uk") val uk: Any? = null,
    @JsonProperty("server_time") val serverTime: Any? = null,
    @JsonProperty("timestamp") val timestamp: Any? = null,
    @JsonProperty("sign") val sign: String? = null
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
