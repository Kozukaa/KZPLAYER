package com.kzplayer.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import java.util.Locale
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

    // v368 : dossier ou sont ranges les telechargements de l application.
    fun dir(ctx: Context): java.io.File? =
        try { ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) } catch (e: Exception) { null }

    // v368 : liste des fichiers telecharges, les plus recents en premier.
    fun list(ctx: Context): List<java.io.File> {
        val d = dir(ctx) ?: return emptyList()
        val all = try { d.listFiles() } catch (e: Exception) { null } ?: return emptyList()
        return all.filter { it.isFile && it.length() > 0L }
            .sortedByDescending { it.lastModified() }
    }

    // v368 : suppression d un titre telecharge.
    fun remove(f: java.io.File): Boolean = try { f.delete() } catch (e: Exception) { false }

    // v370 : suppression COMPLETE d un titre.
    // Avant, seul le fichier etait efface : le gestionnaire Android gardait la
    // tache et reecrivait le fichier (le film supprime revenait tout seul).
    // Ici on annule d abord toutes les taches liees a ce fichier, puis on efface.
    fun removeAll(ctx: Context, f: java.io.File): Boolean {
        val nom = f.name
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (dm != null) {
            for (t in tasks(ctx)) {
                if (t.fileName == nom || t.name == nom) {
                    try { dm.remove(t.id) } catch (e: Exception) {}
                }
            }
        }
        // Le gestionnaire peut avoir deja efface le fichier : c est bien aussi.
        if (!f.exists()) return true
        return remove(f)
    }

    // v370 : annulation d une tache ET nettoyage du fichier partiel restant.
    fun cancelTask(ctx: Context, t: Task): Boolean {
        val ok = cancel(ctx, t.id)
        val d = dir(ctx)
        if (d != null && t.fileName.isNotBlank()) {
            val f = java.io.File(d, t.fileName)
            if (f.exists()) remove(f)
        }
        return ok
    }

    // v368 : taille lisible (Mo / Go).
    fun human(bytes: Long): String {
        val mo = bytes.toDouble() / (1024.0 * 1024.0)
        if (mo >= 1024.0) return String.format(Locale.US, "%.2f Go", mo / 1024.0)
        return String.format(Locale.US, "%.0f Mo", mo)
    }

    fun totalSize(ctx: Context): Long = list(ctx).sumOf { it.length() }

    // v369 : un telechargement en cours (gestionnaire Android).
    class Task(
        val id: Long,
        val name: String,
        val status: Int,
        val done: Long,
        val total: Long,
        val fileName: String
    )

    // v369 : liste des telechargements connus du gestionnaire Android.
    fun tasks(ctx: Context): List<Task> {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return emptyList()
        val out = ArrayList<Task>()
        try {
            val c = dm.query(DownloadManager.Query()) ?: return emptyList()
            c.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                    val st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val done = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val tot = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val uri = cur.getString(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)) ?: ""
                    var nom = cur.getString(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: ""
                    val fic = Uri.decode(uri.substringAfterLast(chr47()))
                    if (nom.isBlank()) nom = fic
                    out.add(Task(id, nom, st, done, tot, fic))
                }
            }
        } catch (e: Exception) {}
        return out
    }

    private fun chr47(): Char = 47.toChar()

    // Telechargements pas encore termines (en attente, en cours, en pause, echoues).
    fun pending(ctx: Context): List<Task> = tasks(ctx).filter {
        it.status == DownloadManager.STATUS_PENDING ||
            it.status == DownloadManager.STATUS_RUNNING ||
            it.status == DownloadManager.STATUS_PAUSED ||
            it.status == DownloadManager.STATUS_FAILED
    }

    fun percent(t: Task): Int {
        if (t.total <= 0L) return 0
        val p = (t.done.toDouble() * 100.0 / t.total.toDouble()).toInt()
        return if (p < 0) 0 else if (p > 100) 100 else p
    }

    // Texte affiche sous le titre pendant le telechargement.
    fun statusText(t: Task): String {
        if (t.status == DownloadManager.STATUS_FAILED) return "\u00c9chec du t\u00e9l\u00e9chargement"
        if (t.status == DownloadManager.STATUS_PAUSED) return "En pause  \u2022  " + human(t.done)
        if (t.status == DownloadManager.STATUS_PENDING) return "En attente de d\u00e9marrage..."
        val tot = if (t.total > 0L) human(t.total) else "taille inconnue"
        return "T\u00e9l\u00e9chargement " + percent(t).toString() + " %  \u2022  " +
            human(t.done) + " / " + tot
    }

    // Annulation d un telechargement en cours (supprime aussi le fichier partiel).
    fun cancel(ctx: Context, id: Long): Boolean {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false
        return try { dm.remove(id) > 0 } catch (e: Exception) { false }
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
            "T\u00e9l\u00e9chargement lanc\u00e9 : " + name +
                " - suis la progression dans Param\u00e8tres > T\u00e9l\u00e9chargement"
        } catch (e: Exception) {
            "Téléchargement impossible : " + (e.message ?: "erreur")
        }
    }
}
