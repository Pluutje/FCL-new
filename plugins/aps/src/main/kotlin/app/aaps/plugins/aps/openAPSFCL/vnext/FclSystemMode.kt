package app.aaps.plugins.aps.openAPSFCL.vnext

/**
 * FclSystemMode (10/07/2026, Ecko) — gedeelde aan/uit + automatisch/handmatig-
 * schakeling voor zowel de interne Learner als de AI-adviseur.
 *
 * OFF      — doet letterlijk niets: geen evaluatie, geen voorstellen, geen
 *            meldingen. De submodus (automatisch/handmatig) wordt in de UI
 *            niet getoond zolang dit de stand is.
 * AUTO     — past aanpassingen direct toe, net als het bestaande gedrag; toont
 *            wél altijd wat en wanneer er iets is aangepast (bestaande logs/
 *            geschiedenis, geen wijziging nodig aan die weergave).
 * MANUAL   — berekent een voorstel maar past het niet toe; de gebruiker moet
 *            expliciet accepteren of afwijzen, met dezelfde native-notificatie
 *            + deep-link-naar-tabblad-methode die de AI-adviseur al gebruikt.
 *
 * Bewust één gedeelde enum i.p.v. twee aparte: zelfde concept, zelfde UI-
 * patroon, en toekomstige systemen (bijv. een activiteit-gebaseerde learner)
 * kunnen 'm meteen hergebruiken zonder een derde variant te verzinnen.
 */
enum class FclSystemMode {
    OFF, AUTO, MANUAL;

    companion object {
        fun fromStored(value: String?): FclSystemMode =
            entries.find { it.name == value } ?: AUTO   // AUTO = huidig bestaand gedrag als default
    }
}
