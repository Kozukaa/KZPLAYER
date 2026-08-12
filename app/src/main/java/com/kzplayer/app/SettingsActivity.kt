package com.kzplayer.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// Ecran Parametres : menu avec sous-menus (Theme + Couleur + Liste de lecture +
// Recharger + Mise a jour). Le meme ecran est ouvert par les deux themes.
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
        // Mise a jour : carte TOUJOURS visible (jamais masquee) qui affiche la
        // version installee et permet de verifier la derniere version publiee
        // depuis le panel admin.
        findViewById<View>(R.id.updateMenu).setOnClickListener { checkUpdate() }
        findViewById<TextView>(R.id.updateStateTv).text = "Version installee : ${currentVersion()}"
        findViewById<View>(R.id.themeMenu).requestFocus()
    }

    private fun currentVersion(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        val name = info.versionName ?: "1.0"
        "$name (v${currentVersionCode()})"
    } catch (e: Exception) { "1.0" }

    private fun currentVersionCode(): Int = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    } catch (e: Exception) { 0 }

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
                    LicenseGuard.rememberOk(this@SettingsActivity, res.expiration)
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
                } else if (LicenseGuard.wasRecentlyActive(this@SettingsActivity)) {
                    // Fenetre de grace : hoquet backend, on ne dit pas "inactive" a tort.
                    stateTv.text = "Backend indisponible, licence conserv\u00e9e"
                } else {
                    stateTv.text = res.message.ifBlank { "Licence inactive" }
                }
            } catch (e: Exception) {
                stateTv.text = "Erreur de rechargement"
            }
        }
    }

    // Verifie la derniere version publiee via le panel admin (best-effort, ne casse
    // rien si le backend ne connait pas encore cette action).
    private fun checkUpdate() {
        val stateTv = findViewById<TextView>(R.id.updateStateTv)
        val cur = currentVersion()
        stateTv.text = "V\u00e9rification en cours\u2026"
        lifecycleScope.launch {
            try {
                val info = Api.checkForUpdate(
                    DeviceIdentity.licenseCode(this@SettingsActivity), cur, currentVersionCode()
                )
                if (info.hasUpdate && info.downloadUrl.isNotBlank()) {
                    val version = if (info.latestVersion.isNotBlank()) info.latestVersion else "nouvelle"
                    stateTv.text = "Mise a jour disponible : $version"
                    val msg = buildString {
                        append("Version installee : ").append(cur).append("\n")
                        append("Nouvelle version : ").append(version)
                        if (info.notes.isNotBlank()) { append("\n\n").append(info.notes) }
                    }
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Mise \u00e0 jour disponible")
                        .setMessage(msg)
                        .setPositiveButton("T\u00e9l\u00e9charger") { _, _ ->
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl)))
                            } catch (e: Exception) {
                                Toast.makeText(this@SettingsActivity, "Impossible d'ouvrir le lien", Toast.LENGTH_LONG).show()
                            }
                        }
                        .setNegativeButton("Plus tard", null)
                        .show()
                } else if (info.latestVersion.isNotBlank()) {
                    stateTv.text = "A jour (version installee : $cur)"
                    Toast.makeText(this@SettingsActivity, "L'application est \u00e0 jour", Toast.LENGTH_SHORT).show()
                } else {
                    // Backend ne repond pas / action inconnue : on garde la carte visible avec la version.
                    stateTv.text = "Version installee : $cur"
                    Toast.makeText(this@SettingsActivity, "Aucune mise \u00e0 jour trouv\u00e9e", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                stateTv.text = "Version installee : $cur"
                Toast.makeText(this@SettingsActivity, "V\u00e9rification impossible", Toast.LENGTH_SHORT).show()
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
        // Toujours re-afficher la version installee (jamais de carte vide).
        findViewById<TextView>(R.id.updateStateTv).text = "Version installee : ${currentVersion()}"
    }
}
