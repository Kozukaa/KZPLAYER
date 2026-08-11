package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load

// Accueil du theme Netflix : grande banniere + raccourcis + rangees "Continuer a regarder" / "Ma liste".
// 100% autonome et 100% local (historique + favoris) : aucun appel reseau ici, donc aucun risque
// pour la lecture IPTV, la licence ou les serveurs. Les tuiles ouvrent les ecrans Netflix dedies.
class NetflixHomeActivity : NtBase() {

    data class Row(val title: String, val items: List<Item>, val landscape: Boolean)

    private lateinit var homeRv: RecyclerView
    private val rows = ArrayList<Row>()
    private var adapter: HomeAdapter? = null
    private var header: HeaderVH? = null
    private var heroItem: Item? = null
    private var firstFocusDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_netflix_home)
        homeRv = findViewById(R.id.homeRv)
        homeRv.layoutManager = LinearLayoutManager(this)
        adapter = HomeAdapter()
        homeRv.adapter = adapter
        ensureSession { }
    }

    override fun onResume() {
        super.onResume()
        buildRows()
    }

    // Rangees construites depuis l'historique et les favoris (instantane, hors ligne).
    private fun buildRows() {
        val newRows = ArrayList<Row>()
        val continueMovies = safe { WatchHistory.recentItems(this, "movie") }
        val continueSeries = safe { WatchHistory.recentItems(this, "series") }
        val continueAll = (continueMovies + continueSeries)
            .sortedByDescending { it.added }
            .take(20)
        if (continueAll.isNotEmpty()) newRows.add(Row("Continuer à regarder", continueAll, true))

        val favMovies = safe { Favorites.forKind(this, "movie") }
        val favSeries = safe { Favorites.forKind(this, "series") }
        val favLive = safe { Favorites.forKind(this, "live") }
        val myList = (favMovies + favSeries).take(20)
        if (myList.isNotEmpty()) newRows.add(Row("Ma liste", myList, false))
        if (favLive.isNotEmpty()) newRows.add(Row("Chaînes favorites", favLive.take(20), true))

        rows.clear()
        rows.addAll(newRows)
        heroItem = continueAll.firstOrNull() ?: myList.firstOrNull()
        adapter?.notifyDataSetChanged()
        header?.bind()
    }

    private fun <T> safe(block: () -> List<T>): List<T> =
        try { block() } catch (e: Exception) { emptyList() }

    private fun open(cls: Class<*>) {
        try { startActivity(Intent(this, cls)) } catch (e: Exception) {}
    }

    private fun openItem(item: Item) {
        when (item.kind) {
            "series" -> { Session.seriesItem = item; open(NewSeriesDetailActivity::class.java) }
            "live", "channel" -> open(NflxLiveActivity::class.java)
            else -> { Session.detailItem = item; open(DetailActivity::class.java) }
        }
    }

    private fun focusEffect(v: View, scale: Float) {
        v.setOnFocusChangeListener { view, has ->
            val s = if (has) scale else 1f
            view.animate().scaleX(s).scaleY(s).setDuration(120).start()
            view.translationZ = if (has) 14f else 0f
        }
    }

    inner class HomeAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int): Int = if (position == 0) 0 else 1
        override fun getItemCount(): Int = rows.size + 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) HeaderVH(inf.inflate(R.layout.item_nflx_home_header, parent, false))
            else RowVH(inf.inflate(R.layout.item_nflx_row, parent, false))
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is HeaderVH) { header = holder; holder.bind() }
            else if (holder is RowVH) holder.bind(rows[position - 1])
        }
        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is HeaderVH && header === holder) header = null
            super.onViewRecycled(holder)
        }
    }

    inner class HeaderVH(val v: View) : RecyclerView.ViewHolder(v) {
        private val img: ImageView = v.findViewById(R.id.heroImg)
        private val title: TextView = v.findViewById(R.id.heroTitle)
        private val meta: TextView = v.findViewById(R.id.heroMeta)
        private var lastLogo: String = ""

        fun bind() {
            wire(R.id.heroPlayTv) { open(NflxLiveActivity::class.java) }
            wire(R.id.heroMovies) { open(NflxMoviesActivity::class.java) }
            wire(R.id.heroSeries) { open(NflxSeriesActivity::class.java) }
            wire(R.id.tileTv) { open(NflxLiveActivity::class.java) }
            wire(R.id.tileMovies) { open(NflxMoviesActivity::class.java) }
            wire(R.id.tileSeries) { open(NflxSeriesActivity::class.java) }
            wire(R.id.tileGuide) { open(NewGuideActivity::class.java) }
            wire(R.id.tileVoice) { open(VoiceActivity::class.java) }
            wire(R.id.tileSettings) { open(SettingsActivity::class.java) }
            wire(R.id.tileTheme) { open(ThemeActivity::class.java) }

            val h = heroItem
            if (h != null) {
                title.text = h.name
                meta.text = if (h.duration.isNotBlank()) "Reprendre • ${h.duration}" else "Reprendre la lecture"
                if (h.logo.isNotBlank() && h.logo != lastLogo) {
                    lastLogo = h.logo
                    img.load(h.logo) { crossfade(true) }
                }
            } else {
                title.text = "Ton univers TV, Films et Séries"
                meta.text = "Choisis une section pour commencer"
            }

            if (!firstFocusDone) {
                firstFocusDone = true
                v.findViewById<View>(R.id.heroPlayTv)?.requestFocus()
            }
        }

        private fun wire(id: Int, onClick: () -> Unit) {
            val view = v.findViewById<View>(id) ?: return
            view.setOnClickListener { onClick() }
            focusEffect(view, 1.06f)
        }
    }

    inner class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        private val rowTitle: TextView = v.findViewById(R.id.rowTitle)
        private val rowRv: RecyclerView = v.findViewById(R.id.rowRv)
        fun bind(row: Row) {
            rowTitle.text = row.title
            rowRv.layoutManager = LinearLayoutManager(rowRv.context, RecyclerView.HORIZONTAL, false)
            rowRv.adapter = CardAdapter(row.items, row.landscape)
        }
    }

    inner class CardAdapter(private val data: List<Item>, private val landscape: Boolean) :
        RecyclerView.Adapter<CardAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.posterIv)
            val name: TextView = v.findViewById(R.id.nameTv)
            val sub: TextView = v.findViewById(R.id.subTv)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val layout = if (landscape) R.layout.item_nflx_land else R.layout.item_nflx_card
            return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
        }
        override fun getItemCount(): Int = data.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = data[position]
            holder.name.text = item.name
            if (item.duration.isNotBlank()) {
                holder.sub.text = item.duration
                holder.sub.visibility = View.VISIBLE
            } else holder.sub.visibility = View.GONE
            if (item.logo.isBlank()) holder.poster.setImageResource(R.drawable.ic_movie)
            else holder.poster.load(item.logo) { crossfade(false); error(R.drawable.ic_movie) }
            holder.itemView.setOnClickListener { openItem(item) }
            holder.itemView.setOnFocusChangeListener { view, has ->
                val s = if (has) 1.10f else 1f
                view.animate().scaleX(s).scaleY(s).setDuration(120).start()
                view.translationZ = if (has) 18f else 0f
                if (has) {
                    heroItem = item
                    header?.bind()
                }
            }
        }
    }
}
