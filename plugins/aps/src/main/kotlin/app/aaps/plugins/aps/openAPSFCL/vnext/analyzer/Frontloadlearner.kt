package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import android.content.Context
import android.content.SharedPreferences
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFMapping.REF_WMD_DEFAULT
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFMapping.REF_WMD_MAX
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFMapping.REF_WMD_MIN
import kotlin.math.abs

/**
 * FrontloadLearner — leert automatisch wanneer en hoe groot de frontload-
 * commit moet zijn.
 *
 * KERNGEDACHTE:
 * Het frontload-mechanisme triggert als BG met voldoende snelheid stijgt.
 * Twee onafhankelijke vragen, twee parameters:
 *   - REF_WMD: WANNEER triggeren (op basis van marge tot de piek)
 *   - REF_WFF: HOEVEEL insuline dan al gegarandeerd gegeven wordt
 *     (op basis van firstBigCommitFrac — het aandeel van de totale dosis
 *     dat al in de eerste grote commit zit)
 *
 * Een goede frontload triggert ruim VOOR de piek (≥ 25 min ervoor) ÉN
 * geeft dan al een substantieel deel van de uiteindelijke dosis.
 * Data-analyse (16-06-2026, 32 episodes) toonde: episodes met fbc < 0.35
 * hadden gemiddeld 1.25 mmol hogere piek en ~3x langere tijd-tot-piek dan
 * episodes met fbc >= 0.60 — fbc is dus een directe, vroege voorspeller
 * van de uitkomst en daarmee een beter stuursignaal dan de vroegere
 * "staart-fractie" (die het probleem pas achteraf detecteert).
 *
 * LEERLOGICA REF_WMD (timing):
 * - Bereken per episode: (piek-tijdstip) − (eerste frontload-tijdstip)
 *   = "marge voor de piek"
 * - Te laat (< 20 min marge) → REF_WMD verlagen (eerder reageren)
 * - Te vroeg (> 50 min marge) + piek laag → REF_WMD verhogen (later reageren)
 * - Goed (20-50 min marge) → niets doen
 *
 * LEERLOGICA REF_WFF (commit-grootte):
 * - Gemiddelde firstBigCommitFrac over veilige episodes (geen hypo, geen
 *   bevestigde rescue) bepaalt de richting.
 * - fbc < 0.45 → REF_WFF omhoog (grotere gegarandeerde vroege dosis)
 * - fbc > 0.65 én staart-fractie laag → REF_WFF omlaag (voorkom over-dosing)
 * - Anders → niets doen
 *
 * VEILIGHEID:
 * - Minimaal 5 bruikbare episodes voor aanpassing
 * - Max stap: 0.05 (WMD) / 0.03 (WFF) per evaluatie
 * - Cooldown: 48 uur tussen aanpassingen
 * - Episodes met hypo of bevestigde rescue tellen niet mee voor de
 *   fbc-gemiddelde (zouden een fout signaal geven: "meer vroege insuline
 *   is hier beter" terwijl de episode al te veel kreeg)
 */
object FrontloadLearner {

    private const val PREFS = "frontload_learner_prefs"
    private const val KEY_LAST_TS       = "fl_last_ts"
    private const val KEY_HISTORY       = "fl_history"
    private const val KEY_AVG_MARGE     = "fl_avg_marge"
    private const val KEY_EVAL_COUNT    = "fl_eval_count"

    private const val MIN_EPISODES      = 5
    private const val COOLDOWN_HOURS    = 48L
    private const val STAP              = 0.05

    // Doelbereik: frontload triggert 20-50 min voor de piek
    private const val MARGE_MIN         = 20
    private const val MARGE_IDEAAL      = 35
    private const val MARGE_MAX         = 50

