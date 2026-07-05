package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import android.content.Context
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLCycleLogRepository
import kotlinx.coroutines.runBlocking
import org.joda.time.DateTime
import kotlin.math.abs
import kotlin.math.min

/**
 * NachtLearner — leert de NF-schaalwaarde (1-9) op basis van nachtelijk gedrag.
 *
 * DOEL: BG keert na de laatste maaltijd/episode geleidelijk terug naar target
 *       en blijft daar tot ontbijt.
 *
 * EVALUATIE-MOMENT: eenmaal per nacht, in de ochtend wanneer de nachtperiode
 *       achter ons ligt.
 *
 * START EVALUATIEVENSTER:
 *   het LAATSTE van: NachtStart-tijdstip (uit Settings) OF endTs van de
 *   laatste voltooide, niet-verworpen episode die ná NachtStart viel.
 *   Zo telt een avondsnack die om 23:15 is afgehandeld niet mee als "vroeg
 *   probleem" in de nacht-evaluatie.
 *
 * "DOORBROKEN"-CRITERIUM (afgeleid uit CSV-analyse 7 nachten, 18/06/2026):
 *   BG ≤ target + deltaVereist(IOB)
 *   deltaVereist = min(0.7 + 0.5 × IOB, 3.0)  [mmol]
 *
 * PROXY VOOR "PERSISTENT HOOG" (zonder persistentFired in entity):
 *   Telt het aantal aaneengesloten cycli in het evaluatievenster waarbij
 *   BG > target + 1.0 mmol. ≥ 6 aaneengesloten cycli (~30 min) = probleem.
 *
 * LEERSTAP: ±0.5 NF-punt per nacht, cooldown 1 nacht.
 */
object NachtLearner {

    private const val PREFS_NAME         = "nacht_learner_prefs"
    private const val KEY_LAST_EVAL_DATE = "last_eval_date"  // yyyy-MM-dd
    private const val KEY_HISTORY        = "nf_history"

    /** Historie wordt op tijd beperkt (zelfde patroon als VLearner), niet op aantal. */
    private const val HISTORY_MAX_DAYS = 14

    /** Eén punt in de NF-geschiedenis, voor de 14-dagen lijngrafiek op de Nacht-tab. */
    data class NfHistoryPoint(
        val tsUtc: String,
        val nfLevel: Double,
        val reden: String   // "OMHOOG" | "OMLAAG" — voor toelichtingstekst in de UI
    )

