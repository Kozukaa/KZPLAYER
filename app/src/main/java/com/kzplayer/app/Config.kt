package com.kzplayer.app

object Config {
    // URL backend reconstruite par morceaux pour éviter une chaîne trop évidente dans l'APK.
    // Ce n'est pas une sécurité parfaite, mais ça complique l'extraction basique.
    private const val P1 = "https://script.google.com"
    private const val P2 = "/macros/s/"
    private const val P3 = "AKfycbx31seeUfUfgHkBj8Zjxjl9QizKfWNbLFTVGl-iKMSxzqoHhCSvxPyUtni5w9NE71az"
    const val LOGIN_PATH = "/exec"
    const val USER_AGENT = "KZPlayer/1.0 (Android)"

    val API_BASE: String get() = P1 + P2 + P3
    val LOGIN_URL: String get() = API_BASE.trimEnd('/') + LOGIN_PATH

    // Anti-modification : mets ici le SHA-256 de TA signature release, sans les deux-points.
    // Tant que c'est vide, l'app ne bloque pas sur la signature.
    // Exemple format attendu : ABCD1234... en majuscules, sans ':' ni espaces.
    const val EXPECTED_RELEASE_SIGNATURE_SHA256 = "E4288AF20EF2962943F4A7A0056CCB2661DE9D2838A311CDF4FA39BC6197BE6C"

    // Cle API TMDB (v3) pour enrichir les episodes Stalker/M3U avec photos + resumes FR.
    // Cle gratuite a creer sur https://www.themoviedb.org (Parametres du compte > API > cle API v3).
    // Laisse vide pour desactiver l'enrichissement (aucun effet sur le reste de l'app).
    const val TMDB_API_KEY = "9ce341907d2f15740cde4a2e746b75c3"
}
