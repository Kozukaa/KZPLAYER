package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView

// Sous-menu Parametres : choix du theme (Classique / NewTivi / Netflix).
class ThemeActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme)
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        val classicState = findViewById<TextView>(R.id.classicState)
        val newState = findViewById<TextView>(R.id.newState)
        val netflixState = findViewById<TextView>(R.id.netflixState)
        val cur = ThemePref.get(this)
        classicState.text = if (cur == ThemePref.CLASSIC) "\u2713 Actuel" else ""
        newState.text = if (cur == ThemePref.NEWTIVI) "\u2713 Actuel" else ""
        netflixState.text = if (cur == ThemePref.NETFLIX) "\u2713 Actuel" else ""
        findViewById<View>(R.id.themeClassic).setOnClickListener { apply(ThemePref.CLASSIC) }
        findViewById<View>(R.id.themeNew).setOnClickListener { apply(ThemePref.NEWTIVI) }
        findViewById<View>(R.id.themeNetflix).setOnClickListener { apply(ThemePref.NETFLIX) }
        findViewById<View>(R.id.themeClassic).requestFocus()
    }

    private fun apply(v: String) {
        ThemePref.set(this, v)
        startActivity(
            Intent(this, ThemePref.homeClass(this))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }
}
