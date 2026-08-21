package com.kzplayer.app

import android.content.Intent
import android.os.Bundle

class CineNovaReplayActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.browseTitle = "Replay"
        startActivity(
            Intent(this, BrowseActivity::class.java)
                .putExtra("kind", "replay")
        )
        finish()
    }
}
