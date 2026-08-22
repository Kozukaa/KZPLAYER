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
    private const val UA_MOBILE = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    /** Flux pret a lire : URL + User-Agent a utiliser pour le telecharger. */
    data class Stream(val url: String, val ua: String)

    /** Derniere raison d echec, affichee a l utilisateur pour diagnostic. */
    @Volatile var lastError: String = ""

    /** Statut renvoye par le dernier client interroge (UNPLAYABLE, HTTP403...). */
    @Volatile private var lastStatus: String = ""

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

    /**
     * v348 : renvoie l identifiant YouTube de la bande-annonce du titre demande,
     * sans tenter de resoudre le flux (rapide : une seule requete dans la majorite des cas).
     */
    suspend fun searchTrailerId(rawName: String): String = withContext(Dispatchers.IO) {
        lastError = ""
        val cands = Tmdb.titleCandidates(rawName)
        val names = if (cands.isEmpty()) listOf(rawName.trim()) else cands
        for (n in names.take(2)) {
            if (n.length < 2) continue
            for (suffix in listOf(" bande annonce VF", " official trailer")) {
                val vid = try { searchVideoId(n + suffix) } catch (_: Exception) { "" }
                if (vid.isNotBlank()) return@withContext vid
            }
        }
        if (lastError.isBlank()) lastError = "recherche sans resultat"
        ""
    }

    /** Tente de recuperer un flux direct pour cet identifiant (peut echouer : YouTube bloque). */
    suspend fun resolveVideo(id: String): Stream? = withContext(Dispatchers.IO) {
        if (id.isBlank()) null else resolveId(id)
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
        val journal = ArrayList<String>()
        for (c in clients) {
            lastStatus = ""
            val s = try { ask(id, c) } catch (e: Exception) {
                lastStatus = "reseau"
                null
            }
            if (s != null) { streamCache[id] = s; return s }
            journal.add(c.name + "=" + (if (lastStatus.isBlank()) "?" else lastStatus))
        }
        // Aucun client n a pu lire la video : on liste ce que chacun a repondu.
        lastError = journal.joinToString(" ")
        return null
    }

    private class Cli(
        val name: String,
        val id: Int,
        val version: String,
        val ua: String,
        val preferHls: Boolean,
        val embed: Boolean,
        val extra: Map<String, Any>
    )

    /**
     * v347 : YouTube exige desormais une attestation pour les clients mobiles
     * classiques (IOS / ANDROID), qui repondent UNPLAYABLE. On passe donc en priorite
     * par les clients casque et lecteur integre, qui n en ont pas besoin.
     */
    private val clients: List<Cli> = listOf(
        // 1. Client casque (Quest) : pas d attestation, renvoie des mp4 complets.
        Cli(
            "ANDROID_VR", 28, "1.60.19",
            "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            false, false,
            mapOf(
                "androidSdkVersion" to 32,
                "deviceMake" to "Oculus",
                "deviceModel" to "Quest 3",
                "osName" to "Android",
                "osVersion" to "12L"
            )
        ),
        // 2. Lecteur integre TV : concu pour la lecture hors du site.
        Cli(
            "TVHTML5_SIMPLY_EMBEDDED_PLAYER", 85, "2.0",
            "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Safari/605.1.15",
            true, true, emptyMap()
        ),
        // 3. Lecteur integre web.
        Cli("WEB_EMBEDDED_PLAYER", 56, "1.20240723.01.00", UA_WEB, false, true, emptyMap()),
        // 4. Client iOS : manifeste HLS quand il repond encore.
        Cli(
            "IOS", 5, "19.29.1",
            "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)",
            true, false,
            mapOf(
                "deviceMake" to "Apple",
                "deviceModel" to "iPhone16,2",
                "osName" to "iPhone",
                "osVersion" to "17.5.1.21F90"
            )
        ),
        // 5. Client mobile web.
        Cli("MWEB", 2, "2.20240726.01.00", UA_MOBILE, false, false, emptyMap()),
        // 6. Dernier recours : client web classique.
        Cli("WEB", 1, "2.20240726.00.00", UA_WEB, false, false, emptyMap())
    )

    private fun ask(id: String, c: Cli): Stream? {
        // Corps JSON construit avec JSONObject (aucun risque de guillemets mal echappes).
        val cl = JSONObject()
        cl.put("clientName", c.name)
        cl.put("clientVersion", c.version)
        cl.put("hl", "fr")
        cl.put("gl", "FR")
        for ((k, v) in c.extra) cl.put(k, v)
        if (c.embed) cl.put("clientScreen", "EMBED")
        val ctx = JSONObject().put("client", cl)
        if (c.embed) {
            // Les lecteurs integres exigent la page qui "heberge" la video.
            ctx.put(
                "thirdParty",
                JSONObject().put("embedUrl", "https://www.youtube.com/")
            )
        }
        val root = JSONObject()
        root.put("context", ctx)
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
            if (!r.isSuccessful) { lastStatus = "HTTP" + r.code; return null }
            r.body?.string() ?: return null
        }
        val o = JSONObject(txt)
        val sd = o.optJSONObject("streamingData")
        if (sd == null) {
            val st = o.optJSONObject("playabilityStatus")?.optString("status", "") ?: ""
            lastStatus = if (st.isBlank()) "reponse inattendue" else st
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
        lastStatus = "sans flux"
        return null
    }
}
