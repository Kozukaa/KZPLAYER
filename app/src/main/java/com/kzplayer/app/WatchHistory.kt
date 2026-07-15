package com.kzplayer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object WatchHistory {
    private const val PREF = "kz_watch_history"
    private const val KEY = "items"
    private const val MAX_ITEMS = 80

    data class Entry(
        val url: String,
        val title: String,
        val logo: String,
        val kind: String, // movie | series
        val positionMs: Long,
        val durationMs: Long,
        val updatedAt: Long,
        val seriesName: String = "",
        val seriesLogo: String = "",
        val seriesId: String = "",
        val seriesCmd: String = "",
        val sourceCmd: String = "",
        val sourceStreamId: String = "",
        val sourceContainerExt: String = ""
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Entry> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isBlank()) continue
            out.add(
                Entry(
                    url = url,
                    title = o.optString("title"),
                    logo = o.optString("logo"),
                    kind = o.optString("kind", "movie"),
                    positionMs = o.optLong("positionMs", 0L),
                    durationMs = o.optLong("durationMs", 0L),
                    updatedAt = o.optLong("updatedAt", 0L),
                    seriesName = o.optString("seriesName"),
                    seriesLogo = o.optString("seriesLogo"),
                    seriesId = o.optString("seriesId"),
                    seriesCmd = o.optString("seriesCmd"),
                    sourceCmd = o.optString("sourceCmd"),
                    sourceStreamId = o.optString("sourceStreamId"),
                    sourceContainerExt = o.optString("sourceContainerExt")
                )
            )
        }
        return out.sortedByDescending { it.updatedAt }
    }

    fun recentItems(ctx: Context, browseKind: String): List<Item> {
        val wanted = if (browseKind == "series") "series" else "movie"
        val entries = all(ctx).filter { it.kind == wanted }
        if (wanted == "series") {
            val map = LinkedHashMap<String, Item>()
            for (e in entries) {
                val seriesTitle = e.seriesName.ifBlank { e.title.substringBefore(" - ").trim() }
                if (seriesTitle.isBlank() || map.containsKey(seriesTitle)) continue
                map[seriesTitle] = Item(
                    name = seriesTitle,
                    logo = e.seriesLogo.ifBlank { e.logo },
                    kind = "series",
                    seriesId = e.seriesId.ifBlank { seriesTitle },
                    cmd = e.seriesCmd.ifBlank { null },
                    duration = formatProgress(e.positionMs, e.durationMs),
                    added = e.updatedAt
                )
            }
            return map.values.toList()
        }
        val movieMap = LinkedHashMap<String, Item>()
        for (e in entries) {
            val key = e.title.trim()
            if (key.isBlank() || movieMap.containsKey(key)) continue
            movieMap[key] = Item(
                name = e.title,
                logo = e.logo,
                kind = "movie",
                // Si c'est un film Stalker, on ne rejoue pas l'ancienne URL create_link/token
                // car elle expire et provoque des 404. On garde la commande d'origine et
                // DetailActivity demandera un lien frais au portail au moment du clic.
                directUrl = if (e.sourceCmd.isBlank()) e.url else null,
                streamId = e.sourceStreamId.ifBlank { null },
                containerExt = e.sourceContainerExt.ifBlank { null },
                cmd = e.sourceCmd.ifBlank { null },
                duration = formatProgress(e.positionMs, e.durationMs),
                added = e.updatedAt
            )
        }
        return movieMap.values.toList()
    }

    fun progressPercent(ctx: Context, url: String?): Int {
        if (url.isNullOrBlank()) return 0
        val e = all(ctx).firstOrNull { it.url == url } ?: return 0
        return percent(e)
    }

    fun progressForSeries(ctx: Context, seriesTitle: String): Int {
        if (seriesTitle.isBlank()) return 0
        // Les episodes sont sauvegardes sous la forme "Nom de la série - Episode X".
        // La tuile de la série doit donc afficher la progression du dernier episode vu.
        val prefix = "$seriesTitle - "
        val e = all(ctx).firstOrNull { it.kind == "series" && (it.title == seriesTitle || it.title.startsWith(prefix)) } ?: return 0
        return percent(e)
    }

    fun progressForTitle(ctx: Context, fullTitle: String): Int {
        if (fullTitle.isBlank()) return 0
        val e = all(ctx).firstOrNull { it.title == fullTitle } ?: return 0
        return percent(e)
    }

    fun progressLabel(ctx: Context, fullTitle: String): String {
        val e = all(ctx).firstOrNull { it.title == fullTitle } ?: return ""
        val p = percent(e)
        return when {
            p >= 90 -> "Vu"
            p > 0 -> "$p% vu"
            else -> ""
        }
    }

    private fun percent(e: Entry): Int {
        if (e.durationMs <= 0L || e.positionMs <= 0L) return 0
        return ((e.positionMs * 100L) / e.durationMs).toInt().coerceIn(1, 100)
    }

    fun positionFor(ctx: Context, url: String?): Long {
        if (url.isNullOrBlank()) return 0L
        val e = all(ctx).firstOrNull { it.url == url } ?: return 0L
        // Ne reprend pas dans les 10 dernieres secondes.
        if (e.durationMs > 0 && e.durationMs - e.positionMs < 10_000L) return 0L
        return e.positionMs.coerceAtLeast(0L)
    }

    fun positionForTitle(ctx: Context, title: String): Long {
        if (title.isBlank()) return 0L
        val e = all(ctx).firstOrNull { it.title == title } ?: return 0L
        // Ne reprend pas dans les 10 dernieres secondes.
        if (e.durationMs > 0 && e.durationMs - e.positionMs < 10_000L) return 0L
        return e.positionMs.coerceAtLeast(0L)
    }

    fun touch(
        ctx: Context,
        url: String,
        title: String,
        logo: String,
        kind: String,
        seriesName: String = "",
        seriesLogo: String = "",
        seriesId: String = "",
        seriesCmd: String = "",
        sourceCmd: String = "",
        sourceStreamId: String = "",
        sourceContainerExt: String = ""
    ) {
        val prev = all(ctx).firstOrNull { it.url == url || (it.kind == kind && it.title == title) }
        save(
            ctx,
            url,
            title,
            logo,
            kind,
            positionMs = prev?.positionMs ?: 0L,
            durationMs = prev?.durationMs ?: 0L,
            seriesName = seriesName.ifBlank { prev?.seriesName ?: "" },
            seriesLogo = seriesLogo.ifBlank { prev?.seriesLogo ?: "" },
            seriesId = seriesId.ifBlank { prev?.seriesId ?: "" },
            seriesCmd = seriesCmd.ifBlank { prev?.seriesCmd ?: "" },
            sourceCmd = sourceCmd.ifBlank { prev?.sourceCmd ?: "" },
            sourceStreamId = sourceStreamId.ifBlank { prev?.sourceStreamId ?: "" },
            sourceContainerExt = sourceContainerExt.ifBlank { prev?.sourceContainerExt ?: "" }
        )
    }

    fun save(
        ctx: Context,
        url: String,
        title: String,
        logo: String,
        kind: String,
        positionMs: Long,
        durationMs: Long,
        seriesName: String = "",
        seriesLogo: String = "",
        seriesId: String = "",
        seriesCmd: String = "",
        sourceCmd: String = "",
        sourceStreamId: String = "",
        sourceContainerExt: String = ""
    ) {
        if (url.isBlank() || title.isBlank()) return
        val now = System.currentTimeMillis()
        val existing = all(ctx).filter { it.url != url && !(it.kind == kind && it.title == title) }.toMutableList()
        existing.add(0, Entry(url, title, logo, kind, positionMs.coerceAtLeast(0L), durationMs.coerceAtLeast(0L), now, seriesName, seriesLogo, seriesId, seriesCmd, sourceCmd, sourceStreamId, sourceContainerExt))
        val arr = JSONArray()
        for (e in existing.take(MAX_ITEMS)) {
            arr.put(JSONObject().apply {
                put("url", e.url)
                put("title", e.title)
                put("logo", e.logo)
                put("kind", e.kind)
                put("positionMs", e.positionMs)
                put("durationMs", e.durationMs)
                put("updatedAt", e.updatedAt)
                put("seriesName", e.seriesName)
                put("seriesLogo", e.seriesLogo)
                put("seriesId", e.seriesId)
                put("seriesCmd", e.seriesCmd)
                put("sourceCmd", e.sourceCmd)
                put("sourceStreamId", e.sourceStreamId)
                put("sourceContainerExt", e.sourceContainerExt)
            })
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    private fun formatProgress(pos: Long, dur: Long): String {
        if (pos <= 0L || dur <= 0L) return ""
        val p = ((pos * 100L) / dur).toInt().coerceIn(1, 100)
        if (p >= 90) return "Vu"
        return "$p% vu"
    }
}
