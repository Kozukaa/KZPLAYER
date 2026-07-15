package com.kzplayer.app

import android.content.Intent

class NewMoviesActivity : NtCatalogActivity() {
    override val kind = "movie"
    override val navTag = "movies"
    override val screenTitle = "Films"
    override fun openItem(item: Item) {
        if (item.kind == "series") {
            Session.seriesItem = item
            startActivity(Intent(this, NewSeriesDetailActivity::class.java))
            return
        }
        Session.detailItem = item
        startActivity(Intent(this, DetailActivity::class.java))
    }
}
