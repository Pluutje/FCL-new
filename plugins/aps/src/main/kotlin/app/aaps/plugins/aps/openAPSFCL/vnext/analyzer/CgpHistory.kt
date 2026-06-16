package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import android.content.Context

/**
 * CgpHistory — twee onafhankelijke tijdreeksen:
 *
 * 1. KEY_14D_SCORES: 14-daagse schuifvenster-punten (één per dag, max 90).
 *    Elke dag: PGR berekend over de 14 dagen tot en met die dag.
 *    Gebruikt voor: bovenste blok (PGR-getal, tabel, pentagon) en de LIJN
 *    in de trendgrafiek.
 *
 * 2. KEY_24H_SCORES: 24-uurs dagpunten (één per dag, max 90).
 *    Elke dag: PGR berekend over alleen die 24 uur.
 *    Gebruikt voor: STIPPEN in de trendgrafiek — toont dagelijkse spreiding.
 *
 * De lijn is stabiel (veel overlap tussen opeenvolgende vensters).
 * De stippen zijn gevoelig (alleen die dag) en tonen goede/slechte dagen.
 */
object CgpHistory {

    private const val PREFS_NAME     = "cgp_history"
    private const val KEY_14D_SCORES = "cgp_14d_scores_v3"   // 14-daags schuifvenster
    private const val KEY_24H_SCORES = "cgp_24h_scores_v3"   // 24-uurs dagpunten
    private const val MAX_POINTS     = 90

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── 14-daags schuifvenster-punten (voor lijn + bovenste blok) ─────────

    fun get14dScores(context: Context): List<CgpScore> =
        loadScores(context, KEY_14D_SCORES)

    fun upsert14dScore(context: Context, score: CgpScore) =
        upsertScore(context, KEY_14D_SCORES, score)

    // ── 24-uurs dagpunten (voor stippen) ──────────────────────────────────

    fun get24hScores(context: Context): List<CgpScore> =
        loadScores(context, KEY_24H_SCORES)

    fun upsert24hScore(context: Context, score: CgpScore) =
        upsertScore(context, KEY_24H_SCORES, score)

    // ── Backward compatibility (oude code gebruikt getDayScores) ──────────

    /** @deprecated gebruik get14dScores of get24hScores */
    fun getDayScores(context: Context): List<CgpScore> = get14dScores(context)

    /** @deprecated gebruik upsert14dScore */
    fun upsertDayScore(context: Context, score: CgpScore) = upsert14dScore(context, score)

    // ── Afgeleide waarden voor de UI ──────────────────────────────────────

    /**
     * Voortschrijdend gemiddelde van de 24h-PGR-stippen (scores24h).
     * Voor punt i: gemiddelde van scores24h[max(0,i-13)..i].
     * Dit is het gemiddelde van de ZICHTBARE STIPPEN tot dat punt —
     * dus het laatste punt = gemiddelde van alle 14 zichtbare stippen,
     * en verder terug in de tijd het gemiddelde van minder stippen.
     * Retourneert een lijst parallel aan get24hScores().
     */
    fun getRollingAverageOfDots(context: Context): List<Double?> {
        val dots = get24hScores(context)
        return dots.indices.map { i ->
            val van = maxOf(0, i - 13)
            val window = dots.subList(van, i + 1).map { it.pgr }
            if (window.isNotEmpty()) window.average() else null
        }
    }

    /** Wis alle history (beide reeksen) */
    fun clearHistory(context: Context) {
        prefs(context).edit()
            .remove(KEY_14D_SCORES)
            .remove(KEY_24H_SCORES)
            // ook oude sleutel opruimen
            .remove("cgp_day_scores_v2")
            .apply()
    }

    // ── Interne hulpfuncties ──────────────────────────────────────────────

    private fun loadScores(context: Context, key: String): List<CgpScore> {
        val raw = prefs(context).getString(key, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { parseLine(it) }
    }

    private fun upsertScore(context: Context, key: String, score: CgpScore) {
        val cutoff = java.time.Instant.now()
            .minus(MAX_POINTS.toLong(), java.time.temporal.ChronoUnit.DAYS)
        val today = java.time.LocalDate.now().toString()

        val existing = loadScores(context, key).filter { s ->
            try { java.time.Instant.parse(s.tsUtc).isAfter(cutoff) }
            catch (_: Exception) { false }
        }
        val withoutToday = existing.filter { s ->
            try {
                java.time.Instant.parse(s.tsUtc)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate().toString() != today
            } catch (_: Exception) { true }
        }
        prefs(context).edit()
            .putString(key, (withoutToday + score).joinToString("\n") { encodeLine(it) })
            .apply()
    }

    private fun encodeLine(s: CgpScore): String =
        "${s.tsUtc}|${s.torPct}|${s.cvPct}|${s.hypoPct}|${s.hyperPct}" +
            "|${s.meanMgdl}|${s.pgr}|${s.weakestDimension}"

    private fun parseLine(line: String): CgpScore? {
        val p = line.split("|"); if (p.size < 8) return null
        return try {
            CgpScore(
                tsUtc            = p[0],
                torPct           = p[1].toDouble(),
                cvPct            = p[2].toDouble(),
                hypoPct          = p[3].toDouble(),
                hyperPct         = p[4].toDouble(),
                meanMgdl         = p[5].toDouble(),
                pgr              = p[6].toDouble(),
                weakestDimension = p[7]
            )
        } catch (_: Exception) { null }
    }
}
