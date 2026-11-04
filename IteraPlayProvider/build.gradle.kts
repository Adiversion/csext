version = 19

cloudstream {
    language = "en"
    description = "iTeraPlay viral & premium TeraBox streaming player provider."
    authors = listOf("Adiversion")

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
        "Others"
    )
    iconUrl = "https://raw.githubusercontent.com/Adiversion/csext/builds/IteraPlay_icon.png"
}
