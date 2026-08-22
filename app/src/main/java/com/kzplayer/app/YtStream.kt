package com.kzplayer.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * v346 : bande-annonce lue DANS le lecteur KZ (Media3), sans WebView ni barre YouTube.
 *
 * Plus aucune dependance a TMDB pour TROUVER la video : on cherche directement
 * sur YouTube ("titre bande annonce VF"), on prend le premier resultat, puis on
 * recupere son vrai flux video (HLS ou mp4 complet) et on le donne au lecteur.
 *
 * lastError retient l etape qui a echoue, pour pouvoir diagnostiquer.
 */
object YtStream {

    private const val KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val PLAYER = "https://www.youtube.com/youtubei/v1/player"
    private const val SEARCH = "https://www.youtube.com/youtubei/v1/search"
    private const val UA_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    /** Flux pret a lire : URL + User-Agent a utiliser pour le telecharger. */
    data class Stream(val url: String, val ua: String)

    /** Derniere raison d echec, affichee a l utilisateur pour diagnostic. */
    @Volatile var lastError: String = ""

    private val streamCache = ConcurrentHashMap<String, Stream>()
    private val searchCache = ConcurrentHashMap<String, String>()

    // ---------------------------------------------------------------- recherche

    /**
     * Cherche une bande-annonce pour ce titre et renvoie un flux lisible.
     * Essaie plusieurs variantes du titre et plusieurs formulations de recherche.
     */
    suspend fun findTrailer(rawName: String): Stream? = withContext(Dispatchers.IO) {
        lastError = ""
        val cands = Tmdb.titleCandidates(rawName)
        val names = if (cands.isEmpty()) listOf(rawName.trim()) else cands
        var searched = false
        for (n in names.take(3)) {
            if (n.length < 2) continue
            for (suffix in listOf(" bande annonce VF", " bande annonce", " official trailer")) {
                val vid = try { searchVideoId(n + suffix) } catch (_: Exception) { "" }
                if (vid.isNotBlank()) {
                    searched = true
                    val s = resolveId(vid)
                    if (s != null) return@withContext s
                }
            }
        }
        if (lastError.isBlank()) {
            lastError = if (searched) "flux illisible" else "recherche sans resultat"
        }
        null
    }

