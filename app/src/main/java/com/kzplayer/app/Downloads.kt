package com.kzplayer.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// v359 : telechargement des films et episodes via le gestionnaire de
// telechargement d Android. La copie continue meme si on quitte l ecran.
// Le fichier est range dans le dossier Films de l application.
object Downloads {
    private fun safeName(title: String, url: String): String {
        var base = title.trim()
        if (base.isBlank()) base = "video"
        base = base.replace(Regex("[^A-Za-z0-9 ._-]"), " ").replace(Regex(" +"), " ").trim().take(80)
        val clean = url.substringBefore("?")
        val ext = when {
            clean.endsWith(".mkv", true) -> "mkv"
            clean.endsWith(".avi", true) -> "avi"
            clean.endsWith(".ts", true) -> "ts"
            else -> "mp4"
        }
        return base + "." + ext
    }

    private fun toast(ctx: Context, msg: String) {
        try { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() } catch (e: Exception) {}
    }

    // v359 : telechargement direct depuis une fiche (film ou episode de serie).
    // Resout d abord le lien du serveur (Stalker) puis lance le telechargement.
    fun requestItem(ctx: Context, item: Item, notify: Boolean = true) {
        val direct = item.directUrl
        if (!direct.isNullOrBlank()) {
            val msg = enqueue(ctx, item.name, direct)
            if (notify) toast(ctx, msg)
            return
        }
        val pl = Session.current
        val cmd = item.cmd
        if (pl == null || cmd.isNullOrBlank()) {
            toast(ctx, "Lien de t\u00e9l\u00e9chargement introuvable pour ce titre.")
            return
        }
        if (notify) toast(ctx, "Pr\u00e9paration du t\u00e9l\u00e9chargement...")
        CoroutineScope(Dispatchers.Main).launch {
            val link = try { Api.stalkerLink(pl, cmd, "movie") } catch (e: Exception) { null }
            val msg = enqueue(ctx, item.name, link ?: "")
            if (notify) toast(ctx, msg)
        }
    }

    // Renvoie le message a afficher a l utilisateur.
    fun enqueue(ctx: Context, title: String, url: String): String {
        if (url.isBlank()) return "Lien de téléchargement introuvable."
        if (url.contains(".m3u8")) return "Ce contenu est diffusé en direct : téléchargement impossible."
        return try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (dm == null) return "Téléchargement indisponible sur cet appareil."
            val name = safeName(title, url)
            val req = DownloadManager.Request(Uri.parse(url))
            req.addRequestHeader("User-Agent", Config.USER_AGENT)
            req.setTitle(if (title.isBlank()) name else title)
            req.setDescription("KZ Player")
            req.setAllowedOverRoaming(false)
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            req.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_MOVIES, name)
            dm.enqueue(req)
            "Téléchargement lancé : " + name
        } catch (e: Exception) {
            "Téléchargement impossible : " + (e.message ?: "erreur")
        }
    }
}
