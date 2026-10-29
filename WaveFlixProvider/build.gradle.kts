version = 58

cloudstream {
    language = "en"
    description = "WaveFlix TMDB & Multi-OTT streaming engine with individual homepages for Netflix, JioHotstar, Prime Video, Zee5, SonyLIV, Apple TV+, and Crunchyroll powered by 45+ direct scrapers."
    authors = listOf("Cloudstream Community")

    /**
     * Status int:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime"
    )
    iconUrl = "https://raw.githubusercontent.com/Adiversion/csext/builds/WaveFlix_icon.png"
}
