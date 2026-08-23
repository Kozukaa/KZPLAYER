package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch

// v361 : PAGE REPLAY DIRECTE.
// - Les chaines avec archive sont trouvees automatiquement (toutes les categories),
//   il n y a plus a passer par le menu des categories.
// - Une barre de jours permet de remonter jusqu a 7 jours en arriere.
// - Un clic sur un programme ouvre l archive dans le lecteur habituel.
class ReplayHubActivity : BaseActivity() {

    private lateinit var dayBar: LinearLayout
    private lateinit var chRv: RecyclerView
    private lateinit var progRv: RecyclerView
    private lateinit var msgTv: TextView
    private lateinit var progress: ProgressBar
    private lateinit var searchEt: EditText
    private var channels: List<Item> = emptyList()
    private var shown: List<Item> = emptyList()
    private var progs: List<ReplayApi.Prog> = emptyList()
    private var selCh: Item? = null
    private var dayOffset = 0
    private val dayCount = 8

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_replay_hub)
        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        dayBar = findViewById(R.id.dayBar)
        chRv = findViewById(R.id.chRv)
        progRv = findViewById(R.id.progRv)
        msgTv = findViewById(R.id.msgTv)
        progress = findViewById(R.id.progress)
        searchEt = findViewById(R.id.searchEt)
        chRv.layoutManager = LinearLayoutManager(this)
        chRv.adapter = ChAdapter()
        progRv.layoutManager = LinearLayoutManager(this)
        progRv.adapter = ProgAdapter()
        renderDays()
        searchEt.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { applyFilter() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        loadChannels()
    }

    // Barre des jours : Aujourd hui, Hier, puis les jours precedents.
    private fun renderDays() {
        dayBar.removeAllViews()
        val dens = resources.displayMetrics.density
        for (i in 0 until dayCount) {
            val tv = TextView(this)
            tv.text = ReplayApi.dayLabel(i)
            tv.textSize = 13f
            tv.setPadding((16 * dens).toInt(), (9 * dens).toInt(), (16 * dens).toInt(), (9 * dens).toInt())
            tv.isFocusable = true
            tv.isClickable = true
            tv.setBackgroundResource(R.drawable.bg_cat)
            tv.isSelected = i == dayOffset
            tv.setTextColor(ContextCompat.getColor(this, if (i == dayOffset) R.color.text else R.color.muted))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (8 * dens).toInt()
            tv.layoutParams = lp
            val idx = i
            tv.setOnClickListener {
                dayOffset = idx
                renderDays()
                loadProgs()
            }
            dayBar.addView(tv)
        }
    }

    // Trouve automatiquement les chaines qui ont du replay, dans TOUTES les categories.
    private fun loadChannels() {
        val pl = Session.current
        if (pl == null) { msgTv.text = "Aucun serveur actif."; return }
        if (pl.type != "xtream" && pl.type != "stalker") {
            msgTv.text = "Le replay n est disponible que sur les serveurs Xtream et Stalker."
            return
        }
        progress.visibility = View.VISIBLE
        msgTv.text = "Recherche des chaines avec replay..."
        lifecycleScope.launch {
            val cats = try {
                if (pl.type == "stalker") Api.stalkerCategories(pl, "live")
                else Api.xtreamCategories(pl, "live")
            } catch (e: Exception) { emptyList<Category>() }
            val real = cats.filter { !it.id.startsWith("__") }
            val withCatchup = ArrayList<Item>()
            val all = ArrayList<Item>()
            var scanned = 0
            for (c in real) {
                if (scanned >= 40 || all.size > 4000) break
                scanned++
                val items = try {
                    kotlinx.coroutines.withTimeoutOrNull(15000L) {
                        if (pl.type == "stalker") Api.stalkerItems(pl, "live", c.id)
                        else Api.xtreamItems(pl, "live", c.id)
                    } ?: emptyList()
                } catch (e: Exception) { emptyList<Item>() }
                for (it in items) {
                    all.add(it)
                    if (it.catchup) withCatchup.add(it)
                }
                channels = if (withCatchup.isNotEmpty()) withCatchup.toList() else all.toList()
                applyFilter()
                if (channels.isNotEmpty()) progress.visibility = View.GONE
                msgTv.text = "Chaines replay : " + channels.size +
                    "   (analyse " + scanned + " / " + real.size + ")"
            }
            progress.visibility = View.GONE
            channels = if (withCatchup.isNotEmpty()) withCatchup.toList() else all.toList()
            applyFilter()
            msgTv.text = if (channels.isEmpty()) "Aucune chaine avec replay sur ce serveur."
                else "Choisis une chaine, puis un jour."
            val first = shown.firstOrNull()
            if (first != null && selCh == null) select(first)
        }
    }

    private fun applyFilter() {
        val q = searchEt.text.toString().trim().lowercase()
        shown = if (q.isBlank()) channels else channels.filter { it.name.lowercase().contains(q) }
        chRv.adapter?.notifyDataSetChanged()
    }

    private fun select(ch: Item) {
        selCh = ch
        chRv.adapter?.notifyDataSetChanged()
        loadProgs()
    }

    // Programmes du jour choisi. Si le serveur ne donne pas de guide pour ce jour,
    // on propose des tranches de 30 minutes (utilisable sur tous les serveurs).
    private fun loadProgs() {
        val pl = Session.current ?: return
        val ch = selCh ?: return
        progs = emptyList()
        progRv.adapter?.notifyDataSetChanged()
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val all = try { ReplayApi.programs(pl, ch.streamId ?: "") } catch (e: Exception) { emptyList() }
            val ofDay = ReplayApi.filterDay(all, dayOffset)
            progs = if (ofDay.isNotEmpty()) ofDay else ReplayApi.slotsForDay(dayOffset)
            progress.visibility = View.GONE
            msgTv.text = ch.name + "   -   " + ReplayApi.dayLabel(dayOffset) +
                (if (ofDay.isEmpty()) "   (guide indisponible : tranches horaires)" else "")
            progRv.adapter?.notifyDataSetChanged()
        }
    }

    private fun playProg(p: ReplayApi.Prog) {
        val pl = Session.current ?: return
        val ch = selCh ?: return
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val url = try {
                ReplayApi.archiveUrl(pl, ch.streamId ?: "", ch.cmd ?: "", p)
            } catch (e: Exception) { "" }
            progress.visibility = View.GONE
            if (url.isBlank()) { msgTv.text = "Archive indisponible pour ce programme."; return@launch }
            startActivity(
                Intent(this@ReplayHubActivity, PlayerActivity::class.java)
                    .putExtra("url", url)
                    .putExtra("title", p.title + " - " + ch.name)
                    .putExtra("logo", ch.logo)
                    .putExtra("historyKind", "live")
                    .putExtra("mode", "vod")
            )
        }
    }

    inner class ChAdapter : RecyclerView.Adapter<ChAdapter.VH>() {
        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val logo: ImageView = v.findViewById(R.id.chLogo)
            val name: TextView = v.findViewById(R.id.chName)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_replay_channel, parent, false))
        override fun getItemCount(): Int = shown.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val ch = shown[position]
            holder.name.text = ch.name
            holder.logo.load(ch.logo) { crossfade(false); placeholder(R.drawable.bg_tile); error(R.drawable.ic_live_tv) }
            holder.v.isSelected = ch === selCh
            holder.v.setOnClickListener { select(ch) }
            holder.v.setOnFocusChangeListener { view, has ->
                view.animate().scaleX(if (has) 1.02f else 1f).scaleY(if (has) 1.02f else 1f)
                    .setDuration(100).start()
            }
        }
    }

    inner class ProgAdapter : RecyclerView.Adapter<ProgAdapter.VH>() {
        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val time: TextView = v.findViewById(R.id.progTime)
            val title: TextView = v.findViewById(R.id.progTitle)
            val desc: TextView = v.findViewById(R.id.progDesc)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_replay_prog, parent, false))
        override fun getItemCount(): Int = progs.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = progs[position]
            holder.time.text = p.time
            holder.title.text = p.title
            if (p.desc.isBlank()) holder.desc.visibility = View.GONE
            else { holder.desc.visibility = View.VISIBLE; holder.desc.text = p.desc }
            holder.v.setOnClickListener { playProg(p) }
            holder.v.setOnFocusChangeListener { view, has ->
                view.animate().scaleX(if (has) 1.02f else 1f).scaleY(if (has) 1.02f else 1f)
                    .setDuration(100).start()
            }
        }
    }
}
