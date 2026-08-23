package com.kzplayer.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v340 : REPLAY (catch-up) reel.
 *
 * Api.kt n'est PAS modifie : on fait ici notre propre appel EPG (get_simple_data_table)
 * en reutilisant seulement le client HTTP public Api.imageClient(), puis on construit
 * l'URL d'archive timeshift standard Xtream.
 *
 * Format timeshift Xtream :
 *   <serveur>/timeshift/<user>/<pass>/<duree_min>/<yyyy-MM-dd:HH-mm>/<stream_id>.ts
 */
object ReplayApi {

    data class Prog(
        val title: String,
        val time: String,
        val desc: String,
        val startMs: Long,
        val endMs: Long
    )

    private fun enc(s: String): String = try { URLEncoder.encode(s, "UTF-8") } catch (e: Exception) { s }

    private fun clock(ms: Long): String = try {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
    } catch (e: Exception) { "" }

    private fun day(ms: Long): String = try {
        SimpleDateFormat("EEE d MMM", Locale.FRENCH).format(Date(ms))
    } catch (e: Exception) { "" }

    fun label(p: Prog): String = (day(p.startMs) + "  " + clock(p.startMs) + " - " + clock(p.endMs)).trim()

    private fun maybeBase64(raw: String): String {
        val t = raw.trim()
        if (t.isBlank()) return ""
        if (t.length >= 4 && t.length % 4 == 0 && t.matches(Regex("^[A-Za-z0-9+/=]+$"))) {
            try {
                val d = String(android.util.Base64.decode(t, android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
                if (d.isNotBlank()) return d
            } catch (e: Exception) {}
        }
        return t
    }

    /** Programmes DEJA DIFFUSES (les 3 derniers jours), du plus recent au plus ancien. */
    suspend fun programs(pl: Playlist, streamId: String): List<Prog> = withContext(Dispatchers.IO) {
        if (streamId.isBlank()) return@withContext emptyList()
        // v341 : Stalker/MAG -> on reutilise l'EPG portail deja gere par Api (get_short_epg).
        if (pl.type == "stalker") return@withContext stalkerPrograms(pl, streamId)
        if (pl.type != "xtream") return@withContext emptyList()
        val url = pl.serverUrl.trimEnd('/') + "/player_api.php?username=" + enc(pl.username) +
            "&password=" + enc(pl.password) + "&action=get_simple_data_table&stream_id=" + enc(streamId)
        val txt = try {
            Api.imageClient().newCall(Request.Builder().url(url).build()).execute().use { r ->
                r.body?.string() ?: ""
            }
        } catch (e: Exception) { "" }
        if (txt.isBlank()) return@withContext emptyList()
        val arr: JSONArray = try {
            val o = JSONObject(txt)
            o.optJSONArray("epg_listings") ?: o.optJSONArray("data") ?: JSONArray()
        } catch (e: Exception) { JSONArray() }
        val now = System.currentTimeMillis()
        // v361 : on remonte jusqu a 8 jours en arriere (avant : 3 jours).
        val floor = now - 8L * 24L * 3600L * 1000L
        val out = ArrayList<Prog>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = maybeBase64(o.optString("title").ifBlank { o.optString("name") })
            if (title.isBlank()) continue
            val desc = maybeBase64(o.optString("description").ifBlank { o.optString("descr") })
            val start = (o.optString("start_timestamp").toLongOrNull() ?: 0L) * 1000L
            val stop = (o.optString("stop_timestamp").ifBlank { o.optString("end_timestamp") }.toLongOrNull() ?: 0L) * 1000L
            if (start <= 0L || stop <= start) continue
            if (stop > now) continue
            if (start < floor) continue
            val p = Prog(title, "", desc, start, stop)
            out.add(p.copy(time = label(p)))
        }
        out.sortedByDescending { it.startMs }
    }

    /** Si le serveur ne donne pas d'EPG : tranches de 30 min sur les dernieres 24 h. */
    fun fallbackSlots(hours: Int = 24): List<Prog> {
        val out = ArrayList<Prog>()
        val step = 30L * 60L * 1000L
        var end = (System.currentTimeMillis() / step) * step
        var n = hours * 2
        while (n > 0) {
            val start = end - step
            val p = Prog("Archive", "", "", start, end)
            out.add(p.copy(time = label(p)))
            end = start
            n--
        }
        return out
    }

    // v361 : minuit du jour demande (0 = aujourd hui, 1 = hier, ...).
    private fun midnight(offsetDays: Int): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        c.add(java.util.Calendar.DAY_OF_YEAR, -offsetDays)
        return c.timeInMillis
    }

