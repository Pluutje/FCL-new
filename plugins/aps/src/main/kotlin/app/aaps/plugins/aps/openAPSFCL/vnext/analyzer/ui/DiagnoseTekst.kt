package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

/**
 * Vertaalt interne diagnose-/signaalcodes van de learners naar korte,
 * begrijpelijke Nederlandse toelichtingszinnen voor de Advisor-UI.
 *
 * Bronnen van de codes:
 *   - DFLearner.LearningStep.diagnose      → diagnoseTekst()
 *   - FrontloadLearner.FrontloadLearningStep.richting → frontloadTekst()
 *   - VLearner.VHistoryPoint.signal        → vSignaalTekst()
 *
 * Bij toevoeging van een nieuwe diagnosecode in een learner: voeg hier
 * een regel toe. Onbekende codes vallen terug op de ruwe code zelf,
 * zodat niets stil verdwijnt — een ontbrekende mapping is dan zichtbaar
 * in de UI in plaats van onopgemerkt.
 */
object DiagnoseTekst {

    /**
     * Korte toelichting bij de laatste D/F-aanpassing, voor onder de
     * Sterkte- en Timing-kaart. [peakFout] en [hypoStraf] worden gebruikt
     * om de zin met concrete cijfers aan te vullen waar dat de duidelijkheid
     * vergroot.
     */
    fun diagnoseTekst(diagnose: String, peakFout: Double = 0.0, hypoStraf: Double = 0.0): String =
        when (diagnose) {
            "OK" ->
                "Resultaat binnen verwachting, geen aanpassing nodig"

            "HYPO" ->
                "Te lage BG na de maaltijd — dosis verlaagd"

            "HYPO_D_PROBLEEM" ->
                "Te lage BG ondanks normale opbouw — sterkte verlaagd"

            "HYPO_D_DEMPED" ->
                "Te lage BG, deels gedempt door afterload — sterkte licht verlaagd"

            "HYPO_FRONTLOAD" ->
                "Te lage BG na een te grote eerste dosis — sterkte en timing verlaagd"

            "TE_VROEG_SOLO" ->
                "Eerste dosis te groot zonder vervolgdoses — timing teruggenomen"

            "MEER_DOSIS" ->
                "Piek te hoog — sterkte verhoogd"

            "MEER_DOSIS_VROEG_ONDERSCHAT" ->
                "Piek te hoog door onderschatting vroeg in de maaltijd — sterkte verhoogd"

            "TE_WEINIG" ->
                "Piek te laag — sterkte verlaagd"

            "TIMING_SPREAD" ->
                "Piek te hoog en dosis te laat verdeeld — timing naar voren bijgesteld"

            "TIMING_SPREAD_VROEG" ->
                "Piek te hoog ondanks vroege start — timing verder naar voren bijgesteld"

            "FRONTLOAD_LAG" ->
                "Eerste dosis kwam te laat op gang — timing naar voren bijgesteld"

            "FRONTLOAD_LAG_VROEG" ->
                "Eerste dosis kwam ondanks vroege start te laat op gang — timing verder naar voren bijgesteld"

            "IOB_SPREAD_TE_LAAT" ->
                "Opgebouwde insuline werkte te laat door — timing bijgesteld"

            "RESCUE_OVERPOWERED" ->
                "Reddingsdosis nodig ondanks normale opbouw — sterkte verlaagd"

            "EARLYBOOST_TE_KLEIN" ->
                "Vroege boost was te klein om de piek te dempen — frontload verhoogd"

            "AFTERLOAD_GUARD_OK" ->
                "Afterload-grens werkte zoals bedoeld, geen aanpassing"

            "AUTO_DISABLED" ->
                "Automaat staat uit — geen aanpassingen toegepast"

            "COOLDOWN" ->
                "Recent al aangepast — wacht op afkoelperiode"

            "GEEN_EPISODE", "SKIP_GEEN_EPISODE" ->
                "Geen geschikte episode gevonden om van te leren"

            else -> diagnose
        }

    /**
     * Korte toelichting bij de laatste FrontloadLearner-aanpassing,
     * voor onder de Frontload-timing kaart.
     */
    fun frontloadTekst(richting: String, gemiddeldeMargeMin: Int): String =
        when (richting) {
            "EERDER" ->
                "Piek viel gemiddeld $gemiddeldeMargeMin min te laat — timing naar voren bijgesteld"

            "LATER" ->
                "Piek viel gemiddeld ${-gemiddeldeMargeMin} min te vroeg — timing naar achteren bijgesteld"

            "GOED" ->
                "Piektiming binnen marge, geen aanpassing nodig"

            else -> richting
        }

    /**
     * Korte toelichting bij het laatste VLearner-signaal,
     * voor onder de Vasthoudendheid-kaart.
     */
    fun vSignaalTekst(signal: String): String =
        when (signal) {
            "FORWARD" ->
                "Persistente correctie hielp niet genoeg — vasthoudendheid verhoogd"

            "BACK" ->
                "Persistente correctie werkte te sterk door — vasthoudendheid verlaagd"

            "NONE" ->
                "Persistente correcties werken naar verwachting"

            else -> signal
        }
}
