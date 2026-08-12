package com.kzplayer.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient

// Fournit a Coil un chargeur d'images base sur notre client OkHttp permissif,
// afin que les logos/affiches servis en HTTP ou avec un certificat SSL incomplet
// s'affichent quand meme.
//
// PERF (v137) : cache memoire (25% de la RAM app) + cache disque (250 Mo) + RGB_565
// (bitmaps 2x plus petits) + respectCacheHeaders=false (les serveurs IPTV envoient
// rarement des headers Cache-Control corrects). Impact tres visible avec plusieurs
// serveurs et beaucoup de logos/affiches : plus fluide, moins de re-telechargements,
// moins de GC.
class KzApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { Api.imageClient() }
            .crossfade(false)
            .allowRgb565(true)
            .respectCacheHeaders(false)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
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
