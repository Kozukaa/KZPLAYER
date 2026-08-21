package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Accueil du theme Netflix : grande banniere + raccourcis + rangees "Continuer a regarder" / "Ma liste".
// 100% autonome et 100% local (historique + favoris) : aucun appel reseau ici, donc aucun risque
// pour la lecture IPTV, la licence ou les serveurs. Les tuiles ouvrent les ecrans Netflix dedies.
open class NetflixHomeActivity : NtBase() {

    data class Row(val title: String, val items: List<Item>, val landscape: Boolean)

    private lateinit var homeRv: RecyclerView
    private val rows = ArrayList<Row>()
    private var adapter: HomeAdapter? = null
    private var header: HeaderVH? = null
    private var heroItem: Item? = null
    private var firstFocusDone = false
    // v157 : indique si on a deja tente de charger le "dernier film du catalogue" en vedette.
    // Une seule tentative par vie d'activity (evite les appels reseau a chaque onResume).
    private var featuredMovieAttempted = false
    // Cache local du film mis en vedette (dernier film du catalogue trie par date d'ajout).
    private var featuredMovie: Item? = null
    // v340 : derniers films / dernieres series du catalogue actif (rangees du haut).
    private var recentMovies: List<Item> = emptyList()
    private var recentSeries: List<Item> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_netflix_home)
        homeRv = findViewById(R.id.homeRv)
        homeRv.layoutManager = LinearLayoutManager(this)
        adapter = HomeAdapter()
        homeRv.adapter = adapter
        wireNavBar()
        ensureSession { }
    }

    override fun onResume() {
        super.onResume()
        buildRows()
    }

    // Rangees construites depuis l'historique et les favoris (instantane, hors ligne).
    private fun buildRows() {
        val newRows = ArrayList<Row>()
        // v340 : en haut, les derniers ajouts du catalogue (Films recents puis Series recentes).
        if (recentMovies.isNotEmpty()) newRows.add(Row("Films récents", recentMovies, false))
        if (recentSeries.isNotEmpty()) newRows.add(Row("Séries récentes", recentSeries, false))
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
        // v157 : priorite au DERNIER FILM du catalogue du serveur actif (Films recents).
        // Fallbacks : reprise de lecture -> Ma liste -> chaines favorites -> chaines live chargees.
        heroItem = featuredMovie
            ?: continueAll.firstOrNull()
            ?: myList.firstOrNull()
            ?: favLive.firstOrNull()
            ?: Session.liveChannels.firstOrNull { it.logo.isNotBlank() }
        // Declenche le chargement du dernier film (une seule fois).
        if (!featuredMovieAttempted) {
            featuredMovieAttempted = true
            loadCatalogRows()
        }
        adapter?.notifyDataSetChanged()
        header?.bind()
    }

    // v157 : charge en tache de fond le dernier film ajoute au catalogue du serveur actif
    // (Films recents), le trie par date d'ajout decroissante et prend le premier. Le resultat
    // remplace le contenu du hero "En vedette" via un rebind.
    // - N'appelle rien si aucun serveur actif.
    // - Utilise l'API existante (m3uItems / xtreamItems / stalkerItems) sur la categorie "__all__",
    //   deja mise en cache par Api.catalogFor -> pas de sur-cout reseau si deja precharge.
    // - Aucune modification du lecteur, de la licence, ni des flux : purement lecture liste films.
    // v340 : charge le catalogue du serveur actif pour alimenter le hero "En vedette"
    // ET les deux rangees du haut : Films recents / Series recentes (tri par date d'ajout).
    private fun loadCatalogRows() {
        val pl = Session.current ?: return
        lifecycleScope.launch {
            val movies = fetchKind(pl, "movie")
            val series = fetchKind(pl, "series")
            recentMovies = movies.take(20)
            recentSeries = series.take(20)
            val latest = movies.firstOrNull { it.logo.isNotBlank() } ?: movies.firstOrNull()
            if (latest != null) {
                featuredMovie = latest
                heroItem = latest
            }
            buildRows()
        }
    }

    private suspend fun fetchKind(pl: Playlist, wanted: String): List<Item> = try {
        val items: List<Item> = withContext(Dispatchers.IO) {
            when (pl.type) {
                "m3u" -> Api.m3uItems(pl, wanted, "__all__")
                "stalker" -> Api.stalkerItems(pl, wanted, "__all__")
                else -> Api.xtreamItems(pl, wanted, "__all__")
            }
        }
        val anyAdded = items.any { it.added > 0L }
        if (anyAdded) items.sortedByDescending { it.added } else items
    } catch (e: Exception) { emptyList() }


    // v338 : barre de navigation haute facon Netflix (Accueil / Chaines / Films / Series /
    // Replay / Guide TV / Ma liste / Liste de lecture / Parametres).
    private fun wireNavBar() {
        fun nav(id: Int, action: () -> Unit) {
            val v = findViewById<View>(id) ?: return
            v.setOnClickListener { action() }
            focusEffect(v, 1.05f)
        }
        nav(R.id.navHome) { homeRv.scrollToPosition(0) }
        nav(R.id.navChannels) { open(NflxLiveActivity::class.java) }
        nav(R.id.navMovies) { open(NflxMoviesActivity::class.java) }
        nav(R.id.navSeries) { open(NflxSeriesActivity::class.java) }
        nav(R.id.navReplay) { openBrowse("replay", "Replay") }
        nav(R.id.navGuide) { open(NewGuideActivity::class.java) }
        nav(R.id.navMyList) { openBrowse("favorites", "Ma liste") }
        nav(R.id.navPlaylist) { open(PlaylistSettingsActivity::class.java) }
        nav(R.id.navSettings) { open(SettingsActivity::class.java) }
    }

    private fun openBrowse(kind: String, title: String) {
        try {
            Session.browseTitle = title
            startActivity(Intent(this, BrowseActivity::class.java).putExtra("kind", kind))
        } catch (e: Exception) {}
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
        // v156 : refs optionnelles (peuvent etre null sur d'anciens layouts).
        private val posterCard: View? = v.findViewById(R.id.heroPosterCard)
        private val badges: View? = v.findViewById(R.id.heroBadges)
        private var lastLogo: String = ""

        fun bind() {
            wire(R.id.heroPlayTv) { open(NflxLiveActivity::class.java) }
            wire(R.id.heroMovies) { open(NflxMoviesActivity::class.java) }
            wire(R.id.heroSeries) { open(NflxSeriesActivity::class.java) }
            // v341 : la rangee "Parcourir" a ete supprimee -> plus de tuiles a brancher.

            val h = heroItem
            if (h != null) {
                // v156 : on a un item en vedette -> on affiche tout (badges + poster + titre).
                title.visibility = View.VISIBLE
                title.text = h.name
                badges?.visibility = View.VISIBLE
                posterCard?.visibility = View.VISIBLE
                img.visibility = View.VISIBLE
                meta.text = when {
                    h.kind == "live" || h.kind == "channel" -> "En direct • ${h.name}"
                    h.duration.isNotBlank() -> "Reprendre • ${h.duration}"
                    else -> "Reprendre la lecture"
                }
                if (h.logo.isNotBlank() && h.logo != lastLogo) {
                    lastLogo = h.logo
                    img.load(h.logo) { crossfade(true) }
                }
            } else {
                // v156 : aucun item en vedette -> on masque TOUT le visuel "vitrine"
                // (badges + poster + titre) pour ne plus afficher le rectangle gris vide.
                // Seul le message "Choisis une section pour commencer" reste.
                title.text = ""
                title.visibility = View.GONE
                badges?.visibility = View.GONE
                posterCard?.visibility = View.GONE
                meta.text = "Choisis une section pour commencer"
                lastLogo = ""
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
