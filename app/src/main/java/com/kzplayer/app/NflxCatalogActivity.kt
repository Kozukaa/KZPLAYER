package com.kzplayer.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import coil.load
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

// Base des ecrans "style Netflix" AUTONOMES (Films / Series / TV en direct).
// Presente chaque categorie en RANGEE horizontale d'affiches, avec une grande
// banniere en haut qui suit l'element survole a la telecommande.
// IMPORTANT : reutilise UNIQUEMENT la couche de chargement existante (Api / Session).
// Aucun flux (Stalker/M3U/Xtream), lecteur ExoPlayer, licence ou protection n'est modifie.
abstract class NflxCatalogActivity : NtBase() {
    abstract val kind: String            // movie | series | live
    abstract val screenTitle: String
    protected open val landscape: Boolean = false
    abstract fun onCardClick(item: Item)

    private lateinit var rowsRv: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var msgTv: TextView

    private val cats = ArrayList<Category>()
    private val rowCache = HashMap<String, List<Item>>()

    // Refs de la banniere (renseignees quand l'entete est affiche).
    private var heroImg: ImageView? = null
    private var heroTitle: TextView? = null
    private var heroMeta: TextView? = null
    private var heroItem: Item? = null
    private var heroLogo: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nflx_catalog)
        rowsRv = findViewById(R.id.rowsRv)
        progress = findViewById(R.id.progress)
        msgTv = findViewById(R.id.msgTv)
        rowsRv.layoutManager = LinearLayoutManager(this)
        ensureSession { loadCategories() }
    }

    private fun setLoading(b: Boolean) { progress.visibility = if (b) View.VISIBLE else View.GONE }

    // Tous les items deja charges (toutes rangees confondues) - utilise par l'ecran TV pour le zapping.
    protected fun allLoadedItems(): List<Item> = rowCache.values.flatten()

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
                cats.clear()
                cats.add(Category("__fav__", "Ma liste"))
                cats.addAll(base.filter { !it.id.startsWith("__") })
                setLoading(false)
                msgTv.text = if (cats.size <= 1) "Aucun contenu." else ""
                rowsRv.adapter = RowsAdapter()
            } catch (e: Exception) { setLoading(false); msgTv.text = "Erreur : ${e.message}" }
        }
    }

    private fun setHero(item: Item) {
        heroItem = item
        heroTitle?.text = item.name
        heroMeta?.text = item.duration
        if (item.logo != heroLogo) {
            heroLogo = item.logo
            heroImg?.load(item.logo) { crossfade(true); placeholder(R.drawable.bg_tile); error(R.drawable.ic_movie) }
        }
    }

    // Charge (une seule fois, avec cache) les items d'une categorie et les fournit a la rangee.
    private fun loadRow(cat: Category, submit: (List<Item>) -> Unit) {
        rowCache[cat.id]?.let { cached ->
            submit(cached)
            if (heroItem == null) cached.firstOrNull()?.let { setHero(it) }
            return
        }
        if (cat.id == "__fav__") {
            val l = Favorites.forKind(this, kind)
            rowCache[cat.id] = l; submit(l)
            if (heroItem == null) l.firstOrNull()?.let { setHero(it) }
            return
        }
        val pl = Session.current ?: return
        lifecycleScope.launch {
            val list = try {
                when (pl.type) {
                    "m3u" -> Api.m3uItems(pl, kind, cat.id)
                    "stalker" -> {
                        val acc = ArrayList<Item>()
                        Api.stalkerItemsPaged(pl, kind, cat.id) { batch -> acc.addAll(batch) }
                        acc
                    }
                    else -> Api.xtreamItems(pl, kind, cat.id)
                }
            } catch (e: Exception) { emptyList() }
            val capped = list.filter { it.kind != "header" }.take(40)
            rowCache[cat.id] = capped
            submit(capped)
            if (heroItem == null) capped.firstOrNull()?.let { setHero(it) }
        }
    }

    // ----- Adaptateur des rangees (entete + une rangee par categorie) -----
    inner class RowsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val HEADER = 0
        private val ROW = 1
        override fun getItemCount() = cats.size + 1
        override fun getItemViewType(position: Int) = if (position == 0) HEADER else ROW
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == HEADER) HeaderVH(inf.inflate(R.layout.item_nflx_header, parent, false))
            else RowVH(inf.inflate(R.layout.item_nflx_row, parent, false))
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is HeaderVH) {
                heroImg = holder.img; heroTitle = holder.title; heroMeta = holder.meta
                holder.brand.text = screenTitle.uppercase()
                holder.back.setOnClickListener { finish() }
                heroItem?.let { setHero(it) }
            } else if (holder is RowVH) {
                val cat = cats[position - 1]
                holder.title.text = cat.name
                val adapter = CardAdapter()
                holder.rv.adapter = adapter
                loadRow(cat) { list -> adapter.submit(list) }
            }
        }
    }

    inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.heroImg)
        val title: TextView = v.findViewById(R.id.heroTitle)
        val meta: TextView = v.findViewById(R.id.heroMeta)
        val brand: TextView = v.findViewById(R.id.heroBrand)
        val back: TextView = v.findViewById(R.id.backBtn)
    }

    inner class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val rv: RecyclerView = v.findViewById(R.id.rowRv)
        init {
            rv.layoutManager = LinearLayoutManager(v.context, RecyclerView.HORIZONTAL, false)
            rv.setHasFixedSize(true)
        }
    }

    // ----- Adaptateur des affiches d'une rangee -----
    inner class CardAdapter : RecyclerView.Adapter<CardAdapter.VH>() {
        private val data = ArrayList<Item>()
        fun submit(list: List<Item>) { data.clear(); data.addAll(list); notifyDataSetChanged() }
        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.posterIv)
            val name: TextView = v.findViewById(R.id.nameTv)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val res = if (landscape) R.layout.item_nflx_land else R.layout.item_nflx_card
            return VH(LayoutInflater.from(parent.context).inflate(res, parent, false))
        }
        override fun getItemCount() = data.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = data[position]
            holder.name.text = item.name
            val fallback = if (landscape) R.drawable.ic_live_tv else R.drawable.ic_movie
            if (item.logo.isBlank()) holder.poster.setImageResource(fallback)
            else holder.poster.load(item.logo) { crossfade(false); placeholder(R.drawable.bg_tile); error(fallback) }
            holder.v.setOnClickListener { onCardClick(item) }
            holder.v.setOnFocusChangeListener { view, has ->
                val s = if (has) 1.10f else 1f
                view.animate().scaleX(s).scaleY(s).setDuration(130)
                    .setInterpolator(DecelerateInterpolator()).start()
                view.translationZ = if (has) 18f else 0f
                if (has) setHero(item)
            }
        }
    }
}
