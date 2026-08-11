package com.kzplayer.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

// Ecran "Mise a jour" : verifie la derniere version publiee (pilotee par le panel),
// telecharge l'APK signee et lance l'installation. Ne touche ni au player, ni au
// systeme de licence, ni a la signature de l'app.
class UpdateActivity : BaseActivity() {
    private lateinit var statusTv: TextView
    private lateinit var noteTv: TextView
    private lateinit var currentTv: TextView
    private lateinit var dlBtn: Button
    private lateinit var progress: ProgressBar

    private var latest: Api.UpdateInfo? = null
    private var downloadId: Long = -1L
    private var receiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        statusTv = findViewById(R.id.updStatusTv)
        noteTv = findViewById(R.id.updNoteTv)
        currentTv = findViewById(R.id.updCurrentTv)
        dlBtn = findViewById(R.id.updDownloadBtn)
        progress = findViewById(R.id.updProgress)
        dlBtn.isEnabled = false
        dlBtn.setOnClickListener { onDownloadClick() }
        currentTv.text = "Version install\u00e9e : " + currentName() + " (" + currentCode() + ")"
        checkUpdate()
    }

    private fun currentCode(): Int = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (e: Exception) { 0 }

    private fun currentName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    private fun checkUpdate() {
        progress.visibility = View.VISIBLE
        statusTv.text = "V\u00e9rification en cours\u2026"
        lifecycleScope.launch {
            val info = Api.getAppUpdate()
            latest = info
            progress.visibility = View.GONE
            if (!info.ok || info.url.isBlank() || info.versionCode <= 0) {
                statusTv.text = "Aucune mise \u00e0 jour disponible pour le moment."
                dlBtn.isEnabled = false
                return@launch
            }
            if (info.versionCode > currentCode()) {
                statusTv.text = "Mise \u00e0 jour disponible" + (if (info.versionName.isNotBlank()) " : " + info.versionName else "")
                noteTv.text = info.note
                noteTv.visibility = if (info.note.isBlank()) View.GONE else View.VISIBLE
                dlBtn.isEnabled = true
                dlBtn.requestFocus()
            } else {
                statusTv.text = "Votre application est \u00e0 jour \u2713"
                dlBtn.isEnabled = false
            }
        }
    }

    private fun onDownloadClick() {
        val info = latest ?: return
        if (info.url.isBlank()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Autorise l'installation d'applications, puis reviens ici.", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                try { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } catch (e2: Exception) {}
            }
            return
        }
        startDownload(info.url)
    }

    private fun startDownload(url: String) {
        try {
            val dir = getExternalFilesDir("apk")
            val file = File(dir, "kz-update.apk")
            if (file.exists()) file.delete()
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle("KZ Player - Mise \u00e0 jour")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, "apk", "kz-update.apk")
            registerCompletion()
            downloadId = dm.enqueue(req)
            statusTv.text = "T\u00e9l\u00e9chargement en cours\u2026"
            progress.visibility = View.VISIBLE
            dlBtn.isEnabled = false
        } catch (e: Exception) {
            statusTv.text = "Erreur de t\u00e9l\u00e9chargement : " + e.message
        }
    }

    private fun registerCompletion() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                if (id == downloadId) {
                    progress.visibility = View.GONE
                    installApk()
                }
            }
        }
        receiver = r
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        ContextCompat.registerReceiver(this, r, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun installApk() {
        try {
            val dir = getExternalFilesDir("apk")
            val file = File(dir, "kz-update.apk")
            if (!file.exists() || file.length() <= 0L) {
                statusTv.text = "T\u00e9l\u00e9chargement \u00e9chou\u00e9. R\u00e9essaie."
                dlBtn.isEnabled = true
                return
            }
            val uri = FileProvider.getUriForFile(this, "com.kzplayer.app.fileprovider", file)
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, "application/vnd.android.package-archive")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            statusTv.text = "Installation\u2026"
        } catch (e: Exception) {
            statusTv.text = "Erreur d'installation : " + e.message
            dlBtn.isEnabled = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        receiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }
        receiver = null
    }
}