    data class FrontloadLearningStep(
        val tsUtc: String,
        val oudeWmd: Double,
        val nieuweWmd: Double,
        val gemiddeldeMarge: Int,    // minuten voor piek
        val aantalEpisodes: Int,
        val richting: String         // "EERDER" | "LATER" | "GOED"
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Publieke API ──────────────────────────────────────────────────────

    fun getHistory(context: Context): List<FrontloadLearningStep> {
        val raw = prefs(context).getString(KEY_HISTORY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { parseStep(it) }
    }

    /** Historie beperkt tot de laatste [days] dagen, voor de 14-dagen lijngrafiek. */
    fun getHistorySince(context: Context, days: Int): List<FrontloadLearningStep> {
        val cutoff = java.time.Instant.now().minus(days.toLong(), java.time.temporal.ChronoUnit.DAYS)
        return getHistory(context).filter { step ->
            try { java.time.Instant.parse(step.tsUtc).isAfter(cutoff) }
            catch (_: Exception) { false }
        }
    }

    fun getGemiddeldeMarge(context: Context): Int =
        prefs(context).getInt(KEY_AVG_MARGE, -1)

    fun getEvalCount(context: Context): Int =
        prefs(context).getInt(KEY_EVAL_COUNT, 0)

    /**
     * Evalueer alle episodes en pas REF_WMD aan als nodig.
     * Retourneert een LearningStep als er een aanpassing is gedaan, anders null.
     */
    fun evaluate(
        context: Context,
        metrics: List<EpisodeMetrics>
    ): FrontloadLearningStep? {
        // Cooldown check
        val lastTs = prefs(context).getLong(KEY_LAST_TS, 0L)
        val hoursSinceLast = (System.currentTimeMillis() - lastTs) / 3_600_000L
        if (lastTs > 0 && hoursSinceLast < COOLDOWN_HOURS) return null

        val bruikbaar = metrics.filter { m ->
            m.firstFrontloadMinutes >= 0 &&
                m.timeToPeakMinutes != null &&
                m.timeToPeakMinutes > 0 &&
                !m.rescueConfirmed &&
                m.firstFrontloadMinutes < m.timeToPeakMinutes
        }

        if (bruikbaar.size < MIN_EPISODES) return null

        val marges = bruikbaar.map { m ->
            (m.timeToPeakMinutes!! - m.firstFrontloadMinutes).toInt()
        }
        val gemiddeldeMarge = marges.average().toInt()

        prefs(context).edit()
            .putInt(KEY_AVG_MARGE, gemiddeldeMarge)
            .putInt(KEY_EVAL_COUNT, bruikbaar.size)
            .apply()

        val huidigWmd = DFLearner.getRefWmd(context)
        val huidigWff = DFLearner.getRefWff(context)

        // ── REF_WMD: wanneer triggeren ──────────────────────────────────────
        val richting = when {
            gemiddeldeMarge < MARGE_MIN -> "EERDER"
            gemiddeldeMarge > MARGE_MAX -> "LATER"
            else                        -> "GOED"
        }

        val afwijking = abs(gemiddeldeMarge - MARGE_IDEAAL).toDouble()
        val stapFractie = (afwijking / 30.0).coerceIn(0.5, 1.0)
        val stapWmd = STAP * stapFractie

        val nieuwWmd = when (richting) {
            "EERDER" -> (huidigWmd - stapWmd).coerceIn(REF_WMD_MIN, REF_WMD_MAX)
            "LATER"  -> (huidigWmd + stapWmd).coerceIn(REF_WMD_MIN, REF_WMD_MAX)
            else     -> huidigWmd
        }

        // ── REF_WFF: hoe groot de frontload-commit ──────────────────────────
        // Stuurt nu DIRECT op firstBigCommitFrac (fbc) — het aandeel van de
        // totale dosis dat al in de eerste grote commit zit. Data-analyse
        // (16-06-2026) toonde een sterke samenhang: episodes met fbc < 0.35
        // hadden gemiddeld 1.25 mmol hogere piek en 3x langere tijd-tot-piek
        // dan episodes met fbc >= 0.60. lastSignificantCommitFrac (staart)
        // werd voorheen gebruikt maar reageert pas NA het probleem; fbc is
        // het directere en vroegere signaal.
        //
        // Doelbereik fbc: 0.45-0.65 (vergelijkbaar met de "hoog frontload"
        // groep uit de analyse, met marge zodat niet structureel over-dosed
        // wordt bij kleine/onzekere episodes).
        // Veiligheid: WFF wordt NIET verhoogd op basis van episodes met hypo
        // of bevestigde rescue — die episodes zijn geen goede leersignaal
        // voor "meer vroege insuline is beter".
        val veiligeEpisodes = bruikbaar.filter { !it.hypoDetected && !it.rescueConfirmed }

        val gemiddeldeFbc = if (veiligeEpisodes.isNotEmpty())
            veiligeEpisodes.map { it.firstBigCommitFrac }.average()
        else null

        val gemiddeldeStaartFrac = bruikbaar
            .map { it.lastSignificantCommitFrac }
            .average()
        val stapWff = 0.03  // voorzichtige stap voor WFF

        val FBC_TARGET_MIN = 0.45
        val FBC_TARGET_MAX = 0.65

        val nieuwWff = when {
            // Geen veilige episodes om op te leren: WFF onveranderd
            gemiddeldeFbc == null -> huidigWff
            // fbc structureel te laag: te weinig vroege insuline → WFF omhoog
            gemiddeldeFbc < FBC_TARGET_MIN ->
                (huidigWff + stapWff).coerceIn(DFMapping.REF_WFF_MIN, DFMapping.REF_WFF_MAX)
            // fbc structureel te hoog: mogelijk over-frontloading → WFF omlaag,
            // maar alleen als de staart niet ook al hoog is (anders is het geen
            // WFF-probleem maar eerder een algemene dosis-kwestie)
            gemiddeldeFbc > FBC_TARGET_MAX && gemiddeldeStaartFrac < 0.15 ->
                (huidigWff - stapWff).coerceIn(DFMapping.REF_WFF_MIN, DFMapping.REF_WFF_MAX)
            else -> huidigWff
        }

        val wmdVeranderd = abs(nieuwWmd - huidigWmd) >= 0.001
        val wffVeranderd = abs(nieuwWff - huidigWff) >= 0.001

        if (!wmdVeranderd && !wffVeranderd && richting == "GOED") {
            app.aaps.plugins.aps.openAPSFCL.vnext.logging.FclLearnerLogger.logFrontload(
                richting       = "GOED",
                gemMarge       = gemiddeldeMarge,
                oudeWmd        = huidigWmd,
                nieuweWmd      = huidigWmd,
                oudeWff        = huidigWff,
                nieuweWff      = huidigWff,
                bruikbaarCount = bruikbaar.size
            )
            return null
        }

        if (wmdVeranderd) DFLearner.setRefWmd(context, nieuwWmd)
        if (wffVeranderd) DFLearner.setRefWff(context, nieuwWff)
        prefs(context).edit().putLong(KEY_LAST_TS, System.currentTimeMillis()).apply()

        val effectiefRichting = if (richting != "GOED") richting
            else if (gemiddeldeFbc != null && gemiddeldeFbc < FBC_TARGET_MIN) "EERDER"
            else "GOED"

        val step = FrontloadLearningStep(
            tsUtc            = java.time.Instant.now().toString(),
            oudeWmd          = huidigWmd,
            nieuweWmd        = nieuwWmd,
            gemiddeldeMarge  = gemiddeldeMarge,
            aantalEpisodes   = bruikbaar.size,
            richting         = effectiefRichting
        )

        appendHistory(context, step)

        app.aaps.plugins.aps.openAPSFCL.vnext.logging.FclLearnerLogger.logFrontload(
            richting       = effectiefRichting,
            gemMarge       = gemiddeldeMarge,
            oudeWmd        = huidigWmd,
            nieuweWmd      = nieuwWmd,
            oudeWff        = huidigWff,
            nieuweWff      = nieuwWff,
            bruikbaarCount = bruikbaar.size
        )

        return step
    }

    // ── Serialisatie ──────────────────────────────────────────────────────

    private fun appendHistory(context: Context, step: FrontloadLearningStep) {
        val existing = getHistory(context).takeLast(9)
        val line = "${step.tsUtc}|${step.oudeWmd}|${step.nieuweWmd}|" +
            "${step.gemiddeldeMarge}|${step.aantalEpisodes}|${step.richting}"
        val all = (existing.map { serialize(it) } + line).joinToString("\n")
        prefs(context).edit().putString(KEY_HISTORY, all).apply()
    }

    private fun serialize(s: FrontloadLearningStep) =
        "${s.tsUtc}|${s.oudeWmd}|${s.nieuweWmd}|${s.gemiddeldeMarge}|${s.aantalEpisodes}|${s.richting}"

    private fun parseStep(line: String): FrontloadLearningStep? = try {
        val p = line.split("|")
        if (p.size < 6) null
        else FrontloadLearningStep(
            tsUtc           = p[0],
            oudeWmd         = p[1].toDouble(),
            nieuweWmd       = p[2].toDouble(),
            gemiddeldeMarge = p[3].toInt(),
            aantalEpisodes  = p[4].toInt(),
            richting        = p[5]
        )
    } catch (_: Exception) { null }
}