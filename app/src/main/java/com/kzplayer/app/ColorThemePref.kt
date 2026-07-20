package com.kzplayer.app

import android.content.Context

// Choix de la palette de couleurs, persiste sur l'appareil.
// N'affecte que l'apparence (accent + fond) ; aucun impact lecteur / flux / licence.
object ColorThemePref {
    private const val PREF = "kz_prefs_color"
    private const val KEY = "color_theme"

    data class Palette(
        val id: String,
        val label: String,
        val styleRes: Int,
        val accent: Int,
        val bg: Int
    )

    // La 1ere entree est la palette par defaut (theme de base = Rouge et Noir).
    val ALL: List<Palette> = listOf(
        Palette("red_black", "Rouge et Noir", R.style.Theme_KZ, 0xFFE50914.toInt(), 0xFF0A0A0C.toInt()),
        Palette("blue_black", "Bleu et Noir", R.style.Theme_KZ_BleuNoir, 0xFF2E7DFF.toInt(), 0xFF0A0A0C.toInt()),
        Palette("blue_gray", "Bleu et Gris fonce", R.style.Theme_KZ_BleuGris, 0xFF3B8CFF.toInt(), 0xFF15171C.toInt()),
        Palette("green_black", "Vert et Noir", R.style.Theme_KZ_VertNoir, 0xFF22C55E.toInt(), 0xFF0A0A0C.toInt()),
        Palette("orange_black", "Orange et Noir", R.style.Theme_KZ_OrangeNoir, 0xFFFF7A18.toInt(), 0xFF0A0A0C.toInt()),
        Palette("purple_black", "Violet et Noir", R.style.Theme_KZ_VioletNoir, 0xFF8B5CF6.toInt(), 0xFF0A0A0C.toInt()),
        Palette("cyan_black", "Cyan et Noir", R.style.Theme_KZ_CyanNoir, 0xFF06B6D4.toInt(), 0xFF0A0A0C.toInt()),
        Palette("pink_black", "Rose et Noir", R.style.Theme_KZ_RoseNoir, 0xFFEC4899.toInt(), 0xFF0A0A0C.toInt())
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun get(ctx: Context): String = prefs(ctx).getString(KEY, ALL[0].id) ?: ALL[0].id
    fun set(ctx: Context, v: String) { prefs(ctx).edit().putString(KEY, v).apply() }
    fun current(ctx: Context): Palette = ALL.firstOrNull { it.id == get(ctx) } ?: ALL[0]
    fun styleRes(ctx: Context): Int = current(ctx).styleRes
}
