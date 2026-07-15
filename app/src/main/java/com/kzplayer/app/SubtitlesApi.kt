package com.kzplayer.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

// Acces a la base de sous-titres OpenSubtitles (API v1).
// Reutilise le client OkHttp permissif de Api (Api.imageClient()).
object SubtitlesApi {
    data class SubOption(val fileId: Int, val lang: String, val release: String, val format: String)

    private const val BASE = "https://api.opensubtitles.com/api/v1"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    // Recherche de sous-titres par titre + langues (ex: "fr,en,es").
    suspend fun search(query: String, langs: String): List<SubOption> = withContext(Dispatchers.IO) {
        val key = SubtitlesConfig.OPENSUBTITLES_API_KEY
        if (key.isBlank() || query.isBlank()) return@withContext emptyList<SubOption>()
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE/subtitles?query=$q&languages=$langs&order_by=download_count&order_direction=desc"
        val req = Request.Builder().url(url)
            .header("Api-Key", key)
            .header("User-Agent", "KZPlayer v1.0")
            .header("Accept", "application/json")
            .get().build()
        val out = ArrayList<SubOption>()
        try {
            Api.imageClient().newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                if (resp.isSuccessful && body != null) {
                    val data = JSONObject(body).optJSONArray("data")
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val attr = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                            val files = attr.optJSONArray("files") ?: continue
                            val f = files.optJSONObject(0) ?: continue
                            val fid = f.optInt("file_id", 0)
                            if (fid == 0) continue
                            out.add(
                                SubOption(
                                    fileId = fid,
                                    lang = attr.optString("language", "und"),
                                    release = attr.optString("release", ""),
                                    format = attr.optString("format", "srt").ifBlank { "srt" }
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }
        out.take(30)
    }

    // Recupere l'URL directe du fichier de sous-titres pour un file_id donne.
    suspend fun downloadUrl(fileId: Int): String? = withContext(Dispatchers.IO) {
        val key = SubtitlesConfig.OPENSUBTITLES_API_KEY
        if (key.isBlank()) return@withContext null
        val bodyJson = JSONObject().put("file_id", fileId).toString()
        val req = Request.Builder().url("$BASE/download")
            .header("Api-Key", key)
            .header("User-Agent", "KZPlayer v1.0")
            .header("Accept", "application/json")
            .post(bodyJson.toRequestBody(JSON))
            .build()
        var link: String? = null
        try {
            Api.imageClient().newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                if (resp.isSuccessful && body != null) {
                    link = JSONObject(body).optString("link").ifBlank { null }
                }
            }
        } catch (e: Exception) {
        }
        link
    }
}
