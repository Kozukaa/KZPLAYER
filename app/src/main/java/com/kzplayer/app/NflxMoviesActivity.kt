package com.kzplayer.app

import android.content.Intent

class NflxMoviesActivity : NflxCatalogActivity() {
    override val kind = "movie"
    override val screenTitle = "Films"
    override fun onCardClick(item: Item) {
        if (item.kind == "series") {
            Session.seriesItem = item
            startActivity(Intent(this, NewSeriesDetailActivity::class.java))
            return
        }
        Session.detailItem = item
        startActivity(Intent(this, DetailActivity::class.java))
    }
}
