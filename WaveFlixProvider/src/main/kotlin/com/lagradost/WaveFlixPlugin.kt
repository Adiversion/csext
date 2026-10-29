package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WaveFlixPlugin : Plugin() {
    override fun load(context: Context) {
        // Core shared provider engine (must be registered in APIHolder so Player link generators can resolve it)
        registerMainAPI(WaveFlixProvider())

        // Dedicated OTT platform providers with individual homepages & catalogs
        registerMainAPI(NetflixProvider())
        registerMainAPI(JioHotstarProvider())
        registerMainAPI(PrimeVideoProvider())
        registerMainAPI(Zee5Provider())
        registerMainAPI(SonyLivProvider())
        registerMainAPI(AppleTvProvider())
        registerMainAPI(CrunchyrollProvider())

        // Register direct Indian CDN & Cloud Extractors
        registerExtractorAPI(VCloudExtractor())
        registerExtractorAPI(VCloudLolExtractor())
        registerExtractorAPI(VCloudCxExtractor())
        registerExtractorAPI(NexdriveExtractor())
        registerExtractorAPI(NexdriveYouExtractor())
        registerExtractorAPI(NexdriveStoreExtractor())
        registerExtractorAPI(FastDLExtractor())
        registerExtractorAPI(FastDlBeerExtractor())
        registerExtractorAPI(FastDlLolExtractor())
        registerExtractorAPI(ZeeDlExtractor())
        registerExtractorAPI(DriveSeedExtractor())
        registerExtractorAPI(HubdriveExtractor())
        registerExtractorAPI(HubcloudExtractor())
        registerExtractorAPI(HubcloudCxExtractor())
        registerExtractorAPI(HubcloudClubExtractor())
        registerExtractorAPI(HubcloudLolExtractor())
        registerExtractorAPI(BollydriveExtractor())
        registerExtractorAPI(BollydriveMomExtractor())
        registerExtractorAPI(CineverseExtractor())
        registerExtractorAPI(FilmuExtractor())
        registerExtractorAPI(FastDlServerExtractor())
        registerExtractorAPI(FastDlSiteExtractor())
        registerExtractorAPI(HubdrivePicsExtractor())
        registerExtractorAPI(HubdriveLolExtractor())
        registerExtractorAPI(HubdriveMeExtractor())
        registerExtractorAPI(HubdriveProExtractor())
        registerExtractorAPI(HubdriveInExtractor())
        registerExtractorAPI(HubdriveClubExtractor())
        registerExtractorAPI(HubcloudPicsExtractor())
        registerExtractorAPI(HubcloudBarExtractor())
        registerExtractorAPI(VidboltExtractor())
        registerExtractorAPI(VidriftExtractor())
        registerExtractorAPI(HanerixExtractor())
        registerExtractorAPI(MinochinosExtractor())

        // KatMovieHD auxiliary extractors
        registerExtractorAPI(FuckingfastExtractor())
        registerExtractorAPI(KatdriveExtractor())

        // HDHub4u & Greenmotors auxiliary extractors
        registerExtractorAPI(GreenmotorsExtractor())
        registerExtractorAPI(GreenmotorsClubExtractor())
        registerExtractorAPI(HblinksExtractor())
        registerExtractorAPI(HubcdnExtractor())
        registerExtractorAPI(HubcdnWikiExtractor())
    }
}
