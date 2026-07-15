package com.kzplayer.app

import android.content.Context
import android.media.MediaDrm
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

object DeviceIdentity {
    private val WIDEVINE_UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)

    fun stableId(ctx: Context): String {
        // 1) Le plus stable sur Android TV/boîtiers : identifiant matériel DRM Widevine.
        // Il reste normalement identique après désinstallation/réinstallation de l'app.
        widevineId()?.let { return "wv:$it" }

        // 2) Fallback Android. Sur certains appareils il peut changer selon signature/profil,
        // mais il reste meilleur qu'un UUID stocké dans l'app.
        val androidId = try {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) { null }
        if (!androidId.isNullOrBlank() && androidId.lowercase() != "9774d56d682e549c") {
            return "android:$androidId"
        }

        // 3) Dernier fallback matériel. Moins unique, mais stable sur l'appareil.
        return "hw:${Build.MANUFACTURER}|${Build.BRAND}|${Build.MODEL}|${Build.DEVICE}|${Build.PRODUCT}|${Build.BOARD}|${Build.HARDWARE}|${Build.FINGERPRINT}"
    }

    fun licenseCode(ctx: Context): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(stableId(ctx).toByteArray())
        val hex = bytes.take(5).joinToString("") { "%02X".format(it) }
        return "KZ-${hex.substring(0, 4)}-${hex.substring(4, 8)}"
    }

    private fun widevineId(): String? {
        return try {
            val drm = MediaDrm(WIDEVINE_UUID)
            try {
                val id = drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
                if (id.isEmpty()) null else sha256(id)
            } finally {
                try { drm.release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
