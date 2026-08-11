package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.view.View

// Accueil du theme Netflix : grande banniere + rangee de tuiles.
// 100% autonome : ses tuiles ouvrent les ecrans Netflix (TV / Films / Series) et les ecrans partages.
class NetflixHomeActivity : NtBase() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_netflix_home)
        wire(R.id.heroPlayTv) { open(NflxLiveActivity::class.java) }
        wire(R.id.heroMovies) { open(NflxMoviesActivity::class.java) }
        wire(R.id.tileTv) { open(NflxLiveActivity::class.java) }
        wire(R.id.tileMovies) { open(NflxMoviesActivity::class.java) }
        wire(R.id.tileSeries) { open(NflxSeriesActivity::class.java) }
        wire(R.id.tileGuide) { open(NewGuideActivity::class.java) }
        wire(R.id.tileVoice) { open(VoiceActivity::class.java) }
        wire(R.id.tileSettings) { open(SettingsActivity::class.java) }
        wire(R.id.tileTheme) { open(ThemeActivity::class.java) }
        ensureSession { }
        findViewById<View>(R.id.heroPlayTv).requestFocus()
    }

    private fun open(cls: Class<*>) { startActivity(Intent(this, cls)) }

    private fun wire(id: Int, onClick: () -> Unit) {
        val v = findViewById<View>(id) ?: return
        v.setOnClickListener { onClick() }
        v.setOnFocusChangeListener { view, has ->
            val s = if (has) 1.06f else 1f
            view.animate().scaleX(s).scaleY(s).setDuration(120).start()
            view.translationZ = if (has) 12f else 0f
        }
    }
}
