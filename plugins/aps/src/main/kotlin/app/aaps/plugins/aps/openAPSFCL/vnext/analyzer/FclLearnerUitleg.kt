package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import kotlin.math.abs

/**
 * FclLearnerUitleg (10/07/2026) — vertaalt de technische diagnose-code
 * en D/F-getallen van een LearningStep naar een leesbare Nederlandse
 * uitleg voor het Learner-voorstel (LearnerProposalCard). De ruwe getallen
 * (D=1.023 F=0.818 wmd=... etc.) waren voor een niet-ingewijde niet te
 * volgen — dit bestand is de vertaalslag "wat gaat hij doen en waarom".
 *
 * Bewust een aparte, kleine vertaaltabel i.p.v. de diagnose-strings zelf
 * leesbaar te maken in Dflearner.kt — die codes worden ook gebruikt in
 * logs/CSV's waar de korte, technische vorm juist handig is voor filteren.
 */
object FclLearnerUitleg {

    /** Eén-tot-twee-zins-uitleg van WAAROM deze aanpassing wordt voorgesteld. */
    fun waarom(diagnose: String): String = when (diagnose) {
        "RESCUE_OVERPOWERED" ->
            "De maaltijd bleef alleen stabiel dankzij extra koolhydraten — er werd iets te veel en te laat gedoseerd."
        "HYPO_FRONTLOAD" ->
            "Er ontstond een hypo vlak na een grote, vroege dosis zonder dat er daarna nog werd bijgestuurd."
        "TE_VROEG_SOLO" ->
            "De bloedsuiker kwam wat te laag uit na één grote, vroege dosis — geen hypo, maar wel een signaal om iets voorzichtiger te starten."
        "HYPO_LATE_TIMING" ->
            "Er kwam een hypo, maar er stond op dat moment nog relatief veel insuline actief — het probleem lijkt timing, niet de hoeveelheid."
        "HYPO_D_DEMPED", "HYPO_D_PROBLEEM" ->
            "Er kwam een hypo terwijl de verdeling over de maaltijd verder in orde was — dat wijst op iets te veel insuline in totaal."
        "HYPO" ->
            "Er kwam een hypo."
        "TBT" ->
            "De bloedsuiker zat lang onder target, zonder dat het een echte hypo werd."
        "TIMING_SPREAD_VROEG", "TIMING_SPREAD" ->
            "De piek kwam te hoog uit én de insuline was te verspreid over de maaltijd — vroeger en geconcentreerder starten zou moeten helpen."
        "MEER_DOSIS_VROEG_ONDERSCHAT" ->
            "De piek kwam te hoog uit, terwijl de vroege inschatting eigenlijk al een sterkere reactie liet zien — het systeem durfde niet genoeg."
        "MEER_DOSIS_HYPER_BYPASS" ->
            "De piek kwam duidelijk te hoog uit (flink boven target) — zowel timing als hoeveelheid worden bijgesteld."
        "MEER_DOSIS" ->
            "De piek kwam te hoog uit."
        "TE_WEINIG" ->
            "De bloedsuiker bleef juist te laag na de maaltijd — er mag iets meer insuline bij."
        "FRONTLOAD_LAG_VROEG", "FRONTLOAD_LAG" ->
            "Een relatief groot deel van de insuline kwam pas laat in de maaltijd — dat had eerder gemogen."
        "IOB_SPREAD_TE_LAAT", "IOB_SPREAD_TE_LAAT_VERSPREID" ->
            "De piek was op zich prima, maar de insuline werd pas laat geconcentreerd gegeven — dat mag vroeger."
        "EARLYBOOST_TE_KLEIN" ->
            "Een te klein deel van de insuline zat in de vroege, sterke respons — de rest kwam als losse, latere doses."
        "AFTERLOAD_GUARD_OK" ->
            "De ingebouwde rem hield de piek binnen de perken — er lijkt ruimte voor iets meer."
        "OK" ->
            "De laatste maaltijd verliep goed — een kleine, voorzichtige verdere verfijning."
        else -> "Gebaseerd op de laatste maaltijdepisode."
    }

    /** Beschrijft de D-verandering (totale hoeveelheid) in gewone taal. */
    fun dRichting(oldD: Double, newD: Double): String {
        val delta = newD - oldD
        if (abs(delta) < 0.003) return "De totale hoeveelheid insuline blijft ongeveer gelijk."
        val richting = if (delta > 0) "iets meer" else "iets minder"
        val sterk = if (abs(delta) > 0.02) (if (delta > 0) "merkbaar meer" else "merkbaar minder") else richting
        return "Geeft in totaal $sterk insuline."
    }

    /** Beschrijft de F-verandering (timing/frontload) in gewone taal. */
    fun fRichting(oldF: Double, newF: Double): String {
        val delta = newF - oldF
        if (abs(delta) < 0.005) return "De timing blijft ongeveer gelijk."
        val sterk = abs(delta) > 0.03
        return if (delta > 0) {
            if (sterk) "Geeft insuline duidelijk vroeger/geconcentreerder in de maaltijd."
            else "Geeft insuline iets vroeger in de maaltijd."
        } else {
            if (sterk) "Spreidt de insuline duidelijk meer uit over de maaltijd."
            else "Spreidt de insuline iets meer uit over de maaltijd."
        }
    }
}
