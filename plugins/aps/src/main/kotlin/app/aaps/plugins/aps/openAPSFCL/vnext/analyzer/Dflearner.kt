package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits
import android.content.Context
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * DFLearner — leert D en F bij op basis van maaltijdepisode-uitkomsten.
 *
 * Na elke afgeronde episode wordt het leeralgoritme aangeroepen:
 *   - Piek te hoog    → D omhoog (meer totaal insuline)
 *   - Hypo na piek    → D omlaag + F omhoog (minder + eerder)
 *   - IOBpiek te hoog → F omhoog (eerder geven zodat insuline werkt vóór de piek)
 *
 * D en F worden persistent opgeslagen in SharedPreferences.
 */
object DFLearner {

    private const val PREFS_NAME = "df_learner_prefs"

    private const val KEY_D         = "df_d"
    private const val KEY_F         = "df_f"
    private const val KEY_TEMPO     = "df_tempo"
    private const val KEY_AUTO      = "df_auto_enabled"
    private const val KEY_LAST_TS   = "df_last_learn_ts"
    private const val KEY_EP_COUNT       = "df_episode_count_since_last"
    private const val KEY_ACCUM_D         = "df_accum_d"   // geaccumuleerde rawΔD over wachtepisodes
    private const val KEY_ACCUM_F         = "df_accum_f"   // geaccumuleerde rawΔF over wachtepisodes
    private const val KEY_HISTORY         = "df_history"   // laatste 20 aanpassingen
    private const val KEY_V_EXTRA = "df_v_extra"


    // ── Kalibratie-waarden ────────────────────────────────────────────────
    private const val KEY_REF_WMD = "df_ref_wmd"   // Stijgingsdrempel frontload
    private const val KEY_REF_WFF = "df_ref_wff"   // Frontload grootte
    private const val KEY_REF_EB  = "df_ref_eb"    // Vroege boost
    private const val KEY_REF_PEAK_BIAS = "df_ref_peak_bias"  // Vroege piek-bias-correctie

    // ── EarlyBoost timing leren ──────────────────────────────────────────
    // Budget-neutraal: earlyBoostFactor groter → watchingFrontloadFrac kleiner.
    // Totale insuline per episode blijft hierdoor gelijk.
    private const val KEY_EB_BOOST    = "eb_factor"        // earlyBoostFactor
    private const val KEY_EB_WATCHING = "eb_watching_frac" // watchingFrontloadFrac
    private const val KEY_EB_STEP     = "eb_step_size"     // adaptieve stapgrootte
    private const val KEY_EB_LAST_SIG = "eb_last_signal"   // FORWARD / BACK / NONE
    private const val KEY_EB_PREV_SIG = "eb_prev_signal"   // signaal daarvoor
    private const val KEY_EB_FWD_STREAK = "eb_fwd_streak" // opeenvolgende FORWARD signalen
    private const val KEY_EB_LAST_TS    = "eb_last_ts"    // tijdstempel laatste EB-evaluatie (ms)

    // Na convergentie: als EB_RESTART_AFTER opeenvolgende FORWARD signalen
    // → maak één sprong van streak/3 stappen en reset streak.
    // Zo blijft het systeem monitoren zonder volledig te stoppen.
    private const val EB_RESTART_AFTER = 6  // 6 FORWARD signalen → kleine heropleving

    private const val EB_BOOST_DEFAULT    = 1.69f
    private const val EB_WATCHING_DEFAULT = 0.64f
    // step is in absolute U (insuline), niet in %
    private const val EB_STEP_DEFAULT     = 0.15f  // 0.15U per stap
    private const val EB_BOOST_MIN        = 1.30
    private const val EB_BOOST_MAX        = 2.20
    private const val EB_WATCHING_MIN     = 0.45
    private const val EB_WATCHING_MAX     = 0.85
    private const val EB_STEP_MIN         = 0.02   // minimum 0.02U per stap
    private const val EB_STEP_MAX         = 0.30   // maximum 0.30U per stap

    // Agressiviteitsschaal (1-9). Stap 1: opgeslagen maar nog niet
    // gekoppeld aan params (dat gebeurt in Stap 2).
    // 1=voorzichtig, 5=standaard, 9=agressief
    private const val KEY_AGGRESSIVENESS = "df_aggressiveness"
    private const val KEY_AGGRESSIVENESS_APPLIED = "df_aggressiveness_applied"
    const val AGGRESSIVENESS_DEFAULT = 5
    const val AGGRESSIVENESS_MIN = 1
    const val AGGRESSIVENESS_MAX = 9

    // ── Maaltijdtype-specifieke D/F waarden ──────────────────────────────

    // ── Onafhankelijke volhoudendheidsoffset per type (los van D) ─────────
    // vExtra ∈ −0.5..+0.5 → V = 95 + (D−1)*50 + vExtra*30
    // Stap per aanpassing: ±0.067 ≈ ±2% V.
    private const val V_EXTRA_MIN = -0.5
    private const val V_EXTRA_MAX =  0.5
    private const val V_EXTRA_STEP = 0.067   // ≈ 2% V per stap

    // Episodes per type (voor minimum-drempel)
    private const val MIN_EPISODES_PER_TYPE = 3

    // Type-specifieke history (apart van de algemene history)

    // ── Doelzone ──────────────────────────────────────────────────────────
    private const val TARGET_PEAK_BG    = 9.0    // mmol ideale piek
    private const val AFTERLOAD_MAX_PEAK = 8.0   // mmol plafond voor D-ophogen bij guard-actief episode
    private const val TARGET_IOBR_PEAK  = 0.45   // ideale IOBratio op piek
    private const val HYPO_THRESHOLD    = 4.5    // onder dit → hypo-straf
    private const val HYPO_WEIGHT       = 2.0    // straf multiplier


    private const val DEAD_ZONE_FRAC    = 0.10
    private const val TARGET_FIRST_FRAC = 0.45   // doel: ≥45% in eerste grote commit


    // ── Validatie- en dead-zone drempels ──────────────────────────────────
    private const val DEAD_ZONE_PEAK    = 0.5    // mmol: geen actie bij kleine piek-afwijking
    private const val DEAD_ZONE_IOBR    = 0.08   // geen actie bij kleine IOBratio-afwijking
    private const val MIN_VALID_PEAK_BG = 6.5    // mmol: minimale piek voor echte maaltijdepisode
    private const val MIN_VALID_INSULIN = 1.5    // U: minimale insuline voor echte maaltijdepisode

    // ── Leertempo's ───────────────────────────────────────────────────────
    enum class Tempo { LANGZAAM, NORMAAL, SNEL }

    data class TempoParams(
        val alphaPiek: Double,    // ΔD per mmol piek-fout
        val betaHypo:  Double,    // ΔD per eenheid hypo-straf
        val gammaIobr: Double,    // ΔF per eenheid IOBratio-fout
        val deltaHypo: Double,    // ΔF per eenheid hypo-straf
        val maxStep:   Double,    // maximale stap per episode
        val minEpisodes: Int,     // min episodes voor eerste aanpassing
        val minHours:  Int        // min uur tussen aanpassingen
    )

    // Tempo-toelichting:
    //   SNEL:     1 episode per aanpassing, max 5% stap. Reageert snel maar conservatief per stap.
    //   NORMAAL:  3 episodes accumuleren, max 8% stap. Consistente signalen geven grotere stap.
    //   LANGZAAM: 5 episodes accumuleren, max 12% stap. Accumulatie filtert ruis eruit;
    //             consistente richting → grote stap, wisselende richting → kleine of nul stap.
    //   maxStep is de grens voor de GECOMBINEERDE stap na accumulatie, niet per episode.
    private val tempoMap = mapOf(
        Tempo.SNEL     to TempoParams(0.040, 0.120, 0.080, 0.060, 0.050, 1,  8),
        Tempo.NORMAAL  to TempoParams(0.030, 0.090, 0.060, 0.045, 0.080, 3, 20),
        Tempo.LANGZAAM to TempoParams(0.020, 0.060, 0.040, 0.030, 0.120, 5, 36)
    )

