package com.lagradost

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

open class StremioOttProvider(
    override var name: String,
    val providerId: String,
    val jwPackage: String = providerId,
    val defaultType: TvType = TvType.TvSeries,
    val customMainPage: List<MainPageData>? = null
) : MainAPI() {
    override var mainUrl = "https://waveflix.online"
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Live
    )

    override val hasMainPage = true

    override val mainPage: List<MainPageData> = customMainPage ?: if (defaultType == TvType.Anime) {
        mainPageOf(
            "SHOW:15:0" to "Trending Anime Series",
            "MOVIE:15:0" to "Anime Movies"
        )
    } else {
        mainPageOf(
            "SHOW:15:0" to "Popular Series",
            "MOVIE:15:0" to "Popular Movies"
        )
    }

    companion object {
        private val ottCache = mutableMapOf<String, Pair<Long, HomePageResponse>>()
        private const val CACHE_TTL_MS = 15 * 60 * 1000L // 15 mins cache
        private val sharedEngine = WaveFlixProvider()
        private val idCache = ConcurrentHashMap<String, Long>()
        private val metaCache = ConcurrentHashMap<String, TmdbResolvedMeta>()
        private val hotstarSlugCache = ConcurrentHashMap<String, Pair<Long, String>>()
        private val hotstarSlugMutex = Mutex()

        private val PRIME_TOP_10 = listOf(
            "Rise And Fall",
            "Jailer",
            "Lenin Pandiyan",
            "Chaar Yaar",
            "Dupahiya",
            "The Traitors",
            "The Terminal List",
            "Awarapan 2",
            "Khalifa",
            "Sardar 2"
        )

        private val PRIME_COP_UNIVERSE = listOf(
            "Drishyam 2",
            "Waiting Hai",
            "Rahasya",
            "Section 375",
            "B.A. Pass",
            "Yashoda",
            "Sankalp",
            "Security",
            "Mahaan",
            "Jai Bhim",
            "Neeyat"
        )

        private val PRIME_FEATURED_ORIGINALS = listOf(
            "Masterplan",
            "Love Of Your Life",
            "My Fault",
            "Your Fault",
            "Red One"
        )

        private val PRIME_TOP_MOVIES = listOf(
            "Awarapan 2",
            "Khalifa",
            "Sardar 2",
            "Lenin Pandiyan",
            "Romeo"
        )

        private val PRIME_KOREAN = listOf(
            "True Beauty",
            "Good Boy",
            "Guardian: The Lonely And Great God",
            "Tale Of The Nine Tailed"
        )

        private val PRIME_SERIES = listOf(
            "Mirzapur",
            "The Family Man",
            "Panchayat",
            "Farzi",
            "Paatal Lok",
            "Jubilee",
            "Made in Heaven",
            "Breathe",
            "Dahaad",
            "Bambai Meri Jaan"
        )

        private val PRIME_ACTION = listOf(
            "K.G.F: Chapter 2",
            "Kantara",
            "Pushpa: The Rise",
            "Salaar",
            "Garudan",
            "Raayan",
            "Tiger 3",
            "Pathaan"
        )

        private val COP_UNIVERSE_QUERIES = listOf(
            "Singham Again",
            "Sooryavanshi",
            "Simmba",
            "Singham Returns",
            "Singham",
            "Indian Police Force",
            "Dahaad",
            "Paatal Lok"
        )

        private val HOTSTAR_TOP_10 = listOf(
            "Bigg Boss",
            "The Legend of Hanuman",
            "Anupama",
            "Yeh Rishta Kya Kehlata Hai",
            "Ghum Hai Kisikey Pyaar Meiin",
            "Jhanak",
            "Aashiqana",
            "Criminal Justice",
            "Special Ops",
            "Taaza Khabar"
        )

        private val HOTSTAR_NEW = listOf(
            "The Legend of Hanuman",
            "Showtime",
            "Lootere",
            "Aarya",
            "Karmma Calling",
            "Perilloor Premier League"
        )

        private val HOTSTAR_BOLLYWOOD = listOf(
            "Singham Returns",
            "Brahmāstra: Part One – Shiva",
            "Chhichhore",
            "Tanhaji: The Unsung Warrior",
            "Super 30",
            "Housefull 4",
            "Mission Mangal",
            "MS Dhoni: The Untold Story",
            "Baby",
            "Sanju"
        )

        private val HOTSTAR_HOLLYWOOD = listOf(
            "Harry Potter And The Sorcerer's Stone",
            "Avengers: Endgame",
            "Avatar: The Way of Water",
            "Guardians of the Galaxy Vol. 3",
            "Deadpool",
            "Black Panther: Wakanda Forever",
            "Iron Man",
            "Thor: Ragnarok",
            "The Lion King"
        )

        private val HOTSTAR_THRILLER = listOf(
            "Kill",
            "Freddy",
            "Gaslight",
            "A Thursday",
            "IB71",
            "Cuttputlli",
            "Neerja",
            "Talaash"
        )

        private val HOTSTAR_SPECIALS = listOf(
            "The Magic Of Shiri",
            "Special Ops",
            "Criminal Justice",
            "Aarya",
            "The Night Manager",
            "Rudra: The Edge of Darkness",
            "Dahan",
            "Human"
        )

        private val HOTSTAR_KIDS = listOf(
            "Avatar: The Last Airbender",
            "The Lion King",
            "Frozen II",
            "Moana",
            "Coco",
            "Finding Nemo",
            "Cars",
            "Tangled"
        )

        private val HOTSTAR_POPULAR_SHOWS = listOf(
            "Anupama",
            "Yeh Rishta Kya Kehlata Hai",
            "Ghum Hai Kisikey Pyaar Meiin",
            "Jhanak",
            "Bigg Boss",
            "Koffee With Karan",
            "Mahabharat",
            "Devon Ke Dev Mahadev"
        )

        private val HOTSTAR_POPULAR_MOVIES = listOf(
            "Maa Inti Bangaaram",
            "Singham Returns",
            "Brahmāstra: Part One – Shiva",
            "Chhichhore",
            "Tanhaji: The Unsung Warrior",
            "Super 30",
            "Housefull 4"
        )

        private val SONYLIV_ORIGINALS = listOf(
            "Maharani",
            "Scam 1992 The Harshad Mehta Story",
            "Gullak",
            "Scam 2003: The Telgi Story",
            "Rocket Boys",
            "Undekhi",
            "Freedom At Midnight",
            "Manvat Murders",
            "The Hunt - The Rajiv Gandhi Assassination Case",
            "Brinda",
            "Your Honor",
            "Avrodh",
            "Tanaav"
        )

        private val SONYLIV_BLOCKBUSTERS = listOf(
            "DC",
            "Rekhachithram",
            "Pani",
            "Unmadham",
            "Turbo",
            "Bramayugam",
            "Garudan",
            "2018: Everyone Is A Hero",
            "Por Thozhil"
        )

        private val SONYLIV_SAB_TV = listOf(
            "Taarak Mehta Ka Ooltah Chashmah",
            "CID",
            "Crime Patrol",
            "Pushpa Impossible",
            "Wagle Ki Duniya",
            "Indian Game Show",
            "Aladdin - Naam Toh Suna Hoga"
        )

        private val SONYLIV_TRENDING = listOf(
            "Family Full House With Rohit Sharma",
            "Indian Idol - Legends Return",
            "Celebrity Master Chef - Tamil",
            "Kon Honaar Crorepati",
            "Maharani",
            "Gullak"
        )

        private val PEACOCK_QUERIES = listOf(
            "Suits",
            "Fifty Shades of Grey",
            "Fifty Shades Freed",
            "Fifty Shades Darker",
            "Anora",
            "The Day of the Jackal",
            "Oppenheimer",
            "The Office",
            "Fast X",
            "Jurassic World"
        )
    }

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Content-Type" to "application/json"
    )

    private fun getUserCountry(): String {
        return try {
            val country = Locale.getDefault().country
            if (!country.isNullOrBlank() && country.length == 2) country.uppercase() else "IN"
        } catch (_: Throwable) {
            "IN"
        }
    }

    private suspend fun resolveTmdbMeta(id: String?, isMovie: Boolean, name: String?): TmdbResolvedMeta? {
        if (id.isNullOrBlank() && name.isNullOrBlank()) return null
        val cacheKey = id ?: "name:${name?.lowercase()?.trim()}"
        metaCache[cacheKey]?.let { return it }

        val numId = id?.toLongOrNull()
        if (numId != null) {
            val resolved = TmdbResolvedMeta(numId, null)
            metaCache[cacheKey] = resolved
            return resolved
        }

        // 1. If it's an IMDb ID (e.g. tt11663228), query Cinemeta
        if (!id.isNullOrBlank() && id.startsWith("tt")) {
            val cType = if (isMovie) "movie" else "series"
            val cinRes = runCatching {
                app.get("https://v3-cinemeta.strem.io/meta/$cType/$id.json", timeout = 3)
                    .parsedSafe<CinemetaResponse>()
            }.getOrNull()
            val foundId = cinRes?.meta?.moviedbId
            val foundPoster = cinRes?.meta?.poster
            if (foundId != null && foundId > 0) {
                val resolved = TmdbResolvedMeta(foundId, foundPoster)
                metaCache[cacheKey] = resolved
                idCache[cacheKey] = foundId
                return resolved
            }
        }

        // 2. Fallback: Search TMDB multi-search API by title
        if (!name.isNullOrBlank()) {
            val found = runCatching {
                val enc = URLEncoder.encode(name, "UTF-8")
                val searchRes = app.get("https://api.tmdb.org/3/search/multi?api_key=4e44d9029b1270a757cddc766a1bcb63&query=$enc", timeout = 4)
                    .parsedSafe<WaveFlixProvider.WaveSearchResponse>()
                val match = searchRes?.results?.firstOrNull()
                val mid = match?.id
                val rawPoster = match?.posterPath ?: match?.posterPathSnake
                val poster = rawPoster?.let { "https://image.tmdb.org/t/p/w500$it" }
                if (mid != null && mid > 0) {
                    TmdbResolvedMeta(mid, poster)
                } else null
            }.getOrNull()
            if (found != null) {
                metaCache[cacheKey] = found
                idCache[cacheKey] = found.id
                return found
            }
        }

        return null
    }

    private suspend fun resolveTmdbId(id: String?, isMovie: Boolean, name: String?): Long? {
        return resolveTmdbMeta(id, isMovie, name)?.id
    }

    private suspend fun fetchCncChannels(filterType: String): List<SearchResponse> {
        val filterUpper = filterType.uppercase()
        val isSony = filterUpper.startsWith("SONY")

        if (isSony) {
            val results = mutableListOf<SearchResponse>()

            // 1. If sports, fetch live cricket/sports events from LivXow
            if (filterUpper.contains("SPORTS")) {
                try {
                    val eventsRes = app.get(
                        "https://cncverse.dpdns.org/catalog/tv/cnc_LivXowLiveEvents_tv.json",
                        headers = mapOf("User-Agent" to "Mozilla/5.0"),
                        timeout = 5
                    ).parsedSafe<CncCatalogResponse>()

                    eventsRes?.metas?.forEach { meta ->
                        val id = meta.id ?: return@forEach
                        val name = meta.name?.trim() ?: return@forEach
                        if (name.isBlank() || name == "⠀") return@forEach

                        val targetUrl = "https://cncverse.dpdns.org/live/${URLEncoder.encode(name, "UTF-8")}?data=${URLEncoder.encode(id, "UTF-8")}"
                        results.add(
                            newMovieSearchResponse(name, targetUrl, TvType.Live) {
                                this.posterUrl = meta.poster
                            }
                        )
                    }
                } catch (_: Throwable) {}
            }

            // 2. Fetch Sony live channels from JIOTVIND
            try {
                val jioRes = app.get(
                    "https://cncverse.dpdns.org/catalog/tv/cnc_JIOTVIND_tv.json",
                    headers = mapOf("User-Agent" to "Mozilla/5.0"),
                    timeout = 6
                ).parsedSafe<CncCatalogResponse>()

                val channels = jioRes?.metas?.mapNotNull { meta ->
                    val id = meta.id ?: return@mapNotNull null
                    val name = meta.name?.trim() ?: return@mapNotNull null
                    if (name.isBlank() || name == "⠀") return@mapNotNull null
                    val lower = name.lowercase()

                    if (lower.contains("epg") || lower.contains("sonic") || lower.contains("sabka bazaar") || lower.contains("sansad")) {
                        return@mapNotNull null
                    }

                    val match = if (filterUpper.contains("SPORTS")) {
                        lower.contains("ten 1") || lower.contains("ten 2") || lower.contains("ten 3") ||
                        lower.contains("ten 4") || lower.contains("ten 5") || lower.contains("sony six") ||
                        lower.contains("sony liv sports")
                    } else {
                        lower.contains("sony sab") || lower.contains("sony max") || lower.contains("set hd") ||
                        lower.contains("set sd") || lower.contains("sony pix") || lower.contains("sony bbc earth") ||
                        lower.contains("sony pal") || lower.contains("sony yay") || lower.contains("sony marathi") ||
                        lower.contains("sony aath") || lower.contains("sony vizha")
                    }

                    if (!match) return@mapNotNull null

                    val cleanDisplayName = name
                        .replace(" STB", "")
                        .replace(" SD", "")
                        .trim()

                    val targetUrl = "https://cncverse.dpdns.org/live/${URLEncoder.encode(cleanDisplayName, "UTF-8")}?data=${URLEncoder.encode(id, "UTF-8")}"
                    newMovieSearchResponse(cleanDisplayName, targetUrl, TvType.Live) {
                        this.posterUrl = meta.poster
                    }
                }?.distinctBy { it.name } ?: emptyList()

                results.addAll(channels)
            } catch (_: Throwable) {}

            return results
        }

        return try {
            val res = app.get(
                "https://cncverse.dpdns.org/catalog/tv/cnc_JioHotstar2_tv.json",
                headers = mapOf("User-Agent" to "Mozilla/5.0"),
                timeout = 6
            ).parsedSafe<CncCatalogResponse>() ?: return emptyList()

            res.metas?.mapNotNull { meta ->
                val id = meta.id ?: return@mapNotNull null
                val name = meta.name?.trim() ?: return@mapNotNull null
                if (name.isBlank() || name == "⠀") return@mapNotNull null

                val lower = name.lowercase()
                val match = when (filterType.uppercase()) {
                    "SPORTS" -> lower.contains("sport") || lower.contains("ipl") || lower.contains("espn") || lower.contains("cricket")
                    "BIGGBOSS" -> lower.contains("bigboss") || lower.contains("bigg boss")
                    "KIDS" -> lower.contains("disney") || lower.contains("nick") || lower.contains("sonic") || lower.contains("hungama")
                    "ENTERTAINMENT" -> lower.contains("star plus") || lower.contains("colors hd") || lower.contains("star maa") || lower.contains("star vijay") || lower.contains("star jalsha") || lower.contains("asianet hd")
                    else -> true
                }
                if (!match) return@mapNotNull null

                val displayName = if (filterType.equals("BIGGBOSS", ignoreCase = true) && lower.contains("bigboss 24/7 live-")) {
                    "Bigg Boss 24x7 Live (${name.substringAfter("live-").uppercase()})"
                } else name

                val targetUrl = "https://cncverse.dpdns.org/live/${URLEncoder.encode(name, "UTF-8")}?data=${URLEncoder.encode(id, "UTF-8")}"
                newMovieSearchResponse(displayName, targetUrl, TvType.Live) {
                    this.posterUrl = meta.poster
                }
            } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private suspend fun fetchCuratedTitles(queries: List<String>, pkg: String, country: String): List<SearchResponse> {
        return coroutineScope {
            queries.map { q ->
                async {
                    val jw = searchJustWatch(q, pkg, country).firstOrNull()
                    if (jw != null) return@async jw

                    val meta = resolveTmdbMeta(null, isMovie = true, name = q) ?: return@async null
                    val itemUrl = "https://www.waveflix.online/details/movie/${meta.id}"
                    newMovieSearchResponse(q, itemUrl, TvType.Movie) {
                        this.posterUrl = meta.posterUrl
                    }
                }
            }.awaitAll().filterNotNull().distinctBy { it.url }
        }
    }

    private suspend fun fetchJustWatchCatalog(
        pkg: String,
        isMovie: Boolean,
        country: String,
        count: Int,
        genres: List<String>? = null,
        productionCountries: List<String>? = null,
        sortBy: String = "TRENDING",
        minScore: Double? = null,
        searchQuery: String? = null
    ): List<SearchResponse> {
        return try {
            val gql = """
                query GetPopularTitles(${"$" + "country"}: Country!, ${"$" + "language"}: Language!, ${"$" + "first"}: Int!, ${"$" + "filter"}: TitleFilter, ${"$" + "sortBy"}: PopularTitlesSorting!) {
                  popularTitles(country: ${"$" + "country"}, first: ${"$" + "first"}, filter: ${"$" + "filter"}, sortBy: ${"$" + "sortBy"}) {
                    edges {
                      node {
                        objectType
                        content(country: ${"$" + "country"}, language: ${"$" + "language"}) {
                          title
                          originalReleaseYear
                          posterUrl
                          scoring {
                            imdbScore
                          }
                          externalIds {
                            imdbId
                            tmdbId
                          }
                        }
                      }
                    }
                  }
                }
            """.trimIndent()

            val filterMap = mutableMapOf<String, Any>(
                "packages" to listOf(pkg),
                "objectTypes" to listOf(if (isMovie) "MOVIE" else "SHOW"),
                "monetizationTypes" to listOf("FLATRATE")
            )
            if (!genres.isNullOrEmpty()) {
                filterMap["genres"] = genres
            }
            if (!productionCountries.isNullOrEmpty()) {
                filterMap["productionCountries"] = productionCountries
            }
            if (minScore != null && minScore > 0) {
                filterMap["imdbScore"] = mapOf("min" to minScore)
            }
            if (!searchQuery.isNullOrBlank()) {
                filterMap["searchQuery"] = searchQuery
            }

            val payloadMap = mapOf(
                "operationName" to "GetPopularTitles",
                "query" to gql,
                "variables" to mapOf(
                    "country" to country,
                    "language" to "en",
                    "first" to count,
                    "filter" to filterMap,
                    "sortBy" to sortBy
                )
            )

            val res = app.post(
                "https://apis.justwatch.com/graphql",
                headers = commonHeaders,
                json = payloadMap,
                timeout = 6
            ).parsedSafe<JwGraphQLResponse>()

            val edges = res?.data?.popularTitles?.edges ?: return emptyList()

            coroutineScope {
                edges.map { edge ->
                    async {
                        val node = edge.node ?: return@async null
                        val content = node.content ?: return@async null
                        val title = content.title ?: return@async null

                        val rawTmdb = content.externalIds?.tmdbId?.toLongOrNull()
                        val imdbId = content.externalIds?.imdbId

                        val tmdbId = rawTmdb ?: resolveTmdbId(imdbId, isMovie, title) ?: return@async null
                        val targetUrl = "https://www.waveflix.online/details/${if (isMovie) "movie" else "tv"}/$tmdbId"

                        val poster = content.posterUrl?.let {
                            if (it.startsWith("/")) {
                                "https://images.justwatch.com" + it.replace("{profile}", "s332").replace("{format}", "jpg")
                            } else it
                        }

                        if (isMovie) {
                            newMovieSearchResponse(title, targetUrl, TvType.Movie) {
                                this.posterUrl = poster
                                this.year = content.originalReleaseYear
                            }
                        } else {
                            newTvSeriesSearchResponse(title, targetUrl, if (defaultType == TvType.Anime) TvType.Anime else TvType.TvSeries) {
                                this.posterUrl = poster
                                this.year = content.originalReleaseYear
                            }
                        }
                    }
                }.awaitAll().filterNotNull().distinctBy { it.url }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private suspend fun fetchSonyLivTray(trayId: String, count: Int = 15): List<SearchResponse> {
        val curatedFallback = when (trayId) {
            "9015093" -> SONYLIV_ORIGINALS
            "9016638" -> SONYLIV_BLOCKBUSTERS
            "POPULAR_SEARCH" -> SONYLIV_SAB_TV
            "9014011" -> SONYLIV_TRENDING
            else -> emptyList()
        }

        val directResults = runCatching {
            val url = if (trayId == "POPULAR_SEARCH") {
                "https://apiv3.sonyliv.com/AGL/4.8/A/ENG/WEB/IN/CT/TRAY/SEARCH/POPULAR?from=0&to=$count"
            } else {
                "https://apiv3.sonyliv.com/AGL/4.8/A/ENG/WEB/IN/CT/TRAY/EXTCOLLECTION/$trayId?from=0&to=$count"
            }

            val json = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Origin" to "https://www.sonyliv.com",
                    "Referer" to "https://www.sonyliv.com/"
                ),
                timeout = 7
            ).text

            val parsed = runCatching { AppUtils.parseJson<SonyLivTrayResponse>(json) }.getOrNull()
            val containers = parsed?.resultObj?.containers ?: return@runCatching emptyList()

            coroutineScope {
                containers.map { container: SonyLivContainer ->
                    async {
                        val meta = container.metadata ?: return@async null
                        val title = meta.title?.trim() ?: return@async null
                        if (title.isBlank()) return@async null

                        val isMovie = meta.contentSubtype?.contains("MOVIE", ignoreCase = true) == true
                        val year = meta.year?.toIntOrNull()

                        val imdbId = meta.emfAttributes?.sameAs?.firstOrNull { it.contains("imdb.com/title/") }
                            ?.substringAfter("title/")?.trimEnd('/')

                        val tmdbId = resolveTmdbId(imdbId, isMovie, title) ?: return@async null

                        val poster: String? = meta.emfAttributes?.imgPoster20003000
                            ?: meta.emfAttributes?.image600x900Clean
                            ?: meta.emfAttributes?.apvPosterArt
                            ?: meta.emfAttributes?.tvBackgroundImage
                            ?: meta.emfAttributes?.imgTile38402160
                            ?: meta.emfAttributes?.mastheadLargeV2
                            ?: if (!meta.pictureUrl.isNullOrBlank() && meta.pictureUrl != "NA") meta.pictureUrl else null

                        val itemUrl = "https://www.waveflix.online/details/${if (isMovie) "movie" else "tv"}/$tmdbId"

                        if (isMovie) {
                            newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                                this.posterUrl = poster
                                this.year = year
                            }
                        } else {
                            newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) {
                                this.posterUrl = poster
                                this.year = year
                            }
                        }
                    }
                }.awaitAll().filterNotNull().distinctBy { it.url }
            }
        }.getOrNull() ?: emptyList()

        if (directResults.isNotEmpty()) return directResults

        if (curatedFallback.isNotEmpty()) {
            return fetchCuratedTitles(curatedFallback.take(count), jwPackage, "IN")
        }

        return emptyList()
    }

    private suspend fun fetchAppleTvShelf(shelfId: String, count: Int = 15): List<SearchResponse> {
        val utsk = "6e3013c6d6fae3c2::::::"
        val url = "https://uts-api.itunes.apple.com/uts/v3/shelves/$shelfId?caller=web&sf=143467&v=72&pfm=web&locale=en-IN&utsk=$utsk"

        val json = runCatching {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Accept" to "application/json",
                    "Origin" to "https://tv.apple.com",
                    "Referer" to "https://tv.apple.com/"
                ),
                timeout = 7
            ).text
        }.getOrNull() ?: return emptyList()

        val parsed = runCatching { AppUtils.parseJson<AppleTvShelfResponse>(json) }.getOrNull()
        val items = parsed?.data?.shelf?.items?.take(count) ?: return emptyList()

        return coroutineScope {
            items.map { item: AppleTvShelfItem ->
                async {
                    val title = item.title?.trim() ?: return@async null
                    if (title.isBlank()) return@async null

                    val isMovie = item.type?.equals("Movie", ignoreCase = true) == true
                    val year = item.releaseDate?.let { rd ->
                        val cal = java.util.Calendar.getInstance()
                        cal.timeInMillis = rd
                        cal.get(java.util.Calendar.YEAR)
                    }

                    val resolved = resolveTmdbMeta(null, isMovie, title) ?: return@async null
                    val tmdbId = resolved.id

                    val rawPoster = item.images?.shelfImage?.url ?: item.images?.transitionImage?.url
                    val poster: String? = resolved.posterUrl
                        ?: rawPoster?.replace("{w}x{h}.{f}", "720x1080.webp")

                    val itemUrl = "https://www.waveflix.online/details/${if (isMovie) "movie" else "tv"}/$tmdbId"

                    if (isMovie) {
                        newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    } else {
                        newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    }
                }
            }.awaitAll().filterNotNull().distinctBy { it.url }
        }
    }

    private suspend fun getHotstarSlugJson(pageSlug: String): String? {
        val now = System.currentTimeMillis()
        val cached = hotstarSlugCache[pageSlug]
        if (cached != null && (now - cached.first) < CACHE_TTL_MS) {
            return cached.second
        }

        return hotstarSlugMutex.withLock {
            val freshCheck = hotstarSlugCache[pageSlug]
            if (freshCheck != null && (System.currentTimeMillis() - freshCheck.first) < CACHE_TTL_MS) {
                return@withLock freshCheck.second
            }

            val token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ7XCJoSWRcIjpcImEzMDQ0ZjYxN2NkOTRlMmY5NjAxNmU3ZTZkYzFmMWJiXCIsXCJwSWRcIjpcIjE4M2YwNDgxYzI4MTQ3OTZhNzFiNzYzMTUzNThmZGJmXCIsXCJkd0hpZFwiOlwiNTcyNDAzZjM1OGM4MTlkNTZmZWY5NDliOWFmNTZlM2ZjODU3MmE2ZTBkN2JjMDZmYmY1MmFhYzc2YWY4NDc5ZlwiLFwiZHdQaWRcIjpcImFiZmZmMGNmMDNmN2JhNDY4MWUwODZlZDIxZTllMGQ4ZGI0MjBiODBhYjZjNWU3YmU5ZjgxMzFlMTlmYWY5NGJcIixcIm9sZEhpZFwiOlwiYTMwNDRmNjE3Y2Q5NGUyZjk2MDE2ZTdlNmRjMWYxYmJcIixcIm9sZFBpZFwiOlwiMTgzZjA0ODFjMjgxNDc5NmE3MWI3NjMxNTM1OGZkYmZcIixcImlzUGlpVXNlck1pZ3JhdGVkXCI6ZmFsc2UsXCJuYW1lXCI6XCJZb3VcIixcImlwXCI6XCIyNDAxOjQ5MDA6ODhiZDpiMzY2OjE0OTM6Mzg5OTo1OGIyOmNkMGZcIixcImNvdW50cnlDb2RlXCI6XCJpblwiLFwiY3VzdG9tZXJUeXBlXCI6XCJudVwiLFwidHlwZVwiOlwiZ3Vlc3RcIixcImlzRW1haWxWZXJpZmllZFwiOmZhbHNlLFwiaXNQaG9uZVZlcmlmaWVkXCI6ZmFsc2UsXCJkZXZpY2VJZFwiOlwiODkxMTllLTYyMjNlOS04ZTg5NjYtMzdhNTdjXCIsXCJwcm9maWxlXCI6XCJBRFVMVFwiLFwidmVyc2lvblwiOlwidjJcIixcInN1YnNjcmlwdGlvbnNcIjp7XCJpblwiOnt9fSxcImlzc3VlZEF0XCI6MTc5MjczNzM1Mzk0NixcIm1hdHVyaXR5TGV2ZWxcIjpcIkFcIixcImRwaWRcIjpcIjE4M2YwNDgxYzI4MTQ3OTZhNzFiNzYzMTUzNThmZGJmXCIsXCJzdFwiOjEsXCJkYXRhXCI6XCJDZ1FJQURvQUNnd0lBQ0lJa0FHYXRlUzdsalFLTGdnQVFpb0tLRUkzWTJVeE9HVTJObVUyTWpJME9EUTNPR1l6WTJZM01qTTFNamMyTmpWbU16a3ROV1J5UWpBPVwifSIsImlzcyI6IlVNIiwiZXhwIjoxNzkyODIzNzUzLCJqdGkiOiIyNDQxZTg1ZjEwNzQ0OGZkOWNmOTlmZjczYjQwMmZjMCIsImlhdCI6MTc5MjczNzM1MywiYXBwSWQiOiIiLCJ0ZW5hbnQiOiIiLCJ2ZXJzaW9uIjoiMV8wIiwiYXVkIjoidW1fYWNjZXNzIn0.TrOwZtwk1VHX4_i-C3280eyLfqDXX_PMcYAlPRoMXhc"
            val url = "https://www.hotstar.com/api/internal/bff/v2/slugs/in/$pageSlug"

            val json = runCatching {
                app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                        "Accept" to "application/json, text/plain, */*",
                        "x-hs-usertoken" to token,
                        "X-HS-Platform" to "web",
                        "X-Country-Code" to "in",
                        "Origin" to "https://www.hotstar.com",
                        "Referer" to "https://www.hotstar.com/"
                    ),
                    timeout = 15
                ).text
            }.getOrNull()

            if (!json.isNullOrBlank()) {
                hotstarSlugCache[pageSlug] = System.currentTimeMillis() to json
            }
            json
        }
    }

    private suspend fun fetchHotstarTray(pageSlug: String, trayKeyword: String, count: Int = 15): List<SearchResponse> {
        val curatedFallback = when {
            trayKeyword.contains("Top 10", ignoreCase = true) -> HOTSTAR_TOP_10
            trayKeyword.contains("New on", ignoreCase = true) -> HOTSTAR_NEW
            trayKeyword.contains("Bollywood", ignoreCase = true) -> HOTSTAR_BOLLYWOOD
            trayKeyword.contains("Hollywood", ignoreCase = true) -> HOTSTAR_HOLLYWOOD
            trayKeyword.contains("Thriller", ignoreCase = true) -> HOTSTAR_THRILLER
            trayKeyword.contains("Specials", ignoreCase = true) || trayKeyword.contains("Originals", ignoreCase = true) -> HOTSTAR_SPECIALS
            trayKeyword.contains("Kids", ignoreCase = true) -> HOTSTAR_KIDS
            trayKeyword.contains("Shows", ignoreCase = true) -> HOTSTAR_POPULAR_SHOWS
            else -> HOTSTAR_POPULAR_MOVIES
        }

        val directResults = runCatching {
            val json = getHotstarSlugJson(pageSlug) ?: return@runCatching emptyList()

            val parsed = runCatching { AppUtils.parseJson<HotstarSlugResponse>(json) }.getOrNull()
            val wrappers = parsed?.success?.page?.spaces?.get("tray")?.widgetWrappers ?: return@runCatching emptyList()

            val idx = trayKeyword.toIntOrNull()
            val targetWrapper = if (idx != null && idx >= 0 && idx < wrappers.size) {
                wrappers[idx]
            } else {
                wrappers.firstOrNull { w ->
                    if (w.template != "ScrollableTrayWidget") return@firstOrNull false
                    val headerMap = w.widget?.data?.header ?: return@firstOrNull false
                    headerMap.values.any { v ->
                        (v as? Map<*, *>)?.get("title")?.toString()?.contains(trayKeyword, ignoreCase = true) == true
                    }
                }
            } ?: return@runCatching emptyList()

            val items = targetWrapper.widget?.data?.items?.take(count) ?: return@runCatching emptyList()
            val isMovie = pageSlug.contains("movies", ignoreCase = true)

            coroutineScope {
                items.map { itemWrapper: HotstarItemWrapper ->
                    async {
                        val posterData = itemWrapper.verticalPoster?.data
                            ?: itemWrapper.overlayPoster?.data?.verticalPoster?.data
                            ?: itemWrapper.horizontalPoster?.data
                            ?: itemWrapper.horizontalCard?.data
                            ?: return@async null
                        val title = (posterData.expandedPoster?.contentInfo?.title ?: posterData.image?.alt)?.trim() ?: return@async null
                        if (title.isBlank()) return@async null

                        val tags = posterData.expandedPoster?.contentInfo?.tags?.mapNotNull { it.value } ?: emptyList()
                        val year = tags.firstOrNull { it.length == 4 && it.all { c -> c.isDigit() } }?.toIntOrNull()

                        val resolved = resolveTmdbMeta(null, isMovie, title) ?: return@async null
                        val tmdbId = resolved.id

                        val hsSrc = posterData.image?.src
                        val poster: String? = if (hsSrc != null && !hsSrc.endsWith("-h")) {
                            "https://img.hotstar.com/image/upload/v1/$hsSrc"
                        } else {
                            resolved.posterUrl ?: hsSrc?.let { "https://img.hotstar.com/image/upload/v1/$it" }
                        }
                        val itemUrl = "https://www.waveflix.online/details/${if (isMovie) "movie" else "tv"}/$tmdbId"

                        if (isMovie) {
                            newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                                this.posterUrl = poster
                                this.year = year
                            }
                        } else {
                            newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) {
                                this.posterUrl = poster
                                this.year = year
                            }
                        }
                    }
                }.awaitAll().filterNotNull().distinctBy { it.url }
            }
        }.getOrNull() ?: emptyList()

        if (directResults.isNotEmpty()) return directResults

        // Fail-safe curated fallback: Matches exact Hotstar website titles with 0% failure
        return fetchCuratedTitles(curatedFallback.take(count), jwPackage, "IN")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val country = getUserCountry()
        val cacheKey = "$name/$country/${request.data}/${request.name}/$page"
        val now = System.currentTimeMillis()

        synchronized(ottCache) {
            val cached = ottCache[cacheKey]
            if (cached != null && (now - cached.first) < CACHE_TTL_MS) {
                return cached.second
            }
        }

        val items = when {
            request.data.startsWith("APPLE_SHELF:") -> {
                val parts = request.data.removePrefix("APPLE_SHELF:").split(":")
                val shelfId = parts.getOrNull(0) ?: "uts.col.ChartsMovies.tvs.sbd.4000"
                val count = parts.getOrNull(1)?.toIntOrNull() ?: 15
                fetchAppleTvShelf(shelfId, count)
            }
            request.data.startsWith("HOTSTAR_TRAY:") -> {
                val parts = request.data.removePrefix("HOTSTAR_TRAY:").split(":")
                val pageType = parts.getOrNull(0) ?: "movies"
                val trayName = parts.getOrNull(1) ?: "Popular"
                val count = parts.getOrNull(2)?.toIntOrNull() ?: 15
                fetchHotstarTray(pageType, trayName, count)
            }
            request.data.startsWith("SONYLIV_TRAY:") -> {
                val parts = request.data.removePrefix("SONYLIV_TRAY:").split(":")
                val trayId = parts.getOrNull(0) ?: "9014011"
                val count = parts.getOrNull(1)?.toIntOrNull() ?: 15
                fetchSonyLivTray(trayId, count)
            }
            request.data.startsWith("LIVE_CNC:") -> {
                val cat = request.data.substringAfter("LIVE_CNC:")
                fetchCncChannels(cat)
            }
            request.data.startsWith("SONY_CNC:") -> {
                val cat = "SONY_" + request.data.substringAfter("SONY_CNC:")
                fetchCncChannels(cat)
            }
            request.data.startsWith("LIVE_SPORTS") -> {
                fetchCncChannels("SPORTS")
            }
            request.data.startsWith("PRIME_TOP_10") -> {
                fetchCuratedTitles(PRIME_TOP_10, jwPackage, country)
            }
            request.data.startsWith("PRIME_TOP_MOVIES") -> {
                fetchCuratedTitles(PRIME_TOP_MOVIES, jwPackage, country)
            }
            request.data.startsWith("PRIME_ORIGINALS") -> {
                fetchCuratedTitles(PRIME_FEATURED_ORIGINALS, jwPackage, country)
            }
            request.data.startsWith("PRIME_COP") -> {
                fetchCuratedTitles(PRIME_COP_UNIVERSE, jwPackage, country)
            }
            request.data.startsWith("PRIME_KOREAN") -> {
                fetchCuratedTitles(PRIME_KOREAN, jwPackage, country)
            }
            request.data.startsWith("PRIME_SERIES") -> {
                fetchCuratedTitles(PRIME_SERIES, jwPackage, country)
            }
            request.data.startsWith("PRIME_ACTION") -> {
                fetchCuratedTitles(PRIME_ACTION, jwPackage, country)
            }
            request.data.startsWith("COP_UNIVERSE") -> {
                fetchCuratedTitles(COP_UNIVERSE_QUERIES, jwPackage, country)
            }
            request.data.startsWith("PEACOCK") -> {
                fetchCuratedTitles(PEACOCK_QUERIES, jwPackage, country)
            }
            request.data.startsWith("SHOW_IN:") -> {
                val parts = request.data.removePrefix("SHOW_IN:").split(":")
                val count = parts.getOrNull(0)?.toIntOrNull() ?: 10
                val offset = parts.getOrNull(1)?.toIntOrNull() ?: 0
                fetchJustWatchCatalog(
                    pkg = jwPackage,
                    isMovie = false,
                    country = country,
                    count = count + offset,
                    productionCountries = listOf("IN"),
                    sortBy = "TRENDING",
                    minScore = 6.0
                ).drop(offset).take(count)
            }
            request.data.startsWith("MOVIE_IN:") -> {
                val parts = request.data.removePrefix("MOVIE_IN:").split(":")
                val count = parts.getOrNull(0)?.toIntOrNull() ?: 10
                val offset = parts.getOrNull(1)?.toIntOrNull() ?: 0
                fetchJustWatchCatalog(
                    pkg = jwPackage,
                    isMovie = true,
                    country = country,
                    count = count + offset,
                    productionCountries = listOf("IN"),
                    sortBy = "TRENDING",
                    minScore = 6.0
                ).drop(offset).take(count)
            }
            request.data.startsWith("SHOW_GLOBAL:") -> {
                val parts = request.data.removePrefix("SHOW_GLOBAL:").split(":")
                val count = parts.getOrNull(0)?.toIntOrNull() ?: 12
                val offset = parts.getOrNull(1)?.toIntOrNull() ?: 0
                fetchJustWatchCatalog(
                    pkg = jwPackage,
                    isMovie = false,
                    country = country,
                    count = count + offset,
                    sortBy = "POPULAR",
                    minScore = 7.5
                ).drop(offset).take(count)
            }
            request.data.startsWith("MOVIE_GLOBAL:") -> {
                val parts = request.data.removePrefix("MOVIE_GLOBAL:").split(":")
                val count = parts.getOrNull(0)?.toIntOrNull() ?: 12
                val offset = parts.getOrNull(1)?.toIntOrNull() ?: 0
                fetchJustWatchCatalog(
                    pkg = jwPackage,
                    isMovie = true,
                    country = country,
                    count = count + offset,
                    sortBy = "POPULAR",
                    minScore = 7.0
                ).drop(offset).take(count)
            }
            request.data.startsWith("SHOW_KOREAN:") -> {
                val count = request.data.substringAfter("SHOW_KOREAN:").toIntOrNull() ?: 12
                fetchJustWatchCatalog(
                    pkg = jwPackage,
                    isMovie = false,
                    country = country,
                    count = count,
                    productionCountries = listOf("KR"),
                    sortBy = "POPULAR",
                    minScore = 7.0
                )
            }
            request.data.startsWith("SHOW_GENRE:") -> {
                val parts = request.data.removePrefix("SHOW_GENRE:").split(":")
                val genreList = parts.getOrNull(0)?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                val count = parts.getOrNull(1)?.toIntOrNull() ?: 12
                val offset = parts.getOrNull(2)?.toIntOrNull() ?: 0
                fetchJustWatchCatalog(
                    pkg = jwPackage,
                    isMovie = false,
                    country = country,
                    count = count + offset,
                    genres = genreList,
                    minScore = 7.0
                ).drop(offset).take(count)
            }
            request.data.startsWith("MOVIE_GENRE:") -> {
                val parts = request.data.removePrefix("MOVIE_GENRE:").split(":")
                val genreList = parts.getOrNull(0)?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                val count = parts.getOrNull(1)?.toIntOrNull() ?: 12
                val offset = parts.getOrNull(2)?.toIntOrNull() ?: 0
                fetchJustWatchCatalog(
                    pkg = jwPackage,
                    isMovie = true,
                    country = country,
                    count = count + offset,
                    genres = genreList,
                    minScore = 6.5
                ).drop(offset).take(count)
            }
            request.data.startsWith("MOVIE_COP") -> {
                fetchCuratedTitles(COP_UNIVERSE_QUERIES, jwPackage, country)
            }
            else -> {
                val parts = request.data.split(":")
                val isMovie = parts.getOrNull(0)?.equals("MOVIE", ignoreCase = true) == true
                val count = parts.getOrNull(1)?.toIntOrNull() ?: 15
                val offset = parts.getOrNull(2)?.toIntOrNull() ?: 0
                val fetchCount = (count + offset).coerceAtLeast(10)

                val allItems = fetchJustWatchCatalog(
                    pkg = jwPackage,
                    isMovie = isMovie,
                    country = country,
                    count = fetchCount
                )

                if (offset > 0 && allItems.size > offset) {
                    allItems.drop(offset).take(count)
                } else {
                    allItems.take(count)
                }
            }
        }

        val isHorizontal = request.data.startsWith("LIVE_") ||
            request.data.startsWith("SONY_CNC:") ||
            request.data.contains("horizontal", ignoreCase = true)

        val response = newHomePageResponse(
            HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = isHorizontal
            ),
            hasNext = false
        )
        synchronized(ottCache) {
            ottCache[cacheKey] = now to response
        }
        return response
    }

    private fun normalizeTokens(str: String): List<String> {
        return str.lowercase()
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.length > 1 }
            .map { w ->
                if (w.length > 3 && w.endsWith("es")) w.dropLast(2)
                else if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1)
                else w
            }
    }

    private fun isRelevantTitle(query: String, title: String): Boolean {
        val qTokens = normalizeTokens(query)
        if (qTokens.isEmpty()) return true
        val tTokens = normalizeTokens(title)
        if (tTokens.isEmpty()) return false

        if (qTokens.size == 1) {
            val q = qTokens[0]
            return tTokens.any { it.contains(q) || q.contains(it) }
        }

        val cleanQ = query.trim().lowercase()
        val cleanT = title.trim().lowercase()
        if (cleanT.contains(cleanQ)) return true

        val matches = qTokens.count { q ->
            tTokens.any { t -> t.contains(q) || q.contains(t) }
        }

        if (qTokens.size == 2) {
            return matches == 2
        }

        return (matches.toDouble() / qTokens.size) >= 0.6
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val country = getUserCountry()
        return searchJustWatch(cleanQuery, jwPackage, country)
    }

    private suspend fun searchJustWatch(query: String, pkgCode: String, country: String): List<SearchResponse> {
        return try {
            val gql = """
                query GetSuggestedTitles(${"$"}country: Country!, ${"$"}language: Language!, ${"$"}first: Int!, ${"$"}filter: TitleFilter) {
                  popularTitles(country: ${"$"}country, first: ${"$"}first, filter: ${"$"}filter) {
                    edges {
                      node {
                        id
                        objectType
                        objectId
                        content(country: ${"$"}country, language: ${"$"}language) {
                          title
                          originalReleaseYear
                          posterUrl
                          scoring {
                            imdbScore
                          }
                          externalIds {
                            imdbId
                            tmdbId
                          }
                        }
                      }
                    }
                  }
                }
            """.trimIndent()

            val payloadMap = mapOf(
                "operationName" to "GetSuggestedTitles",
                "query" to gql,
                "variables" to mapOf(
                    "country" to country,
                    "language" to "en",
                    "first" to 20,
                    "filter" to mapOf(
                        "searchQuery" to query,
                        "packages" to listOf(pkgCode)
                    )
                )
            )

            val res = app.post(
                "https://apis.justwatch.com/graphql",
                headers = commonHeaders,
                json = payloadMap,
                timeout = 8
            ).parsedSafe<JwGraphQLResponse>()

            val edges = res?.data?.popularTitles?.edges ?: return emptyList()
            coroutineScope {
                edges.map { edge ->
                    async {
                        val node = edge.node ?: return@async null
                        val content = node.content ?: return@async null
                        val title = content.title ?: return@async null

                        // Strict relevance check to prevent irrelevant matches on other platforms
                        if (!isRelevantTitle(query, title)) return@async null

                        val isMovie = node.objectType?.equals("MOVIE", ignoreCase = true) == true
                        val rawTmdb = content.externalIds?.tmdbId?.toLongOrNull()
                        val imdbId = content.externalIds?.imdbId

                        val tmdbId = rawTmdb ?: resolveTmdbId(imdbId, isMovie, title) ?: return@async null
                        val targetUrl = "https://www.waveflix.online/details/${if (isMovie) "movie" else "tv"}/$tmdbId"

                        val poster = content.posterUrl?.let {
                            if (it.startsWith("/")) {
                                "https://images.justwatch.com" + it.replace("{profile}", "s332").replace("{format}", "jpg")
                            } else it
                        }

                        if (isMovie) {
                            newMovieSearchResponse(title, targetUrl, TvType.Movie) {
                                this.posterUrl = poster
                                this.year = content.originalReleaseYear
                            }
                        } else {
                            newTvSeriesSearchResponse(title, targetUrl, if (defaultType == TvType.Anime) TvType.Anime else TvType.TvSeries) {
                                this.posterUrl = poster
                                this.year = content.originalReleaseYear
                            }
                        }
                    }
                }.awaitAll().filterNotNull().distinctBy { it.url }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.startsWith("https://cncverse.dpdns.org/live/")) {
            val rawData = url.substringAfter("?data=")
            val decodedId = URLDecoder.decode(rawData, "UTF-8")
            val rawTitle = url.substringBefore("?data=").substringAfter("/live/")
            val title = URLDecoder.decode(rawTitle, "UTF-8")

            return newMovieLoadResponse(title, url, TvType.Live, decodedId) {
                this.plot = "Live stream via CNCverse Bridge"
            }
        }

        val res = sharedEngine.load(url)
        res.apiName = this.name
        return res
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("cnc:")) {
            var foundLinks = false
            // 1. CNCstream multi-quality and auto-decrypted stream bridge
            try {
                val encodedData = URLEncoder.encode(data, "UTF-8")
                val streamApiUrl = "https://cncverse.dpdns.org/stream/tv/$encodedData.json"
                val streamRes = app.get(
                    streamApiUrl,
                    headers = mapOf("User-Agent" to "Mozilla/5.0"),
                    timeout = 8
                ).parsedSafe<CncStreamResponse>()

                val rawStreams = streamRes?.streams ?: emptyList()
                val sortedStreams = rawStreams.sortedByDescending { s ->
                    val u = s.url?.lowercase().orEmpty()
                    val titleAndName = (s.title.orEmpty() + " " + s.name.orEmpty()).lowercase()
                    when {
                        // Top priority: Direct Akamai CDN SonyLiv / Sony channels (fast, 0-stall native HLS)
                        u.contains("akamaized.net") || u.contains("sonydaime") -> 300
                        // Direct native HLS broadcasters (e.g. fullscreen.nz, etc.)
                        u.contains("fullscreen.nz") || (!u.contains("/d/master.m3u8") && !u.contains("cncverse")) -> 200
                        // Channels specifically labeled SonyLiv / Sony
                        titleAndName.contains("sonyliv") || titleAndName.contains("sony") -> 150
                        // Other non-transcode streams
                        !u.contains("/d/master.m3u8") -> 100
                        // Transcoded bridge streams as fallback
                        else -> 50
                    }
                }

                sortedStreams.forEach { s ->
                    val rawUrl = s.url?.trim() ?: return@forEach
                    if (rawUrl.isBlank() || rawUrl.startsWith("javascript:")) return@forEach

                    // Upgrade insecure HTTP bridge IP to official HTTPS domain to prevent Android cleartext 2004 errors
                    val sUrl = if (rawUrl.contains("140.238.244.130")) {
                        rawUrl.replace("http://140.238.244.130", "https://cncverse.dpdns.org")
                    } else rawUrl

                    val sTitle = s.title ?: ""
                    val sName = s.name ?: "Live Stream"

                    val quality = when {
                        sTitle.contains("1080") || sName.contains("1080") -> Qualities.P1080.value
                        sTitle.contains("720") || sName.contains("720") -> Qualities.P720.value
                        sTitle.contains("480") || sName.contains("480") -> Qualities.P480.value
                        sTitle.contains("360") || sName.contains("360") -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }

                    val headers = s.behaviorHints?.proxyHeaders?.request?.toMutableMap() ?: mutableMapOf()
                    if (!headers.containsKey("User-Agent")) {
                        headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                    }

                    // Skip dead Akamai live streams returning HTTP 404 to prevent ERROR_CODE_IO_BAD_HTTP_STATUS (2004)
                    if (sUrl.contains("akamaized.net") || sUrl.contains("sonydaime")) {
                        val isDead = runCatching {
                            val check = app.get(sUrl, headers = headers, timeout = 2)
                            check.code == 404 || check.code == 403
                        }.getOrDefault(false)
                        if (isDead) return@forEach
                    }

                    val label = when {
                        sTitle.contains("🏷️") -> sTitle.substringAfter("🏷️").substringBefore("🎞️").substringBefore("🛰️").trim()
                        sName.isNotBlank() && !sName.startsWith("✨") -> sName.substringBefore("•").trim()
                        sTitle.isNotBlank() && !sTitle.startsWith("Click to donate") -> sTitle.lines().firstOrNull()?.trim() ?: "Live Stream"
                        else -> "Live Stream"
                    }

                    val isDASH = sUrl.substringBefore("?").endsWith(".mpd", ignoreCase = true)
                    val isM3U8 = sUrl.substringBefore("?").endsWith(".m3u8", ignoreCase = true)
                    val linkType = when {
                        isM3U8 -> ExtractorLinkType.M3U8
                        isDASH -> ExtractorLinkType.DASH
                        sUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                        sUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                        else -> ExtractorLinkType.M3U8
                    }
                    callback(
                        newExtractorLink(
                            source = "Live Stream",
                            name = label,
                            url = sUrl,
                            type = linkType
                        ) {
                            this.headers = headers
                            this.quality = quality
                        }
                    )
                    foundLinks = true
                }
            } catch (_: Throwable) {
                // Ignore and fall back to direct base64 decode
            }

            if (foundLinks) return true

            // 2. Direct base64 fallback (e.g. JioHotstar direct m3u8 streams)
            try {
                val b64Payload = data.removePrefix("cnc:").replace("-", "+").replace("_", "/")
                val padLen = (4 - (b64Payload.length % 4)) % 4
                val padded = b64Payload + "=".repeat(padLen)
                val jsonStr = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
                val cleanJson = if (jsonStr.contains("::{")) jsonStr.substringAfter("::") else jsonStr

                val cncObj = AppUtils.parseJson<CncLiveStreamItem>(cleanJson)
                val rawStreamUrl = cncObj.url ?: return false
                val streamUrl = if (rawStreamUrl.contains("140.238.244.130")) {
                    rawStreamUrl.replace("http://140.238.244.130", "https://cncverse.dpdns.org")
                } else rawStreamUrl
                val cookie = cncObj.cookie
                val referer = cncObj.headers?.get("Referer") ?: "https://www.hotstar.com/"
                val origin = cncObj.headers?.get("Origin") ?: "https://www.hotstar.com"
                val ua = cncObj.userAgent ?: "Hotstar;in.startv.hotstar/25.02.24.8.11169@Premium Plugx(Android/15)"

                val headers = mutableMapOf(
                    "User-Agent" to ua,
                    "Referer" to referer,
                    "Origin" to origin
                )
                if (!cookie.isNullOrBlank()) {
                    headers["Cookie"] = cookie
                }

                val isDASH = streamUrl.substringBefore("?").endsWith(".mpd", ignoreCase = true)
                val isM3U8 = streamUrl.substringBefore("?").endsWith(".m3u8", ignoreCase = true)
                val linkType = when {
                    isM3U8 -> ExtractorLinkType.M3U8
                    isDASH -> ExtractorLinkType.DASH
                    streamUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                    streamUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                    else -> ExtractorLinkType.M3U8
                }
                callback(
                    newExtractorLink(
                        source = "Live Stream",
                        name = cncObj.title ?: "Live Stream",
                        url = streamUrl,
                        type = linkType
                    ) {
                        this.headers = headers
                    }
                )
                return true
            } catch (_: Throwable) {
                // fallback to sharedEngine
            }
        }

        return sharedEngine.loadLinks(data, isCasting, subtitleCallback, callback)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CncCatalogResponse(
        @JsonProperty("metas") val metas: List<CncMetaItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CncMetaItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CncStreamResponse(
        @JsonProperty("streams") val streams: List<CncStreamItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CncStreamItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("behaviorHints") val behaviorHints: CncBehaviorHints? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CncBehaviorHints(
        @JsonProperty("proxyHeaders") val proxyHeaders: CncProxyHeaders? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CncProxyHeaders(
        @JsonProperty("request") val request: Map<String, String>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CncLiveStreamItem(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("cookie") val cookie: String? = null,
        @JsonProperty("userAgent") val userAgent: String? = null,
        @JsonProperty("headers") val headers: Map<String, String>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SonyLivTrayResponse(
        @JsonProperty("resultObj") val resultObj: SonyLivResultObj? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SonyLivResultObj(
        @JsonProperty("containers") val containers: List<SonyLivContainer>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SonyLivContainer(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("metadata") val metadata: SonyLivMetadata? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SonyLivMetadata(
        @JsonProperty("contentId") val contentId: Long? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("contentSubtype") val contentSubtype: String? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("pictureUrl") val pictureUrl: String? = null,
        @JsonProperty("emfAttributes") val emfAttributes: SonyLivEmfAttributes? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SonyLivEmfAttributes(
        @JsonProperty("SameAs") val sameAs: List<String>? = null,
        @JsonProperty("img_poster_2000_3000") val imgPoster20003000: String? = null,
        @JsonProperty("image_600x900_clean") val image600x900Clean: String? = null,
        @JsonProperty("apv_poster_art") val apvPosterArt: String? = null,
        @JsonProperty("tv_background_image") val tvBackgroundImage: String? = null,
        @JsonProperty("img_tile_3840_2160") val imgTile38402160: String? = null,
        @JsonProperty("masthead_large_v2") val mastheadLargeV2: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AppleTvShelfResponse(
        @JsonProperty("data") val data: AppleTvShelfData? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AppleTvShelfData(
        @JsonProperty("shelf") val shelf: AppleTvShelfObj? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AppleTvShelfObj(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("items") val items: List<AppleTvShelfItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AppleTvShelfItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("images") val images: AppleTvImages? = null,
        @JsonProperty("releaseDate") val releaseDate: Long? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AppleTvImages(
        @JsonProperty("shelfImage") val shelfImage: AppleTvImageDetails? = null,
        @JsonProperty("transitionImage") val transitionImage: AppleTvImageDetails? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AppleTvImageDetails(
        @JsonProperty("url") val url: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HotstarSlugResponse(
        @JsonProperty("success") val success: HotstarSlugSuccess? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HotstarSlugSuccess(
        @JsonProperty("page") val page: HotstarSlugPage? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HotstarSlugPage(
        @JsonProperty("spaces") val spaces: Map<String, HotstarSlugSpace>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HotstarSlugSpace(
        @JsonProperty("widget_wrappers") val widgetWrappers: List<HotstarWidgetWrapper>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HotstarWidgetWrapper(
        @JsonProperty("template") val template: String? = null,
        @JsonProperty("widget") val widget: HotstarWidget? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HotstarWidget(
        @JsonProperty("data") val data: HotstarWidgetData? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HotstarWidgetData(
        @JsonProperty("header") val header: Map<String, Any?>? = null,
        @JsonProperty("items") val items: List<HotstarItemWrapper>? = null
    )

    data class TmdbResolvedMeta(
        val id: Long,
        val posterUrl: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HotstarItemWrapper(
        @JsonProperty("vertical_content_poster") val verticalPoster: HotstarPosterWrapper? = null,
        @JsonProperty("horizontal_content_card") val horizontalCard: HotstarPosterWrapper? = null,
        @JsonProperty("horizontal_content_poster") val horizontalPoster: HotstarPosterWrapper? = null,
        @JsonProperty("image_overlay_vertical_content_poster") val overlayPoster: HotstarOverlayPosterWrapper? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HotstarOverlayPosterWrapper(
        @JsonProperty("data") val data: HotstarOverlayPosterData? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HotstarOverlayPosterData(
        @JsonProperty("vertical_content_poster") val verticalPoster: HotstarPosterWrapper? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HotstarPosterWrapper(
        @JsonProperty("data") val data: HotstarPosterData? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HotstarPosterData(
        @JsonProperty("content_id") val contentId: String? = null,
        @JsonProperty("image") val image: HotstarImageData? = null,
        @JsonProperty("expanded_content_poster") val expandedPoster: HotstarExpandedPoster? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HotstarImageData(
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("alt") val alt: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HotstarExpandedPoster(
        @JsonProperty("content_info") val contentInfo: HotstarContentInfo? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HotstarContentInfo(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("tags") val tags: List<HotstarTag>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HotstarTag(
        @JsonProperty("value") val value: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JwGraphQLResponse(
        @JsonProperty("data") val data: JwData? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JwData(
        @JsonProperty("popularTitles") val popularTitles: JwPopularTitles? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JwPopularTitles(
        @JsonProperty("totalCount") val totalCount: Int? = null,
        @JsonProperty("edges") val edges: List<JwEdge>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JwEdge(
        @JsonProperty("node") val node: JwNode? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JwNode(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("objectType") val objectType: String? = null,
        @JsonProperty("objectId") val objectId: Long? = null,
        @JsonProperty("content") val content: JwContent? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JwContent(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("originalReleaseYear") val originalReleaseYear: Int? = null,
        @JsonProperty("posterUrl") val posterUrl: String? = null,
        @JsonProperty("shortDescription") val shortDescription: String? = null,
        @JsonProperty("scoring") val scoring: JwScoring? = null,
        @JsonProperty("externalIds") val externalIds: JwExternalIds? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JwScoring(
        @JsonProperty("imdbScore") val imdbScore: Double? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JwExternalIds(
        @JsonProperty("imdbId") val imdbId: String? = null,
        @JsonProperty("tmdbId") val tmdbId: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CinemetaResponse(
        @JsonProperty("meta") val meta: CinemetaMeta? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CinemetaMeta(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("moviedb_id") val moviedbId: Long? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("year") val year: String? = null
    )
}

class NetflixProvider : StremioOttProvider(
    name = "Netflix",
    providerId = "nfx",
    jwPackage = "nfx",
    customMainPage = mainPageOf(
        "SHOW_IN:10:0" to "Top 10 Shows on Netflix Today",
        "MOVIE_IN:10:0" to "Top 10 Movies on Netflix Today",
        "SHOW:15:10" to "Popular Series on Netflix",
        "MOVIE:15:10" to "Popular Movies on Netflix",
        "MOVIE_IN:12:10" to "Bollywood & Indian Cinema on Netflix",
        "SHOW_KOREAN:12" to "K-Dramas & Asian Cinema"
    )
)

class JioHotstarProvider : StremioOttProvider(
    name = "JioHotstar",
    providerId = "jhs",
    jwPackage = "jhs",
    customMainPage = mainPageOf(
        "LIVE_CNC:SPORTS" to "⚡ Live Sports & Star Sports",
        "HOTSTAR_TRAY:home:Top 10 in India Today:10" to "🔟 Top 10 in India Today",
        "LIVE_CNC:BIGGBOSS" to "👁️ Enter the Bigg Boss House",
        "HOTSTAR_TRAY:home:New on JioHotstar:10" to "✨ New on JioHotstar",
        "HOTSTAR_TRAY:shows:Popular Shows:20" to "🔥 Popular Shows on JioHotstar",
        "HOTSTAR_TRAY:movies:Popular Movies:20" to "🎬 Popular Movies on JioHotstar",
        "HOTSTAR_TRAY:movies:Best of Bollywood Movies:15" to "✨ Best of Bollywood Movies",
        "HOTSTAR_TRAY:movies:Best of Hollywood Dubbed:15" to "🌍 Best of Hollywood Dubbed",
        "HOTSTAR_TRAY:movies:Thriller Movies:10" to "🔪 Thriller Movies on JioHotstar",
        "HOTSTAR_TRAY:shows:Must Watch Originals:10" to "⭐ Must Watch Hotstar Specials",
        "HOTSTAR_TRAY:shows:Kids Shows:12" to "🧸 Kids Shows & Animation",
        "HOTSTAR_TRAY:shows:Real Lives, Unlimited Drama:10" to "🎭 Reality & Drama Shows"
    )
)

class PrimeVideoProvider : StremioOttProvider(
    name = "Amazon Prime",
    providerId = "prv",
    jwPackage = "prv",
    customMainPage = mainPageOf(
        "PRIME_TOP_10" to "🔟 Top 10 with Prime Today",
        "PRIME_TOP_MOVIES" to "🎬 Top Movies on Prime Video",
        "PRIME_ORIGINALS" to "✨ Featured Originals on Prime",
        "PRIME_COP" to "🚨 Cop Universe & Crime Thrillers",
        "PRIME_KOREAN" to "🎎 Korean Dramas on Prime",
        "PRIME_SERIES" to "🔥 Popular Series on Prime Video",
        "PRIME_ACTION" to "⚡ Action Blockbusters on Prime"
    )
)

class Zee5Provider : StremioOttProvider(
    name = "Zee5",
    providerId = "zee",
    jwPackage = "zee",
    customMainPage = mainPageOf(
        "SHOW_IN:10:0" to "Top 10 Shows on Zee5 Today",
        "MOVIE_IN:10:0" to "Top 10 Movies on Zee5 Today",
        "SHOW_IN:15:10" to "Popular Zee5 Originals",
        "MOVIE_IN:15:10" to "Popular Movies on Zee5"
    )
)

class SonyLivProvider : StremioOttProvider(
    name = "SonyLIV",
    providerId = "snl",
    jwPackage = "snl",
    customMainPage = mainPageOf(
        "SONY_CNC:SPORTS" to "⚡ Sony Sports Live & Matches",
        "SONY_CNC:ENTERTAINMENT" to "📺 Sony Network Live TV Channels",
        "SONYLIV_TRAY:9014011:15" to "🔥 Trending in India Today",
        "SONYLIV_TRAY:9015093:15" to "✨ Popular SonyLIV Originals",
        "SONYLIV_TRAY:9016638:15" to "🎬 Blockbuster Movies on SonyLIV",
        "SONYLIV_TRAY:POPULAR_SEARCH:15" to "📺 Popular on Sony SAB & TV",
        "SONYLIV_TRAY:3937924064:12" to "🏆 Trending in Sports",
        "SONYLIV_TRAY:9012681:10" to "⏳ Upcoming on LIV"
    )
)

class AppleTvProvider : StremioOttProvider(
    name = "Apple TV+",
    providerId = "atp",
    jwPackage = "atp",
    customMainPage = mainPageOf(
        "APPLE_SHELF:uts.col.ChartsMovies.tvs.sbd.4000:10" to "🔥 Top 10 Movies on Apple TV+ Today",
        "APPLE_SHELF:uts.col.ChartsShows.tvs.sbd.4000:10" to "⭐ Top 10 Shows on Apple TV+ Today",
        "APPLE_SHELF:uts.col.tv-plus-must-see-hits:15" to "✨ Must-See Hits & Blockbusters",
        "APPLE_SHELF:uts.col.tv-plus-newest-releases:15" to "🆕 New Releases on Apple TV+",
        "APPLE_SHELF:uts.col.apple-tv-movies-action:12" to "💥 Action & Thriller Movies",
        "APPLE_SHELF:uts.col.apple-tv-movies-comedies:12" to "🎭 Feel-Good Comedies",
        "APPLE_SHELF:uts.col.apple-tv-movies-dramas:12" to "🏆 Acclaimed Dramas & Originals",
        "APPLE_SHELF:uts.col.kids-tv-plus-shows:12" to "🧸 Kids & Family Shows",
        "APPLE_SHELF:uts.col.free-premieres-on-tv-plus:5" to "🎁 Watch Premieres for Free"
    )
)

class CrunchyrollProvider : StremioOttProvider(
    name = "Crunchyroll",
    providerId = "cru",
    jwPackage = "cru",
    defaultType = TvType.Anime,
    customMainPage = mainPageOf(
        "SHOW:10:0" to "Top 10 Anime Series Today",
        "MOVIE:10:0" to "Top 10 Anime Movies Today",
        "SHOW:15:10" to "Popular Anime Series",
        "MOVIE:15:10" to "Popular Anime Movies"
    )
)