    /** Geschiedenis van NF-waarden voor de 14-dagen lijngrafiek (zie AsKaart/Sparkline). */
    fun getHistory(context: Context): List<NfHistoryPoint> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val p = line.split("|")
            if (p.size < 3) return@mapNotNull null
            try {
                NfHistoryPoint(tsUtc = p[0], nfLevel = p[1].toDouble(), reden = p[2])
            } catch (_: Exception) { null }
        }
    }

    private fun appendHistory(context: Context, point: NfHistoryPoint) {
        val cutoff = java.time.Instant.now().minus(
            HISTORY_MAX_DAYS.toLong(), java.time.temporal.ChronoUnit.DAYS
        )
        val existing = getHistory(context).filter { p ->
            try { java.time.Instant.parse(p.tsUtc).isAfter(cutoff) }
            catch (_: Exception) { false }
        }
        val all = (existing + point)
            .joinToString("\n") { "${it.tsUtc}|${it.nfLevel}|${it.reden}" }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, all).apply()
    }

    // Doorbroken-formule (afgeleid uit CSV-analyse 18/06/2026)
    const val DELTA_BASE = 0.7   // mmol — minimum bij IOB≈0
    const val DELTA_K    = 0.5   // mmol/U — extra per eenheid IOB
    const val DELTA_CAP  = 3.0   // mmol — bovengrens

    // Leer-parameters
    const val NF_STAP       = 0.5
    const val NF_MIN        = 1.0
    const val NF_MAX        = 9.0
    const val HOOG_DREMPEL  = 1.0   // mmol boven target = "te hoog"
    const val HOOG_MIN_CYCLI = 6    // ≥6 aaneengesloten cycli = persistent probleem (~30 min)

    /**
     * Vereiste delta-naar-target voor het "doorbroken"-criterium op basis van IOB.
     */
    fun deltaVereist(iobU: Double): Double =
        min(DELTA_BASE + DELTA_K * iobU.coerceAtLeast(0.0), DELTA_CAP)

    /**
     * Evalueert de afgelopen nacht en past NF aan indien nodig.
     * Aanroep: vanuit FCLCycleLogRepository.runLearners(), alleen wanneer de
     * laatste gelogde cyclus niet meer 's nachts is (dus: overdag, ná een
     * afgeronde nacht). De dagelijkse cooldown hieronder zorgt dat dit ook
     * bij elke uurlijkse runLearners()-aanroep maar 1x per dag echt iets doet.
     *
     * @param laasteEpisodeEndMs endTs van de laatste voltooide episode (null = geen)
     *
     * Target wordt NIET als los profiel-getal meegegeven, maar per cyclus
     * gelezen uit FCLCycleLogEntity.target — dat is exact de target die
     * FCLvNext zelf die cyclus gebruikte (inclusief eventuele tijdelijke
     * targets), nauwkeuriger dan een los opgevraagde profielwaarde.
     */
    fun maybeLearnNacht(
        context: Context,
        preferences: Preferences,
        repository: FCLCycleLogRepository,
        laasteEpisodeEndMs: Long?
    ) {
        val today = DateTime.now().toString("yyyy-MM-dd")
        val sp    = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Eén evaluatie per dag (cooldown)
        if (sp.getString(KEY_LAST_EVAL_DATE, "") == today) return

        // Evaluatievenster-start
        val vensterStartMs = evaluatieVensterStart(preferences, laasteEpisodeEndMs)

        // Haal nacht-cycli op binnen het venster. FCLCycleLogEntity.isNight is
        // de daadwerkelijk op dát moment geldende dag/nacht-classificatie
        // (zelfde FCLvNextDayNightHelper-logica, per cyclus vastgelegd) —
        // nauwkeuriger dan opnieuw uitrekenen met de HUIDIGE instellingen,
        // want die kunnen ondertussen gewijzigd zijn.
        val rows = runBlocking { repository.getRowsInRange(vensterStartMs, System.currentTimeMillis()) }
            .filter { it.context.isNight }

        if (rows.size < 6) return  // te weinig data

        // Laatste BG/IOB/target voor doorbroken-check (target per cyclus, zie kdoc)
        val laasteBg     = rows.last().glucoseIob.bg
        val laasteIob    = rows.last().glucoseIob.iob
        val laasteTarget = rows.last().glucoseIob.target
        val delta        = laasteBg - laasteTarget
        val vereist      = deltaVereist(laasteIob)
        val doorbroken   = delta <= vereist

        // Proxy voor "persistent hoog": langste aaneengesloten reeks boven target+1.0
        // (elke rij vergeleken met zijn EIGEN target, niet de laatste — een
        // tijdelijke target-wijziging in de nacht mag het venster niet vertekenen)
        var maxReeks = 0
        var lopend   = 0
        for (r in rows) {
            if (r.glucoseIob.bg > r.glucoseIob.target + HOOG_DREMPEL) {
                lopend++
                if (lopend > maxReeks) maxReeks = lopend
            } else {
                lopend = 0
            }
        }
        val persistentHoog = maxReeks >= HOOG_MIN_CYCLI

        // Aanpassing
        val huidigeNf = DFLearner.getNfLevel(context)
        val nieuweNf = when {
            !doorbroken && persistentHoog ->
                // BG bleef te lang hoog: NF omhoog (actiever 's nachts)
                (huidigeNf + NF_STAP).coerceAtMost(NF_MAX)
            doorbroken && !persistentHoog && delta < 0.3 ->
                // BG bereikte target snel en bleef er stabiel: NF iets omlaag
                (huidigeNf - NF_STAP).coerceAtLeast(NF_MIN)
            else ->
                huidigeNf  // onzeker → geen aanpassing
        }

        // Cooldown geldt sowieso (1x per dag geëvalueerd), ongeacht of er
        // een aanpassing was — anders blijft dezelfde nacht steeds opnieuw
        // geëvalueerd worden bij elke uurlijkse runLearners()-aanroep.
        sp.edit().putString(KEY_LAST_EVAL_DATE, today).apply()

        if (abs(nieuweNf - huidigeNf) <= 0.01) return  // geen wijziging

        // Geschiedenis loggen ZODRA er een wijziging is berekend — los van of
        // "Automaat leert" aan staat, zodat de grafiek op de Nacht-tab ook
        // toont wat de learner zou doen terwijl auto-toepassen uit staat
        // (zelfde patroon als DFLearner.evaluate() voor de Dag-tab).
        appendHistory(
            context,
            NfHistoryPoint(
                tsUtc   = java.time.Instant.now().toString(),
                nfLevel = nieuweNf,
                reden   = if (nieuweNf > huidigeNf) "OMHOOG" else "OMLAAG"
            )
        )

        // ── Daadwerkelijk toepassen ────────────────────────────────────────
        // Volgt exact het patroon van de dag-learner in
        // FCLCycleLogRepository.runLearners(): alleen schrijven naar AAPS als
        // "Automaat leert" aan staat (DFLearner.isAutoEnabled), zodat dezelfde
        // schakelaar (nu in Settings → Analyser Automaat) dag én nacht-learner
        // samen aan-/uitzet. Bij uit: NF wordt nog wel intern bijgehouden
        // (UI-weergave), maar niet naar AAPS gestuurd.
        DFLearner.setNfLevel(context, nieuweNf)

        if (!DFLearner.isAutoEnabled(context)) return

        // Effectieve waarde = geleerde NF + handmatige Nacht-Agressiviteit-
        // offset (DFLearner.effectiveNfLevel) — zelfde optelling als de
        // "Toepassen in AAPS"-knop op de Nacht-tab gebruikt, zodat een
        // handmatige stap van de gebruiker niet wordt overschreven door de
        // automatische leerstap (vraag Ecko 19/06/2026).
        val effectieveNf = DFLearner.effectiveNfLevel(context)

        val active = app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ConfigOverrideWriter.readActiveParams()
        val currentState = app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor.StvState(
            sterkte        = active.sterkte,
            timing         = active.timing,
            volhoudendheid = active.volhoudendheid
        )
        val ok = app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ConfigOverrideWriter.writeWithNfLevel(
            currentState = currentState,
            newNfLevel   = effectieveNf,
            reason       = "auto-nachtlearner: delta=${"%.2f".format(delta)} " +
                "vereist=${"%.2f".format(vereist)} persistentHoog=$persistentHoog " +
                "NF(geleerd) ${"%.1f".format(huidigeNf)}→${"%.1f".format(nieuweNf)} " +
                "NF(effectief)=${"%.1f".format(effectieveNf)}",
            episodeCount = rows.size
        )
        if (ok) DFLearner.setLastAppliedNfLevel(context, nieuweNf)
    }

    /**
     * Startmoment evaluatievenster: het laatste van NachtStart-tijdstip
     * en het einde van de laatste voltooide episode (indien ná NachtStart).
     */
    fun evaluatieVensterStart(
        preferences: Preferences,
        laasteEpisodeEndMs: Long?
    ): Long {
        val nachtStartMs = nachtStartGisteravondMs(preferences)
        return if (laasteEpisodeEndMs != null && laasteEpisodeEndMs > nachtStartMs)
            laasteEpisodeEndMs
        else
            nachtStartMs
    }

    private fun nachtStartGisteravondMs(preferences: Preferences): Long {
        val str   = preferences.get(StringKey.NachtStart)  // "HH:MM"
        val parts = str.split(":")
        val uur   = parts.getOrNull(0)?.toIntOrNull() ?: 22
        val min   = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return DateTime.now()
            .withTimeAtStartOfDay()
            .minusDays(1)
            .withHourOfDay(uur)
            .withMinuteOfHour(min)
            .millis
    }
}
