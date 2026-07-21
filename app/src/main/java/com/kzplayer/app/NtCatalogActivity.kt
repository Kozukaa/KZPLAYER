package com.kzplayer.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import coil.load
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Ecran catalogue NewTivi (Films / Series) : categories a gauche, grand visuel en haut,
// grille d'affiches en dessous. Reutilise 100% la logique de chargement existante (Api).
abstract class NtCatalogActivity : NtBase() {
    abstract val kind: String
    abstract val navTag: String
    abstract val screenTitle: String
    abstract fun openItem(item: Item)

    // Liste actuellement affichee (utile aux sous-ecrans, ex. zapping TV).
    protected fun visibleItems(): List<Item> = filtered

    private lateinit var catRv: RecyclerView
    private lateinit var itemRv: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var msgTv: TextView
    private lateinit var searchEt: EditText
    private lateinit var heroImg: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroDesc: TextView

    private var categories: List<Category> = emptyList()
    private var items: List<Item> = emptyList()
    private var filtered: List<Item> = emptyList()
    private var selectedCat: String = ""
    private var voiceQuery: String = ""
    private var multiMode: Boolean = false
    private var searchEpoch: Int = 0
    private var searchJob: kotlinx.coroutines.Job? = null
    private var catAdapter: CatAdapter? = null
    private var itemAdapter: TileAdapter? = null
    private lateinit var glm: GridLayoutManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_catalog)
        NavHelper.setup(this, navTag)
        findViewById<TextView>(R.id.titleTv).text = screenTitle
        catRv = findViewById(R.id.catRv)
        itemRv = findViewById(R.id.itemRv)
        progress = findViewById(R.id.progress)
        msgTv = findViewById(R.id.msgTv)
        searchEt = findViewById(R.id.searchEt)
        heroImg = findViewById(R.id.heroImg)
        heroTitle = findViewById(R.id.heroTitle)
        heroMeta = findViewById(R.id.heroMeta)
        heroDesc = findViewById(R.id.heroDesc)
        catRv.layoutManager = LinearLayoutManager(this)
        glm = GridLayoutManager(this, computeSpan())
        itemRv.layoutManager = glm
        itemRv.setItemViewCacheSize(24)
        itemAdapter = TileAdapter { onTileClick(it) }
        itemRv.adapter = itemAdapter
        searchEt.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = searchEt.text.toString().trim()
                // Films/Series NewTivi : la recherche interroge TOUS les serveurs (comme le classique).
                if (q.length >= 2) {
                    runMultiServerSearch(q)
                } else {
                    multiMode = false
                    searchJob?.cancel()
                    applyFilter()
                }
            }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })
        voiceQuery = intent.getStringExtra("voiceQuery")?.trim().orEmpty()
        if (voiceQuery.isNotBlank()) { searchEt.setText(voiceQuery); searchEt.setSelection(voiceQuery.length) }
        ensureSession { loadCategories() }
    }

    private fun setLoading(b: Boolean) { progress.visibility = if (b) View.VISIBLE else View.GONE }

    private fun computeSpan(): Int {
        val m = resources.displayMetrics
        val totalDp = m.widthPixels / m.density
        val content = (totalDp - 64f - 170f).coerceAtLeast(200f)
        return (content / 108f).toInt().coerceIn(2, 9)
    }

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
                categories = listOf(
                    Category("__favorites__", "Favoris"),
                    Category("__recent__", "Vu r\u00e9cemment")
                ) + base.filter { !it.id.startsWith("__") }
                catAdapter = CatAdapter(categories) { selectCategory(it) }
                catRv.adapter = catAdapter
                setLoading(false)
                val firstReal = categories.firstOrNull { !it.id.startsWith("__") } ?: categories.firstOrNull()
                if (firstReal != null) selectCategory(firstReal)
            } catch (e: Exception) { setLoading(false); msgTv.text = "Erreur : ${e.message}" }
        }
    }

    private fun selectCategory(cat: Category) {
        selectedCat = cat.id
        catAdapter?.notifyDataSetChanged()
        val pl = Session.current ?: return
        if (cat.id == "__favorites__") {
            items = Favorites.forKind(this, kind); applyFilter()
            msgTv.text = if (items.isEmpty()) "Aucun favori." else ""; setLoading(false); return
        }
        if (cat.id == "__recent__") {
            items = WatchHistory.recentItems(this, kind); applyFilter()
            msgTv.text = if (items.isEmpty()) "Rien vu r\u00e9cemment." else ""; setLoading(false); return
        }
        setLoading(true); items = emptyList(); applyFilter()
        lifecycleScope.launch {
            try {
                when (pl.type) {
                    "stalker" -> {
                        val acc = ArrayList<Item>()
                        Api.stalkerItemsPaged(pl, kind, cat.id) { batch ->
                            withContext(Dispatchers.Main) { acc.addAll(batch); items = acc.toList(); applyFilter(); setLoading(false) }
                        }
                    }
                    "m3u" -> { items = Api.m3uItems(pl, kind, cat.id); applyFilter(); setLoading(false) }
                    else -> { items = Api.xtreamItems(pl, kind, cat.id); applyFilter(); setLoading(false) }
                }
                if (items.isEmpty()) msgTv.text = "Aucun contenu." else msgTv.text = ""
            } catch (e: Exception) { setLoading(false); msgTv.text = "Erreur : ${e.message}" }
        }
    }

    // Recherche multi-serveurs (Films/Series) : interroge TOUS les serveurs ajoutes, fusionne les
    // doublons et affiche chaque resultat avec le(s) serveur(s) ou il se trouve (identique au classique).
    private fun runMultiServerSearch(q: String) {
        multiMode = true
        searchJob?.cancel()
        val epoch = ++searchEpoch
        val playlists = Session.playlists
        if (playlists.isEmpty()) { msgTv.text = "Aucun serveur ajoute."; return }
        setLoading(true); msgTv.text = ""
        searchJob = lifecycleScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(250) // anti-rebond pendant la frappe
            if (epoch != searchEpoch) return@launch
            try {
                Api.searchAllServers(playlists, q, kind) { done, total, merged ->
                    withContext(Dispatchers.Main) {
                        if (!multiMode || epoch != searchEpoch) return@withContext
                        filtered = merged
                        itemAdapter?.submit(filtered)
                        filtered.firstOrNull()?.let { updateHero(it) }
                        if (merged.isNotEmpty()) { setLoading(false); msgTv.text = "" }
                        else if (done >= total) { setLoading(false); msgTv.text = "Aucun resultat pour \"$q\"." }
                        else msgTv.text = ""
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { msgTv.text = "Erreur : ${e.message}"; setLoading(false) }
            }
        }
    }

    // Clic sur une tuile : si le resultat vient d'un autre serveur, on bascule dessus avant d'ouvrir.
    private fun onTileClick(item: Item) {
        if (item.ownerPlaylistId.isNotBlank() && item.ownerPlaylistId != Session.current?.id) {
            Session.playlists.firstOrNull { it.id == item.ownerPlaylistId }?.let { Session.current = it }
        }
        openItem(item)
    }

    private fun ntNorm(s: String): String =
        java.text.Normalizer.normalize(s.trim().lowercase(), java.text.Normalizer.Form.NFD)
            .replace("\\p{Mn}".toRegex(), "")

    private fun applyFilter() {
        if (multiMode) return
        val q = ntNorm(searchEt.text.toString())
        filtered = if (q.isBlank()) items else items.filter { it.kind != "header" && ntNorm(it.name).contains(q) }
        itemAdapter?.submit(filtered)
        filtered.firstOrNull()?.let { updateHero(it) }
    }

    private fun updateHero(item: Item) {
        heroTitle.text = item.name
        heroMeta.text = item.duration
        heroDesc.text = when {
            item.description.isNotBlank() -> item.description
            item.summary.isNotBlank() -> item.summary
            else -> ""
        }
        heroImg.load(item.logo) { crossfade(true); placeholder(R.drawable.bg_tile); error(R.drawable.ic_movie) }
    }

    inner class CatAdapter(val data: List<Category>, val onClick: (Category) -> Unit) :
        RecyclerView.Adapter<CatAdapter.VH>() {
        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false) as TextView
            return VH(tv)
        }
        override fun getItemCount() = data.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = data[position]
            holder.tv.text = c.name
            val sel = c.id == selectedCat
            holder.tv.isSelected = sel
            holder.tv.setTextColor(ContextCompat.getColor(holder.tv.context, if (sel) R.color.text else R.color.muted))
            holder.tv.setOnClickListener { onClick(c) }
        }
    }

    inner class TileAdapter(val onClick: (Item) -> Unit) : RecyclerView.Adapter<TileAdapter.VH>() {
        private val data = ArrayList<Item>()
        fun submit(list: List<Item>) { data.clear(); data.addAll(list); notifyDataSetChanged() }
        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.nameTv)
            val poster: ImageView = v.findViewById(R.id.posterIv)
            val progressWrap: View = v.findViewById(R.id.progressWrap)
            val serverChip: TextView = v.findViewById(R.id.serverChip)
            val quality: TextView = v.findViewById(R.id.qualityBadge)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false))
        override fun getItemCount() = data.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = data[position]
            holder.name.text = item.name
            holder.progressWrap.visibility = View.GONE
            if (item.serverLabel.isNotBlank()) { holder.serverChip.text = item.serverLabel; holder.serverChip.visibility = View.VISIBLE }
            else holder.serverChip.visibility = View.GONE
            val q = when {
                Regex("(?i)(4k|uhd|2160)").containsMatchIn(item.name) -> "4K"
                Regex("(?i)(fhd|1080)").containsMatchIn(item.name) -> "FHD"
                Regex("(?i)(\\bhd\\b|720)").containsMatchIn(item.name) -> "HD"
                else -> ""
            }
            holder.quality.text = q
            holder.quality.visibility = if (q.isBlank()) View.GONE else View.VISIBLE
            val fallback = R.drawable.ic_movie
            holder.poster.scaleType = ImageView.ScaleType.CENTER_CROP
            if (item.logo.isBlank()) holder.poster.setImageResource(fallback)
            else holder.poster.load(item.logo) { crossfade(false); placeholder(R.drawable.bg_tile); error(fallback) }
            holder.v.setOnFocusChangeListener { _, hasFocus ->
                holder.v.animate().scaleX(if (hasFocus) 1.05f else 1f).scaleY(if (hasFocus) 1.05f else 1f).setDuration(90).start()
                holder.v.translationZ = if (hasFocus) 16f else 0f
                holder.name.setTextColor(if (hasFocus) KzColors.accent(holder.name.context) else ContextCompat.getColor(holder.name.context, R.color.text))
                if (hasFocus) updateHero(item)
            }
            holder.v.setOnClickListener { onClick(item) }
        }
    }
}
