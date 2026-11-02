package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IteraPlayPlugin : Plugin() {
    override fun load(context: Context) {
        // Initialize saved settings if present
        IteraPlayProvider.getSavedCookie(context)
        TeraBoxExtractor.getSavedNdus(context)

        // Register the IteraPlay MainAPI provider
        registerMainAPI(IteraPlayProvider())

        // Register TeraBox extractors for direct and proxy streaming
        registerExtractorAPI(TeraBoxExtractor())
        registerExtractorAPI(TeraBoxComExtractor())
        registerExtractorAPI(TeraBox1024Extractor())
        registerExtractorAPI(TeraBox1024BoxExtractor())
        registerExtractorAPI(TeraBoxLinkExtractor())
    }
}
