package com.kzplayer.app

import android.content.Context
import java.security.MessageDigest

// v356 : mode administrateur.
// Le journal de diagnostic (Stalker + flux) n est plus visible par les utilisateurs.
// Il n apparait que si la licence de l appareil figure dans Config.ADMIN_LICENSE_HASHES,
// et seulement si l administrateur l a active a la main sur son appareil.
// Le code de licence n est jamais ecrit dans l application : on ne compare que des
// empreintes SHA-256, impossibles a retrouver a l envers.
object AdminMode {
    private const val PREF = "kz_admin"
    private const val KEY_DIAG = "diag_on"

    private fun sha256(txt: String): String =
        MessageDigest.getInstance("SHA-256").digest(txt.toByteArray())
            .joinToString("") { "%02X".format(it) }

    // Empreinte de la licence de cet appareil, a communiquer a l administrateur.
    fun fingerprint(ctx: Context): String =
        sha256(DeviceIdentity.licenseCode(ctx).trim().uppercase())

    // Vrai uniquement sur l appareil dont la licence est declaree administrateur.
    fun isAdmin(ctx: Context): Boolean {
        val fp = fingerprint(ctx)
        return Config.ADMIN_LICENSE_HASHES.any { it.trim().uppercase() == fp }
    }

    // Journal visible : il faut etre administrateur ET avoir active l option.
    fun diagEnabled(ctx: Context): Boolean {
        if (!isAdmin(ctx)) return false
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_DIAG, false)
    }

    fun setDiag(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_DIAG, on).apply()
    }

    fun toggleDiag(ctx: Context): Boolean {
        val next = !diagEnabled(ctx)
        setDiag(ctx, next)
        return next
    }
}
