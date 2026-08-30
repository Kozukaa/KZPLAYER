package com.kzplayer.app

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.widget.Toast
import java.security.MessageDigest

object SecurityChecks {
    fun enforce(activity: Activity): Boolean {
        // En debug, on laisse l'app fonctionner pour les tests.
        // On \u00e9vite BuildConfig pour ne pas casser certains builds Gradle/AGP.
        if (isDebuggable(activity)) return true

        if (Debug.isDebuggerConnected()) {
            block(activity, "Application non officielle")
            return false
        }

        val expected = Config.EXPECTED_RELEASE_SIGNATURE_SHA256.trim().replace(":", "").uppercase()
        if (expected.isNotBlank()) {
            val current = currentSignatureSha256(activity).uppercase()
            if (current.isBlank() || current != expected) {
                block(activity, "Application modifi\u00e9e ou non officielle")
                return false
            }
        }
        return true
    }

    private fun isDebuggable(ctx: Context): Boolean {
        return (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun block(activity: Activity, message: String) {
        try { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
        activity.finishAffinity()
    }

    fun currentSignatureSha256(ctx: Context): String {
        return try {
            val cert: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signers = info.signingInfo?.apkContentsSigners
                signers?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toByteArray()
            }
            if (cert == null) "" else sha256(cert)
        } catch (e: Exception) {
            ""
        }
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02X".format(it) }
    }
}
