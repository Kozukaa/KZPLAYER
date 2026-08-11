package com.kzplayer.app

import android.content.Intent

// Ecran SERIES style Netflix (autonome). Ouvre la fiche serie existante (saisons/episodes).
class NflxSeriesActivity : NflxCatalogActivity() {
    override val kind = "series"
    override val screenTitle = "S\u00e9ries"
    override fun onCardClick(item: Item) {
        Session.seriesItem = item
        startActivity(Intent(this, NewSeriesDetailActivity::class.java))
    }
}
