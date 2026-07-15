package com.kzplayer.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory

// Fournit a Coil un chargeur d'images base sur notre client OkHttp permissif,
// afin que les logos/affiches servis en HTTP ou avec un certificat SSL incomplet
// s'affichent quand meme.
class KzApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { Api.imageClient() }
            .crossfade(true)
            .build()
}
