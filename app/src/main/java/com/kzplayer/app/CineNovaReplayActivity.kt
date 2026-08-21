package com.kzplayer.app

import android.os.Bundle
import android.widget.TextView

class CineNovaReplayActivity : BrowseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        intent.putExtra("kind", "replay")
        Session.browseTitle = "Replay"
        super.onCreate(savedInstanceState)
    }
}
