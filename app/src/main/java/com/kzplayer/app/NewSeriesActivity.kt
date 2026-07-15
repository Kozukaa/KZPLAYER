package com.kzplayer.app

import android.content.Intent

class NewSeriesActivity : NtCatalogActivity() {
    override val kind = "series"
    override val navTag = "series"
    override val screenTitle = "S\u00e9ries"
    override fun openItem(item: Item) {
        Session.seriesItem = item
        startActivity(Intent(this, NewSeriesDetailActivity::class.java))
    }
}
