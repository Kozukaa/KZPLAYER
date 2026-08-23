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
    // v363 : les serveurs ne parlent pas tous le meme langage pour les archives.
    // On prepare donc plusieurs URL possibles, on les teste vraiment une par une
    // (petite requete HTTP) et on garde la premiere qui renvoie bien de la video.
    // Fini le ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED sur une page d erreur HTML.
    var lastArchiveLog: String = ""

    suspend fun archiveUrl(pl: Playlist, streamId: String, cmd: String, p: Prog): String =
        withContext(Dispatchers.IO) {
            val cands = archiveCandidates(pl, streamId, cmd, p)
            if (cands.isEmpty()) {
                lastArchiveLog = "Aucune URL d archive possible pour cette chaine."
                return@withContext ""
            }
            val tried = ArrayList<String>()
            for (u in cands) {
                val ok = verify(u, p.startMs)
                tried.add((if (ok) "OK   " else "NON  ") + u)
                if (ok) {
                    lastArchiveLog = tried.joinToString(System.lineSeparator())
                    return@withContext u
                }
            }
            lastArchiveLog = tried.joinToString(System.lineSeparator())
            ""
        }

    // Toutes les URL d archive connues pour ce serveur / ce programme.
    private suspend fun archiveCandidates(pl: Playlist, streamId: String, cmd: String, p: Prog): List<String> {
        val out = ArrayList<String>()
        val dur = durationMin(p)
        val srv = pl.serverUrl.trimEnd(chr47())
        val startSec = p.startMs / 1000L
        val nowSec = System.currentTimeMillis() / 1000L
        val durSec = ((p.endMs - p.startMs) / 1000L).coerceAtLeast(60L)
        val stamp = try {
            SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).format(Date(p.startMs))
        } catch (e: Exception) { "" }
        if (pl.type == "xtream") {
            if (streamId.isBlank()) return out
            val u = enc(pl.username)
            val w = enc(pl.password)
            out.add(srv + "/timeshift/" + u + "/" + w + "/" + dur + "/" + stamp + "/" + streamId + ".ts")
            out.add(srv + "/timeshift/" + u + "/" + w + "/" + dur + "/" + stamp + "/" + streamId + ".m3u8")
            out.add(srv + "/streaming/timeshift.php?username=" + u + "&password=" + w +
                "&stream=" + enc(streamId) + "&start=" + enc(stamp) + "&duration=" + dur)
            out.add(srv + "/timeshift.php?username=" + u + "&password=" + w +
                "&stream=" + enc(streamId) + "&start=" + enc(stamp) + "&duration=" + dur)
            return out.distinct()
        }
        if (pl.type != "stalker" || cmd.isBlank()) return out
        val base = try { Api.stalkerLink(pl, cmd, "live") } catch (e: Exception) { null }
        if (base.isNullOrBlank()) return out
        val clean = base.trim()
        // Essai A : parametres utc et lutc - Ministra, Astra, Flussonic
        out.add(withArchiveParams(clean, p))
        val sep = if (clean.contains("?")) "&" else "?"
        out.add(clean + sep + "utc=" + startSec + "&lutc=" + nowSec)
        out.add(clean + sep + "utcstart=" + startSec + "&lutc=" + nowSec)
        // Essai B : formats Flussonic timeshift_abs et archive
        val noQuery = clean.substringBefore("?")
        val slash = noQuery.lastIndexOf(chr47())
        if (slash > 8) {
            val root = noQuery.substring(0, slash)
            out.add(root + "/timeshift_abs-" + startSec + ".m3u8")
            out.add(root + "/timeshift_abs-" + startSec + ".ts")
            out.add(root + "/archive-" + startSec + "-" + durSec + ".m3u8")
            out.add(root + "/archive-" + startSec + "-" + durSec + ".ts")
            out.add(root + "/index-" + startSec + "-" + durSec + ".m3u8")
        }
        return out.distinct()
    }

    private fun chr47(): Char = 47.toChar()

    // v364 : controle STRICT de l archive.
    // Avant, une URL qui repondait "200 OK" etait acceptee... meme quand le serveur
    // ignorait la demande d archive et renvoyait le DIRECT (on cliquait sur lundi 23h
    // et on tombait sur le direct d aujourd hui). Maintenant :
    //  - page d erreur (HTML / JSON) = refuse ;
    //  - playlist HLS : on lit la date reelle du flux (#EXT-X-PROGRAM-DATE-TIME) et on
    //    refuse si elle ne correspond pas au jour demande ; une playlist de direct
    //    (sans date, sans fin de liste) est refusee ;
    //  - flux binaire : accepte seulement sur une vraie adresse d archive
    //    (/timeshift..., timeshift_abs-..., /archive-...).
    private fun verify(url: String, wantStartMs: Long): Boolean = try {
        val req = Request.Builder().url(url)
            .header("Range", "bytes=0-16384")
            .header("User-Agent", "IPTVSmartersPro")
            .build()
        Api.imageClient().newCall(req).execute().use { r ->
            val ct = (r.header("Content-Type") ?: "").lowercase()
            if (!r.isSuccessful) false
            else if (ct.contains("html") || ct.contains("json") || ct.contains("/xml")) false
            else {
                val isPlaylist = ct.contains("mpegurl") || url.contains(".m3u8")
                if (isPlaylist) {
                    val body = try { r.body?.string() ?: "" } catch (e: Exception) { "" }
                    checkPlaylist(body, wantStartMs)
                } else dedicatedArchivePath(url)
            }
        }
    } catch (e: Exception) { false }

    // Une playlist est acceptee seulement si elle prouve qu il s agit bien de l archive
    // du bon jour (date du flux) ou d un enregistrement termine (fin de liste / VOD).
    private fun checkPlaylist(body: String, wantStartMs: Long): Boolean {
        if (body.isBlank()) return false
        if (!body.contains("#EXTM3U")) return false
        val m = Regex("EXT-X-PROGRAM-DATE-TIME:([0-9]{4})-([0-9]{2})-([0-9]{2})").find(body)
        if (m != null) {
            val want = try {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(wantStartMs))
            } catch (e: Exception) { "" }
            val got = m.groupValues[1] + "-" + m.groupValues[2] + "-" + m.groupValues[3]
            if (want.isBlank()) return false
            // meme jour, ou jour voisin (programme a cheval sur minuit / decalage horaire)
            val dayMs = 24L * 3600L * 1000L
            val prev = try { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(wantStartMs - dayMs)) } catch (e: Exception) { "" }
            val next = try { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(wantStartMs + dayMs)) } catch (e: Exception) { "" }
            return got == want || got == prev || got == next
        }
        val vod = body.contains("#EXT-X-ENDLIST") || body.uppercase().contains("PLAYLIST-TYPE:VOD")
        return vod
    }

    // Vraies adresses d archive (elles ne renvoient jamais le direct).
    private fun dedicatedArchivePath(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("/timeshift/") || u.contains("timeshift.php") ||
            u.contains("timeshift_abs-") || u.contains("/archive-")
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
