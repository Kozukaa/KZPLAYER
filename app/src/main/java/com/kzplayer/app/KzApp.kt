package com.kzplayer.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

// Fournit a Coil un chargeur d'images base sur notre client OkHttp permissif,
// afin que les logos/affiches servis en HTTP ou avec un certificat SSL incomplet
// s'affichent quand meme.
//
// PERF (v137) : cache memoire (25% de la RAM app) + cache disque (250 Mo) + RGB_565
// (bitmaps 2x plus petits) + respectCacheHeaders=false (les serveurs IPTV envoient
// rarement des headers Cache-Control corrects). Impact tres visible avec plusieurs
// serveurs et beaucoup de logos/affiches : plus fluide, moins de re-telechargements,
// moins de GC.
//
// v144 : au demarrage, charge l'URL du proxy Cloudflare (fallback DNS pour
// script.google.com) depuis les SharedPreferences vers Api.cfProxyBase.
class KzApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // v149 : initialise le resolveur DNS-over-HTTPS avec le contexte app avant
        // qu'Api.kt (ou toute autre partie de l'app) ne construise ses OkHttpClient.
        try { DohDns.init(this) } catch (_: Exception) {}
        try { Api.cfProxyBase = Config.currentCfProxyUrl(this) } catch (_: Exception) {}
        try { AutoReloader.runIfNeeded(this) } catch (_: Exception) {}
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { Api.imageClient() }
            .crossfade(false)
            .allowRgb565(true)
            .respectCacheHeaders(false)
            .memoryCache {
                MemoryCache.Builder(this)
                    // v375 : 12% au lieu de 25%. Les logos/affiches gardes en memoire
                    // prenaient la place dont le lecteur video a besoin : sur les box,
                    // Android tuait l appli quelques secondes apres le lancement d une
                    // chaine. Le cache disque (250 Mo) garde l affichage rapide.
                    .maxSizePercent(0.12)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(250L * 1024L * 1024L)
                    .build()
            }
            .build()
}
