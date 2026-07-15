# KZ Player - règles R8/ProGuard

# Garder les Activities/Application référencées par le Manifest.
-keep class com.kzplayer.app.KzApp { *; }
-keep class com.kzplayer.app.MainActivity { *; }
-keep class com.kzplayer.app.HomeActivity { *; }
-keep class com.kzplayer.app.PlaylistActivity { *; }
-keep class com.kzplayer.app.BrowseActivity { *; }
-keep class com.kzplayer.app.SeriesActivity { *; }
-keep class com.kzplayer.app.DetailActivity { *; }
-keep class com.kzplayer.app.PlayerActivity { *; }

# Garder les modèles Kotlin utilisés en mémoire / JSON manuel.
-keep class com.kzplayer.app.Playlist { *; }
-keep class com.kzplayer.app.Item { *; }
-keep class com.kzplayer.app.**$Companion { *; }

# Media3 / ExoPlayer / FFmpeg extension : éviter de casser le chargement dynamique des renderers.
-keep class androidx.media3.** { *; }
-keep class org.jellyfin.media3.** { *; }
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn androidx.media3.**
-dontwarn org.jellyfin.media3.**
-dontwarn com.google.android.exoplayer2.**

# OkHttp / Okio / Coroutines / Coil.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn kotlinx.coroutines.**
-dontwarn coil.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# AndroidX warnings inutiles selon les versions.
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Optimisation/obfuscation : autorisée, mais on garde les attributs utiles au runtime.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# v84 Live preview / EPG
-keep class com.kzplayer.app.LivePreviewActivity { *; }
-keep class com.kzplayer.app.EpgEntry { *; }
