package com.kzplayer.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Enrichissement des episodes via TMDB (The Movie Database).
 *
 * Beaucoup de sources Stalker/M3U ne fournissent ni image ni resume par episode
 * (contrairement a Xtream). On complete donc photo (still) + resume FR depuis TMDB.
 *
 * Regles : 100% additif. Si la cle est vide ou si TMDB ne trouve pas la serie,
 * on renvoie les donnees d'origine sans rien modifier. Aucun effet sur le lecteur,
 * les flux, la licence ou le reste de l'app.
 */
object Tmdb {
    private const val API = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p/w300"

    data class EpMeta(val still: String, val overview: String)

    // nom normalise (minuscule) -> tvId (-1 = pas trouve), evite de rechercher 2 fois.
    private val tvIdCache = ConcurrentHashMap<String, Int>()
    // "tvId:saison" -> (numero d'episode -> EpMeta), evite de retelecharger une saison.
    private val seasonCache = ConcurrentHashMap<String, Map<Int, EpMeta>>()

    // Affiches (posters) film/serie : cle normalisee -> URL ("" = aucune trouvee).
    private const val IMGPOSTER = "https://image.tmdb.org/t/p/w342"
    private val posterCache = ConcurrentHashMap<String, String>()

    /**
     * Affiche TMDB (FR) pour un film ou une serie, par nom. Utilisee UNIQUEMENT en
     * complement : on ne l'appelle que lorsque le code ne fournit pas d'image.
     * Resultat mis en cache (y compris les absences) pour rester rapide.
     */
    suspend fun posterFor(name: String, series: Boolean): String = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext ""
        val base = normalize(name)
        if (base.isBlank()) return@withContext ""
        val key = (if (series) "tv:" else "mv:") + base.lowercase()
        posterCache[key]?.let { return@withContext it }
        val primary = if (series) "search/tv" else "search/movie"
        val secondary = if (series) "search/movie" else "search/tv"
        // 1) titre complet sur le bon type.
        var url = queryPoster(primary, base)
        // 2) on retire progressivement les derniers mots (numero/partie/sous-titre parasite).
        if (url.isBlank()) {
            val words = base.split(" ").filter { it.isNotBlank() }
            var n = words.size - 1
            var tries = 0
            while (url.isBlank() && n >= 1 && tries < 3) {
                url = queryPoster(primary, words.take(n).joinToString(" "))
                n--; tries++
            }
        }
        // 3) dernier recours : type oppose (certains "films" sont catalogues en serie et inversement).
        if (url.isBlank()) url = queryPoster(secondary, base)
        posterCache[key] = url
        url
    }

    // Lance une recherche TMDB et renvoie la 1ere affiche REELLEMENT disponible parmi les
    // premiers resultats : le tout 1er resultat n'a parfois pas d'image, d'ou des films sans affiche.
    private fun queryPoster(path: String, q: String): String {
        if (q.isBlank()) return ""
        val o = get("$API/$path?api_key=${Config.TMDB_API_KEY}&language=fr-FR&include_adult=false&query=${enc(q)}")
        val arr = o.optJSONArray("results") ?: return ""
        val max = if (arr.length() < 5) arr.length() else 5
        for (i in 0 until max) {
            val pp = arr.optJSONObject(i)?.optString("poster_path") ?: ""
            if (pp.isNotBlank() && pp != "null") return IMGPOSTER + pp
        }
        return ""
    }


    private data class FoundTitle(val id: Int, val mediaType: String)
    private val titleCache = ConcurrentHashMap<String, FoundTitle?>()
    private val trailerCache = ConcurrentHashMap<String, String>()

    private fun findTitle(name: String, series: Boolean): FoundTitle? {
        if (!enabled()) return null
        val q = normalize(name)
        if (q.isBlank()) return null
        val key = (if (series) "tv:" else "mv:") + q.lowercase()
        if (titleCache.containsKey(key)) return titleCache[key]
        val primary = if (series) "search/tv" else "search/movie"
        val o = get("$API/$primary?api_key=${Config.TMDB_API_KEY}&language=fr-FR&include_adult=false&query=${enc(q)}")
        val arr = o.optJSONArray("results")
        val id = if (arr != null && arr.length() > 0) arr.optJSONObject(0)?.optInt("id", -1) ?: -1 else -1
        val found = if (id > 0) FoundTitle(id, if (series) "tv" else "movie") else null
        titleCache[key] = found
        return found
    }

    suspend fun trailerUrl(name: String, series: Boolean = false): String = withContext(Dispatchers.IO) {
        if (!enabled() || name.isBlank()) return@withContext ""
        val base = normalize(name)
        if (base.isBlank()) return@withContext ""
        val key = (if (series) "tv:" else "mv:") + base.lowercase()
        trailerCache[key]?.let { return@withContext it }
        // v342 : les noms des listes IPTV sont sales ("FR - Le Film 4K MULTI", "VF | Titre (2023)",
        // suffixes de saison/episode...). On essaie donc plusieurs variantes du titre,
        // en film ET en serie, avant d'abandonner.
        var found: FoundTitle? = null
        for (cand in titleCandidates(name)) {
            found = try { findTitle(cand, series) ?: findTitle(cand, !series) } catch (_: Exception) { null }
            if (found != null) break
        }
        if (found == null) { trailerCache[key] = ""; return@withContext "" }
        val pick = fun(language: String): String {
            val o = get("$API/${found.mediaType}/${found.id}/videos?api_key=${Config.TMDB_API_KEY}&language=$language")
            val arr = o.optJSONArray("results") ?: return ""
            var fallback = ""
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                val site = v.optString("site")
                val type = v.optString("type")
                val k = v.optString("key")
                if (!site.equals("YouTube", true) || k.isBlank()) continue
                if (fallback.isBlank()) fallback = k
                if (type.equals("Trailer", true)) return k
            }
            return fallback
        }
        val yt = pick("fr-FR").ifBlank { pick("en-US") }
        val url = if (yt.isBlank()) "" else "https://www.youtube.com/watch?v=$yt"
        trailerCache[key] = url
        url
    }

    fun enabled(): Boolean = Config.TMDB_API_KEY.isNotBlank()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // Reutilise le client OkHttp permissif de Api (meme couche reseau).
    private fun get(url: String): JSONObject {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", Config.USER_AGENT)
                .build()
            Api.imageClient().newCall(req).execute().use { r ->
                val body = r.body?.string() ?: return JSONObject()
                JSONObject(body)
            }
        } catch (e: Exception) {
            JSONObject()
        }
    }

    // Nettoie le titre pour la recherche : retire qualite, langue, annee, crochets, points.
    /**
     * v342 : variantes de titre a tester sur TMDB, de la plus complete a la plus courte.
     * Exemples de nettoyage : "FR - Titre du film 4K MULTI" -> "Titre du film",
     * "Titre - S01 E05" -> "Titre", "Titre : le retour" -> "Titre".
     */
    private fun titleCandidates(raw: String): List<String> {
        val out = LinkedHashSet<String>()
        val base = normalize(raw)
        if (base.isNotBlank()) out.add(base)

        // Retire les marqueurs de saison / episode et ce qui suit.
        var noEp = Regex("(?i)\\bS\\s?\\d{1,2}\\s?[EX]\\s?\\d{1,3}\\b.*$").replace(base, " ")
        noEp = Regex("(?i)\\b(saison|season|episode|ep)\\s?\\d{1,3}\\b.*$").replace(noEp, " ")
        noEp = noEp.replace(Regex("\\s+"), " ").trim().trim('-', '\u2013', '|', ':').trim()
        if (noEp.isNotBlank()) out.add(noEp)

        // Garde la partie avant un separateur fort (tiret / deux-points / barre).
        for (src in listOf(noEp, base)) {
            val cut = src.split(Regex("\\s[-\u2013:|]\\s")).firstOrNull()?.trim().orEmpty()
            if (cut.length >= 3) out.add(cut)
        }

        // Dernier recours : les 4 premiers mots, puis les 2 premiers.
        val words = noEp.ifBlank { base }.split(" ").filter { it.isNotBlank() }
        if (words.size > 4) out.add(words.take(4).joinToString(" "))
        if (words.size > 2) out.add(words.take(2).joinToString(" "))

        return out.filter { it.length >= 2 }.take(6)
    }

    private fun normalize(raw: String): String {
        var s = raw
        s = Regex("\\(([^)]*)\\)").replace(s, " ")
        s = Regex("\\[[^\\]]*]").replace(s, " ")
        // Qualite / format / langue (tags isoles uniquement).
        s = Regex("(?i)\\b(4k|uhd|2160p|1080p|720p|480p|fhd|hd|sd|hdlight|hdrip|brrip|bdrip|dvdrip|webrip|web-?dl|web|bluray|hevc|x265|h265|x264|h264|10bit|aac|ac3|dts|remux|extended|unrated|remastered|vff|vfi|vfq|vof|vf|vostfr|vost|vo|multi|truefrench|subfrench|french)\\b")
            .replace(s, " ")
        // Code pays/langue en prefixe de liste IPTV, uniquement en debut et suivi d'un separateur
        // (on ne touche pas aux vrais titres comme \"Le Comte de Monte-Cristo\").
        s = Regex("(?i)^\\s*(fr|en|ar|tr|us|uk)\\b[\\s|:_-]+").replace(s, " ")
        s = Regex("\\b(19|20)\\d{2}\\b").replace(s, " ")
        s = s.replace(Regex("[._|]"), " ")
        return s.replace(Regex("\\s+"), " ").trim().trim('-', '\u2013', '|', ':').trim()
    }

    private fun findTvId(name: String): Int {
        val q = normalize(name)
        if (q.isBlank()) return -1
        return tvIdCache.getOrPut(q.lowercase()) {
            val o = get("$API/search/tv?api_key=${Config.TMDB_API_KEY}&language=fr-FR&query=${enc(q)}")
            val arr = o.optJSONArray("results")
            if (arr != null && arr.length() > 0) arr.optJSONObject(0)?.optInt("id", -1) ?: -1 else -1
        }
    }

    // Resume (synopsis) FR de la SERIE entiere (pas par episode), par nom.
    // 100% additif : renvoie "" si la cle TMDB est vide ou si la serie est introuvable.
    private val overviewCache = ConcurrentHashMap<String, String>()
    suspend fun seriesOverview(name: String): String = withContext(Dispatchers.IO) {
        if (!enabled() || name.isBlank()) return@withContext ""
        val key = normalize(name).lowercase()
        if (key.isBlank()) return@withContext ""
        overviewCache[key]?.let { return@withContext it }
        val tvId = try { findTvId(name) } catch (e: Exception) { -1 }
        if (tvId < 0) { overviewCache[key] = ""; return@withContext "" }
        val o = get("$API/tv/$tvId?api_key=${Config.TMDB_API_KEY}&language=fr-FR")
        val plot = o.optString("overview").let { if (it == "null") "" else it }.trim()
        overviewCache[key] = plot
        plot
    }

    private fun seasonMeta(tvId: Int, season: Int): Map<Int, EpMeta> {
        val key = "$tvId:$season"
        return seasonCache.getOrPut(key) {
            val o = get("$API/tv/$tvId/season/$season?api_key=${Config.TMDB_API_KEY}&language=fr-FR")
            val eps = o.optJSONArray("episodes") ?: return@getOrPut emptyMap()
            val map = HashMap<Int, EpMeta>()
            for (i in 0 until eps.length()) {
                val e = eps.optJSONObject(i) ?: continue
                val num = e.optInt("episode_number", -1)
                if (num < 0) continue
                val stillPath = e.optString("still_path")
                val still = if (stillPath.isNotBlank() && stillPath != "null") IMG + stillPath else ""
                val overview = e.optString("overview").let { if (it == "null") "" else it }
                map[num] = EpMeta(still, overview)
            }
            map
        }
    }

    /**
     * Renvoie la liste enrichie. Pour chaque episode SANS image ou SANS resume,
     * on complete avec TMDB (FR). La saison est deduite des entetes "Saison X",
     * ou du nom de l'episode (SxxExx / 1x02), sinon on garde la saison courante.
     * On ne remplace jamais une donnee deja fournie par la source.
     */
    suspend fun enrich(seriesName: String, rows: List<Item>): List<Item> = withContext(Dispatchers.IO) {
        if (!enabled() || seriesName.isBlank() || rows.isEmpty()) return@withContext rows
        val tvId = try { findTvId(seriesName) } catch (e: Exception) { -1 }
        if (tvId < 0) return@withContext rows

        var currentSeason = 1
        val out = ArrayList<Item>(rows.size)
        for (item in rows) {
            if (item.kind == "header") {
                Regex("\\d+").find(item.name)?.value?.toIntOrNull()?.let { currentSeason = it }
                out.add(item)
                continue
            }
            if (item.kind != "episode") { out.add(item); continue }
            // Rien a completer -> on n'appelle pas TMDB.
            if (item.logo.isNotBlank() && item.summary.isNotBlank()) { out.add(item); continue }

            var season = currentSeason
            var epNum = -1
            val se = Regex("(?i)s\\s*(\\d{1,2})\\s*e\\s*(\\d{1,3})").find(item.name)
            if (se != null) {
                season = se.groupValues[1].toIntOrNull() ?: season
                epNum = se.groupValues[2].toIntOrNull() ?: -1
            } else {
                val x = Regex("\\b(\\d{1,2})x(\\d{1,3})\\b").find(item.name)
                if (x != null) {
                    season = x.groupValues[1].toIntOrNull() ?: season
                    epNum = x.groupValues[2].toIntOrNull() ?: -1
                } else {
                    epNum = Regex("\\d{1,3}").find(item.name)?.value?.toIntOrNull() ?: -1
                }
            }
            if (epNum < 0) { out.add(item); continue }

            val meta = try { seasonMeta(tvId, season)[epNum] } catch (e: Exception) { null }
            if (meta == null) { out.add(item); continue }
            out.add(item.copy(
                logo = item.logo.ifBlank { meta.still },
                summary = item.summary.ifBlank { meta.overview }
            ))
        }
        out
    }

    // ============================ v338 : CASTING + TITRES SIMILAIRES ============================
    // 100% additif. Renvoie une liste vide si la cle TMDB est absente ou si le titre est introuvable.
    data class CastMember(val name: String, val role: String, val photo: String)

    private const val IMGFACE = "https://image.tmdb.org/t/p/w185"
    private val castCache = ConcurrentHashMap<String, List<CastMember>>()
    private val similarCache = ConcurrentHashMap<String, List<String>>()

    suspend fun castFor(name: String, series: Boolean): List<CastMember> = withContext(Dispatchers.IO) {
        if (!enabled() || name.isBlank()) return@withContext emptyList<CastMember>()
        val key = (if (series) "tv:" else "mv:") + normalize(name).lowercase()
        castCache[key]?.let { return@withContext it }
        val found = try { findTitle(name, series) ?: findTitle(name, !series) } catch (_: Exception) { null }
        if (found == null) { castCache[key] = emptyList(); return@withContext emptyList<CastMember>() }
        val out = ArrayList<CastMember>()
        try {
            val o = get("$API/${found.mediaType}/${found.id}/credits?api_key=${Config.TMDB_API_KEY}&language=fr-FR")
            val arr = o.optJSONArray("cast")
            if (arr != null) {
                val max = if (arr.length() < 15) arr.length() else 15
                for (i in 0 until max) {
                    val c = arr.optJSONObject(i) ?: continue
                    val n = c.optString("name")
                    if (n.isBlank()) continue
                    val pp = c.optString("profile_path")
                    val photo = if (pp.isNotBlank() && pp != "null") IMGFACE + pp else ""
                    out.add(CastMember(n, c.optString("character"), photo))
                }
            }
        } catch (_: Exception) {}
        castCache[key] = out
        out
    }

    // Titres similaires (noms TMDB). Le filtrage "present dans la liste de lecture" est fait cote UI.
    suspend fun similarTitles(name: String, series: Boolean): List<String> = withContext(Dispatchers.IO) {
        if (!enabled() || name.isBlank()) return@withContext emptyList<String>()
        val key = (if (series) "tv:" else "mv:") + normalize(name).lowercase()
        similarCache[key]?.let { return@withContext it }
        val found = try { findTitle(name, series) ?: findTitle(name, !series) } catch (_: Exception) { null }
        if (found == null) { similarCache[key] = emptyList(); return@withContext emptyList<String>() }
        val out = ArrayList<String>()
        for (path in listOf("similar", "recommendations")) {
            try {
                val o = get("$API/${found.mediaType}/${found.id}/$path?api_key=${Config.TMDB_API_KEY}&language=fr-FR&page=1")
                val arr = o.optJSONArray("results") ?: continue
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    val t = r.optString("title").ifBlank { r.optString("name") }
                    val orig = r.optString("original_title").ifBlank { r.optString("original_name") }
                    if (t.isNotBlank()) out.add(t)
                    if (orig.isNotBlank()) out.add(orig)
                }
            } catch (_: Exception) {}
        }
        val uniq = out.distinct()
        similarCache[key] = uniq
        uniq
    }

    // Cle de comparaison souple pour rapprocher un titre TMDB d'un titre de liste de lecture.
    fun matchKey(raw: String): String =
        normalize(raw).lowercase().replace(Regex("[^a-z0-9]"), "")
}
