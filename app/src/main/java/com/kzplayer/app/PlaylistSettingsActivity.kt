package com.kzplayer.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// Sous-menu Parametres : choix de la liste de lecture (serveur actif). Marche sur les 2 themes.
class PlaylistSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist_settings)
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }

        renderPlaylists()
        if (Session.playlists.isEmpty()) {
            lifecycleScope.launch {
                try {
                    val res = Api.checkLicense(
                        DeviceIdentity.stableId(this@PlaylistSettingsActivity),
                        DeviceIdentity.licenseCode(this@PlaylistSettingsActivity),
                        Build.MODEL ?: "Android TV", "1.0"
                    )
                    if (res.ok && res.active) {
                        Session.playlists = res.playlists
                        Session.expiration = res.expiration
                        if (Session.current == null) Session.current = res.playlists.firstOrNull()
                    }
                } catch (e: Exception) {}
                renderPlaylists()
            }
        }
    }

    // Construit la liste cliquable des serveurs. La liste active est marquee d'un point.
    private fun renderPlaylists() {
        val container = findViewById<LinearLayout>(R.id.playlistContainer) ?: return
        container.removeAllViews()
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val mb = (10 * d).toInt()
        if (Session.playlists.isEmpty()) {
            val tv = TextView(this)
            tv.text = "Aucune liste de lecture disponible."
            tv.setTextColor(ContextCompat.getColor(this, R.color.muted))
            tv.textSize = 14f
            container.addView(tv)
            return
        }
        for (pl in Session.playlists) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(pad, pad, pad, pad)
            row.background = ContextCompat.getDrawable(this, R.drawable.bg_tile)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = mb
            row.layoutParams = lp
            row.isClickable = true
            row.isFocusable = true

            val name = TextView(this)
            name.text = pl.nom + if (pl.id == Session.current?.id) "   \u25CF" else ""
            name.setTextColor(ContextCompat.getColor(this, R.color.text))
            name.textSize = 17f
            row.addView(name)

            val sub = TextView(this)
            sub.text = pl.type.uppercase()
            sub.setTextColor(ContextCompat.getColor(this, R.color.muted))
            sub.textSize = 12f
            row.addView(sub)

            row.setOnClickListener { selectPlaylist(pl) }
            container.addView(row)
        }
    }

    // Change la liste active puis relance l'accueil du theme courant pour recharger
    // tout le contenu (chaines, films, series) avec le nouveau serveur.
    private fun selectPlaylist(pl: Playlist) {
        if (pl.id == Session.current?.id) {
            Toast.makeText(this, "Liste d\u00e9j\u00e0 active : ${pl.nom}", Toast.LENGTH_SHORT).show()
            return
        }
        Session.current = pl
        Toast.makeText(this, "Liste active : ${pl.nom}", Toast.LENGTH_SHORT).show()
        val cls = if (ThemePref.isNew(this)) NewLiveActivity::class.java else HomeActivity::class.java
        startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
