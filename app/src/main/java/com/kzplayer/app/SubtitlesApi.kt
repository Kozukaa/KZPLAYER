package com.kzplayer.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

// Base de sous-titres OpenSubtitles.org (API "historique", comptee par IP).
// Aucune cle API : le quota est compte par appareil/IP, donc chaque utilisateur
// a son propre quota. Convient a un player partage entre plusieurs personnes.
object SubtitlesApi {
    data class SubOption(
        val downloadUrl: String,
        val lang: String,
        val release: String,
        val format: String
    )

    // rest.opensubtitles.org attend un User-Agent qui identifie l'app.
    private const val UA = "KZPlayer v1.0"
    private const val BASE = "https://rest.opensubtitles.org/search"

    // langs : codes ISO 639-2 (3 lettres) separes par des virgules, ex "fre,eng,spa".
    suspend fun search(query: String, langs: String): List<SubOption> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList<SubOption>()
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val url = "$BASE/query-$q/sublanguageid-$langs"
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("X-User-Agent", UA)
            .get().build()
        val out = ArrayList<SubOption>()
        try {
            Api.imageClient().newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                if (resp.isSuccessful && body != null && body.trimStart().startsWith("[")) {
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val link = o.optString("SubDownloadLink")
                        if (link.isBlank()) continue
                        out.add(
                            SubOption(
                                downloadUrl = link,
                                lang = o.optString("ISO639", "und").ifBlank { "und" },
                                release = o.optString("MovieReleaseName", o.optString("SubFileName", "")),
                                format = o.optString("SubFormat", "srt").ifBlank { "srt" }
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
        }
        out.take(40)
    }

    // Telecharge le fichier (.gz), le decompresse et l'ecrit dans le cache local.
    // Retourne un URI file:// utilisable directement par ExoPlayer.
    suspend fun downloadToFile(ctx: Context, opt: SubOption): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(opt.downloadUrl)
                .header("User-Agent", UA)
                .get().build()
            Api.imageClient().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val raw = resp.body?.bytes() ?: return@use null
                val data = try { GZIPInputStream(raw.inputStream()).readBytes() } catch (e: Exception) { raw }
                val ext = opt.format.lowercase().ifBlank { "srt" }
                val f = File(ctx.cacheDir, "kzsub_${System.currentTimeMillis()}.$ext")
                f.writeBytes(data)
                android.net.Uri.fromFile(f).toString()
            }
        } catch (e: Exception) {
            null
        }
    }
}
