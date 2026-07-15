package com.kzplayer.app

// Ecran TV (theme NewTivi) : meme presentation que le Guide facon TiviMate
// (categories a gauche + liste des chaines avec numero/logo/nom + grille EPG + apercu en haut).
// Difference : le lecteur reduit s'affiche DIRECTEMENT dans l'apercu au-dessus des chaines
// (playsInline = true). 1er clic = lecture dans l'apercu, 2e clic sur la meme chaine = plein ecran.
// Toute la logique est heritee de NewGuideActivity ; on ne change que le titre, l'onglet et le mode.
class NewLiveActivity : NewGuideActivity() {
    override val navTag = "tv"
    override val headerTitle = "TV"
    override val playsInline = true
}
