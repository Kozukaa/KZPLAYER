package com.kzplayer.app

import android.content.Context

// v149 : preference DNS-over-HTTPS integree a l'app.
//
// Le DNS choisi ici s'applique a TOUT le trafic reseau de KZ Player :
// - portails Xtream / Stalker / M3U (via Api.kt OkHttp)
// - panel Cloudflare + verification de licence + mises a jour GitHub
// - flux video ExoPlayer (via KzHttpDataSource -> OkHttpDataSource)
//
// Ca NE change PAS le DNS des AUTRES apps du boitier : c'est un DNS applicatif,
// pas un DNS systeme. Pas besoin de VPN, pas besoin de root, pas besoin d'un
// DNS changer externe. Utile pour :
// - contourner les DNS FAI cassees / bloquees / lentes
// - utiliser AdGuard pour bloquer pubs et trackers dans les flux
// - utiliser Quad9 pour bloquer les domaines malveillants
object DnsPref {
    private const val PREFS = "kz_player"
    private const val KEY_PROVIDER = "dns_provider"
    private const val KEY_CUSTOM = "dns_custom_url"

    const val SYSTEM = "system"
    const val CLOUDFLARE = "cloudflare"
    const val GOOGLE = "google"
    const val QUAD9 = "quad9"
    const val ADGUARD = "adguard"
    const val CUSTOM = "custom"

    fun current(ctx: Context): String {
        val v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROVIDER, SYSTEM).orEmpty()
        return when (v) {
            CLOUDFLARE, GOOGLE, QUAD9, ADGUARD, CUSTOM -> v
            else -> SYSTEM
        }
    }

    fun set(ctx: Context, value: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROVIDER, value).apply()
        // v151 : purge cache DoH + pool OkHttp -> effet IMMEDIAT sur le prochain appel reseau.
        // Pas besoin de redemarrer l'app ni de cliquer sur "Recharger".
        DohDns.clearCache()
        try { Api.evictConnections() } catch (_: Exception) {}
    }

    fun customUrl(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUSTOM, "").orEmpty()

    fun setCustomUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CUSTOM, url.trim()).apply()
        DohDns.clearCache()
        try { Api.evictConnections() } catch (_: Exception) {}
    }

    // URL DoH RFC 8484 (format binaire wire, POST /dns-query).
    fun endpointFor(ctx: Context, provider: String = current(ctx)): String = when (provider) {
        CLOUDFLARE -> "https://cloudflare-dns.com/dns-query"
        GOOGLE -> "https://dns.google/dns-query"
        QUAD9 -> "https://dns.quad9.net/dns-query"
        ADGUARD -> "https://unfiltered.adguard-dns.com/dns-query"
        CUSTOM -> customUrl(ctx)
        else -> ""
    }

    fun label(value: String): String = when (value) {
        CLOUDFLARE -> "Cloudflare (1.1.1.1)"
        GOOGLE -> "Google (8.8.8.8)"
        QUAD9 -> "Quad9 (9.9.9.9)"
        ADGUARD -> "AdGuard Non-Filtrant (94.140.14.140)"
        CUSTOM -> "Personnalis\u00e9 (URL DoH)"
        else -> "Syst\u00e8me (par d\u00e9faut)"
    }
}
