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

    // Agressiviteitsschaal (1-9). Stap 1: opgeslagen maar nog niet
    // gekoppeld aan params (dat gebeurt in Stap 2).
    // 1=voorzichtig, 5=standaard, 9=agressief
    private const val KEY_AGGRESSIVENESS = "df_aggressiveness"
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

    // Leergeschiedenis tellers (voor de status-indicator in de UI)

    fun getHistory(context: Context): List<LearningStep> {
        val raw = prefs(context).getString(KEY_HISTORY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { parseStep(it) }
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
        if (!isAutoEnabled(context)) return null

        val tempo = getTempo(context)
        val tp    = tempoMap[tempo] ?: return null

        // Veiligheidsslot: geen aanpassing bij hoge TBR
        // (gecontroleerd door aanroeper via allRows)

        // Cooldown: min X uur sinds laatste aanpassing
        val lastTs = prefs(context).getLong(KEY_LAST_TS, 0L)
        val hoursSinceLast = (System.currentTimeMillis() - lastTs) / 3_600_000L
        if (lastTs > 0 && hoursSinceLast < tp.minHours) return null

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
            // Follow-ups kwamen WEL maar hypo trad toch op → D te hoog was oorzaak.
            // Als afterload actief was: de guard deed zijn best maar kon het niet
            // voorkomen → D stap normaal maar niet vergroot.
            hypoStraf > 0.0 && fracHoog && safeFollowUp -> {
                // Halfeer de stap als afterload al actief was (guard remde al)
                val afterloadDemper = if (metrics.afterloadWasActive) 0.5 else 1.0
                rawDeltaD = -tp.betaHypo * hypoStraf * afterloadDemper
                rawDeltaF = 0.0
                diagnose  = if (metrics.afterloadWasActive) "HYPO_D_DEMPED" else "HYPO_D_PROBLEEM"
            }
            // Gewone hypo met lage frontload → ook niet F schuld
            hypoStraf > 0.0 -> {
                rawDeltaD = -tp.betaHypo * hypoStraf
                rawDeltaF = 0.0   // F neutraal: frontload was niet de oorzaak
                diagnose  = "HYPO"
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

            // ── PIEK OK, VERDELING SLECHT ─────────────────────────────────────────
            // Piek was OK maar eerste commit was klein aandeel van totaal.
            // Er waren genoeg follow-ups → veilig bewijs: F omhoog.
            fracLaag && safeFollowUp -> {
                val eb = if (earlyPredTeLaag) 1.0 + earlyPredStrength * 0.5 else 1.0
                rawDeltaD = 0.0
                rawDeltaF = +tp.gammaIobr * 1.5 * abs(frac - TARGET_FIRST_FRAC) * eb
                diagnose  = if (earlyPredTeLaag) "FRONTLOAD_LAG_VROEG" else "FRONTLOAD_LAG"
            }
            fracLaag -> {
                val eb = if (earlyPredTeLaag) 1.0 + earlyPredStrength * 0.3 else 1.0
                rawDeltaD = 0.0
                rawDeltaF = +tp.gammaIobr * 0.6 * abs(frac - TARGET_FIRST_FRAC) * eb
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

            // ── AFTERLOAD GUARD ACTIEF, EPISODE GOED AFGELOPEN ──────────────────
            // De afterload guard heeft insuline teruggehouden en er was geen hypo/hyper.
            // Dit betekent: S is nu defensief door eerdere hypo's, maar het guard
            // doet zijn werk. Voorkom verdere D-daling; laat D neutraal.
            // Als er géén afterload actief was en de episode was goed → S mag omhoog.
            !peekHoog && !peekLaag && hypoStraf == 0.0 && metrics.afterloadWasActive -> {
                // Guard was actief en hield de piek laag — goed resultaat.
                // Beloon voorzichtig: kleine D-stap omhoog zodat S langzaam
                // terugkeert naar het niveau zonder guard-afhankelijkheid.
                rawDeltaD = +tp.alphaPiek * 0.3   // klein positief signaal
                rawDeltaF = 0.0
                diagnose  = "AFTERLOAD_GUARD_OK"
            }

            else -> {
                rawDeltaD = 0.0; rawDeltaF = 0.0; diagnose = "OK"
            }
        }

        if (diagnose == "SKIP_GEEN_EPISODE") return null

        // ── Accumuleer signaal ──────────────────────────────────────────
        // Elke wachtepisode telt mee. Consistente richting → groter gecombineerd effect.
        // Wisselende richting → gedeeltelijke of volledige opheffing → kleine stap.
        val accumD = prefs(context).getFloat(KEY_ACCUM_D, 0f).toDouble() + rawDeltaD
        val accumF = prefs(context).getFloat(KEY_ACCUM_F, 0f).toDouble() + rawDeltaF

        val epCount = prefs(context).getInt(KEY_EP_COUNT, 0) + 1

        // Accumulatie opslaan, nog geen aanpassing
        prefs(context).edit()
            .putFloat(KEY_ACCUM_D, accumD.toFloat())
            .putFloat(KEY_ACCUM_F, accumF.toFloat())
            .putInt(KEY_EP_COUNT, epCount)
            .apply()

        if (epCount < tp.minEpisodes) return null

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
            append("[tempo=$tempo]")
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

        appendHistory(context, step, skip = skipHistory)
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
    fun resetTypeData(context: Context) {
        // Resetwaarden: 1 stap agressiever dan de neutrale standaard.
        // D=0.968 -> S~92%, F=0.775 -> T~117%, V~93%.
        // Dit is de basisinstelling voor een ervaren gebruiker van Lyumjev U200.
        val resetD = 0.968f
        val resetF = 0.775f
        prefs(context).edit()
            // Algemene D/F resetten naar verhoogde basiswaarden
            .putFloat(KEY_D, resetD)
            .putFloat(KEY_F, resetF)
            // Type-specifieke D/F terug naar algemene waarden
            // Onafhankelijke V-offset resetten naar nul
            // Episode-tellers resetten
            // Alle history wissen zodat 'Laatste aanpassingen' leeg is na reset
            .remove(KEY_HISTORY)
            .apply()
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
}