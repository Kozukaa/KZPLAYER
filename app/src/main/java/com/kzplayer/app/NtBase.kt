package com.kzplayer.app

import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// Base commune aux ecrans NewTivi : garantit que la Session (licence + serveurs) est prete.
// Si l'appli a ete relancee (process tue), on recharge licence + serveurs avant d'afficher.
abstract class NtBase : AppCompatActivity() {
    protected fun ensureSession(onReady: () -> Unit) {
        if (Session.current != null) { onReady(); return }
        val existing = Session.playlists.firstOrNull()
        if (existing != null) { Session.current = existing; onReady(); return }
        lifecycleScope.launch {
            try {
                val res = Api.checkLicense(
                    DeviceIdentity.stableId(this@NtBase),
                    DeviceIdentity.licenseCode(this@NtBase),
                    Build.MODEL ?: "Android TV", "1.0"
                )
                if (res.ok && res.active) {
                    Session.playlists = res.playlists
                    Session.expiration = res.expiration
                    Session.current = res.playlists.firstOrNull()
                }
            } catch (e: Exception) {}
            onReady()
        }
    }
}