    // ── Resultaat van één leerstap ─────────────────────────────────────────
    data class LearningStep(
        val oldD: Double, val oldF: Double,
        val newD: Double, val newF: Double,
        val deltaD: Double, val deltaF: Double,
        val peakFout: Double, val iobrFout: Double, val hypoStraf: Double,
        val reason: String,
        val diagnose: String = "",
        val tsUtc: String,
        val mealType: String = "GEMENGD"   // "SNEL" | "TRAAG" | "GEMENGD"
    ) {
        val hasChange get() = abs(deltaD) > 0.001 || abs(deltaF) > 0.001
    }

    // ── Publieke API ───────────────────────────────────────────────────────

    fun getD(context: Context): Double =
        prefs(context).getFloat(KEY_D, DFMapping.D_START.toFloat()).toDouble()

    fun getF(context: Context): Double =
        prefs(context).getFloat(KEY_F, DFMapping.F_START.toFloat()).toDouble()

    fun setD(context: Context, d: Double) =
        prefs(context).edit().putFloat(KEY_D, d.coerceIn(DFMapping.D_MIN, DFMapping.D_MAX).toFloat()).apply()

    fun setF(context: Context, f: Double) =
        prefs(context).edit().putFloat(KEY_F, f.coerceIn(DFMapping.F_MIN, DFMapping.F_MAX).toFloat()).apply()

    fun getTempo(context: Context): Tempo {
        val raw = prefs(context).getString(KEY_TEMPO, Tempo.NORMAAL.name) ?: Tempo.NORMAAL.name
        return runCatching { Tempo.valueOf(raw) }.getOrDefault(Tempo.NORMAAL)
    }

    fun getVExtra(context: Context): Double =
        prefs(context).getFloat("df_v_extra", 0.0f).toDouble()

    fun setVExtra(context: Context, ve: Double) =
        prefs(context).edit().putFloat("df_v_extra", ve.toFloat()).apply()

    fun setTempo(context: Context, tempo: Tempo) =
        prefs(context).edit().putString(KEY_TEMPO, tempo.name).apply()

