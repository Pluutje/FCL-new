package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

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

    // ── Kalibratie-waarden ────────────────────────────────────────────────
    private const val KEY_REF_WMD = "df_ref_wmd"   // Stijgingsdrempel frontload
    private const val KEY_REF_WFF = "df_ref_wff"   // Frontload grootte
    private const val KEY_REF_EB  = "df_ref_eb"    // Vroege boost

    // ── Maaltijdtype-specifieke D/F waarden ──────────────────────────────
    private const val KEY_D_SNEL  = "df_d_snel"
    private const val KEY_F_SNEL  = "df_f_snel"
    private const val KEY_D_TRAAG = "df_d_traag"
    private const val KEY_F_TRAAG = "df_f_traag"
    // Episodes per type (voor minimum-drempel)
    private const val KEY_COUNT_SNEL  = "df_count_snel"
    private const val KEY_COUNT_TRAAG = "df_count_traag"
    private const val MIN_EPISODES_PER_TYPE = 3

    // Type-specifieke history (apart van de algemene history)
    private const val KEY_HISTORY_SNEL  = "df_history_snel"
    private const val KEY_HISTORY_TRAAG = "df_history_traag"

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

    fun setTempo(context: Context, tempo: Tempo) =
        prefs(context).edit().putString(KEY_TEMPO, tempo.name).apply()

    fun isAutoEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO, false)

    fun setAutoEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO, enabled).apply()

    // ── Maaltijdtype-specifieke D/F get/set ──────────────────────────────

    fun getDForType(context: Context, type: app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType): Double {
        val p = prefs(context)
        return when (type) {
            app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType.SNEL  ->
                p.getFloat(KEY_D_SNEL,  getD(context).toFloat()).toDouble()
            app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType.TRAAG ->
                p.getFloat(KEY_D_TRAAG, getD(context).toFloat()).toDouble()
            else -> getD(context)
        }
    }

    fun getFForType(context: Context, type: app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType): Double {
        val p = prefs(context)
        return when (type) {
            app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType.SNEL  ->
                p.getFloat(KEY_F_SNEL,  getF(context).toFloat()).toDouble()
            app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType.TRAAG ->
                p.getFloat(KEY_F_TRAAG, getF(context).toFloat()).toDouble()
            else -> getF(context)
        }
    }

    fun setDForType(context: Context, type: app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType, d: Double) {
        val key = when (type) {
            app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType.SNEL  -> KEY_D_SNEL
            app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType.TRAAG -> KEY_D_TRAAG
            else -> return
        }
        prefs(context).edit().putFloat(key, d.coerceIn(DFMapping.D_MIN, DFMapping.D_MAX).toFloat()).apply()
    }

    fun setFForType(context: Context, type: app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType, f: Double) {
        val key = when (type) {
            app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType.SNEL  -> KEY_F_SNEL
            app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType.TRAAG -> KEY_F_TRAAG
            else -> return
        }
        prefs(context).edit().putFloat(key, f.coerceIn(DFMapping.F_MIN, DFMapping.F_MAX).toFloat()).apply()
    }

    private fun getEpisodeCountForType(context: Context, type: app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType): Int {
        val key = when (type) {
            app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType.SNEL  -> KEY_COUNT_SNEL
            app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType.TRAAG -> KEY_COUNT_TRAAG
            else -> return Int.MAX_VALUE  // GEMENGD telt altijd mee
        }
        return prefs(context).getInt(key, 0)
    }

    private fun incrementEpisodeCountForType(context: Context, type: app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType) {
        val key = when (type) {
            app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType.SNEL  -> KEY_COUNT_SNEL
            app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType.TRAAG -> KEY_COUNT_TRAAG
            else -> return
        }
        val current = prefs(context).getInt(key, 0)
        prefs(context).edit().putInt(key, current + 1).apply()
    }

    /**
     * Type-specifieke evaluate: als mealType bekend is, wordt deltaD/deltaF
     * opgeslagen in de type-specifieke sleutels in plaats van de algemene.
     * Minimaal MIN_EPISODES_PER_TYPE episodes per type vereist voor learning.
     */
    fun evaluateForType(
        context: Context,
        metrics: EpisodeMetrics,
        mealType: app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType
    ): LearningStep? {
        // GEMENGD en ONBEKEND → gebruik standaard evaluate
        if (mealType == app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType.GEMENGD ||
            mealType == app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType.ONBEKEND) {
            return evaluate(context, metrics)
        }

        // Minimale drempel: niet leren tot er genoeg type-episodes zijn
        val count = getEpisodeCountForType(context, mealType)
        incrementEpisodeCountForType(context, mealType)
        if (count < MIN_EPISODES_PER_TYPE) return null

        // Bereken de leerstap op basis van de HUIDIGE type-specifieke D/F
        // (niet via evaluate() die de algemene D/F aanpast)
        val d = getDForType(context, mealType)
        val f = getFForType(context, mealType)

        // Voer evaluate uit puur voor signaalberekening
        // maar BEWAAR de algemene D/F en herstel ze daarna
        val savedD = getD(context)
        val savedF = getF(context)
        // Zet type-D/F als algemeen zodat evaluate de juiste basis gebruikt
        setD(context, d)
        setF(context, f)

        val step = evaluate(context, metrics)

        // Herstel algemene D/F — type-aanpassing mag die niet overschrijven
        setD(context, savedD)
        setF(context, savedF)

        if (step == null) return null

        // Corrigeer mealType in de step
        val typedStep = step.copy(mealType = mealType.name)

        // Overschrijf D/F in type-specifieke sleutels
        val currentD = getDForType(context, mealType)
        val currentF = getFForType(context, mealType)
        setDForType(context, mealType, currentD + typedStep.deltaD)
        setFForType(context, mealType, currentF + typedStep.deltaF)

        // Sla ook op in type-specifieke history
        val histKey = when (mealType) {
            app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType.SNEL  -> KEY_HISTORY_SNEL
            app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType.TRAAG -> KEY_HISTORY_TRAAG
            else -> null
        }
        if (histKey != null) {
            val existing = (prefs(context).getString(histKey, "") ?: "")
                .split("\n").mapNotNull { parseStep(it) }.takeLast(19)
            val all = (existing.map { serializeStep(it) } + serializeStep(typedStep)).joinToString("\n")
            prefs(context).edit().putString(histKey, all).apply()
        }

        return typedStep
    }

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

    fun getHistory(context: Context): List<LearningStep> {
        val raw = prefs(context).getString(KEY_HISTORY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { parseStep(it) }
    }

    fun getHistoryForType(
        context: Context,
        type: app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType
    ): List<LearningStep> {
        val key = when (type) {
            app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType.SNEL  -> KEY_HISTORY_SNEL
            app.aaps.plugins.aps.openAPSFCL.vnext.MealTypeBridge.MealType.TRAAG -> KEY_HISTORY_TRAAG
            else -> return getHistory(context)
        }
        val raw = prefs(context).getString(key, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { parseStep(it) }
    }

    /**
     * Evalueer de uitkomst van één maaltijdepisode en pas D en F aan als
     * aan de voorwaarden wordt voldaan. Retourneert de LearningStep.
     */
    fun evaluate(
        context: Context,
        metrics: EpisodeMetrics
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
            // Follow-ups kwamen WEL maar hypo trad toch op → D te hoog was oorzaak
            hypoStraf > 0.0 && fracHoog && safeFollowUp -> {
                rawDeltaD = -tp.betaHypo * hypoStraf
                rawDeltaF = 0.0   // F neutraal: follow-ups kwamen, budget was teruggehaald
                diagnose  = "HYPO_D_PROBLEEM"
            }
            // Gewone hypo met lage frontload → ook niet F schuld
            hypoStraf > 0.0 -> {
                rawDeltaD = -tp.betaHypo * hypoStraf
                rawDeltaF = 0.0   // F neutraal: frontload was niet de oorzaak
                diagnose  = "HYPO"
            }

            // ── PIEK HOOG ─────────────────────────────────────────────────────────
            // Piek hoog + verdeling slecht: frontload én meer totaal nodig
            peekHoog && fracLaag -> {
                rawDeltaD = +tp.alphaPiek * 0.5 * abs(peakFout)
                rawDeltaF = +tp.gammaIobr * 2.5 * abs(frac - TARGET_FIRST_FRAC)
                diagnose  = "TIMING_SPREAD"
            }
            // Piek hoog + verdeling was al goed: meer totaal nodig, F met rust
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
                rawDeltaD = 0.0
                rawDeltaF = +tp.gammaIobr * 1.5 * abs(frac - TARGET_FIRST_FRAC)
                diagnose  = "FRONTLOAD_LAG"
            }
            // Piek OK, verdeling slecht, maar onvoldoende follow-ups: voorzichtiger
            fracLaag -> {
                rawDeltaD = 0.0
                rawDeltaF = +tp.gammaIobr * 0.6 * abs(frac - TARGET_FIRST_FRAC)
                diagnose  = "FRONTLOAD_LAG"
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
            if (abs(peakFout) > 0.5) append("piek ${if (peakFout > 0) "+" else ""}${String.format("%.1f", peakFout)}mmol ")
            if (abs(iobrFout) > 0.05) append("IOBpiek ${if (iobrFout > 0) "+" else ""}${String.format("%.2f", iobrFout)} ")
            if (hypoStraf > 0.1) append("hypo_straf=${String.format("%.2f", hypoStraf)} ")
            append("[tempo=$tempo]")
        }

        // ── Opslaan ─────────────────────────────────────────────────────
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putFloat(KEY_D, newD.toFloat())
            .putFloat(KEY_F, newF.toFloat())
            .putLong(KEY_LAST_TS, now)
            .putInt(KEY_EP_COUNT, 0)
            .putFloat(KEY_ACCUM_D, 0f)   // accumulator resetten na toepassing
            .putFloat(KEY_ACCUM_F, 0f)
            .putFloat(weekKey,  (weekDeltaD + effectiveDeltaD).toFloat())
            .putFloat(weekKeyF, (weekDeltaF + effectiveDeltaF).toFloat())
            .apply()

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

        appendHistory(context, step)
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
        prefs(context).edit()
            // Type-specifieke D/F terug naar algemene waarden
            .remove(KEY_D_SNEL)
            .remove(KEY_F_SNEL)
            .remove(KEY_D_TRAAG)
            .remove(KEY_F_TRAAG)
            // Episode-tellers resetten
            .putInt(KEY_COUNT_SNEL, 0)
            .putInt(KEY_COUNT_TRAAG, 0)
            // Type-specifieke history wissen
            .remove(KEY_HISTORY_SNEL)
            .remove(KEY_HISTORY_TRAAG)
            .apply()
    }

    // ── Intern ────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun appendHistory(context: Context, step: LearningStep) {
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