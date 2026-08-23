package com.kzplayer.app

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.widget.Toast
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// v359 : telechargement des films et episodes.
// v372 : on n utilise PLUS le gestionnaire de telechargement d Android.
// Il ne supporte pas les serveurs IPTV : il affichait "En attente de connexion",
// "Echec du telechargement" ou "En attente pour reessayer" en boucle.
// Tout passe maintenant par DownloadService (telechargeur interne KZ Player)
// qui reprend exactement la ou il s est arrete et refabrique le lien du serveur
// quand le jeton expire. L affichage de l ecran Telechargement ne change pas.
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
            val msg = enqueue(ctx, item.name, direct, item.cmd ?: "")
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
            val msg = enqueue(ctx, item.name, link ?: "", cmd)
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

    // v370 / v372 : suppression COMPLETE d un titre (tache annulee puis fichier efface,
    // pour que le titre supprime ne revienne pas tout seul).
    fun removeAll(ctx: Context, f: java.io.File): Boolean {
        DownloadService.annuler(f.name)
        if (!f.exists()) return true
        return remove(f)
    }

    // v370 : annulation d une tache ET nettoyage du fichier partiel restant.
    fun cancelTask(ctx: Context, t: Task): Boolean {
        DownloadService.annuler(t.fileName)
        val d = dir(ctx)
        if (d != null && t.fileName.isNotBlank()) {
            val f = java.io.File(d, t.fileName)
            if (f.exists()) remove(f)
        }
        return true
    }

    // v368 : taille lisible (Mo / Go).
    fun human(bytes: Long): String {
        val mo = bytes.toDouble() / (1024.0 * 1024.0)
        if (mo >= 1024.0) return String.format(Locale.US, "%.2f Go", mo / 1024.0)
        return String.format(Locale.US, "%.0f Mo", mo)
    }

    fun totalSize(ctx: Context): Long = list(ctx).sumOf { it.length() }

    // v369 : un telechargement en cours.
    class Task(
        val id: Long,
        val name: String,
        val status: Int,
        val done: Long,
        val total: Long,
        val fileName: String
    )

    // v372 : les taches viennent maintenant de notre propre telechargeur.
    fun tasks(ctx: Context): List<Task> {
        val out = ArrayList<Task>()
        try {
            for (p in DownloadService.jobs.values) {
                out.add(
                    Task(
                        p.fileName.hashCode().toLong(),
                        p.title,
                        p.status,
                        p.done,
                        p.total,
                        p.fileName
                    )
                )
            }
        } catch (e: Exception) {}
        return out
    }

    // Telechargements pas encore termines (en attente, en cours, reprise, echoues).
    fun pending(ctx: Context): List<Task> = tasks(ctx).filter {
        it.status == DownloadService.ST_PENDING ||
            it.status == DownloadService.ST_RUNNING ||
            it.status == DownloadService.ST_PAUSED ||
            it.status == DownloadService.ST_FAILED
    }

    fun percent(t: Task): Int {
        if (t.total <= 0L) return 0
        val p = (t.done.toDouble() * 100.0 / t.total.toDouble()).toInt()
        return if (p < 0) 0 else if (p > 100) 100 else p
    }

    // Texte affiche sous le titre pendant le telechargement.
    fun statusText(t: Task): String {
        if (t.status == DownloadService.ST_FAILED) return "\u00c9chec du t\u00e9l\u00e9chargement"
        if (t.status == DownloadService.ST_PAUSED)
            return "Reprise en cours...  \u2022  " + human(t.done) + " d\u00e9j\u00e0 re\u00e7us"
        if (t.status == DownloadService.ST_PENDING) return "En attente de d\u00e9marrage..."
        val tot = if (t.total > 0L) human(t.total) else "taille inconnue"
        return "T\u00e9l\u00e9chargement " + percent(t).toString() + " %  \u2022  " +
            human(t.done) + " / " + tot
    }

    // ---------------------------------------------------------------------
    // v372 : memoire de la source d un telechargement (pour relancer plus tard).
    // ---------------------------------------------------------------------
    private const val PREF = "kz_dl_src"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun rememberSource(ctx: Context, fileName: String, title: String, url: String, cmd: String) {
        if (fileName.isBlank()) return
        try {
            prefs(ctx).edit()
                .putString("t_" + fileName, title)
                .putString("u_" + fileName, url)
                .putString("c_" + fileName, cmd)
                .apply()
        } catch (e: Exception) {}
    }

    /**
     * Relance un telechargement : le telechargeur interne reprend le fichier
     * la ou il s etait arrete, avec un lien tout neuf si besoin.
     */
    fun retryTask(ctx: Context, t: Task, notify: Boolean = false) {
        val fic = t.fileName
        val p = try { prefs(ctx) } catch (e: Exception) { null }
        val titre = p?.getString("t_" + fic, null) ?: t.name
        val url = p?.getString("u_" + fic, null) ?: ""
        val cmd = p?.getString("c_" + fic, null) ?: ""
        val pl = Session.current
        if (cmd.isNotBlank() && pl != null) {
            if (notify) toast(ctx, "Reprise du t\u00e9l\u00e9chargement...")
            CoroutineScope(Dispatchers.Main).launch {
                val frais = try { Api.stalkerLink(pl, cmd, "movie") } catch (e: Exception) { null }
                val bon = if (!frais.isNullOrBlank()) frais else url
                if (bon.isBlank()) {
                    if (notify) toast(ctx, "Impossible de relancer : refais-le depuis la fiche du titre.")
                    return@launch
                }
                rememberSource(ctx, fic, titre, bon, cmd)
                DownloadService.demarrer(ctx, fic, titre, bon, cmd)
            }
            return
        }
        if (url.isBlank()) {
            if (notify) toast(ctx, "Impossible de relancer : refais-le depuis la fiche du titre.")
            return
        }
        if (notify) toast(ctx, "Reprise du t\u00e9l\u00e9chargement...")
        DownloadService.demarrer(ctx, fic, titre, url, cmd)
    }

    /**
     * v372 : le telechargeur interne reprend deja tout seul sur coupure.
     * Ici on ne relance que les rares taches vraiment abandonnees.
     */
    fun autoRetryFailed(ctx: Context): Boolean {
        var relance = false
        for (t in tasks(ctx)) {
            if (t.status != DownloadService.ST_FAILED) continue
            retryTask(ctx, t, notify = false)
            relance = true
        }
        return relance
    }

    /**
     * v372 : nettoyage des anciennes taches laissees par le gestionnaire Android
     * des versions precedentes (celles qui tournaient en boucle sur
     * "En attente pour reessayer"). Appele a l ouverture de l ecran.
     */
    fun purgeLegacy(ctx: Context) {
        try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
            val c = dm.query(DownloadManager.Query()) ?: return
            val ids = ArrayList<Long>()
            c.use { cur ->
                while (cur.moveToNext()) {
                    val st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (st == DownloadManager.STATUS_SUCCESSFUL) continue
                    ids.add(cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)))
                }
            }
            for (id in ids) try { dm.remove(id) } catch (e: Exception) {}
        } catch (e: Exception) {}
    }

    // Renvoie le message a afficher a l utilisateur.
    // cmd : commande du serveur (Stalker) qui permet de refabriquer un lien frais
    // si le jeton expire pendant le telechargement.
    fun enqueue(ctx: Context, title: String, url: String, cmd: String = ""): String {
        if (url.isBlank()) return "Lien de t\u00e9l\u00e9chargement introuvable."
        if (url.contains(".m3u8")) return "Ce contenu est diffus\u00e9 en direct : t\u00e9l\u00e9chargement impossible."
        return try {
            val name = safeName(title, url)
            val titre = if (title.isBlank()) name else title
            rememberSource(ctx, name, titre, url, cmd)
            DownloadService.demarrer(ctx, name, titre, url, cmd)
            "T\u00e9l\u00e9chargement lanc\u00e9 : " + name +
                " - suis la progression dans Param\u00e8tres > T\u00e9l\u00e9chargement"
        } catch (e: Exception) {
            "T\u00e9l\u00e9chargement impossible : " + (e.message ?: "erreur")
        }
    }
}
