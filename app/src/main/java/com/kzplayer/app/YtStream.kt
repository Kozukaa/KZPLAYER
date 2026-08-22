package com.kzplayer.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * v345 : recupere le VRAI flux video d une bande-annonce YouTube pour la lire
 * directement dans le lecteur KZ (Media3). Aucune WebView, aucune barre YouTube :
 * pour l utilisateur, la bande-annonce se lit comme un film de sa liste.
 *
 * On interroge l API interne "player" de YouTube en se presentant comme
 * l application mobile officielle. La reponse contient soit un manifeste HLS
 * (deja gere par le lecteur pour l IPTV), soit des mp4 complets video+audio.
 */
object YtStream {

    private const val KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val ENDPOINT = "https://www.youtube.com/youtubei/v1/player"

    /** Flux pret a lire : URL + User-Agent a utiliser pour le telecharger. */
    data class Stream(val url: String, val ua: String)

    private val cache = ConcurrentHashMap<String, Stream>()

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
        if (id.isBlank() && s.contains("/v/")) id = s.substringAfter("/v/").substringBefore("?").substringBefore("&")
        return id.trim()
    }

    /** Renvoie un flux lisible par Media3, ou null si rien n a pu etre resolu. */
    suspend fun resolve(rawUrl: String): Stream? = withContext(Dispatchers.IO) {
        val id = videoId(rawUrl)
        if (id.isBlank()) return@withContext null
        cache[id]?.let { return@withContext it }
        for (c in clients) {
            val s = try { ask(id, c) } catch (_: Exception) { null }
            if (s != null) { cache[id] = s; return@withContext s }
        }
        null
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
        // Client Android : renvoie des mp4 complets (video + audio dans le meme fichier).
        Cli(
            "ANDROID", 3, "19.30.36",
            "com.google.android.youtube/19.30.36 (Linux; U; Android 13) gzip",
            false,
            mapOf("androidSdkVersion" to 33, "osName" to "Android", "osVersion" to "13")
        ),
        // Dernier recours : client web.
        Cli("WEB", 1, "2.20240726.00.00", Config.USER_AGENT, false, emptyMap())
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
            .url(ENDPOINT + "?key=" + KEY + "&prettyPrint=false")
            .header("User-Agent", c.ua)
            .header("Accept", "*/*")
            .header("X-Youtube-Client-Name", c.id.toString())
            .header("X-Youtube-Client-Version", c.version)
            .post(root.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val txt = Api.imageClient().newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            r.body?.string() ?: return null
        }
        val sd = JSONObject(txt).optJSONObject("streamingData") ?: return null

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
        return null
    }
}