    fun isAutoEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO, false)

    fun setAutoEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO, enabled).apply()

    // ── Maaltijdtype-specifieke D/F get/set ──────────────────────────────





    // ── Onafhankelijke volhoudendheidsoffset (vExtra) get/set ─────────────






    /**
     * Type-specifieke evaluate: als mealType bekend is, wordt deltaD/deltaF
     * opgeslagen in de type-specifieke sleutels in plaats van de algemene.
     * Minimaal MIN_EPISODES_PER_TYPE episodes per type vereist voor learning.
     */

    // ── Kalibratie get/set ────────────────────────────────────────────────

    fun getRefWmd(context: Context): Double =
        prefs(context).getFloat(KEY_REF_WMD, DFMapping.REF_WMD_DEFAULT.toFloat()).toDouble()

    fun setRefWmd(context: Context, v: Double) =
        prefs(context).edit().putFloat(KEY_REF_WMD, v.coerceIn(DFMapping.REF_WMD_MIN, DFMapping.REF_WMD_MAX).toFloat()).apply()

    fun getRefWff(context: Context): Double =
        prefs(context).getFloat(KEY_REF_WFF, DFMapping.REF_WFF_DEFAULT.toFloat()).toDouble()

    fun setRefWff(context: Context, v: Double) =
        prefs(context).edit().putFloat(KEY_REF_WFF, v.coerceIn(DFMapping.REF_WFF_MIN, DFMapping.REF_WFF_MAX).toFloat()).apply()

    fun getRefPeakBias(context: Context): Double =
        prefs(context).getFloat(KEY_REF_PEAK_BIAS, DFMapping.REF_PEAK_BIAS_DEFAULT.toFloat()).toDouble()

    fun setRefPeakBias(context: Context, v: Double) =
        prefs(context).edit().putFloat(KEY_REF_PEAK_BIAS, v.coerceIn(DFMapping.REF_PEAK_BIAS_MIN, DFMapping.REF_PEAK_BIAS_MAX).toFloat()).apply()

    fun getRefEb(context: Context): Double =
        prefs(context).getFloat(KEY_REF_EB, DFMapping.REF_EB_DEFAULT.toFloat()).toDouble()

    fun setRefEb(context: Context, v: Double) =
        prefs(context).edit().putFloat(KEY_REF_EB, v.coerceIn(DFMapping.REF_EB_MIN, DFMapping.REF_EB_MAX).toFloat()).apply()

    // Agressiviteitsschaal (Stap 1: opslag, Stap 2: koppeling aan params)
    fun getAggressiveness(context: Context): Int =
        prefs(context).getInt(KEY_AGGRESSIVENESS, AGGRESSIVENESS_DEFAULT)

    fun setAggressiveness(context: Context, level: Int) {
        prefs(context).edit()
            .putInt(KEY_AGGRESSIVENESS, level.coerceIn(AGGRESSIVENESS_MIN, AGGRESSIVENESS_MAX))
            .apply()
    }

    /**
     * Het agressiviteitsniveau zoals dat het laatst via "Toepassen in AAPS"
     * (ConfigOverrideWriter) actief is gemaakt. Verschilt dit van
     * getAggressiveness(), dan staat de slider op een niveau dat nog niet
     * is doorgevoerd — de Advisor-UI toont dan de toepassen-knop.
     *
     * Default = AGGRESSIVENESS_DEFAULT (5): bij een schone start is er nog
     * geen override actief, dus "niets toegepast" == "niveau 5".
     */
    fun getLastAppliedAggressiveness(context: Context): Int =
        prefs(context).getInt(KEY_AGGRESSIVENESS_APPLIED, AGGRESSIVENESS_DEFAULT)

    fun setLastAppliedAggressiveness(context: Context, level: Int) {
        prefs(context).edit()
            .putInt(KEY_AGGRESSIVENESS_APPLIED, level.coerceIn(AGGRESSIVENESS_MIN, AGGRESSIVENESS_MAX))
            .apply()
    }

    // Leergeschiedenis tellers (voor de status-indicator in de UI)

    fun getHistory(context: Context): List<LearningStep> {
        val raw = prefs(context).getString(KEY_HISTORY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { parseStep(it) }
    }

    /**
     * Historie beperkt tot de laatste [days] dagen, voor de 14-dagen
     * lijngrafiek per as. Gebruikt dezelfde opgeslagen reeks als
     * getHistory(); de aantal-limiet (19) blijft als achtervang voor
     * periodes met zeer frequente aanpassingen.
     */
    fun getHistorySince(context: Context, days: Int): List<LearningStep> {
        val cutoff = java.time.Instant.now().minus(days.toLong(), java.time.temporal.ChronoUnit.DAYS)
        return getHistory(context).filter { step ->
            try { java.time.Instant.parse(step.tsUtc).isAfter(cutoff) }
            catch (_: Exception) { false }
        }
    }

    /** Totaal aantal geleerde episodes (alle types samen). */
    fun getEpisodeCount(context: Context): Int {
        val p = prefs(context)
        return p.getInt("df_count_all", 0)
    }

    fun incrementEpisodeCount(context: Context) {
        val p = prefs(context)
        p.edit().putInt("df_count_all", p.getInt("df_count_all", 0) + 1).apply()
    }


    /**
     * Evalueer de uitkomst van één maaltijdepisode en pas D en F aan als
     * aan de voorwaarden wordt voldaan. Retourneert de LearningStep.
     */
    fun evaluate(
        context: Context,
        metrics: EpisodeMetrics,
        skipHistory: Boolean = false,
        skipSideEffects: Boolean = false   // true = sla KEY_LAST_TS en weekDelta niet op
    ): LearningStep? {
        // Tempo: de Langzaam/Normaal/Snel-keuze is verwijderd uit de UI.
        // Het systeem gebruikt altijd NORMAAL als basis; de effectieve stap-
        // grootte wordt dynamisch bepaald door episodeVertrouwen en tbtModifier.
        // getTempo/setTempo blijven beschikbaar voor migratie van opgeslagen data.
        val tp = tempoMap[Tempo.NORMAAL]!!

        if (!isAutoEnabled(context)) {
            // Automaat staat uit: geen aanpassingen, maar WEL loggen zodat
            // zichtbaar is wat de learner zou hebben gediagnosticeerd.
            app.aaps.plugins.aps.openAPSFCL.vnext.logging.FclLearnerLogger.logEpisode(
                metrics      = metrics,
                diagnose     = "AUTO_DISABLED",
                rawDeltaD    = 0.0,
                rawDeltaF    = 0.0,
                accumDVoor   = prefs(context).getFloat(KEY_ACCUM_D, 0f).toDouble(),
                accumFVoor   = prefs(context).getFloat(KEY_ACCUM_F, 0f).toDouble(),
                epCountVoor  = prefs(context).getInt(KEY_EP_COUNT, 0),
                weekDVoor    = prefs(context).getFloat("df_week_delta_d", 0f).toDouble(),
                weekFVoor    = prefs(context).getFloat("df_week_delta_f", 0f).toDouble(),
                blokkade     = "AUTO_DISABLED"
            )
            return null
        }

        // Veiligheidsslot: geen aanpassing bij hoge TBR
        // (gecontroleerd door aanroeper via allRows)

        // Cooldown: min X uur sinds laatste aanpassing
        val lastTs = prefs(context).getLong(KEY_LAST_TS, 0L)
        val hoursSinceLast = (System.currentTimeMillis() - lastTs) / 3_600_000L
        if (lastTs > 0 && hoursSinceLast < tp.minHours) {
            app.aaps.plugins.aps.openAPSFCL.vnext.logging.FclLearnerLogger.logEpisode(
                metrics      = metrics,
                diagnose     = "COOLDOWN",
                rawDeltaD    = 0.0,
                rawDeltaF    = 0.0,
                accumDVoor   = prefs(context).getFloat(KEY_ACCUM_D, 0f).toDouble(),
                accumFVoor   = prefs(context).getFloat(KEY_ACCUM_F, 0f).toDouble(),
                epCountVoor  = prefs(context).getInt(KEY_EP_COUNT, 0),
                weekDVoor    = prefs(context).getFloat("df_week_delta_d", 0f).toDouble(),
                weekFVoor    = prefs(context).getFloat("df_week_delta_f", 0f).toDouble(),
                blokkade     = "COOLDOWN(${hoursSinceLast}/${tp.minHours}u)"
            )
            return null
        }

        // ── Bereken signaal van deze episode ───────────────────────────
        //
        // Centrale vraag: was de insuline-verdeling goed?
        //
        //  firstBigCommitFrac = grootste commit / totaal
        //    Hoog (≥0.55): één dominante vroege bolus — goede frontload
        //    Laag (<0.35): insuline verspreid over veel kleine commits
        //
        //  followUpCommitCount: commits ná de grootste commit
        //    ≥2: er volgde insuline na de grote bolus
        //        → bewijs dat eerste bolus niet te groot was
        //        → veilig om verder op te hogen
        //    0/1: alleen één grote bolus, geen follow-up
        //        → als nadir ook laag: eerste was te groot
        //
        // F MAG ALLEEN OMLAAG als alle drie gelden:
        //   A. firstBigCommitFrac hoog  (frontload was actief)
        //   B. followUpCommitCount laag (geen latere commits om budget terug te halen)
        //   C. nadirBg laag             (BG daalde te ver na de eerste bolus)
        //
        // In alle andere hypo-situaties gaat F NIET omlaag (D omlaag is dan correct).

        val peakFout  = metrics.peakBg - TARGET_PEAK_BG
        val nadirBg   = metrics.minBgInWindow
        val hypoStraf = max(0.0, (HYPO_THRESHOLD - nadirBg) * HYPO_WEIGHT)

        // ── Episodevertrouwen: hoe betrouwbaar is deze episode als leersignaal ──
        // Cap- en rem-cycli betekenen dat externe limieten de uitkomst vervormd
        // hebben — de gemeten piek/nadir is dan minder representatief.
        // advisorWeight is al een gecombineerd gewicht (leeftijd, IOB, handmatige correctie).
        // Samen geven ze een moderator 0.30-1.0 die alle rawDelta-waarden schaalt.
        val cyclesTotaal = (metrics.durationMinutes / 5.0).coerceAtLeast(1.0)
        val capPenalty   = (metrics.capReachedCycles / cyclesTotaal).coerceIn(0.0, 0.40)
        val brakePenalty = (metrics.brakeActiveCycles / cyclesTotaal).coerceIn(0.0, 0.25)
        val episodeVertrouwen = ((metrics.advisorWeight - capPenalty - brakePenalty)
            .coerceIn(0.30, 1.0))

        // ── TBT-gewicht: onderscheidt langdurig-laag van kort-laag ───────────
        // hypoStraf kijkt alleen naar diepte; tbtMinutes voegt duur toe.
        // Langdurig onder target (tbt > 30min) zonder echte hypo = structureel
        // D iets te hoog, maar dan wel zonder de scherpte van een echte hypo.
        val tbtModifier: Double = when {
            metrics.hypoDetected && metrics.tbtMinutes > 30 -> 1.25  // langdurige hypo: grotere D-stap
            metrics.hypoDetected                            -> 1.00  // korte hypo: normale stap
            metrics.tbtDetected && metrics.tbtMinutes > 30  -> 0.40  // langdurig onder target, geen hypo
            metrics.tbtDetected                             -> 0.20  // kort onder target, geen hypo
            else                                            -> 0.00
        }

        // Vroege voorspellingssignaal
        // predFout0_20 = gemiddelde(predictedPeak) - werkelijkePiek in 0-20 min venster
        // Negatief = onderschatting = algoritme dacht piek lager = frontload te klein
        // Positief = overschatting  = algoritme dacht piek hoger = frontload te groot
        val earlyFout0_20  = metrics.predFout0_20
        val EARLY_PRED_DEAD_ZONE = 0.5
        val earlyPredTeLaag   = earlyFout0_20 != null && earlyFout0_20 < -EARLY_PRED_DEAD_ZONE
        val earlyPredStrength = if (earlyFout0_20 != null) (abs(earlyFout0_20) / 1.5).coerceIn(0.0, 1.0) else 0.0

        // ── IOB-spreiding signaal: iobRatioAtPeak ─────────────────────────────
        // iobRatioAtPeak > 0.65: veel insuline nog actief op de piek
        //   → insuline was te gespreid in de stijgingsfase (watching in plaats van
        //     geconsolideerde 2e commit)
        //   → F omhoog: volgende keer eerder en groter frontloaden
        //
        // Alleen als:
        //   - Piek was acceptabel (geen hyper = peakBg < 11.0)
        //   - Geen hypo achteraf (nadirBg >= HYPO_THRESHOLD)
        //   - Er waren genoeg follow-ups (safeFollowUp) — bewijs dat het
        //     systeem heeft bijgedoseerd na de frontload
        // Dit is het "te laat geconsolideerd" patroon.
        val iobRatioAtPeak = metrics.iobRatioAtPeak
        val IOB_SPREAD_THRESHOLD = 0.65
        val teGespreid = iobRatioAtPeak > IOB_SPREAD_THRESHOLD &&
            metrics.peakBg < 11.0 &&
            nadirBg >= HYPO_THRESHOLD
        // Sterkte van het signaal: hoe verder boven 0.65, hoe sterker het F-signaal
        val spreadStrength = ((iobRatioAtPeak - IOB_SPREAD_THRESHOLD) / 0.20).coerceIn(0.0, 1.0)

        val frac      = metrics.firstBigCommitFrac
        val fracLaag  = frac < TARGET_FIRST_FRAC - DEAD_ZONE_FRAC   // < 0.35
        val fracHoog  = frac > TARGET_FIRST_FRAC + DEAD_ZONE_FRAC   // > 0.55
        val safeFollowUp = metrics.followUpCommitCount >= 2
        val soloCommit   = metrics.followUpCommitCount <= 1

        // ── iobRatioAt15min: onderscheidt "te laat getriggerd" van "te verspreid" ─
        // Laag bij t+15min (< 0.20): weinig insuline actief in de eerste 15 min
        //   → systeem reageerde te laat → timing-probleem → F meer naar voren
        // Hoog bij t+15min (> 0.35): al flink insuline actief maar toch lage frac
        //   → vroeg gedoseerd maar te verspreid → spread-probleem (al gedekt door IOB_SPREAD)
        val iobAt15min = metrics.iobRatioAt15min
        val teTraagGestart = iobAt15min < 0.20 && fracLaag  // weinig IOB EN kleine eerste commit

        // ── Staart-signaal: was de laatste significante commit te groot/te laat ──
        // lastSignificantCommitFrac > 0.20: meer dan 20% van de insuline zat
        //   in de staart — die had vroeger gegeven kunnen worden.
        // lastSignificantCommitMinutesBeforePeak < 15: viel te dicht bij (of na)
        //   de piek — weinig effectief voor piék-remming, wel risico voor nadir.
        val lastSigFrac = metrics.lastSignificantCommitFrac
        val lastSigMins = metrics.lastSignificantCommitMinutesBeforePeak
        val staartTeGroot = lastSigFrac > 0.20 &&
            lastSigMins != null && lastSigMins < 15 &&
            !metrics.hypoDetected  // als er wel een hypo was, behandelt hypoStraf het al

        // Terugschroef-conditie: eerste commit was groot, geen follow-up kon
        // het "geleende" budget terugpakken, EN nadir daalde te ver.
        val NEAR_HYPO_THRESH = 4.8   // waarschuwingsdrempel (boven echte hypo)
        val nadirTeeLaag = nadirBg < NEAR_HYPO_THRESH
        val frontloadTeGroot = fracHoog && soloCommit && nadirTeeLaag

        val peekHoog  = peakFout >  DEAD_ZONE_PEAK
        val peekLaag  = peakFout < -DEAD_ZONE_PEAK

        // iobrFout als legacy veld voor LearningStep serialisatie
        val iobrFout = frac - TARGET_FIRST_FRAC

        val rawDeltaD: Double
        val rawDeltaF: Double
        val diagnose: String

        when {
            metrics.peakBg < MIN_VALID_PEAK_BG || metrics.totalInsulinDelivered < MIN_VALID_INSULIN -> {
                rawDeltaD = 0.0; rawDeltaF = 0.0; diagnose = "SKIP_GEEN_EPISODE"
            }

            // ── RESCUE: te veel of te laat insuline — alleen stabiel door rescue carbs ──
            // D licht omlaag (minder sterkte), F ook iets omlaag (minder frontload)
            // tenzij er ook een hypo was (dan is de hypo-tak relevant)
            metrics.rescueConfirmed && metrics.minBgInWindow >= HYPO_THRESHOLD -> {
                rawDeltaD = -tp.alphaPiek * 0.5
                rawDeltaF = -tp.alphaPiek * 0.2
                diagnose  = "RESCUE_OVERPOWERED"
            }

            // ── TERUGSCHROEF: eerste commit was groot, geen follow-up, nadir laag ──
            // Dit is de ENIGE situatie waar F omlaag gaat.
            frontloadTeGroot && (hypoStraf > 0.0) -> {
                // Zware terugschroef: echte hypo, budget kon niet worden teruggehaald
                rawDeltaD = -tp.betaHypo  * hypoStraf
                rawDeltaF = -tp.deltaHypo * hypoStraf
                diagnose  = "HYPO_FRONTLOAD"
            }
            frontloadTeGroot -> {
                // Lichte terugschroef: geen hypo maar BG wel te laag na solo commit
                rawDeltaD = -tp.alphaPiek * 0.3 * abs(peakFout).coerceAtLeast(0.3)
                rawDeltaF = -tp.gammaIobr * 0.8 * (frac - TARGET_FIRST_FRAC)
                diagnose  = "TE_VROEG_SOLO"
            }

            // ── HYPO zonder frontload als oorzaak ────────────────────────────────
            hypoStraf > 0.0 && fracHoog && safeFollowUp -> {
                val afterloadDemper = if (metrics.afterloadWasActive) 0.5 else 1.0
                rawDeltaD = -tp.betaHypo * hypoStraf * tbtModifier * afterloadDemper
                rawDeltaF = 0.0
                diagnose  = if (metrics.afterloadWasActive) "HYPO_D_DEMPED" else "HYPO_D_PROBLEEM"
            }
            hypoStraf > 0.0 -> {
                rawDeltaD = -tp.betaHypo * hypoStraf * tbtModifier
                rawDeltaF = 0.0
                diagnose  = "HYPO"
            }

            // ── TBT zonder hypo: BG lang onder target maar niet hypo ─────────────
            // Zwak negatief D-signaal: het systeem doseerde iets te veel maar
            // niet zo veel dat er een echte hypo optrad.
            // tbtModifier > 0 maar hypoStraf = 0 → aparte kleine stap.
            tbtModifier > 0.0 && hypoStraf == 0.0 && metrics.tbtDetected -> {
                rawDeltaD = -tp.alphaPiek * 0.3 * tbtModifier
                rawDeltaF = 0.0
                diagnose  = "TBT"  // onderscheid van echte HYPO: geen BG onder drempel
            }

            // ── PIEK HOOG ─────────────────────────────────────────────────────────
            // Piek hoog + verdeling slecht: frontload en meer totaal nodig
            // Vroeg onderschat: verstevig F-signaal
            peekHoog && fracLaag -> {
                val earlyBoost = if (earlyPredTeLaag) 1.0 + earlyPredStrength else 1.0
                rawDeltaD = +tp.alphaPiek * 0.5 * abs(peakFout)
                rawDeltaF = +tp.gammaIobr * 2.5 * abs(frac - TARGET_FIRST_FRAC) * earlyBoost
                diagnose  = if (earlyPredTeLaag) "TIMING_SPREAD_VROEG" else "TIMING_SPREAD"
            }
            // Piek hoog + verdeling was al goed
            // Vroeg onderschat: algoritme zag het aankomen maar durfde niet -> ook F omhoog
            peekHoog && earlyPredTeLaag -> {
                rawDeltaD = +tp.alphaPiek * 1.5 * abs(peakFout)
                rawDeltaF = +tp.gammaIobr * 1.2 * earlyPredStrength
                diagnose  = "MEER_DOSIS_VROEG_ONDERSCHAT"
            }
            peekHoog -> {
                rawDeltaD = +tp.alphaPiek * 2.0 * abs(peakFout)
                rawDeltaF = 0.0
                diagnose  = "MEER_DOSIS"
            }

            // ── PIEK LAAG ─────────────────────────────────────────────────────────
            peekLaag -> {
                rawDeltaD = -tp.alphaPiek * 1.5 * abs(peakFout)
                rawDeltaF = 0.0   // F neutraal: piek laag is een D-kwestie
                diagnose  = "TE_WEINIG"
            }

            // ── STAART TE GROOT: laatste significante commit te laat/te groot ────
            // Piek was OK (geen hyper), geen hypo, maar > 20% van de insuline zat
            // in een commit die < 15 min voor (of na) de piek viel.
            // Signaal: de vroege commits waren te klein — F omhoog zodat volgende
            // keer meer insuline vroeger wordt gegeven (kleinere staart).
            // D neutraal: het totaal was niet het probleem, alleen de verdeling.
            staartTeGroot && !peekHoog && !peekLaag -> {
                rawDeltaD = 0.0
                rawDeltaF = +tp.gammaIobr * 1.8 * lastSigFrac
                diagnose  = "FRONTLOAD_LAG"   // zelfde bucket: staart → timing
            }

            // ── PIEK OK, VERDELING SLECHT ─────────────────────────────────────────
            // teTraagGestart (laag iobAt15min + lage frac) → sterker F-signaal
            // omdat het bewijs is dat het systeem laat én klein startte.
            fracLaag && safeFollowUp -> {
                val earlyBoost = if (earlyPredTeLaag) 1.0 + earlyPredStrength else 1.0
                val traagBoost = if (teTraagGestart) 1.40 else 1.0
                rawDeltaD = 0.0
                rawDeltaF = +tp.gammaIobr * 1.5 * abs(frac - TARGET_FIRST_FRAC) * earlyBoost * traagBoost
                diagnose  = if (earlyPredTeLaag) "FRONTLOAD_LAG_VROEG" else "FRONTLOAD_LAG"
            }
            fracLaag -> {
                val eb = if (earlyPredTeLaag) 1.0 + earlyPredStrength * 0.3 else 1.0
                val traagBoost = if (teTraagGestart) 1.30 else 1.0
                rawDeltaD = 0.0
                rawDeltaF = +tp.gammaIobr * 0.6 * abs(frac - TARGET_FIRST_FRAC) * eb * traagBoost
                diagnose  = if (earlyPredTeLaag) "FRONTLOAD_LAG_VROEG" else "FRONTLOAD_LAG"
            }

            // ── IOB SPREIDING: piek OK maar insuline te laat geconsolideerd ──────
            // Piek was acceptabel, geen hypo, maar iobRatioAtPeak te hoog.
            // Dit is het patroon: frontload was klein aandeel, systeem doseerde
            // daarna met veel kleine watching commits.
            // F omhoog zodat volgende keer eerder een grotere 2e commit wordt gegeven.
            // Geen D-aanpassing: totale dosis was OK, alleen timing was suboptimaal.
            !peekHoog && !peekLaag && teGespreid && safeFollowUp -> {
                rawDeltaD = 0.0
                rawDeltaF = +tp.gammaIobr * 1.2 * spreadStrength
                diagnose  = "IOB_SPREAD_TE_LAAT"
            }

            // ── EARLYBOOST TE KLEIN: frontload naar voren schuiven ────────────
            // earlyBoostFrac < 0.45: minder dan 45% van de insuline zat in de
            // earlyBoost stages. De rest was watching-commits die later kwamen.
            // Budget-neutraal signaal: earlyBoostFactor groter, watching kleiner.
            // Alleen bij piek > 9.5 (het lukt de stijging niet te remmen) en
            // safeFollowUp (er waren follow-ups, dus de eerste commit was niet te groot).
            !peekHoog && !peekLaag && metrics.earlyBoostFrac in 0.01..0.44
                && !metrics.hypoDetected && safeFollowUp -> {
                rawDeltaD = 0.0
                rawDeltaF = 0.0
                // earlyBoostFactor: klein stapje omhoog (3% per episode)
                // Bijbehorende watchingFrontloadFrac stap omlaag (budget-neutraal)
                diagnose = "EARLYBOOST_TE_KLEIN"
            }

            // ── AFTERLOAD GUARD ACTIEF, EPISODE GOED AFGELOPEN ──────────────────
            // De afterload guard heeft insuline teruggehouden en er was geen hypo/hyper.
            //
            // D mag licht omhoog zolang de piek structureel onder het plafond blijft
            // (AFTERLOAD_MAX_PEAK = 8.0 mmol). Als de piek al laag genoeg is, verhogen
            // we niet verder — anders riskeert de leerloop dat D blijft stijgen totdat
            // de guard het telkens afvangt, en uiteindelijk de hypo toch optreedt.
            //
            // Logica:
            //   piek < 8.0 mmol → D licht omhoog (guard werkt, piek laag genoeg, ruimte voor meer)
            //   piek 8.0-9.0    → D neutraal (acceptabel resultaat, niet verder ophogen)
            //
            // Het plafond is bewust lager dan TARGET_PEAK_BG (9.0): als de guard
            // structureel nodig is, is de echte "vrije" piek zonder guard mogelijk
            // hoger. We willen niet D verhogen totdat de piek zonder guard ook 9.0 haalt.
            !peekHoog && !peekLaag && hypoStraf == 0.0 && metrics.afterloadWasActive -> {
                if (metrics.peakBg < AFTERLOAD_MAX_PEAK) {
                    // Piek laag genoeg: D licht omhoog — het systeem kan meer aan
                    rawDeltaD = +tp.alphaPiek * 0.25   // voorzichtiger dan zonder guard
                    rawDeltaF = 0.0
                    diagnose  = "AFTERLOAD_GUARD_OK"
                } else {
                    // Piek binnen acceptabele marge maar niet meer verhogen
                    rawDeltaD = 0.0
                    rawDeltaF = 0.0
                    diagnose  = "AFTERLOAD_GUARD_OK"
                }
            }

            // ── SCHONE EPISODE: goed afgelopen zonder guard ──────────────────────
            // Geen piek-fout, geen hypo, geen tbt, guard niet nodig.
            // Dit is het sterkste bewijs dat D correct staat of iets mag ophogen.
            // Kleine positieve stap — de piek moet ook hier onder het plafond blijven.
            else -> {
                if (hypoStraf == 0.0 && !metrics.afterloadWasActive &&
                    metrics.peakBg < AFTERLOAD_MAX_PEAK && metrics.totalInsulinDelivered >= 1.0) {
                    rawDeltaD = +tp.alphaPiek * 0.15   // klein positief: alles prima
                    rawDeltaF = 0.0
                    diagnose  = "OK"
                } else {
                    rawDeltaD = 0.0; rawDeltaF = 0.0; diagnose = "OK"
                }
            }
        }

        // ── Schaal rawDelta met episodeVertrouwen ───────────────────────────
        // Episodes met veel cap/rem-cycli of laag advisorWeight tellen minder
        // zwaar mee — hun uitkomst was mede bepaald door externe limieten.
        val scaledRawDeltaD = rawDeltaD * episodeVertrouwen
        val scaledRawDeltaF = rawDeltaF * episodeVertrouwen

        if (diagnose == "SKIP_GEEN_EPISODE") {
            app.aaps.plugins.aps.openAPSFCL.vnext.logging.FclLearnerLogger.logEpisode(
                metrics      = metrics,
                diagnose     = diagnose,
                rawDeltaD    = 0.0,
                rawDeltaF    = 0.0,
                accumDVoor   = prefs(context).getFloat(KEY_ACCUM_D, 0f).toDouble(),
                accumFVoor   = prefs(context).getFloat(KEY_ACCUM_F, 0f).toDouble(),
                epCountVoor  = prefs(context).getInt(KEY_EP_COUNT, 0),
                weekDVoor    = prefs(context).getFloat("df_week_delta_d", 0f).toDouble(),
                weekFVoor    = prefs(context).getFloat("df_week_delta_f", 0f).toDouble(),
                blokkade     = "GEEN_EPISODE"
            )
            return null
        }

        // ── Accumuleer signaal ──────────────────────────────────────────
        val accumDVoor = prefs(context).getFloat(KEY_ACCUM_D, 0f).toDouble()
        val accumFVoor = prefs(context).getFloat(KEY_ACCUM_F, 0f).toDouble()
        val epCountVoor = prefs(context).getInt(KEY_EP_COUNT, 0)
        val weekDVoor   = prefs(context).getFloat("df_week_delta_d", 0f).toDouble()
        val weekFVoor   = prefs(context).getFloat("df_week_delta_f", 0f).toDouble()

        val accumD = accumDVoor + scaledRawDeltaD
        val accumF = accumFVoor + scaledRawDeltaF
        val epCount = epCountVoor + 1

        // Accumulatie opslaan, nog geen aanpassing
        prefs(context).edit()
            .putFloat(KEY_ACCUM_D, accumD.toFloat())
            .putFloat(KEY_ACCUM_F, accumF.toFloat())
            .putInt(KEY_EP_COUNT, epCount)
            .apply()

        if (epCount < tp.minEpisodes) {
            // Nog niet genoeg episodes — log wel zodat de accumulatie zichtbaar is
            app.aaps.plugins.aps.openAPSFCL.vnext.logging.FclLearnerLogger.logEpisode(
                metrics      = metrics,
                diagnose     = diagnose,
                rawDeltaD    = scaledRawDeltaD,
                rawDeltaF    = scaledRawDeltaF,
                accumDVoor   = accumDVoor,
                accumFVoor   = accumFVoor,
                epCountVoor  = epCountVoor,
                weekDVoor    = weekDVoor,
                weekFVoor    = weekFVoor,
                blokkade     = "MIN_EPISODES($epCount/${tp.minEpisodes})"
            )
            return null
        }

        // ── Genoeg episodes: bereken gecombineerde stap ────────────────
        val deltaD = accumD.coerceIn(-tp.maxStep, tp.maxStep)
        val deltaF = accumF.coerceIn(-tp.maxStep, tp.maxStep)

        val d = getD(context)
        val f = getF(context)

        // ── Veiligheidsgrenzen ──────────────────────────────────────────
        val weekKey  = "df_week_delta_d"
        val weekKeyF = "df_week_delta_f"
        val weekDeltaD = prefs(context).getFloat(weekKey, 0f).toDouble()
        val weekDeltaF = prefs(context).getFloat(weekKeyF, 0f).toDouble()

        val effectiveDeltaD = if (abs(weekDeltaD + deltaD) > 0.15) 0.0 else deltaD
        val effectiveDeltaF = if (abs(weekDeltaF + deltaF) > 0.15) 0.0 else deltaF

        if (abs(effectiveDeltaD) < 0.001 && abs(effectiveDeltaF) < 0.001) {
            // Geen effectieve stap — reset accumulator wel
            prefs(context).edit()
                .putFloat(KEY_ACCUM_D, 0f)
                .putFloat(KEY_ACCUM_F, 0f)
                .putInt(KEY_EP_COUNT, 0)
                .apply()
            // Log de blokkade zodat zichtbaar is waarom er niet geleerd werd
            app.aaps.plugins.aps.openAPSFCL.vnext.logging.FclLearnerLogger.logEpisode(
                metrics      = metrics,
                diagnose     = diagnose,
                rawDeltaD    = scaledRawDeltaD,
                rawDeltaF    = scaledRawDeltaF,
                accumDVoor   = accumDVoor,
                accumFVoor   = accumFVoor,
                epCountVoor  = epCountVoor,
                weekDVoor    = weekDVoor,
                weekFVoor    = weekFVoor,
                blokkade     = "WEEK_CAP(D=${weekDVoor+deltaD},F=${weekFVoor+deltaF})"
            )
            return null
        }

        val newD = (d + effectiveDeltaD).coerceIn(DFMapping.D_MIN, DFMapping.D_MAX)
        val newF = (f + effectiveDeltaF).coerceIn(DFMapping.F_MIN, DFMapping.F_MAX)

        // ── Reden opbouwen ──────────────────────────────────────────────
        val reden = buildString {
            append("[$diagnose] ")
            if (abs(peakFout) > 0.5) {
                val mgdl654 = app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.isMgdl(context)
                val pv = app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.fromMmol(peakFout, mgdl654)
                val pfmt = if (mgdl654) "%.0f".format(pv) else "%.1f".format(pv)
                append("piek ${if (peakFout > 0) "+" else ""}$pfmt${app.aaps.plugins.aps.openAPSFCL.vnext.BgUnits.unitShort(mgdl654)} ")
            }
            if (abs(iobrFout) > 0.05) append("IOBpiek ${if (iobrFout > 0) "+" else ""}${String.format("%.2f", iobrFout)} ")
            if (hypoStraf > 0.1) append("hypo_straf=${String.format("%.2f", hypoStraf)} ")
            append("[diagnose=$diagnose]")
        }

        // ── Opslaan ─────────────────────────────────────────────────────
        val now = System.currentTimeMillis()
        val edit = prefs(context).edit()
            .putFloat(KEY_D, newD.toFloat())
            .putFloat(KEY_F, newF.toFloat())
            .putInt(KEY_EP_COUNT, 0)
            .putFloat(KEY_ACCUM_D, 0f)   // accumulator resetten na toepassing
            .putFloat(KEY_ACCUM_F, 0f)
        if (!skipSideEffects) {
            // Bij type-runs (skipSideEffects=true) worden KEY_LAST_TS en weekDelta
            // NIET bijgewerkt — die worden door evaluateForType zelf beheerd.
            // Zo blokkeert een SNEL-run niet de cooldown voor GEMENGD.
            edit.putLong(KEY_LAST_TS, now)
                .putFloat(weekKey,  (weekDeltaD + effectiveDeltaD).toFloat())
                .putFloat(weekKeyF, (weekDeltaF + effectiveDeltaF).toFloat())
        }
        edit.apply()

        val step = LearningStep(
            oldD = d, oldF = f,
            newD = newD, newF = newF,
            deltaD = newD - d, deltaF = newF - f,
            peakFout = peakFout, iobrFout = iobrFout, hypoStraf = hypoStraf,
            reason = reden,
            diagnose = diagnose,
            tsUtc = java.time.Instant.ofEpochMilli(now).toString(),
            mealType = "GEMENGD"
        )

        // Log de aanpassing met volledige context
        app.aaps.plugins.aps.openAPSFCL.vnext.logging.FclLearnerLogger.logEpisode(
            metrics      = metrics,
            diagnose     = diagnose,
            rawDeltaD    = scaledRawDeltaD,
            rawDeltaF    = scaledRawDeltaF,
            accumDVoor   = accumDVoor,
            accumFVoor   = accumFVoor,
            epCountVoor  = epCountVoor,
            weekDVoor    = weekDVoor,
            weekFVoor    = weekFVoor,
            blokkade     = "",
            oldD         = d,
            oldF         = f,
            newD         = newD,
            newF         = newF
        )

        appendHistory(context, step, skip = skipHistory)

        // ── EarlyBoost timing leren ──────────────────────────────────
        // Pas earlyBoostFactor en watchingFrontloadFrac aan op basis van
        // hoe goed de frontload-timing was in deze episode.
        if (!skipSideEffects) {
            evaluateEarlyBoost(context, metrics)
        }

        return step
    }

    /**
     * Handmatige aanpassing van D door de gebruiker (via ±knoppen in UI).
     * Kleine stap = 0.05, registreert niet als leer-stap.
     */
    fun adjustD(context: Context, delta: Double) {
        val d = getD(context)
        setD(context, (d + delta).coerceIn(DFMapping.D_MIN, DFMapping.D_MAX))
    }

    fun adjustF(context: Context, delta: Double) {
        val f = getF(context)
        setF(context, (f + delta).coerceIn(DFMapping.F_MIN, DFMapping.F_MAX))
    }

    fun resetWeeklyDelta(context: Context) {
        prefs(context).edit()
            .putFloat("df_week_delta_d", 0f)
            .putFloat("df_week_delta_f", 0f)
            .apply()
    }

    /**
     * Reset type-specifieke D/F waarden en history terug naar de algemene waarden.
     * Gebruik dit na een verbetering van de type-detectielogica zodat het systeem
     * opnieuw kan leren zonder vervuilde historische data.
     */
    /** Reset D en F naar basiswaarden, wis aanpassingshistorie */
    fun resetTypeData(context: Context) {
        val resetD = 0.968f; val resetF = 0.775f
        prefs(context).edit()
            .putFloat(KEY_D, resetD).putFloat(KEY_F, resetF)
            .remove(KEY_HISTORY).apply()
    }

    /** Reset accumulatie (tussentijdse gespaarde deltas) */
    fun resetAccum(context: Context) {
        prefs(context).edit()
            .putFloat(KEY_ACCUM_D, 0f).putFloat(KEY_ACCUM_F, 0f).apply()
    }

    /** Reset V (vasthoudendheid) naar standaard nul-offset */
    fun resetV(context: Context) {
        prefs(context).edit().putFloat("df_v_extra", 0f).apply()
    }

    /** Reset frontload-leerparameters naar fabrieksinstellingen */
    fun resetFrontload(context: Context) {
        prefs(context).edit()
            .putFloat(KEY_REF_WMD, DFMapping.REF_WMD_DEFAULT.toFloat())
            .putFloat(KEY_REF_WFF, DFMapping.REF_WFF_DEFAULT.toFloat())
            .putFloat(KEY_REF_EB,  DFMapping.REF_EB_DEFAULT.toFloat())
            .putFloat(KEY_REF_PEAK_BIAS, DFMapping.REF_PEAK_BIAS_DEFAULT.toFloat())
            .apply()
    }

    /** Reset EarlyBoost-learner naar defaults */
    fun resetEarlyBoost(context: Context) {
        prefs(context).edit()
            .remove(KEY_EB_BOOST).remove(KEY_EB_WATCHING).remove(KEY_EB_STEP)
            .remove(KEY_EB_LAST_SIG).remove(KEY_EB_PREV_SIG)
            .remove(KEY_EB_FWD_STREAK).remove(KEY_EB_LAST_TS).apply()
    }

    /** Reset alles: D/F, V, frontload, earlyboost, accum, history, VLearner, FrontloadLearner */
    fun resetAll(context: Context) {
        resetTypeData(context); resetAccum(context); resetV(context)
        resetFrontload(context); resetEarlyBoost(context)
        context.getSharedPreferences("v_learner_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        context.getSharedPreferences("fl_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    // ── Intern ────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun appendHistory(context: Context, step: LearningStep, skip: Boolean = false) {
        if (skip) return
        val existing = getHistory(context).takeLast(19)
        val all = (existing.map { serializeStep(it) } + serializeStep(step)).joinToString("\n")
        prefs(context).edit().putString(KEY_HISTORY, all).apply()
    }

    private fun serializeStep(s: LearningStep) =
        "${s.tsUtc}|${s.oldD}|${s.oldF}|${s.newD}|${s.newF}|" +
            "${s.peakFout}|${s.iobrFout}|${s.hypoStraf}|${s.reason}|${s.diagnose}|${s.mealType}"

    private fun parseStep(line: String): LearningStep? {
        return try {
            val p = line.split("|")
            if (p.size < 9) return null
            LearningStep(
                tsUtc      = p[0],
                oldD       = p[1].toDouble(), oldF = p[2].toDouble(),
                newD       = p[3].toDouble(), newF = p[4].toDouble(),
                deltaD     = p[3].toDouble() - p[1].toDouble(),
                deltaF     = p[4].toDouble() - p[2].toDouble(),
                peakFout   = p[5].toDouble(),
                iobrFout   = p[6].toDouble(),
                hypoStraf  = p[7].toDouble(),
                reason     = p[8],
                diagnose   = if (p.size > 9) p[9] else "",
                mealType   = if (p.size > 10) p[10] else "GEMENGD"
            )
        } catch (_: Exception) { null }
    }

    // ─────────────────────────────────────────────────────────────────────
    // EarlyBoost timing leren
    // Budget-neutraal: earlyBoostFactor groter → watchingFrontloadFrac kleiner.
    // Totale insuline per episode onveranderd.
    // ─────────────────────────────────────────────────────────────────────

    fun getEarlyBoostFactor(context: android.content.Context): Double =
        prefs(context).getFloat(KEY_EB_BOOST, EB_BOOST_DEFAULT).toDouble()

    fun getWatchingFrac(context: android.content.Context): Double =
        prefs(context).getFloat(KEY_EB_WATCHING, EB_WATCHING_DEFAULT).toDouble()

    fun getEbStepSize(context: android.content.Context): Double =
        prefs(context).getFloat(KEY_EB_STEP, EB_STEP_DEFAULT).toDouble()

    /** Laatste earlyBoost leersignaal: "FORWARD", "BACK" of "NONE". */
    fun getEbLastSignal(context: android.content.Context): String =
        prefs(context).getString(KEY_EB_LAST_SIG, "NONE") ?: "NONE"

    fun getEbLastSignalTs(context: android.content.Context): Long =
        prefs(context).getLong(KEY_EB_LAST_TS, 0L)

    /**
     * Evalueer één episode en pas earlyBoostFactor/watchingFrac aan.
     *
     * FORWARD: earlyBoostFrac < 0.55 en piek > 9.5 en geen hypo
     *          → kleine stap naar voren (+stepSize)
     * BACK:    hypo én earlyBoostWasActive én geen externe bolus
     *          → 3 stappen terug (3 × stepSize)
     *          → als vorige signaal FORWARD was: stepSize halveren (oscillatiepunt)
     * NONE:    geen aanpassing
     */
    fun evaluateEarlyBoost(
        context: android.content.Context,
        metrics: EpisodeMetrics
    ): String? {
        if (metrics.hasManualCorrection) return null
        if (metrics.totalInsulinDelivered < 1.0) return null

        val p = prefs(context)
        val oldBoost    = p.getFloat(KEY_EB_BOOST,    EB_BOOST_DEFAULT).toDouble()
        val oldWatching = p.getFloat(KEY_EB_WATCHING, EB_WATCHING_DEFAULT).toDouble()
        var step        = p.getFloat(KEY_EB_STEP,     EB_STEP_DEFAULT).toDouble()
        val lastSig     = p.getString(KEY_EB_LAST_SIG, "NONE") ?: "NONE"

        // Absolute budget-neutraliteit:
        // earlyBoostDeliveredU en watchingDeliveredU zijn gemeten per episode.
        // stepU = vaste absolute stap in eenheden insuline.
        // earlyBoostFactor aanpassing: factor zodat +stepU in earlyBoost output.
        // watchingFrac aanpassing: -stepU / (maxSMB × watchingCommits).
        // Zo stijgt earlyBoost met precies stepU en daalt watching met precies stepU.
        val ebDeliveredU = metrics.earlyBoostDeliveredU
        val watchCommits = metrics.followUpCommitCount.coerceAtLeast(1)
        val maxSmb = metrics.totalInsulinDelivered.let {
            // Schat maxSMB: earlyBoostDeliveredU / earlyBoostFactor / ~3 stages
            if (oldBoost > 0) (ebDeliveredU / oldBoost / 2.5).coerceIn(1.5, 4.0)
            else 2.46
        }

        // ── Bepaal signaal ──────────────────────────────────────────────
        val hypoOorzaakFrontload =
            metrics.hypoDetected &&
            metrics.earlyBoostWasActive &&
            !metrics.hasManualCorrection

        // Tier 1: klassiek — te weinig insuline vroeg terwijl piek hoog was
        val frontloadTeKlein =
            !metrics.hypoDetected &&
            metrics.earlyBoostFrac in 0.01..0.54 &&
            metrics.peakBg > 9.5 &&
            metrics.followUpCommitCount >= 1

        // Tier 2: hoge piek ondanks actieve earlyBoost — timing te laat of
        // te weinig gewicht op de vroege commits t.o.v. het watching-deel.
        // earlyBoostFrac < 0.80 garandeert dat er nog ruimte was in het budget.
        // Vuurt met halve stapgrootte (voorzichtiger: we weten alleen dat het
        // eerder moest, niet hoeveel eerder).
        val frontloadTeLaatHogePiek =
            !metrics.hypoDetected &&
            !frontloadTeKlein &&
            metrics.peakBg > 12.0 &&
            metrics.earlyBoostFrac < 0.80 &&
            metrics.earlyBoostWasActive &&
            metrics.followUpCommitCount >= 1

        val signal = when {
            hypoOorzaakFrontload   -> "BACK"
            frontloadTeKlein       -> "FORWARD"
            frontloadTeLaatHogePiek -> "FORWARD"
            else                   -> "NONE"
        }

        // Halve stap bij tier-2: piek was hoog maar frac was niet extreem laag,
        // dus de correctiebehoefte is onzekerder dan bij tier-1.
        val stapHalveerVoorTier2 = frontloadTeLaatHogePiek && !frontloadTeKlein

        if (signal == "NONE") {
            p.edit().putString(KEY_EB_PREV_SIG, lastSig)
                    .putString(KEY_EB_LAST_SIG, "NONE")
                    .putLong(KEY_EB_LAST_TS, System.currentTimeMillis())
                    .apply()
            app.aaps.plugins.aps.openAPSFCL.vnext.logging.FclLearnerLogger.logEarlyBoost(
                metrics     = metrics,
                signal      = "NONE",
                tier        = "",
                oldBoost    = oldBoost,
                newBoost    = oldBoost,
                oldWatching = oldWatching,
                newWatching = oldWatching,
                step        = step
            )
            return null
        }

        // ── Oscillatie: FORWARD gevolgd door BACK → halveer stapgrootte ─
        if (signal == "BACK" && lastSig == "FORWARD") {
            step = (step / 2.0).coerceAtLeast(EB_STEP_MIN)
        }

        // Convergentie: stapgrootte op minimum → verlaagde intensiteit, nooit volledig stop.
        // Tel opeenvolgende FORWARD signalen; na EB_RESTART_AFTER stappen
        // maak één sprong van streak/3 en reset zodat het systeem blijft monitoren.
        val fwdStreak = p.getInt(KEY_EB_FWD_STREAK, 0)
        if (step <= EB_STEP_MIN && signal == "FORWARD") {
            val newStreak = fwdStreak + 1
            if (newStreak < EB_RESTART_AFTER) {
                // Nog niet genoeg opeenvolgende FORWARD → sla op, wacht
                p.edit().putInt(KEY_EB_FWD_STREAK, newStreak).apply()
                return "EARLYBOOST: convergentie monitoring ($newStreak/${EB_RESTART_AFTER})"
            } else {
                // Genoeg opeenvolgende FORWARD: maak één sprong van streak/3 stappen
                step = (EB_STEP_MIN * (newStreak / 3.0)).coerceAtMost(EB_STEP_MAX / 2)
                p.edit().putInt(KEY_EB_FWD_STREAK, 0).apply()  // reset streak
            }
        } else {
            // Niet-FORWARD signaal: reset streak
            if (fwdStreak > 0) p.edit().putInt(KEY_EB_FWD_STREAK, 0).apply()
        }

        // ── Nieuwe waarden (absoluut budget-neutraal) ───────────────────
        // stepU = absolute insuline-stap (bijv. 0.15U).
        // Bij BACK: 3× stepU terug zodat asymmetrie voorzichtigheid borgt.
        // Bij tier-2 FORWARD: gebruik halve stap (onzekerder signaal).
        val effectiveStep = if (stapHalveerVoorTier2) step / 2.0 else step
        val stepU = effectiveStep
        val absStep = if (signal == "FORWARD") stepU else 3.0 * stepU
        val direction = if (signal == "FORWARD") 1.0 else -1.0

        // earlyBoostFactor: pas aan zodat earlyBoostDeliveredU met absStep verandert
        val boostDeltaFrac = if (ebDeliveredU > 0.1)
            direction * absStep / ebDeliveredU else direction * 0.03
        val newBoost = (oldBoost * (1.0 + boostDeltaFrac)).coerceIn(EB_BOOST_MIN, EB_BOOST_MAX)

        // watchingFrontloadFrac: pas aan zodat watchingDeliveredU met -absStep verandert
        val watchDeltaFrac = -direction * absStep / (maxSmb * watchCommits)
        val newWatching = (oldWatching + watchDeltaFrac).coerceIn(EB_WATCHING_MIN, EB_WATCHING_MAX)

        // refEb: pas de structurele "ondergrond" van de early boost aan.
        // refEb bepaalt het basisniveau dat toParamOverrides() gebruikt voor de
        // volgende leerronde, ongeacht de eb_factor-aanpassing hierboven.
        // Kleinere stap dan eb_factor: refEb is een persistente parameter die
        // langzamer beweegt (voorzichtiger dan de directe eb_factor stap).
        // FORWARD: refEb omhoog — systematisch meer gewicht op vroege commits.
        // BACK:    refEb omlaag — systematisch minder vroeg gewicht.
        val oldRefEb = getRefEb(context)
        val refEbStap = effectiveStep * 0.15  // 15% van de eb_factor stap
        val newRefEb = (oldRefEb + direction * refEbStap)
            .coerceIn(DFMapping.REF_EB_MIN, DFMapping.REF_EB_MAX)
        setRefEb(context, newRefEb)

        p.edit()
            .putFloat(KEY_EB_BOOST,    newBoost.toFloat())
            .putFloat(KEY_EB_WATCHING, newWatching.toFloat())
            .putFloat(KEY_EB_STEP,     step.toFloat())
            .putString(KEY_EB_PREV_SIG, lastSig)
            .putString(KEY_EB_LAST_SIG, signal)
            .putLong(KEY_EB_LAST_TS, System.currentTimeMillis())
            .apply()


        val tier = if (stapHalveerVoorTier2) "T2" else "T1"
        val richting = if (signal == "FORWARD") "→ voren" else "← terug"

        app.aaps.plugins.aps.openAPSFCL.vnext.logging.FclLearnerLogger.logEarlyBoost(
            metrics     = metrics,
            signal      = signal,
            tier        = tier,
            oldBoost    = oldBoost,
            newBoost    = newBoost,
            oldWatching = oldWatching,
            newWatching = newWatching,
            step        = effectiveStep
        )

        return "EARLYBOOST[$tier] $richting: boost ${"%.3f".format(oldBoost)}" +
            "→${"%.3f".format(newBoost)} watch ${"%.3f".format(oldWatching)}" +
            "→${"%.3f".format(newWatching)} step=${"%.4f".format(effectiveStep)} [$signal]"
    }
}