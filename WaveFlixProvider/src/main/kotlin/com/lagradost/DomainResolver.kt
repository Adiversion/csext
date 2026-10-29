package com.lagradost

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.util.concurrent.ConcurrentHashMap

object DomainResolver {
    private const val REMOTE_CONFIG_URL = "https://raw.githubusercontent.com/Adiversion/csext/master/domains.json"
    private const val BACKUP_CONFIG_URL = "https://raw.githubusercontent.com/Adiversion/animepahe/master/domains.json"
    private const val CSX_CONFIG_URL = "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json"
    private const val CACHE_TTL = 6 * 3600 * 1000L // 6 hours

    private val defaults = mapOf(
        "rogmovies"   to "https://rogmovies.im",
        "katmovie"    to "https://new.katmoviehd.top",
        "hdhub4u"     to "https://new8.hdhub4u.cl",
        "bollyflix"   to "https://bollyflix.co.za",
        "moviesdrive" to "https://new3.moviesdrive.beer",
        "moviesmod"   to "https://moviesmod.meme",
        "uhdmovies"   to "https://uhdmovies.beauty",
        "fourkhdhub"  to "https://4khdhub.one"
    )

    private val mirrors = mapOf(
        "rogmovies"   to listOf("https://rogmovies.im", "https://rogmovies.one"),
        "katmovie"    to listOf("https://new.katmoviehd.top", "https://katmoviehd.cat"),
        "hdhub4u"     to listOf("https://new8.hdhub4u.cl", "https://hdhub4u.fans", "https://hdhub4u.ms", "https://hdhub4u.cl"),
        "bollyflix"   to listOf("https://bollyflix.co.za", "https://bollyflix.org"),
        "moviesdrive" to listOf("https://new3.moviesdrive.beer", "https://moviesdrive.space", "https://moviesdrive.forum"),
        "moviesmod"   to listOf("https://moviesmod.meme", "https://moviesleech.rest"),
        "uhdmovies"   to listOf("https://uhdmovies.beauty", "https://uhdmovies.click"),
        "fourkhdhub"  to listOf("https://4khdhub.one", "https://4khdhub.click")
    )

    private val domainCache = ConcurrentHashMap<String, String>(defaults)
    @Volatile private var lastFetchTime = 0L

    suspend fun get(key: String): String {
        val now = System.currentTimeMillis()
        if (lastFetchTime == 0L || (now - lastFetchTime) > CACHE_TTL) {
            tryFetch()
        }
        return domainCache[key] ?: defaults[key] ?: ""
    }

    /**
     * Automatically capture HTTP redirects from scrapers and update in-memory cache
     * so subsequent requests go straight to the working mirror without manual intervention.
     */
    fun reportRedirect(key: String, finalUrl: String?) {
        if (finalUrl.isNullOrBlank()) return
        val origin = runCatching {
            val uri = java.net.URI(finalUrl)
            if (uri.scheme != null && uri.host != null) {
                "${uri.scheme}://${uri.host}"
            } else null
        }.getOrNull()
        if (!origin.isNullOrBlank() && origin.startsWith("http")) {
            domainCache[key] = origin.trimEnd('/')
        }
    }

    private suspend fun tryFetch() {
        val text = runCatching {
            app.get(REMOTE_CONFIG_URL, timeout = 5).text
        }.getOrNull() ?: runCatching {
            app.get(BACKUP_CONFIG_URL, timeout = 5).text
        }.getOrNull() ?: runCatching {
            app.get(CSX_CONFIG_URL, timeout = 5).text
        }.getOrNull() ?: return

        runCatching {
            val map = parseJson<Map<String, String>>(text)
            for ((k, v) in map) {
                if (v.isNotBlank()) {
                    domainCache[k] = v.trim().trimEnd('/')
                }
            }
            lastFetchTime = System.currentTimeMillis()
        }
    }
}
