package com.kzplayer.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

// v368 : ECRAN TELECHARGEMENTS.
// Liste les films et episodes telecharges dans l application, permet de les
// lire hors ligne (fichier local) et de les supprimer un par un.
class DownloadsActivity : BaseActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var msgTv: TextView
    private lateinit var subTv: TextView
    private var files: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        msgTv = findViewById(R.id.msgTv)
        subTv = findViewById(R.id.subTv)
        rv = findViewById(R.id.dlRv)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = DlAdapter()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        files = Downloads.list(this)
        rv.adapter?.notifyDataSetChanged()
        if (files.isEmpty()) {
            msgTv.visibility = View.VISIBLE
            msgTv.text = "Aucun t\u00e9l\u00e9chargement pour le moment. " +
                "Utilise le bouton T\u00e9l\u00e9charger sur la fiche d un film ou d un \u00e9pisode."
        } else {
            msgTv.visibility = View.GONE
        }
        val n = files.size
        val t = Downloads.human(Downloads.totalSize(this))
        subTv.text = if (n == 0) "Films et \u00e9pisodes disponibles hors ligne"
            else n.toString() + (if (n > 1) " titres" else " titre") + "  \u2022  " + t + "  \u2022  hors ligne"
    }

    private fun label(f: File): String {
        val n = f.name
        val dot = n.lastIndexOf(46.toChar())
        return if (dot > 0) n.substring(0, dot) else n
    }

    private fun play(f: File) {
        if (!f.exists()) { refresh(); return }
        val i = Intent(this, PlayerActivity::class.java)
        i.putExtra("title", label(f))
        i.putExtra("url", Uri.fromFile(f).toString())
        i.putExtra("mode", "vod")
        i.putExtra("historyKind", "movie")
        startActivity(i)
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer")
            .setMessage(label(f) + "" + NLQ + "Ce fichier sera supprim\u00e9 de l appareil.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Supprimer") { _, _ ->
                Downloads.remove(f)
                refresh()
            }
            .show()
    }

    private inner class DlAdapter : RecyclerView.Adapter<DlVh>() {
        override fun getItemCount(): Int = files.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DlVh {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_download, parent, false)
            return DlVh(v)
        }
        override fun onBindViewHolder(h: DlVh, position: Int) {
            val f = files[position]
            h.nameTv.text = label(f)
            h.infoTv.text = Downloads.human(f.length()) + "  \u2022  " +
                java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRANCE)
                    .format(java.util.Date(f.lastModified()))
            h.playRow.setOnClickListener { play(f) }
            h.playBtn.setOnClickListener { play(f) }
            h.delBtn.setOnClickListener { confirmDelete(f) }
        }
    }

    private inner class DlVh(v: View) : RecyclerView.ViewHolder(v) {
        val nameTv: TextView = v.findViewById(R.id.nameTv)
        val infoTv: TextView = v.findViewById(R.id.infoTv)
        val playRow: View = v.findViewById(R.id.playRow)
        val playBtn: TextView = v.findViewById(R.id.playBtn)
        val delBtn: TextView = v.findViewById(R.id.delBtn)
    }

    companion object {
        private val NLQ: String = System.lineSeparator() + System.lineSeparator()
    }
}