    /** Recherche YouTube : renvoie l identifiant de la premiere video trouvee. */
    private fun searchVideoId(query: String): String {
        searchCache[query]?.let { return it }
        val cl = JSONObject()
        cl.put("clientName", "WEB")
        cl.put("clientVersion", "2.20240726.00.00")
        cl.put("hl", "fr")
        cl.put("gl", "FR")
        val root = JSONObject()
        root.put("context", JSONObject().put("client", cl))
        root.put("query", query)
        // Filtre : videos uniquement.
        root.put("params", "EgIQAQ%3D%3D")
        val req = Request.Builder()
            .url(SEARCH + "?key=" + KEY + "&prettyPrint=false")
            .header("User-Agent", UA_WEB)
            .header("Accept", "*/*")
            .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .header("X-Youtube-Client-Name", "1")
            .header("X-Youtube-Client-Version", "2.20240726.00.00")
            .post(root.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val txt = Api.imageClient().newCall(req).execute().use { r ->
            if (!r.isSuccessful) { lastError = "recherche HTTP " + r.code; return "" }
            r.body?.string() ?: ""
        }
        if (txt.isBlank()) { lastError = "recherche vide"; return "" }
        // Extraction directe du premier identifiant de video (structure YouTube instable).
        val id = firstVideoId(txt)
        if (id.isNotBlank()) searchCache[query] = id
        return id
    }

    /** Trouve le premier identifiant de video dans une reponse JSON brute. */
    private fun firstVideoId(txt: String): String {
        val marker = "\"videoId\":\""
        var from = 0
        while (true) {
            val i = txt.indexOf(marker, from)
            if (i < 0) return ""
            val s = i + marker.length
            val e = txt.indexOf("\"", s)
            if (e < 0) return ""
            val id = txt.substring(s, e)
            if (id.length == 11) return id
            from = e
        }
    }

    // ---------------------------------------------------------------- resolution

    /** Extrait l identifiant video d une URL YouTube (watch, youtu.be, embed, shorts). */
    fun videoId(raw: String): String {
        val s = raw.trim()
        if (s.isBlank()) return ""
        if (!s.contains("/") && !s.contains("=") && s.length in 8..20) return s
        var id = ""
        if (s.contains("v=")) id = s.substringAfter("v=").substringBefore("&").substringBefore("#")
        if (id.isBlank() && s.contains("youtu.be/")) id = s.substringAfter("youtu.be/").substringBefore("?").substringBefore("&")
        if (id.isBlank() && s.contains("/embed/")) id = s.substringAfter("/embed/").substringBefore("?").substringBefore("&")
        if (id.isBlank() && s.contains("/shorts/")) id = s.substringAfter("/shorts/").substringBefore("?").substringBefore("&")
        return id.trim()
    }

    /** Renvoie un flux lisible par Media3 a partir d une URL YouTube. */
    suspend fun resolve(rawUrl: String): Stream? = withContext(Dispatchers.IO) {
        val id = videoId(rawUrl)
        if (id.isBlank()) { lastError = "lien invalide"; return@withContext null }
        resolveId(id)
    }

    private fun resolveId(id: String): Stream? {
        streamCache[id]?.let { return it }
        for (c in clients) {
            val s = try { ask(id, c) } catch (e: Exception) {
                lastError = "reseau (" + (e.message ?: "inconnu") + ")"
                null
            }
            if (s != null) { streamCache[id] = s; return s }
        }
        return null
    }

    private class Cli(
        val name: String,
        val id: Int,
        val version: String,
        val ua: String,
        val preferHls: Boolean,
        val extra: Map<String, Any>
    )

    private val clients: List<Cli> = listOf(
        // Client iOS : renvoie un manifeste HLS, le plus fiable, deja gere par le lecteur.
        Cli(
            "IOS", 5, "19.29.1",
            "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)",
            true,
            mapOf(
                "deviceMake" to "Apple",
                "deviceModel" to "iPhone16,2",
                "osName" to "iPhone",
                "osVersion" to "17.5.1.21F90"
            )
        ),
        // Client Android TV : mp4 complets, tres permissif.
        Cli(
            "ANDROID", 3, "19.30.36",
            "com.google.android.youtube/19.30.36 (Linux; U; Android 13) gzip",
            false,
            mapOf("androidSdkVersion" to 33, "osName" to "Android", "osVersion" to "13")
        ),
        // Dernier recours : client web.
        Cli("WEB", 1, "2.20240726.00.00", UA_WEB, false, emptyMap())
    )

    private fun ask(id: String, c: Cli): Stream? {
        // Corps JSON construit avec JSONObject (aucun risque de guillemets mal echappes).
        val cl = JSONObject()
        cl.put("clientName", c.name)
        cl.put("clientVersion", c.version)
        cl.put("hl", "fr")
        cl.put("gl", "FR")
        for ((k, v) in c.extra) cl.put(k, v)
        val root = JSONObject()
        root.put("context", JSONObject().put("client", cl))
        root.put("videoId", id)
        root.put("contentCheckOk", true)
        root.put("racyCheckOk", true)

        val req = Request.Builder()
            .url(PLAYER + "?key=" + KEY + "&prettyPrint=false")
            .header("User-Agent", c.ua)
            .header("Accept", "*/*")
            .header("X-Youtube-Client-Name", c.id.toString())
            .header("X-Youtube-Client-Version", c.version)
            .post(root.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val txt = Api.imageClient().newCall(req).execute().use { r ->
            if (!r.isSuccessful) { lastError = "lecteur HTTP " + r.code; return null }
            r.body?.string() ?: return null
        }
        val o = JSONObject(txt)
        val sd = o.optJSONObject("streamingData")
        if (sd == null) {
            val st = o.optJSONObject("playabilityStatus")?.optString("status", "") ?: ""
            lastError = if (st.isBlank()) "reponse inattendue" else "video bloquee (" + st + ")"
            return null
        }

        if (c.preferHls) {
            val hls = sd.optString("hlsManifestUrl", "")
            if (hls.isNotBlank()) return Stream(hls, c.ua)
        }
        // mp4 complets : on garde la meilleure definition raisonnable (<= 1080p).
        val fmts = sd.optJSONArray("formats")
        var best = ""
        var bestH = -1
        if (fmts != null) {
            for (i in 0 until fmts.length()) {
                val f = fmts.optJSONObject(i) ?: continue
                val u = f.optString("url", "")
                if (u.isBlank()) continue
                if (!f.optString("mimeType", "").contains("video")) continue
                val h = f.optInt("height", 0)
                if (h > bestH && h <= 1080) { bestH = h; best = u }
            }
        }
        if (best.isNotBlank()) return Stream(best, c.ua)
        val hls2 = sd.optString("hlsManifestUrl", "")
        if (hls2.isNotBlank()) return Stream(hls2, c.ua)
        lastError = "aucun flux lisible"
        return null
    }
}
