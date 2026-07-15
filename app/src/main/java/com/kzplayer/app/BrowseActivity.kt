package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.widget.ImageView
import coil.load
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

class BrowseActivity : AppCompatActivity() {
    private lateinit var kind: String
    private var categories: List<Category> = emptyList()
    private var items: List<Item> = emptyList()
    private var filtered: List<Item> = emptyList()
    private var selectedCat: String = ""
    private var sortMode: Int = 0
    private var loadingAllForSearch: Boolean = false
    private var searchJob: Job? = null
    private var multiMode: Boolean = false

    private lateinit var catRv: RecyclerView
    private lateinit var itemRv: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var msgTv: TextView
    private lateinit var searchEt: EditText
    private lateinit var sortBtn: TextView
    private lateinit var viewBtn: TextView
    private lateinit var glm: GridLayoutManager
    private var liveListMode: Boolean = false
    private var catAdapter: CatAdapter? = null
    private var itemAdapter: ItemAdapter? = null
    private var lastItemFocusPos: Int = 0
    private var focusItemsAfterLoad: Boolean = false
    private var ownPlaylistId: String = ""
    private var searchEpoch = 0
    private var lastRealCategories: List<Category> = emptyList()
    private var lastBaseCategories: List<Category> = emptyList()
    private var didWhitelistRefresh = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
        kind = intent.getStringExtra("kind") ?: "live"
        findViewById<TextView>(R.id.titleTv).text = Session.browseTitle

        catRv = findViewById(R.id.catRv)
        itemRv = findViewById(R.id.itemRv)
        progress = findViewById(R.id.progress)
        msgTv = findViewById(R.id.msgTv)
        searchEt = findViewById(R.id.searchEt)
        sortBtn = findViewById(R.id.sortBtn)
        viewBtn = findViewById(R.id.viewBtn)
        updateSortLabel()
        updateViewLabel()
        viewBtn.visibility = if (kind == "live") View.VISIBLE else View.GONE
        viewBtn.setOnClickListener {
            liveListMode = !liveListMode
            updateViewLabel()
            applyLayoutMode()
            itemAdapter?.notifyDataSetChanged()
        }
        sortBtn.setOnClickListener {
            sortMode = (sortMode + 1) % 3
            updateSortLabel()
            if (multiMode) { filtered = applySort(filtered); itemAdapter?.submit(filtered) }
            else applyFilter()
        }

        val catBtn = findViewById<TextView>(R.id.catBtn)
        catBtn.visibility = if (kind == "favorites") View.GONE else View.VISIBLE
        catBtn.setOnClickListener { showManageCategoriesDialog() }