    fun dayLabel(offsetDays: Int): String = when (offsetDays) {
        0 -> "Aujourd hui"
        1 -> "Hier"
        else -> try {
            SimpleDateFormat("EEE d MMM", Locale.FRENCH).format(Date(midnight(offsetDays)))
        } catch (e: Exception) { "J-" + offsetDays }
    }

    // Garde seulement les programmes du jour demande.
    fun filterDay(list: List<Prog>, offsetDays: Int): List<Prog> {
        val from = midnight(offsetDays)
        val to = from + 24L * 3600L * 1000L
        return list.filter { it.startMs >= from && it.startMs < to }
    }

    // Si le serveur ne donne pas de guide pour ce jour : tranches de 30 minutes.
    fun slotsForDay(offsetDays: Int): List<Prog> {
        val step = 30L * 60L * 1000L
        val from = midnight(offsetDays)
        val now = System.currentTimeMillis()
        var end = (from + 24L * 3600L * 1000L).coerceAtMost((now / step) * step)
        val out = ArrayList<Prog>()
        while (end - step >= from && out.size < 48) {
            val start = end - step
            val p = Prog("Archive", "", "", start, end)
            out.add(p.copy(time = label(p)))
            end = start
        }
        return out
    }

    fun timeshiftUrl(pl: Playlist, streamId: String, startMs: Long, durationMin: Int): String {
        val d = if (durationMin < 1) 60 else durationMin
        val stamp = try {
            SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).format(Date(startMs))
        } catch (e: Exception) { "" }
        return pl.serverUrl.trimEnd('/') + "/timeshift/" + enc(pl.username) + "/" + enc(pl.password) +
            "/" + d + "/" + stamp + "/" + streamId + ".ts"
    }

    // v341 : programmes passes d'une chaine Stalker (via l'EPG du portail).
    private suspend fun stalkerPrograms(pl: Playlist, chId: String): List<Prog> {
        val epg = try { Api.stalkerShortEpg(pl, chId) } catch (e: Exception) { emptyList() }
        val now = System.currentTimeMillis()
        // v361 : on remonte jusqu a 8 jours en arriere (avant : 3 jours).
        val floor = now - 8L * 24L * 3600L * 1000L
        val out = ArrayList<Prog>()
        for (e in epg) {
            if (e.startMs <= 0L || e.endMs <= e.startMs) continue
            if (e.endMs > now || e.startMs < floor) continue
            val p = Prog(e.title, "", e.description, e.startMs, e.endMs)
            out.add(p.copy(time = label(p)))
        }
        return out.sortedByDescending { it.startMs }
    }

    /**
     * v341 : URL de l'archive, quel que soit le type de serveur.
     *  - Xtream : URL timeshift standard.
     *  - Stalker/MAG : on demande d'abord le lien direct de la chaine au portail
     *    (Api.stalkerLink, inchange), puis on ajoute les parametres d'archive
     *    utc / lutc acceptes par les portails Stalker (Flussonic / Astra / Ministra).
     *    Aucune modification d'Api.kt ni du protocole Stalker existant.
     */
    suspend fun archiveUrl(pl: Playlist, streamId: String, cmd: String, p: Prog): String {
        if (pl.type == "xtream") return timeshiftUrl(pl, streamId, p.startMs, durationMin(p))
        if (pl.type != "stalker") return ""
        if (cmd.isBlank()) return ""
        val base = try { Api.stalkerLink(pl, cmd, "live") } catch (e: Exception) { null }
        if (base.isNullOrBlank()) return ""
        return withArchiveParams(base, p)
    }

    // Ajoute utc=<debut> & lutc=<maintenant> (+ duree) sans casser les parametres deja presents.
    private fun withArchiveParams(url: String, p: Prog): String {
        val startSec = p.startMs / 1000L
        val nowSec = System.currentTimeMillis() / 1000L
        val durSec = ((p.endMs - p.startMs) / 1000L).coerceAtLeast(60L)
        val clean = url.trim()
        val sep = if (clean.contains("?")) "&" else "?"
        return clean + sep + "utc=" + startSec + "&lutc=" + nowSec + "&duration=" + durSec
    }

    fun durationMin(p: Prog): Int {
        val m = ((p.endMs - p.startMs) / 60000L).toInt()
        return if (m < 1) 30 else m
    }
}
