package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import coil.load
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class SeriesActivity : BaseActivity() {
    companion object { private val rowsCache = HashMap<String, List<Item>>() }
    private lateinit var rv: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var msgTv: TextView
    private var rows: List<Item> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series)

        val series = Session.seriesItem
        val pl = Session.current
        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        if (series == null || pl == null) { finish(); return }

        val favBtn = findViewById<TextView>(R.id.favBtn)
        fun refreshFav() {
            favBtn.text = if (Favorites.isFavorite(this, series)) "★" else "☆"
        }
        refreshFav()
        favBtn.setOnClickListener {
            val added = Favorites.toggle(this, series)
            favBtn.text = if (added) "★" else "☆"
        }

        findViewById<TextView>(R.id.seriesTitle).text = series.name
        findViewById<ImageView>(R.id.posterIv).load(series.logo) {
            placeholder(R.drawable.bg_tile)
            error(R.drawable.ic_movie)
            crossfade(false)
        }

        // Resume de la SERIE entiere (pas par episode). Xtream : bloc info. Stalker/M3U : TMDB (FR).
        // 100% additif : reste masque si aucun resume n'est trouve.
        val summaryTv = findViewById<TextView>(R.id.seriesSummary)
        val existingSummary = Api.cleanPlot(if (series.description.isNotBlank()) series.description else series.summary)
        if (existingSummary.isNotBlank()) {
            summaryTv.visibility = View.VISIBLE
            summaryTv.text = existingSummary
        }
        lifecycleScope.launch {
            val plot = try {
                if (pl.type != "stalker" && pl.type != "m3u")
                    Api.xtreamSeriesPlot(pl, series.seriesId ?: "").ifBlank { Tmdb.seriesOverview(series.name) }
                else Tmdb.seriesOverview(series.name)
            } catch (e: Exception) { "" }
            if (plot.isNotBlank()) {
                summaryTv.visibility = View.VISIBLE
                summaryTv.text = plot
            }
        }

        rv = findViewById(R.id.episodeRv)
        progress = findViewById(R.id.progress)
        msgTv = findViewById(R.id.msgTv)
        rv.layoutManager = LinearLayoutManager(this)

        setLoading(true)
        msgTv.text = ""
        lifecycleScope.launch {
            try {
                val cacheKey = "${pl.id}:${pl.type}:${series.seriesId ?: series.cmd ?: series.name}"
                rows = rowsCache[cacheKey] ?: run {
                    val base = when (pl.type) {
                        "stalker" -> Api.stalkerSeriesExpanded(pl, series.seriesId ?: series.cmd ?: "")
                        "m3u" -> Api.m3uSeriesExpanded(pl, series.seriesId ?: series.name)
                        else -> Api.xtreamSeriesExpanded(pl, series.seriesId ?: "")
                    }
                    // Stalker/M3U ne fournissent souvent ni image ni resume par episode :
                    // on complete via TMDB (photos + resumes FR). 100% additif.
                    val enriched = if (pl.type == "stalker" || pl.type == "m3u")
                        Tmdb.enrich(series.name, base) else base
                    rowsCache[cacheKey] = enriched
                    enriched
                }
                rv.adapter = EpisodeAdapter(rows) { playEpisode(it) }
                rv.setItemViewCacheSize(30)
                msgTv.text = if (rows.isEmpty()) "Aucun episode disponible pour cette serie." else ""
            } catch (e: Exception) {
                msgTv.text = "Erreur : ${e.message}"
            }
            setLoading(false)
        }
    }

    private fun playEpisode(item: Item) {
        // File d'attente = tous les episodes jouables de la serie -> lecture a la suite.
        val queue = rows.filter { it.kind == "episode" || it.directUrl != null || it.cmd != null }
        Session.episodeQueue = queue
        Session.episodeIndex = queue.indexOf(item)
        val pl = Session.current ?: return
        val direct = item.directUrl
        if (!direct.isNullOrBlank()) { play(direct, item.name, item.logo); return }
        val cmd = item.cmd
        if (cmd.isNullOrBlank()) { msgTv.text = "Flux indisponible pour cet episode."; return }
        setLoading(true)
        lifecycleScope.launch {
            val link = try { Api.stalkerLink(pl, cmd, "movie") } catch (e: Exception) { null }
            setLoading(false)
            if (!link.isNullOrBlank()) play(link, item.name, item.logo) else msgTv.text = "Impossible d'obtenir le flux."
        }
    }

    private fun play(url: String, title: String, episodeLogo: String = "") {
        val series = Session.seriesItem
        val fullTitle = if (series != null) "${series.name} - $title" else title
        val seriesName = series?.name ?: ""
        val seriesLogo = series?.logo ?: ""
        val seriesId = series?.seriesId ?: ""
        val seriesCmd = series?.cmd ?: ""
        val logo = episodeLogo.ifBlank { seriesLogo }
        WatchHistory.touch(this, url, fullTitle, logo, "series", seriesName, seriesLogo, seriesId, seriesCmd)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("url", url)
                .putExtra("title", fullTitle)
                .putExtra("logo", logo)
                .putExtra("historyKind", "series")
                .putExtra("seriesName", seriesName)
                .putExtra("seriesLogo", seriesLogo)
                .putExtra("seriesId", seriesId)
                .putExtra("seriesCmd", seriesCmd)
                .putExtra("mode", "vod")
                .putExtra("queued", true)
        )
    }

    private fun setLoading(b: Boolean) { progress.visibility = if (b) View.VISIBLE else View.GONE }

    inner class EpisodeAdapter(val data: List<Item>, val onClick: (Item) -> Unit) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        inner class HeaderVH(val v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.headerTv)
        }
        inner class EpVH(val v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.epTitle)
            val thumb: ImageView = v.findViewById(R.id.epThumb)
            val progressWrap: View = v.findViewById(R.id.epProgressWrap)
            val progressFill: View = v.findViewById(R.id.epProgressFill)
            val progressText: TextView = v.findViewById(R.id.epProgressText)
            val summary: TextView = v.findViewById(R.id.epSummary)
        }
        override fun getItemViewType(position: Int): Int =
            if (data[position].kind == "header") 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 1) HeaderVH(inf.inflate(R.layout.item_season_header, parent, false))
            else EpVH(inf.inflate(R.layout.item_episode, parent, false))
        }
        override fun getItemCount() = data.size
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = data[position]
            if (holder is HeaderVH) { holder.tv.text = item.name; return }
            holder as EpVH
            holder.tv.text = item.name
            val epSummary = item.summary
            if (epSummary.isNotBlank() && item.kind == "episode") {
                holder.summary.visibility = View.VISIBLE
                holder.summary.text = epSummary
            } else {
                holder.summary.visibility = View.GONE
                holder.summary.text = ""
            }

            val series = Session.seriesItem
            val fullTitle = if (series != null) "${series.name} - ${item.name}" else item.name
            // Important : ne pas reprendre automatiquement l'affiche de la serie,
            // sinon tous les episodes affichent la meme image. On affiche la vraie
            // image episode si le serveur la fournit, sinon le placeholder neutre.
            val thumbUrl = item.logo
            holder.thumb.load(thumbUrl) {
                placeholder(R.drawable.bg_tile)
                error(R.drawable.ic_movie)
                crossfade(false)
            }

            val pct = WatchHistory.progressForTitle(holder.v.context, fullTitle)
            val label = WatchHistory.progressLabel(holder.v.context, fullTitle)
            if (pct > 0) {
                holder.progressWrap.visibility = View.VISIBLE
                holder.progressText.visibility = View.VISIBLE
                holder.progressText.text = label
                holder.progressWrap.post {
                    val lp = holder.progressFill.layoutParams
                    lp.width = (holder.progressWrap.width * (pct / 100f)).toInt().coerceAtLeast(4)
                    holder.progressFill.layoutParams = lp
                }
            } else {
                holder.progressWrap.visibility = View.GONE
                holder.progressText.visibility = View.GONE
            }

            holder.v.setOnClickListener { onClick(item) }
        }
    }
}
