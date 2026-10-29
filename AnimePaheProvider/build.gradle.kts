version = 79

cloudstream {
    language = "en"
    description = "AnimePahe anime provider with interactive Cloudflare Turnstile verification and Kwik resolvers."
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
        "AnimeMovie",
        "Anime",
        "OVA"
    )
    iconUrl = "https://raw.githubusercontent.com/Adiversion/csext/builds/AnimePahe_icon.png"
}
