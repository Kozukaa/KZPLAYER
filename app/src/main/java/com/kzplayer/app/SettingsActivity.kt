package com.kzplayer.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// Ecran Parametres : menu avec 2 sous-menus (Theme + Liste de lecture).
// Le meme ecran est ouvert par les 2 themes.
class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<View>(R.id.themeMenu).setOnClickListener {
            startActivity(Intent(this, ThemeActivity::class.java))
        }
        findViewById<View>(R.id.colorMenu).setOnClickListener {
            startActivity(Intent(this, ColorThemeActivity::class.java))
        }
        findViewById<View>(R.id.playlistMenu).setOnClickListener {
            startActivity(Intent(this, PlaylistSettingsActivity::class.java))
        }
        findViewById<View>(R.id.reloadMenu).setOnClickListener { reloadPlaylists() }
        findViewById<View>(R.id.themeMenu).requestFocus()
    }

    // Recharge la licence + les serveurs (playlists), comme l'ancien bouton "Recharger" de l'accueil.
    private fun reloadPlaylists() {
        val stateTv = findViewById<TextView>(R.id.reloadStateTv)
        stateTv.text = "Rechargement\u2026"
        lifecycleScope.launch {
            try {
                val res = Api.checkLicense(
                    DeviceIdentity.stableId(this@SettingsActivity),
                    DeviceIdentity.licenseCode(this@SettingsActivity),
                    Build.MODEL ?: "Android TV", "1.0"
                )
                if (res.ok && res.active) {
                    Session.playlists = res.playlists
                    Session.expiration = res.expiration
                    if (Session.current == null || Session.playlists.none { it.id == Session.current?.id }) {
                        Session.current = Session.playlists.firstOrNull()
                    }
                    stateTv.text = "Listes recharg\u00e9es \u2713"
                    Toast.makeText(this@SettingsActivity, "Listes de lecture recharg\u00e9es", Toast.LENGTH_SHORT).show()
                    val plName = Session.current?.nom
                    findViewById<TextView>(R.id.playlistStateTv).text =
                        if (!plName.isNullOrBlank()) "Active : $plName" else "Choisir le serveur / la liste active"
                } else {
                    stateTv.text = res.message.ifBlank { "Licence inactive" }
                }
            } catch (e: Exception) {
                stateTv.text = "Erreur de rechargement"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Met a jour les libelles avec les valeurs actuelles (theme + liste active).
        val themeName = if (ThemePref.isNew(this)) "NewTivi" else "Classique"
        findViewById<TextView>(R.id.themeStateTv).text = "Actuel : $themeName"
        findViewById<TextView>(R.id.colorStateTv).text = "Actuel : " + ColorThemePref.current(this).label
        val plName = Session.current?.nom
        findViewById<TextView>(R.id.playlistStateTv).text =
            if (!plName.isNullOrBlank()) "Active : $plName" else "Choisir le serveur / la liste active"
    }
}
