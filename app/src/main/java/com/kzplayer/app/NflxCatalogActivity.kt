package com.kzplayer.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch

// Ecran catalogue facon Netflix : grande banniere en haut + rangees horizontales par categorie.
// Reutilise a 100% la logique de chargement existante (Api) pour ne rien casser cote flux IPTV.
abstract class NflxCatalogActivity : NtBase() {
    abstract val kind: String
    abstract val screenTitle: String
    open val landscape: Boolean = false
    abstract fun onCardClick(item: Item)

    private lateinit var rowsRv: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var msgTv: TextView
    private val rows = ArrayList<Row>()
    private var rowsAdapter: RowsAdapter? = null
    private var headerVH: HeaderVH? = null
    private var heroItem: Item? = null
    private val allItems = ArrayList<Item>()

    data class Row(val title: String, val items: List<Item>)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nflx_catalog)
        rowsRv = findViewById(R.id.rowsRv)
        progress = findViewById(R.id.progress)
        msgTv = findViewById(R.id.msgTv)
        rowsRv.layoutManager = LinearLayoutManager(this)
        rowsAdapter = RowsAdapter()
        rowsRv.adapter = rowsAdapter
        ensureSession { loadCategories() }
    }

    protected fun allLoadedItems(): List<Item> = allItems.toList()

    private fun setLoading(b: Boolean) { progress.visibility = if (b) View.VISIBLE else View.GONE }

    private fun loadCategories() {
        val pl = Session.current ?: run { msgTv.text = "Serveurs indisponibles."; return }
        setLoading(true); msgTv.text = ""
        lifecycleScope.launch {
            try {
                val base = when (pl.type) {
                    "m3u" -> Api.m3uCategories(pl, kind)
                    "stalker" -> Api.stalkerCategories(pl, kind)
                    else -> Api.xtreamCategories(pl, kind)
                }
                val favs = Favorites.forKind(this@NflxCatalogActivity, kind)
                if (favs.isNotEmpty()) addRow("Ma liste", favs)
                val realCats = base.filter { !it.id.startsWith("__") }.take(12)
                setLoading(false)
                for (c in realCats) {
                    val items = loadItems(pl, c.id)
                    if (items.isNotEmpty()) addRow(c.name, items)
                }
                if (rows.isEmpty()) msgTv.text = "Aucun contenu." else msgTv.text = ""
            } catch (e: Exception) { setLoading(false); msgTv.text = "Erreur : ${e.message}" }
        }
    }

    private suspend fun loadItems(pl: Playlist, catId: String): List<Item> {
        return try {
            val raw = when (pl.type) {
                "m3u" -> Api.m3uItems(pl, kind, catId)
                "stalker" -> {
                    val acc = ArrayList<Item>()
                    Api.stalkerItemsPaged(pl, kind, catId, 2) { batch -> acc.addAll(batch) }
                    acc
                }
                else -> Api.xtreamItems(pl, kind, catId)
            }
            raw.filter { it.kind != "header" }.take(24)
        } catch (e: Exception) { emptyList() }
    }

    private fun addRow(title: String, items: List<Item>) {
        allItems.addAll(items)
        if (heroItem == null) { heroItem = items.firstOrNull(); heroItem?.let { headerVH?.setHero(it) } }
        rows.add(Row(title, items))
        rowsAdapter?.notifyItemInserted(rows.size)
    }

    inner class RowsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int): Int = if (position == 0) 0 else 1
        override fun getItemCount(): Int = rows.size + 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) HeaderVH(inf.inflate(R.layout.item_nflx_header, parent, false))
            else RowVH(inf.inflate(R.layout.item_nflx_row, parent, false))
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is HeaderVH) { headerVH = holder; holder.bind() }
            else if (holder is RowVH) holder.bind(rows[position - 1])
        }
    }

    inner class HeaderVH(val v: View) : RecyclerView.ViewHolder(v) {
        private val img: ImageView = v.findViewById(R.id.heroImg)
        private val brand: TextView = v.findViewById(R.id.heroBrand)
        private val title: TextView = v.findViewById(R.id.heroTitle)
        private val meta: TextView = v.findViewById(R.id.heroMeta)
        private var lastLogo: String = ""
        fun bind() {
            brand.text = screenTitle
            v.findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
            val h = heroItem
            if (h != null) setHero(h) else { title.text = screenTitle; meta.text = "" }
        }
        fun setHero(item: Item) {
            title.text = item.name
            meta.text = item.duration
            if (item.logo != lastLogo) {
                lastLogo = item.logo
                if (item.logo.isNotBlank()) img.load(item.logo) { crossfade(true); placeholder(R.drawable.bg_tile); error(R.drawable.ic_movie) }
                else img.setImageResource(R.drawable.ic_movie)
            }
        }
    }

    inner class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        private val rowTitle: TextView = v.findViewById(R.id.rowTitle)
        private val rowRv: RecyclerView = v.findViewById(R.id.rowRv)
        fun bind(row: Row) {
            rowTitle.text = row.title
            rowRv.layoutManager = LinearLayoutManager(rowRv.context, RecyclerView.HORIZONTAL, false)
            rowRv.setHasFixedSize(false)
            rowRv.adapter = CardAdapter(row.items)
        }
    }

    inner class CardAdapter(val data: List<Item>) : RecyclerView.Adapter<CardAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.posterIv)
            val name: TextView = v.findViewById(R.id.nameTv)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val layout = if (landscape) R.layout.item_nflx_land else R.layout.item_nflx_card
            return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
        }
        override fun getItemCount(): Int = data.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = data[position]
            holder.name.text = item.name
            holder.poster.scaleType = ImageView.ScaleType.CENTER_CROP
            if (item.logo.isBlank()) holder.poster.setImageResource(R.drawable.ic_movie)
            else holder.poster.load(item.logo) { crossfade(false); placeholder(R.drawable.bg_tile); error(R.drawable.ic_movie) }
            holder.itemView.setOnClickListener { onCardClick(item) }
            holder.itemView.setOnFocusChangeListener { view, has ->
                val s = if (has) 1.10f else 1f
                view.animate().scaleX(s).scaleY(s).setDuration(120).start()
                view.translationZ = if (has) 18f else 0f
                if (has) { heroItem = item; headerVH?.setHero(item) }
            }
        }
    }
}