        catRv.layoutManager = LinearLayoutManager(this)
        catRv.setHasFixedSize(true)
        catRv.itemAnimator = null
        glm = GridLayoutManager(this, computeSpan())
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (filtered.getOrNull(position)?.kind == "header") glm.spanCount else 1
            }
        }
        itemRv.layoutManager = glm
        itemRv.setHasFixedSize(true)
        itemRv.itemAnimator = null
        itemRv.setItemViewCacheSize(24)
        itemAdapter = ItemAdapter(filtered) { openItem(it) }
        itemRv.adapter = itemAdapter

        searchEt.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = searchEt.text.toString().trim()
                // Films/Series : la recherche classique cherche sur TOUS les serveurs.
                if ((kind == "movie" || kind == "series") && q.length >= 2) {
                    runMultiServerSearch(q)
                } else {
                    multiMode = false
                    searchJob?.cancel()
                    ensureAllLoadedForSearch()
                    applyFilter()
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        loadCategories()
    }

    private fun loadCategories() {
        val existing = Session.current ?: Session.playlists.firstOrNull()?.also { Session.current = it }
        if (existing == null) {
            // Session vide (appli relancee / process tue / retour tardif) : on recharge licence +
            // serveurs puis on reessaie, au lieu de laisser un ecran sans aucune categorie.
            setLoading(true)
            msgTv.text = "Chargement des serveurs..."
            lifecycleScope.launch {
                try {
                    val res = Api.checkLicense(
                        DeviceIdentity.stableId(this@BrowseActivity),
                        DeviceIdentity.licenseCode(this@BrowseActivity),
                        android.os.Build.MODEL ?: "Android TV", "1.0"
                    )
                    if (res.ok && res.active) {
                        Session.playlists = res.playlists
                        Session.expiration = res.expiration
                        Session.current = Session.playlists.firstOrNull()
                    }
                } catch (e: Exception) {}
                if (Session.current != null) loadCategories()
                else { setLoading(false); msgTv.text = "Serveurs indisponibles. Reviens a l'accueil puis reessaie." }
            }
            return
        }
        val pl = existing
        if (ownPlaylistId.isBlank()) ownPlaylistId = pl.id
        val realKind = if (kind == "replay") "live" else kind
        setLoading(true)
        msgTv.text = ""
        lifecycleScope.launch {
            try {
                if (kind == "favorites") {
                    categories = listOf(
                        Category("__fav_movie__", "Films favoris"),
                        Category("__fav_series__", "Séries favorites"),
                        Category("__fav_live__", "Chaînes favorites")
                    )
                    bindCategories()
                    setLoading(false)
                    selectCategory(categories[0])
                    return@launch
                }
                when (pl.type) {
                    "m3u" -> {
                        categories = prepareCategories(Api.m3uCategories(pl, realKind), pl)
                        bindCategories()
                        if (categories.size > 1) {
                            setLoading(false)
                            msgTv.text = if (kind == "replay") "Choisis une catégorie replay à gauche." else "Choisis une categorie a gauche."
                            selectCategory(categories[0])
                        } else if (categories.isNotEmpty()) {
                            selectCategory(categories[0])
                        } else {
                            setLoading(false)
                            msgTv.text = "Aucun contenu trouve."
                        }
                    }
                    "stalker" -> {
                        categories = prepareCategories(Api.stalkerCategories(pl, realKind), pl)
                        bindCategories()
                        setLoading(false)
                        msgTv.text = if (categories.size <= 1 && Api.lastStalkerLog.isNotBlank()) {
                            "Aucune categorie Stalker.\n\n${Api.lastStalkerLog}"
                        } else {
                            if (kind == "replay") "Choisis une catégorie replay à gauche." else "Choisis une categorie a gauche."
                        }
                    }
                    else -> {
                        categories = prepareCategories(Api.xtreamCategories(pl, realKind), pl)
                        bindCategories()
                        setLoading(false)
                        msgTv.text = if (kind == "replay") "Choisis une catégorie replay à gauche." else "Choisis une categorie a gauche."
                    }
                }
                autoSyncWhitelist(pl.id)
            } catch (e: Exception) {
                msgTv.text = "Erreur de chargement : ${e.message}"
                setLoading(false)
            }
        }
    }

    // Applique automatiquement les listes "a afficher" configurees sur le PANEL, sans avoir a
    // appuyer sur Recharger : on relit la licence en arriere-plan puis on re-filtre le menu
    // (sans re-telecharger les categories). Une seule fois par ouverture d'ecran.
    private fun autoSyncWhitelist(plId: String) {
        if (didWhitelistRefresh) return
        didWhitelistRefresh = true
        lifecycleScope.launch {
            val res = try {
                Api.checkLicense(
                    DeviceIdentity.stableId(this@BrowseActivity),
                    DeviceIdentity.licenseCode(this@BrowseActivity),
                    android.os.Build.MODEL ?: "Android TV", "1.0"
                )
            } catch (e: Exception) { null }
            if (res != null && res.ok && res.active && res.playlists.isNotEmpty()) {
                val cur = res.playlists.firstOrNull { it.id == plId }
                if (cur != null) {
                    Session.playlists = res.playlists
                    Session.current = cur
                    if (!multiMode && lastBaseCategories.isNotEmpty()) {
                        categories = filterHiddenCategories(withSpecialCategories(lastBaseCategories), cur)
                        bindCategories()
                    }
                }
            }
        }
    }

    private fun withSpecialCategories(base: List<Category>): List<Category> {
        if (kind == "movie" || kind == "series") {
            return listOf(Category("__favorites__", "Favoris"), Category("__recent__", "Vu récemment")) + base
        }
        if (kind == "live") return listOf(Category("__favorites__", "Favoris")) + base
        return base
    }

    // Prepare la liste affichee a gauche : memorise les vraies categories (pour le dialogue de
    // gestion), ajoute les entrees speciales, puis retire les categories masquees.
    private fun prepareCategories(base: List<Category>, pl: Playlist): List<Category> {
        lastRealCategories = base.filter { !it.id.startsWith("__") }
        lastBaseCategories = base
        // On transmet la liste des categories de ce serveur au backend pour que le panel puisse
        // les afficher en cases a cocher (best-effort, sans bloquer l'UI).
        val names = lastRealCategories.map { it.name }
        if (names.isNotEmpty()) {
            val lic = DeviceIdentity.licenseCode(this)
            val realK = if (kind == "replay") "live" else kind
            lifecycleScope.launch { Api.reportCategories(lic, pl.id, realK, names) }
        }
        return filterHiddenCategories(withSpecialCategories(base), pl)
    }

    // Retire les categories masquees : localement (choix de l'utilisateur dans l'app) + celles
    // imposees par le panel (champ hidden_categories). Ne touche jamais aux entrees speciales
    // (Favoris, Vu recemment, Tout).
    // Liste blanche EFFECTIVE de cette section : si l'utilisateur a configure dans l'app on prend
    // ce choix (source la plus recente), sinon celui du panel. UNE seule liste, pas de combinaison.
    private fun effectiveShown(pl: Playlist): Set<String> {
        if (ShownCategories.has(this, pl.id, kind)) return ShownCategories.shownNames(this, pl.id, kind)
        val realK = if (kind == "replay") "live" else kind
        return (pl.shownByKind[realK] ?: emptyList()).map { it.lowercase().trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun filterHiddenCategories(list: List<Category>, pl: Playlist): List<Category> {
        // STRICT : si une liste "a afficher" existe, on n'affiche QUE ces categories.
        // Liste vide = aucune restriction => tout est affiche (defaut).
        val shown = effectiveShown(pl)
        if (shown.isEmpty()) return list
        return list.filter { c ->
            if (c.id.startsWith("__")) return@filter true
            c.name.lowercase().trim() in shown
        }
    }

    // Dialogue de gestion : coche = categorie affichee, decoche = masquee. Persiste par serveur+section.
    // Selecteur 2 colonnes : gauche "Categories" (pool) / droite "A afficher" (liste blanche).
    // Clique une ligne pour la deplacer d'un cote a l'autre. Vide a droite = tout est affiche.
    private fun showManageCategoriesDialog() {
        val pl = Session.current ?: return
        val all = lastRealCategories
        if (all.isEmpty()) { msgTv.text = "Aucune categorie a gerer ici."; return }
        val shownNames = effectiveShown(pl)
        val left = java.util.ArrayList<Category>()
        val right = java.util.ArrayList<Category>()
        for (c in all) { if (shownNames.contains(c.name.lowercase().trim())) right.add(c) else left.add(c) }

        val dens = resources.displayMetrics.density
        fun px(v: Int) = (v * dens).toInt()
        val accent = ContextCompat.getColor(this, R.color.accent)
        val textCol = ContextCompat.getColor(this, R.color.text)
        val mutedCol = ContextCompat.getColor(this, R.color.muted)

        val leftBox = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val rightBox = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        lateinit var render: () -> Unit
        fun makeRow(c: Category, inRight: Boolean): android.widget.TextView {
            return android.widget.TextView(this).apply {
                text = (if (inRight) "\u2212  " else "+  ") + c.name
                setTextColor(textCol)
                textSize = 14f
                setPadding(px(10), px(10), px(10), px(10))
                isFocusable = true; isClickable = true
                setBackgroundResource(R.drawable.bg_cat)
                setOnClickListener {
                    if (inRight) { right.remove(c); left.add(c) } else { left.remove(c); right.add(c) }
                    render()
                }
            }
        }
        render = {
            leftBox.removeAllViews(); rightBox.removeAllViews()
            if (left.isEmpty()) leftBox.addView(android.widget.TextView(this).apply { text = "(vide)"; setTextColor(mutedCol); setPadding(px(10), px(10), px(10), px(10)) })
            for (c in left) leftBox.addView(makeRow(c, false))
            if (right.isEmpty()) rightBox.addView(android.widget.TextView(this).apply { text = "(aucune \u2192 tout est affiche)"; setTextColor(mutedCol); setPadding(px(10), px(10), px(10), px(10)) })
            for (c in right) rightBox.addView(makeRow(c, true))
        }

        fun columnHeader(t: String) = android.widget.TextView(this).apply {
            text = t; setTextColor(accent); textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(px(10), px(4), px(10), px(6))
        }
        fun scrollCol(headerTxt: String, box: android.view.View): android.widget.LinearLayout {
            val col = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(columnHeader(headerTxt))
            val sc = android.widget.ScrollView(this)
            sc.layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, px(280))
            sc.addView(box)
            col.addView(sc)
            return col
        }

        val cols = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        cols.addView(scrollCol("Cat\u00e9gories", leftBox))
        val spacer = android.view.View(this); spacer.layoutParams = android.widget.LinearLayout.LayoutParams(px(10), 1)
        cols.addView(spacer)
        cols.addView(scrollCol("\u00c0 afficher", rightBox))

        val toolRow = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(px(2), px(8), px(2), px(2)) }
        val btnAll = android.widget.Button(this).apply { text = "Tout afficher"; setOnClickListener { right.clear(); right.addAll(all); left.clear(); render() } }
        val btnNone = android.widget.Button(this).apply { text = "Tout masquer"; setOnClickListener { left.clear(); left.addAll(all); right.clear(); render() } }
        toolRow.addView(btnAll); toolRow.addView(btnNone)

        val root = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(px(14), px(8), px(14), px(4)) }
        val hint = android.widget.TextView(this).apply { text = "Clique une cat\u00e9gorie pour la d\u00e9placer. \u00ab \u00c0 afficher \u00bb vide = tout est affich\u00e9."; setTextColor(mutedCol); textSize = 12f; setPadding(px(4), 0, px(4), px(6)) }
        root.addView(hint)
        root.addView(cols)
        root.addView(toolRow)

        render()

        AlertDialog.Builder(this)
            .setTitle("Cat\u00e9gories du serveur")
            .setView(root)
            .setPositiveButton("Valider") { d, _ ->
                val chosen = right.map { it.name }
                ShownCategories.setShown(this, pl.id, kind, chosen)
                // On enregistre aussi cote panel pour que les deux restent synchronises.
                val lic = DeviceIdentity.licenseCode(this)
                val realK = if (kind == "replay") "live" else kind
                lifecycleScope.launch { Api.setShown(lic, pl.id, realK, chosen) }
                d.dismiss()
                loadCategories()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun bindCategories() {
        catAdapter = CatAdapter(categories) { cat -> selectCategory(cat) }
        catRv.adapter = catAdapter
    }

    private fun selectCategory(cat: Category) {
        selectedCat = cat.id
        catAdapter?.notifyDataSetChanged()
        val pl = Session.current ?: return
        if (cat.id == "__favorites__") {
            items = Favorites.forKind(this, kind)
            applyFilter()
            msgTv.text = if (items.isEmpty()) "Aucun favori pour cette section." else ""
            setLoading(false)
            return
        }
        if (cat.id.startsWith("__fav_")) {
            val favKind = when (cat.id) {
                "__fav_series__" -> "series"
                "__fav_live__" -> "live"
                else -> "movie"
            }
            items = Favorites.forKind(this, favKind)
            applyFilter()
            msgTv.text = if (items.isEmpty()) "Aucun favori." else ""
            setLoading(false)
            return
        }
        if (cat.id == "__recent__") {
            items = WatchHistory.recentItems(this, kind)
            applyFilter()
            msgTv.text = if (items.isEmpty()) "Aucun contenu vu récemment." else ""
            setLoading(false)
            return
        }
        val realKind = if (kind == "replay") "live" else kind
        when (pl.type) {
            "m3u" -> {
                setLoading(true)
                lifecycleScope.launch {
                    try { items = Api.m3uItems(pl, realKind, cat.id) }
                    catch (e: Exception) { msgTv.text = "Erreur : ${e.message}" }
                    if (kind == "replay") items = items.filter { it.catchup }
                    applyFilter()
                    msgTv.text = if (items.isEmpty()) "Aucun contenu trouve." else ""
                    setLoading(false)
                }
            }
            "stalker" -> { setLoading(true); lifecycleScope.launch { loadStalkerInto(cat.id) } }
            else -> {
                setLoading(true)
                lifecycleScope.launch {
                    try { items = Api.xtreamItems(pl, realKind, cat.id) }
                    catch (e: Exception) { msgTv.text = "Erreur : ${e.message}" }
                    if (kind == "replay") items = items.filter { it.catchup }
                    applyFilter()
                    msgTv.text = if (items.isEmpty()) "Aucun contenu trouve." else ""
                    setLoading(false)
                }
            }
        }
    }

    private suspend fun loadStalkerInto(categoryId: String) {
        val pl = Session.current ?: return
        items = emptyList()
        applyFilter()
        var firstShown = false
        val realKind = if (kind == "replay") "live" else kind
        Api.stalkerItemsPaged(pl, realKind, categoryId) { batch ->
            withContext(Dispatchers.Main) {
                val newItems = if (kind == "replay") batch.filter { it.catchup } else batch
                items = items + newItems
                applyFilter()
                if (!firstShown) { setLoading(false); firstShown = true }
                msgTv.text = ""
            }
        }
        withContext(Dispatchers.Main) {
            setLoading(false)
            if (items.isEmpty()) msgTv.text = "Aucun contenu trouve."
        }
    }

    private fun applyFilter() {
        val q = cleanSearch(searchEt.text.toString())
        val searchHadFocus = searchEt.hasFocus()
        // La grille a-t-elle le focus AVANT la mise a jour ? Si oui et qu'il saute hors
        // de la grille apres (vers les categories) a cause d'un rafraichissement pendant
        // le chargement, on le remettra sur la meme tuile.
        val gridHadFocus = itemRv.hasFocus()
        val tokens = q.split(" ").filter { it.isNotBlank() }
        var list = if (tokens.isEmpty()) {
            items
        } else {
            items.filter { item ->
                if (item.kind == "header") return@filter false
                val haystack = cleanSearch(item.name + " " + item.description)
                tokens.all { token -> haystack.contains(token) }
            }
        }
        val hasHeaders = list.any { it.kind == "header" }
        if (!hasHeaders) {
            list = when (sortMode) {
                1 -> list.sortedBy { it.name.lowercase() }
                2 -> list.sortedByDescending { it.added }
                else -> list
            }
        }
        filtered = list
        itemAdapter?.submit(filtered)
        if (searchHadFocus) {
            searchEt.post { searchEt.requestFocus() }
        } else if (focusItemsAfterLoad && filtered.isNotEmpty()) {
            focusItemsAfterLoad = false
            itemRv.post {
                itemRv.scrollToPosition(0)
                itemRv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    ?: itemRv.postDelayed({ itemRv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }, 80)
            }
        } else if (gridHadFocus && filtered.isNotEmpty()) {
            // Garde anti-saut : si la liste a change pendant la navigation et que le focus
            // a quitte la grille (renvoye vers les categories), on le ramene sur la tuile.
            itemRv.post {
                if (!itemRv.hasFocus() && !searchEt.hasFocus() && !catRv.hasFocus()) {
                    val pos = lastItemFocusPos.coerceIn(0, (filtered.size - 1).coerceAtLeast(0))
                    itemRv.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                        ?: itemRv.postDelayed({ itemRv.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }, 60)
                }
            }
        }
    }

    private fun applySort(list: List<Item>): List<Item> = when (sortMode) {
        1 -> list.sortedBy { it.name.lowercase() }
        2 -> list.sortedByDescending { it.added }
        else -> list
    }

    // Recherche multi-serveurs (Films/Series) : interroge tous les serveurs ajoutes,
    // fusionne les doublons et affiche chaque resultat avec le(s) serveur(s) ou il est.
    private fun runMultiServerSearch(q: String) {
        multiMode = true
        searchJob?.cancel()
        // Garde d'epoque : seule la DERNIERE recherche lancee a le droit de mettre a jour l'ecran.
        // Avant, une ancienne recherche (requete reseau encore en cours) continuait d'ecrire ses
        // resultats -> clignotement / "chargement en boucle" quand on relancait une recherche.
        val epoch = ++searchEpoch
        val playlists = Session.playlists
        if (playlists.isEmpty()) { msgTv.text = "Aucun serveur ajoute."; return }
        setLoading(true)
        msgTv.text = ""
        searchJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(250) // anti-rebond pendant la frappe
            if (epoch != searchEpoch) return@launch
            try {
                Api.searchAllServers(playlists, q, kind) { done, total, merged ->
                    withContext(Dispatchers.Main) {
                        if (!multiMode || epoch != searchEpoch) return@withContext
                        filtered = applySort(merged)
                        itemAdapter?.submit(filtered)
                        if (merged.isNotEmpty()) {
                            // Resultats affiches immediatement, sans texte ni spinner au milieu.
                            setLoading(false)
                            msgTv.text = ""
                        } else if (done >= total) {
                            setLoading(false)
                            msgTv.text = "Aucun resultat pour \"$q\"."
                        } else {
                            msgTv.text = ""
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { msgTv.text = "Erreur : ${e.message}"; setLoading(false) }
            }
        }
    }

    private fun ensureAllLoadedForSearch() {
        val q = searchEt.text.toString().trim()
        if (q.isBlank()) return
        if (selectedCat == "__all__") return
        if (loadingAllForSearch) return
        val allCat = categories.firstOrNull { it.id == "__all__" } ?: return
        loadingAllForSearch = true
        msgTv.text = "Recherche dans tout le catalogue..."
        selectCategory(allCat)
        itemRv.postDelayed({ loadingAllForSearch = false }, 1200)
    }

    private fun cleanSearch(s: String): String {
        val noAccent = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return noAccent.lowercase()
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    private fun updateSortLabel() {
        sortBtn.text = when (sortMode) {
            1 -> "Tri : A-Z"
            2 -> "Tri : Récents"
            else -> "Tri : Défaut"
        }
    }

    private fun updateViewLabel() {
        if (::viewBtn.isInitialized) viewBtn.text = if (liveListMode) "Vue : Liste" else "Vue : Grille"
    }

    private fun applyLayoutMode() {
        if (kind == "live" && liveListMode) {
            itemRv.layoutManager = LinearLayoutManager(this)
        } else {
            glm = GridLayoutManager(this, computeSpan())
            glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (filtered.getOrNull(position)?.kind == "header") glm.spanCount else 1
                }
            }
            itemRv.layoutManager = glm
        }
    }

    private fun openItem(item: Item) {
        // Resultat multi-serveurs : on bascule d'abord sur le serveur ou l'item se trouve.
        if (item.ownerPlaylistId.isNotBlank() && item.ownerPlaylistId != Session.current?.id) {
            Session.playlists.firstOrNull { it.id == item.ownerPlaylistId }?.let { Session.current = it }
        }
        if (item.kind == "series") {
            Session.seriesItem = item
            startActivity(Intent(this, SeriesActivity::class.java))
            return
        }
        if (item.kind == "episode") {
            val pl = Session.current ?: return
            val direct = item.directUrl
            if (!direct.isNullOrBlank()) { play(direct, item.name, item.logo, "series"); return }
            val cmd = item.cmd
            if (cmd.isNullOrBlank()) { msgTv.text = "Flux indisponible pour cet episode."; return }
            setLoading(true)
            lifecycleScope.launch {
                val link = try { Api.stalkerLink(pl, cmd, "movie") } catch (e: Exception) { null }
                setLoading(false)
                if (!link.isNullOrBlank()) play(link, item.name, item.logo, "series") else msgTv.text = "Impossible d'obtenir le flux."
            }
            return
        }
        if (item.kind == "movie") {
            Session.detailItem = item
            startActivity(Intent(this, DetailActivity::class.java))
            return
        }
        val plCur = Session.current
        if (plCur != null && plCur.type == "stalker") {
            val cmd = item.cmd
            if (cmd.isNullOrBlank()) { msgTv.text = "Flux indisponible pour cet element."; return }
            setLoading(true)
            lifecycleScope.launch {
                val link = try { Api.stalkerLink(plCur, cmd, item.kind) } catch (e: Exception) { null }
                setLoading(false)
                if (!link.isNullOrBlank()) {
                    if (item.kind == "live") previewLive(link, item.name, item.logo, item.streamId ?: "")
                    else play(link, item.name, item.logo, "movie")
                } else msgTv.text = "Impossible d'obtenir le flux."
            }
            return
        }
        val url = item.directUrl ?: return
        if (item.kind == "live") previewLive(url, item.name, item.logo, item.streamId ?: "")
        else play(url, item.name, item.logo, if (item.kind == "movie" || item.kind == "episode") "movie" else "live")
    }

    private fun previewLive(url: String, title: String, logo: String = "", streamId: String = "") {
        Session.liveChannels = filtered.filter { it.kind == "live" }
        startActivity(
            Intent(this, LivePreviewActivity::class.java)
                .putExtra("url", url)
                .putExtra("title", title)
                .putExtra("logo", logo)
                .putExtra("streamId", streamId)
        )
    }

    private fun play(url: String, title: String, logo: String = "", historyKind: String = "live") {
        if (historyKind == "movie" || historyKind == "series") {
            WatchHistory.touch(this, url, title, logo, historyKind)
        }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("url", url)
                .putExtra("title", title)
                .putExtra("logo", logo)
                .putExtra("historyKind", historyKind)
                .putExtra("mode", if (historyKind == "movie" || historyKind == "series") "vod" else "live")
        )
    }

    private fun computeSpan(): Int {
        val m = resources.displayMetrics
        val totalDp = m.widthPixels / m.density
        val content = (totalDp - 230f).coerceAtLeast(220f)
        val target = if (kind == "movie" || kind == "series") 120f else 145f
        return (content / target).toInt().coerceIn(2, 8)
    }

    private fun setLoading(b: Boolean) { progress.visibility = if (b) View.VISIBLE else View.GONE }

    private fun keepFocusOnItems() {
        // Ne jamais voler le focus a la barre de recherche pendant la saisie.
        if (searchEt.hasFocus()) return
        if (filtered.isEmpty()) return
        val current = currentFocus
        if (current != null && catRv.findContainingViewHolder(current) != null) return
        if (current != null && searchEt.hasFocus()) return
        val alreadyOnItem = current != null && itemRv.findContainingViewHolder(current) != null
        if (alreadyOnItem) return
        itemRv.post {
            val pos = lastItemFocusPos.coerceIn(0, (filtered.size - 1).coerceAtLeast(0))
            itemRv.scrollToPosition(pos)
            itemRv.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                ?: itemRv.postDelayed({ itemRv.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }, 80)
        }
    }

    override fun onResume() {
        super.onResume()
        if (filtered.isNotEmpty() && currentFocus == null) keepFocusOnItems()
    }

    inner class CatAdapter(val data: List<Category>, val onClick: (Category) -> Unit) :
        RecyclerView.Adapter<CatAdapter.VH>() {
        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_category, parent, false) as TextView
            return VH(tv)
        }
        override fun getItemCount() = data.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = data[position]
            holder.tv.text = c.name
            val sel = c.id == selectedCat
            holder.tv.isSelected = sel
            holder.tv.setTextColor(ContextCompat.getColor(holder.tv.context, if (sel) R.color.text else R.color.muted))
            holder.tv.setOnClickListener { focusItemsAfterLoad = true; onClick(c) }
        }
    }

    inner class ItemAdapter(initialData: List<Item>, val onClick: (Item) -> Unit) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val data = ArrayList<Item>(initialData)

        fun submit(newData: List<Item>) {
            // Ajout incremental : si la nouvelle liste ne fait que PROLONGER l'actuelle
            // (meme prefixe), on insere uniquement les nouveaux elements. notifyDataSetChanged
            // reconstruit TOUTE la grille et fait sauter/perdre le focus de la telecommande
            // pendant le chargement page par page (Stalker) -> c'etait le bug de navigation.
            if (newData.size > data.size && isPrefix(data, newData)) {
                val start = data.size
                data.addAll(newData.subList(start, newData.size))
                notifyItemRangeInserted(start, newData.size - start)
                return
            }
            data.clear()
            data.addAll(newData)
            notifyDataSetChanged()
        }
        private fun isPrefix(old: List<Item>, new: List<Item>): Boolean {
            if (new.size < old.size) return false
            for (i in old.indices) if (old[i] != new[i]) return false
            return true
        }
        inner class TileVH(val v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.nameTv)
            val poster: ImageView = v.findViewById(R.id.posterIv)
            val progressWrap: View = v.findViewById(R.id.progressWrap)
            val progressFill: View = v.findViewById(R.id.progressFill)
            val serverChip: TextView = v.findViewById(R.id.serverChip)
            // Nom de l'item actuellement affiche : garde anti-recyclage pour les affiches TMDB.
            var boundName: String = ""
        }
        inner class ListVH(val v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.nameTv)
            val sub: TextView = v.findViewById(R.id.subTv)
            val logo: ImageView = v.findViewById(R.id.logoIv)
        }
        inner class HeaderVH(val v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.headerTv)
        }
        override fun getItemViewType(position: Int): Int = when {
            data[position].kind == "header" -> 1
            kind == "live" && liveListMode -> 2
            else -> 0
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                1 -> HeaderVH(inf.inflate(R.layout.item_season_header, parent, false))
                2 -> ListVH(inf.inflate(R.layout.item_channel_list, parent, false))
                else -> TileVH(inf.inflate(R.layout.item_tile, parent, false))
            }
        }
        override fun getItemCount() = data.size
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = data[position]
            if (holder is HeaderVH) {
                holder.tv.text = item.name
                return
            }
            if (holder is ListVH) {
                holder.name.text = item.name
                holder.sub.text = "OK : aperçu + EPG"
                holder.logo.load(item.logo) {
                    crossfade(false)
                    placeholder(R.drawable.bg_tile)
                    error(R.drawable.ic_live_tv)
                }
                holder.v.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) lastItemFocusPos = holder.bindingAdapterPosition.coerceAtLeast(0)
                    holder.v.animate().scaleX(if (hasFocus) 1.025f else 1.0f)
                        .scaleY(if (hasFocus) 1.025f else 1.0f)
                        .setDuration(80)
                        .start()
                    holder.v.translationZ = if (hasFocus) 12f else 0f
                    holder.name.setTextColor(ContextCompat.getColor(holder.name.context, if (hasFocus) R.color.accent else R.color.text))
                }
                holder.v.setOnClickListener {
                    lastItemFocusPos = holder.bindingAdapterPosition.coerceAtLeast(0)
                    onClick(item)
                }
                return
            }
            holder as TileVH
            holder.name.text = item.name
            holder.boundName = item.name
            val isPoster = item.kind == "movie" || item.kind == "series" || item.kind == "season" || item.kind == "episode"
            val h = resources.getDimensionPixelSize(if (isPoster) R.dimen.tile_poster_h else R.dimen.tile_logo_h)
            val lp = holder.poster.layoutParams
            lp.height = h
            holder.poster.layoutParams = lp
            holder.poster.scaleType = if (isPoster) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            val fallback = if (isPoster) R.drawable.ic_movie else R.drawable.ic_live_tv
            // Repli TMDB EN COMPLEMENT des codes : on tente une affiche TMDB si le code n'a
            // PAS d'image OU si l'image du code est CASSEE (404/timeout). C'etait le bug :
            // beaucoup de films ont une URL d'affiche invalide, donc logo n'est pas vide et
            // l'ancien repli (logo vide uniquement) ne se declenchait jamais.
            // Garde anti-recyclage stricte (boundName) pour ne jamais ecraser une autre tuile.
            val tmdbFallback = tmdbFallback@{
                if (!((item.kind == "movie" || item.kind == "series") && Tmdb.enabled())) return@tmdbFallback
                val target = item.name
                val series = item.kind == "series"
                lifecycleScope.launch {
                    val url = Tmdb.posterFor(target, series)
                    if (url.isNotBlank() && holder.boundName == target) {
                        holder.poster.load(url) { crossfade(false); placeholder(R.drawable.bg_tile); error(fallback) }
                    }
                }
                Unit
            }
            if (item.logo.isBlank()) {
                holder.poster.setImageResource(fallback)
                tmdbFallback()
            } else {
                holder.poster.load(item.logo) {
                    // Pas de crossfade dans les grandes grilles TV : ça crée de la latence et des saccades.
                    crossfade(false)
                    placeholder(R.drawable.bg_tile)
                    error(fallback)
                    // Image du code injoignable -> on bascule automatiquement sur TMDB.
                    listener(onError = { _, _ -> tmdbFallback() })
                }
            }
            // Petite case serveur (recherche multi-serveurs).
            if (item.serverLabel.isNotBlank()) {
                holder.serverChip.visibility = View.VISIBLE
                holder.serverChip.text = item.serverLabel
            } else {
                holder.serverChip.visibility = View.GONE
            }
            val pct = when {
                item.directUrl != null -> WatchHistory.progressPercent(holder.v.context, item.directUrl)
                item.kind == "series" -> WatchHistory.progressForSeries(holder.v.context, item.name)
                else -> 0
            }
            if (pct > 0) {
                holder.progressWrap.visibility = View.VISIBLE
                holder.progressWrap.post {
                    val lp2 = holder.progressFill.layoutParams
                    lp2.width = (holder.progressWrap.width * (pct / 100f)).toInt().coerceAtLeast(3)
                    holder.progressFill.layoutParams = lp2
                }
            } else {
                holder.progressWrap.visibility = View.GONE
            }
            holder.v.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) lastItemFocusPos = holder.bindingAdapterPosition.coerceAtLeast(0)
                // Curseur TV tres visible : zoom + titre rouge + elevation.
                holder.v.animate().scaleX(if (hasFocus) 1.04f else 1.0f)
                    .scaleY(if (hasFocus) 1.04f else 1.0f)
                    .setDuration(90)
                    .start()
                holder.v.translationZ = if (hasFocus) 16f else 0f
                holder.name.setTextColor(
                    ContextCompat.getColor(holder.name.context, if (hasFocus) R.color.accent else R.color.text)
                )
            }
            holder.v.setOnClickListener {
                lastItemFocusPos = holder.bindingAdapterPosition.coerceAtLeast(0)
                onClick(item)
            }
        }
    }
}
