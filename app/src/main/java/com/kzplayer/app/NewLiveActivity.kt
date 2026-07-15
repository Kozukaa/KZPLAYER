package com.kzplayer.app

// Ecran TV (theme NewTivi) : meme presentation que le Guide facon TiviMate
// (categories a gauche + liste des chaines avec numero/logo/nom + grille EPG + apercu en haut).
// Difference : ici un clic sur une chaine lance directement la lecture.
// Toute la logique est heritee de NewGuideActivity ; on ne change que le titre et l'onglet actif.
class NewLiveActivity : NewGuideActivity() {
    override val navTag = "tv"
    override val headerTitle = "TV"
}
