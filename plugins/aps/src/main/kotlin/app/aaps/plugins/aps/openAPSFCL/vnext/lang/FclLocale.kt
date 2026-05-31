package app.aaps.plugins.aps.openAPSFCL.vnext.lang

/**
 * FclLocale — taalcode enum voor FCL vNext.
 *
 * Een nieuwe taal toevoegen:
 *   1. Voeg een entry toe aan deze enum (bijv. DE)
 *   2. Maak FclStrings_DE.kt aan in hetzelfde package
 *   3. Voeg de entry toe aan FclStrings.forLocale()
 *   4. Voeg het label toe aan FclLocale.displayName
 */
enum class FclLocale(val displayName: String, val code: String) {
    NL("Nederlands", "nl"),
    EN("English",    "en"),
    DE("Deutsch",    "de");   // placeholder — activeer FclStrings_DE als vertaald

    companion object {
        /** Detecteert de systeemtaal. Terugvaloptie: Engels. */
        fun fromSystem(): FclLocale {
            val lang = java.util.Locale.getDefault().language
            return values().firstOrNull { it.code == lang } ?: EN
        }

        /** Herstel vanuit opgeslagen code-string (bijv. "nl"). */
        fun fromCode(code: String): FclLocale =
            values().firstOrNull { it.code == code } ?: EN
    }
}
