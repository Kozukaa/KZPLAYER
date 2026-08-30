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
class PlaylistSettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist_settings)
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.licenseTv).text = DeviceIdentity.licenseCode(this)

        renderPlaylists()
        checkHealth()
        if (Session.playlists.isEmpty()) {
            lifecycleScope.launch {
                try {
                    val res = Api.checkLicense(
                        DeviceIdentity.stableId(this@PlaylistSettingsActivity),
                        DeviceIdentity.licenseCode(this@PlaylistSettingsActivity),
                        Build.MODEL ?: "Android TV", "1.0"
                    )
                    if (res.ok && res.active) {
                        Session.playlists = LocalPlaylists.merge(res.playlists)
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

        // v391 : ajout manuel d une liste de lecture depuis l application.
        val addBtn = TextView(this)
        addBtn.text = "+   Ajouter une liste de lecture"
        addBtn.setTextColor(ContextCompat.getColor(this, R.color.text))
        addBtn.textSize = 16f
        addBtn.setPadding(pad, pad, pad, pad)
        addBtn.background = ContextCompat.getDrawable(this, R.drawable.bg_ghost_btn)
        val alp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        alp.bottomMargin = mb
        addBtn.layoutParams = alp
        addBtn.isClickable = true
        addBtn.isFocusable = true
        addBtn.setOnClickListener { startActivity(Intent(this, AddPlaylistActivity::class.java)) }
        container.addView(addBtn)
        FocusFx.apply(addBtn)

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


            // v391 : etat de la liste (active / expiree / ne repond plus).
            val healthTxt = PlaylistHealth.label(this, pl.id)
            if (healthTxt.isNotBlank()) {
                val stTv = TextView(this)
                stTv.text = healthTxt
                stTv.setTextColor(if (PlaylistHealth.isProblem(this, pl.id)) 0xFFFF6B6B.toInt() else 0xFF4CD07D.toInt())
                stTv.textSize = 12f
                row.addView(stTv)
            }
            if (LocalPlaylists.isLocal(pl.id)) {
                val locTv = TextView(this)
                locTv.text = "Ajout\u00e9e depuis l application \u2022 appui long pour supprimer"
                locTv.setTextColor(ContextCompat.getColor(this, R.color.muted))
                locTv.textSize = 11f
                row.addView(locTv)
                row.setOnLongClickListener {
                    LocalPlaylists.remove(this, pl.id)
                    Session.playlists = Session.playlists.filter { it.id != pl.id }
                    if (Session.current?.id == pl.id) Session.current = Session.playlists.firstOrNull()
                    Toast.makeText(this, "Liste supprim\u00e9e", Toast.LENGTH_SHORT).show()
                    renderPlaylists()
                    true
                }
            }
            FocusFx.apply(row)

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
        val cls = ThemePref.homeClass(this)
        startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    override fun onResume() {
        super.onResume()
        renderPlaylists()
    }

    // v391 : verifie chaque liste (expiree / hors service), le signale dans l app
    // et l envoie au panel utilisateur.
    private fun checkHealth() {
        val lic = DeviceIdentity.licenseCode(this)
        for (pl in Session.playlists.toList()) {
            lifecycleScope.launch {
                val res = try { Api.playlistHealth(pl) } catch (e: Exception) { Pair(PlaylistHealth.DOWN, "") }
                PlaylistHealth.set(this@PlaylistSettingsActivity, pl.id, res.first, res.second)
                if (res.first != PlaylistHealth.OK) {
                    try { Api.reportPlaylistStatus(lic, pl.id, res.first, res.second) } catch (e: Exception) {}
                    if (pl.id == Session.current?.id) {
                        Toast.makeText(
                            this@PlaylistSettingsActivity,
                            "Attention : " + PlaylistHealth.label(this@PlaylistSettingsActivity, pl.id),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                renderPlaylists()
            }
        }
    }
}
