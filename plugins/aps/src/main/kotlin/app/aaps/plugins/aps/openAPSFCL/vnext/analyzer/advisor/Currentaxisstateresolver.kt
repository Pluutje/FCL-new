package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor

import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLCycleLogEntity

object CurrentAxisStateResolver {

    /**
     * Leest de huidige S/T/V waarden rechtstreeks uit de meest recente FCLCycleLogEntity.
     * Geen enum-conversie meer nodig — de waarden zijn al integers in percentages.
     */
    fun fromLogRow(row: FCLCycleLogEntity): StvState =
        StvState(
            sterkte        = row.sterktePct.coerceIn(80, 125),
            timing         = row.timingPct.coerceIn(80, 120),
            volhoudendheid = row.volhoudendheidPct.coerceIn(70, 130)
        )

    // Bereken de effectieve S/T/V zoals ze worden gebruikt na toepassing
    // van de agressiviteitsschuif. Dit is wat de gebruiker ziet en verwacht:
    // consistent met wat StatusFormatter toont na de volgende cyclus.
    fun withAggLevel(base: StvState, aggLevel: Int): StvState {
        val scale = (aggLevel - 5) / 4.0
        return StvState(
            sterkte        = (base.sterkte * (1.0 + scale * 0.12)).toInt().coerceIn(75, 130),
            timing         = (base.timing  * (1.0 + scale * 0.06)).toInt().coerceIn(80, 130),
            volhoudendheid = (base.volhoudendheid * (1.0 + scale * 0.10)).toInt().coerceIn(70, 130)
        )
    }

    /**
     * Vergelijkt de S/T/V waarden van een episode met de huidige staat.
     * Gebruikt een tolerantie van ±2% om kleine afwijkingen te tolereren.
     */
    fun matchesEpisodeSettings(
        episodeSterkte: Int,
        episodeTiming: Int,
        episodeVolhoudendheid: Int,
        current: StvState,
        tolerancePct: Int = 2
    ): Boolean =
        kotlin.math.abs(episodeSterkte        - current.sterkte)        <= tolerancePct &&
            kotlin.math.abs(episodeTiming         - current.timing)         <= tolerancePct &&
            kotlin.math.abs(episodeVolhoudendheid - current.volhoudendheid) <= tolerancePct

    /**
     * Unieke sleutel voor een S/T/V combinatie — gebruikt voor episode-filtering.
     * Wordt afgerond op 5% zodat kleine schommelingen één groep vormen.
     */
    fun signature(state: StvState): String {
        val s = roundTo5(state.sterkte)
        val t = roundTo5(state.timing)
        val v = roundTo5(state.volhoudendheid)
        return "S${s}T${t}V${v}"
    }

    fun signatureFromPcts(sterkte: Int, timing: Int, volhoudendheid: Int): String {
        val s = roundTo5(sterkte)
        val t = roundTo5(timing)
        val v = roundTo5(volhoudendheid)
        return "S${s}T${t}V${v}"
    }

    private fun roundTo5(value: Int): Int = ((value + 2) / 5) * 5
}