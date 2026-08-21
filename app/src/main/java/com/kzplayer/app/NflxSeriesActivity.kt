package com.kzplayer.app

import android.content.Intent

open class NflxSeriesActivity : NflxCatalogActivity() {
    override val kind = "series"
    override val screenTitle = "Séries"
    override fun onCardClick(item: Item) {
        Session.seriesItem = item
        startActivity(Intent(this, NewSeriesDetailActivity::class.java))
    }
}
