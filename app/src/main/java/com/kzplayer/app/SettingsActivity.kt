package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

// Ecran Parametres : choix du theme (Classique / NewTivi).
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        val classicState = findViewById<TextView>(R.id.classicState)
        val newState = findViewById<TextView>(R.id.newState)
        fun mark() {
            val cur = ThemePref.get(this)
            classicState.text = if (cur == ThemePref.CLASSIC) "\u2713 Actuel" else ""
            newState.text = if (cur == ThemePref.NEWTIVI) "\u2713 Actuel" else ""
        }
        mark()
        findViewById<View>(R.id.themeClassic).setOnClickListener { apply(ThemePref.CLASSIC) }
        findViewById<View>(R.id.themeNew).setOnClickListener { apply(ThemePref.NEWTIVI) }
        findViewById<View>(R.id.themeClassic).requestFocus()
    }

    private fun apply(v: String) {
        ThemePref.set(this, v)
        val cls = if (v == ThemePref.NEWTIVI) NewLiveActivity::class.java else HomeActivity::class.java
        startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
