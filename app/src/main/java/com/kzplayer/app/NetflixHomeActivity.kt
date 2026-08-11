package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator

// Theme 3 "Netflix" : ecran d'accueil facon Netflix (fond noir, accent rouge,
// grande banniere en haut + rangees horizontales de grandes tuiles).
// IMPORTANT : ne touche NI au lecteur, NI aux flux (Stalker/M3U/Xtream), NI a la
// licence, NI a la protection. Chaque tuile ouvre simplement les ecrans existants
// qui fonctionnent deja (TV / Films / Series / Guide / Reglages...).
class NetflixHomeActivity : NtBase() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_netflix_home)
        ensureSession { }

        wire(R.id.nflxPlayTv) { open(NewLiveActivity::class.java) }
        wire(R.id.nflxHeroMovies) { open(NewMoviesActivity::class.java) }

        wire(R.id.tileTv) { open(NewLiveActivity::class.java) }
        wire(R.id.tileGuide) { open(NewGuideActivity::class.java) }
        wire(R.id.tileMovies) { open(NewMoviesActivity::class.java) }
        wire(R.id.tileSeries) { open(NewSeriesActivity::class.java) }
        wire(R.id.tileSettings) { open(SettingsActivity::class.java) }
        wire(R.id.tileTheme) { open(ThemeActivity::class.java) }
        wire(R.id.tileUpdate) { open(UpdateActivity::class.java) }
        wire(R.id.tileVoice) { open(VoiceActivity::class.java) }

        findViewById<View?>(R.id.nflxPlayTv)?.requestFocus()
    }

    private fun open(cls: Class<*>) { startActivity(Intent(this, cls)) }

    // Effet Netflix : l'element cible grossit legerement quand la telecommande le vise.
    private fun wire(id: Int, onClick: () -> Unit) {
        val v = findViewById<View?>(id) ?: return
        v.setOnClickListener { onClick() }
        v.onFocusChangeListener = View.OnFocusChangeListener { view, has ->
            val s = if (has) 1.06f else 1f
            view.animate().scaleX(s).scaleY(s).setDuration(140)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }
}
