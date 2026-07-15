package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

// Ecran Parametres : menu avec 2 sous-menus (Theme + Liste de lecture).
// Le meme ecran est ouvert par les 2 themes.
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<View>(R.id.themeMenu).setOnClickListener {
            startActivity(Intent(this, ThemeActivity::class.java))
        }
        findViewById<View>(R.id.playlistMenu).setOnClickListener {
            startActivity(Intent(this, PlaylistSettingsActivity::class.java))
        }
        findViewById<View>(R.id.themeMenu).requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // Met a jour les libelles avec les valeurs actuelles (theme + liste active).
        val themeName = if (ThemePref.isNew(this)) "NewTivi" else "Classique"
        findViewById<TextView>(R.id.themeStateTv).text = "Actuel : $themeName"
        val plName = Session.current?.nom
        findViewById<TextView>(R.id.playlistStateTv).text =
            if (!plName.isNullOrBlank()) "Active : $plName" else "Choisir le serveur / la liste active"
    }
}
