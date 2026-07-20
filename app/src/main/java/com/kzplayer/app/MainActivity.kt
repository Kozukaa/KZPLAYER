package com.kzplayer.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : BaseActivity() {
    private lateinit var codeTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var reloadBtn: Button
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SecurityChecks.enforce(this)) return
        setContentView(R.layout.activity_main)

        codeTv = findViewById(R.id.codeTv)
        statusTv = findViewById(R.id.statusTv)
        reloadBtn = findViewById(R.id.reloadBtn)
        progress = findViewById(R.id.progress)

        codeTv.text = deviceCode()
        reloadBtn.setOnClickListener { checkLicense() }
        checkLicense()
    }

    private fun deviceId(): String = DeviceIdentity.stableId(this)

    private fun deviceCode(): String = DeviceIdentity.licenseCode(this)

    private fun checkLicense() {
        setLoading(true)
        statusTv.text = "Vérification de la licence..."
        lifecycleScope.launch {
            try {
                val res = Api.checkLicense(
                    deviceId = deviceId(),
                    deviceCode = deviceCode(),
                    deviceName = Build.MODEL ?: "Android TV",
                    appVersion = "1.0"
                )
                if (res.ok && res.active) {
                    Session.playlists = res.playlists
                    Session.expiration = res.expiration
                    if (Session.current == null || Session.playlists.none { it.id == Session.current?.id }) {
                        Session.current = Session.playlists.firstOrNull()
                    }
                    val homeCls = if (ThemePref.isNew(this@MainActivity)) NewLiveActivity::class.java else HomeActivity::class.java
                    startActivity(Intent(this@MainActivity, homeCls))
                } else {
                    codeTv.text = res.deviceCode.ifBlank { deviceCode() }
                    statusTv.text = res.message.ifBlank {
                        "Licence en attente d’activation. Ouvre le panel admin pour activer cet appareil."
                    }
                }
            } catch (e: Exception) {
                statusTv.text = "Erreur de vérification : ${e.message}"
            }
            setLoading(false)
        }
    }

    private fun setLoading(b: Boolean) {
        progress.visibility = if (b) View.VISIBLE else View.GONE
        reloadBtn.isEnabled = !b
    }
}
