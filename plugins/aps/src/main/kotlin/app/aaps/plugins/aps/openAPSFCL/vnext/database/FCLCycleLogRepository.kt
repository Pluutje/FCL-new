package app.aaps.plugins.aps.openAPSFCL.vnext.database

import android.content.Context
import app.aaps.plugins.aps.openAPSFCL.vnext.FclActiveConfigBridge
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFLearner
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.EpisodeDetector
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.EpisodeMetricsBuilder
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.FrontloadLearner
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.FclLearnerBackup
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.toLogRow
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.PostHypoBrakeLogEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.persist.VLearner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FCLCycleLogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: app.aaps.core.keys.interfaces.Preferences
) {
    private val db by lazy { FCLAnalyzerDatabase.getInstance(context) }
    private val dao by lazy { db.cycleLogDao() }
    private val episodeDao by lazy { db.episodeDao() }
    private val postHypoBrakeDao by lazy { db.postHypoBrakeLogDao() }
    private val scope = CoroutineScope(Dispatchers.IO)

    private val persistDb by lazy {
        app.aaps.plugins.aps.openAPSFCL.vnext.persist.FCLPersistDatabase.getInstance(context)
    }
    private val persistDao by lazy { persistDb.persistEventDao() }

    private var lastCsvExportHour: Int = -1
    private var lastBackupCheckHour: Int = -1
    private var lastLearnerRunHour: Int = -1
    private var lastCgpCalcHour: Int = -1   // berekening elke 2 uur

    fun insert(entity: FCLCycleLogEntity) {
        scope.launch {
            dao.insert(entity)
            pruneOldData()
            maybeExportCsv()
            maybeWriteBackup()
            maybeRunLearners()
            maybeCgpCalc()
        }
    }

    /**
     * Log één cyclus van PersistentCorrectionController waarin active==true.
     * Aangeroepen direct vanuit FCLvNext.kt, los van de hoofd-CSV-insert,
     * zodat dit een eigen, klein leerbestand blijft (zie FCLPersistDatabase).
     */
    fun logPersistEvent(entity: app.aaps.plugins.aps.openAPSFCL.vnext.persist.FCLPersistEventEntity) {
        scope.launch {
            persistDao.insert(entity)
            persistDao.deleteOlderThan(
                app.aaps.plugins.aps.openAPSFCL.vnext.persist.FCLPersistDatabase.cutoffMs()
            )
        }
    }

    /**
     * Log de eindstand van de post-hypo-brake per cyclus (26/08/2026) in een
     * eigen, kleine tabel -- zie kdoc bij PostHypoBrakeLogEntity voor de
     * aanleiding. Los van de hoofd-CSV-insert, zelfde fire-and-forget
     * patroon als logPersistEvent() hierboven.
     */
    fun logPostHypoBrake(active: Boolean, armedMinutes: Int, timestampMs: Long) {
        scope.launch {
            postHypoBrakeDao.insert(
                PostHypoBrakeLogEntity(
                    timestampMs = timestampMs,
                    active = active,
                    armedMinutes = armedMinutes
                )
            )
            postHypoBrakeDao.deleteOlderThan(FCLAnalyzerDatabase.cutoffMs())
        }
    }

    private suspend fun pruneOldData() {
        dao.deleteOlderThan(FCLAnalyzerDatabase.cutoffMs())
    }

    private suspend fun maybeExportCsv() {
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (currentHour == lastCsvExportHour) return
        lastCsvExportHour = currentHour
        exportCsvLast7Days()
    }

    /**
     * Dagelijkse backup van de geleerde D/F/V-staat en verwante
     * plugin-interne instellingen (zie FclLearnerBackup.kt, 26/08/2026 —
     * n.a.v. het verlies van deze staat bij de overstap naar de nieuwe
     * telefoon op 20/08/2026). Zelfde uur-gate als maybeExportCsv(); de
     * daadwerkelijke "vandaag al gedaan?"-check zit in
     * FclLearnerBackup.maybeWriteDailyBackup() zelf, dus dit hier is
     * puur om niet elke cyclus een bestand-exists-check te doen.
     */
    private suspend fun maybeWriteBackup() {
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (currentHour == lastBackupCheckHour) return
        lastBackupCheckHour = currentHour
        FclLearnerBackup.maybeWriteDailyBackup(context)
    }

    /**
     * Autonoom leerproces — draait eens per uur na de CSV-export.
     *
     * Volgt exact hetzelfde patroon als maybeExportCsv():
     * geen UI, geen user-interactie vereist, werkt volledig op de Room-database.
     *
     * Pipeline:
     *   1. Lees alle LogRows uit de DB (zelfde bron als de Analyzer-screen)
     *   2. EpisodeDetector.detect() — zelfde logica als in de UI
     *   3. EpisodeMetricsBuilder.build() — zelfde logica als in de UI
     *   4. DFLearner.evaluate() — D/F aanpassen + loggen naar FclLearnerLogger
     *   5. FrontloadLearner.evaluate() — REF_WMD aanpassen + loggen
     *      (earlyBoost wordt aangeroepen vanuit DFLearner.evaluate())
     *
     * De learner-cooldowns (minHours, 48u) en weekgrens zorgen ervoor dat
     * de aanpassingen niet te frequent zijn, ook al draait dit elk uur.
     *
     * Bewuste vereenvoudigingen t.o.v. de UI-pipeline:
     * - Geen enrichMetricsWithAdviceState: advice-status is niet relevant voor leren
     * - Geen rescue-classificatie override: rescueConfirmed blijft false
     *   (gebruiker kan rescue-vinkjes zetten via UI; dit beïnvloedt de volgende run)
     * - Geen episode-upsert naar EpisodeDao: dat doet de UI al bij openen
     * - Filter: alleen afgesloten episodes (isComplete=true via EpisodeDetector)
     */
    private suspend fun maybeRunLearners() {
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (currentHour == lastLearnerRunHour) return
        lastLearnerRunHour = currentHour

        // Kleine vertraging zodat de learner nooit synchroon met een insert() loopt.
        // Dit voorkomt dat de zware getAll-query de Room-threadpool blokkeert
        // terwijl AAPS zijn 5-minuten berekeningscyclus start.
        kotlinx.coroutines.delay(5_000L)

        runLearners()
    }

    private suspend fun runLearners() {
        // ── Stap 1: lees alleen de laatste 7 dagen (niet getAll) ──────────
        // getSince() is dezelfde query als exportCsvLast7Days gebruikt —
        // beperkt tot ~2000 rows ipv potentieel veel meer bij getAll().
        val sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000L
        val entities = dao.getSince(sevenDaysAgo)
        if (entities.size < 10) return

        val allRows = entities.map { it.toLogRow() }

        // ── Stap 2: episode-detectie ──────────────────────────────────────
        val detected = EpisodeDetector.detect(allRows)

        // Verwijder de eerste (mogelijk onvolledige) episode en filter
        // episodes die geen significante dosis bevatten — identiek aan de UI.
        val manualMaxSmb = FclActiveConfigBridge.get()?.manualMaxBolus ?: 1.25
        // 12/07/2026 — zie kdoc bij Fclactiveconfigbridge.Snapshot.manualMaxIob.
        val manualMaxIob = FclActiveConfigBridge.get()?.manualMaxIob ?: 10.0
        // 12/07/2026 — zie kdoc bij Fclactiveconfigbridge.Snapshot.effectiveIsfMmol.
        val effectiveIsfMmol = FclActiveConfigBridge.get()?.effectiveIsfMmol ?: 4.0
        val significantDoseThreshold = manualMaxSmb * 0.80

        val allCleaned = if (detected.size > 1) detected.drop(1) else emptyList()
        if (allCleaned.isEmpty()) return

        // Alleen afgesloten episodes voor het leerproces
        val completedEpisodes = allCleaned.filter { it.isComplete }
        if (completedEpisodes.isEmpty()) return

        // ── Stap 3: metrics bouwen ────────────────────────────────────────
        var episodeMetrics = EpisodeMetricsBuilder.build(completedEpisodes, manualMaxSmb)

        // BUGFIX (20/06/2026): EpisodeMetricsBuilder zet rescueConfirmed
        // altijd op false ("wordt later overschreven vanuit DB") — die
        // overschrijving gebeurde tot nu toe ALLEEN in Fclanalyzerscreen.kt
        // (UI-getriggerd, "Stap 4"), nooit in deze automatische
        // achtergrond-pijplijn. Gevolg: een bevestiging op "Daadwerkelijk
        // genomen: Ja" in de Episode Viewer kwam nooit bij de automatische
        // DFLearner.evaluate()/evaluateEarlyBoost()-aanroep hieronder terecht
        // — de RESCUE_OVERPOWERED-tak (D/F) en de nieuwe nearMissViaRescue-tak
        // (earlyBoostFactor/watchingFrontloadFrac) vuurden hierdoor in de
        // praktijk nooit automatisch af, ook niet na een bevestigd
        // afgewend incident. Hier dezelfde matching als in Fclanalyzerscreen.kt
        // (op Episode.start.toString() == EpisodeEntity.startTs).
        val rescueYesTs = episodeDao.getRescueConfirmedEpisodes().map { it.startTs }.toSet()
        if (rescueYesTs.isNotEmpty()) {
            episodeMetrics = episodeMetrics.mapIndexed { i, m ->
                val ep = completedEpisodes.getOrNull(i)
                if (ep != null && ep.start.toString() in rescueYesTs) m.copy(rescueConfirmed = true) else m
            }
        }

        // ── Stap 4: DFLearner ─────────────────────────────────────────────
        // evaluate() logt altijd (ook bij AUTO_DISABLED/COOLDOWN/etc) via
        // FclLearnerLogger, en past D/F alleen toe als isAutoEnabled=true.
        val latestMetrics = episodeMetrics.lastOrNull()
        // 10/07/2026 — OFF-modus: evaluate() zelf overslaan, niet alleen
        // het toepassen. Voorheen rekende/logde evaluate() altijd, ook bij
        // isAutoEnabled=false — dat voldeed niet meer aan "doet dan echt niks
        // meer" zodra er een expliciete OFF-stand bijkomt naast AUTO/MANUAL.
        //
        // HERZIEN (10/07/2026) — de return-waarde (LearningStep?) werd
        // hier altijd genegeerd. Die bevat de diagnose-code en oude D/F-
        // waarden, nodig voor een leesbare uitleg bij het MANUAL-voorstel
        // (zie FclLearnerUitleg.kt) — vastgelegd in learningStep hieronder.
        // 26/07/2026 — dag/nacht-splitsing: welke as geldt hangt af van
        // de episode zelf (latestMetrics.isNight), niet van "is het nu nacht".
        var learningStep: DFLearner.LearningStep? = null
        if (latestMetrics != null && DFLearner.isEvaluationEnabled(context, latestMetrics.isNight)) {
            learningStep = DFLearner.evaluate(context, latestMetrics, manualMaxSmb = manualMaxSmb, manualMaxIob = manualMaxIob, effectiveIsfMmol = effectiveIsfMmol)
            // Losse leeras voor refLcd (laatste-commit-demping) — zie kdoc
            // bij DFMapping.REF_LCD_DEFAULT en DFLearner.evaluateLateCommitDecay.
            // Bewust een eigen aanroep, niet ondergebracht in evaluate()
            // zelf: dit reageert specifiek op het "te late/te grote laatste
            // commit"-patroon, los van de algehele D/F-balans.
            DFLearner.evaluateLateCommitDecay(context, latestMetrics, manualMaxSmb = manualMaxSmb)
        }

        // ── Stap 5: FrontloadLearner ───────────────────────────────────────
        // Onafhankelijk van DFLearner.isAutoEnabled — logt altijd via
        // FclLearnerLogger (ook bij GOED).
        FrontloadLearner.evaluate(context, episodeMetrics)

        // ── Stap 6: VLearner (Volhoudendheid) ──────────────────────────────
        // Volledig onafhankelijk van episodes — werkt op PERSIST-clusters
        // uit de aparte FCLPersistDatabase. Dekt zowel postprandiale
        // plateaus als nachtelijke persistente hoge BG bij lage IOB.
        val persistEvents = persistDao.getSince(sevenDaysAgo)
        VLearner.evaluate(context, persistEvents, episodeMetrics)

        // ── Stap 7: Automatisch toepassen van alle geleerde waarden ────────
        // D/F/refWmd/refWff/refEb worden na elke leerronde direct via
        // ConfigOverrideWriter actief gemaakt — zonder dat de gebruiker
        // op "Toepassen in AAPS" hoeft te drukken voor de leer-assen.
        // Dit dicht het architecturele gat waarbij evaluateEarlyBoost()
        // refEb bijwerkt maar die waarde nooit in loadFCLvNextConfig()
        // terechtkwam. De "Toepassen in AAPS"-knop blijft alleen voor
        // de agressiviteits-override.
        //
        // HERZIEN (10/07/2026, de gebruiker — Fase 2, Learner MANUAL-modus): de
        // getters hieronder lopen nu buiten de mode-check, want ze zijn
        // hoe dan ook nodig (ook om een voorstel te bouwen bij MANUAL).
        // Alleen het TOEPASSEN (AUTO) vs. VOORSTELLEN (MANUAL) vertakt.
        // OFF: evaluate() zelf draaide al niet (zie isEvaluationEnabled
        // hierboven), dus hier is dan simpelweg niets te doen.
        // 26/07/2026 — zelfde as als Stap 4 hierboven: welke episode
        // net beoordeeld is (latestMetrics?.isNight) bepaalt welke modus
        // hier geldt. Geen latestMetrics (geen episodes deze cyclus) → val
        // terug op de DAG-as, puur zodat er iets zinnigs gebeurt; er is dan
        // toch niets nieuws om toe te passen/voor te stellen.
        val learnerModeIsNight = latestMetrics?.isNight ?: false
        val learnerMode = DFLearner.getMode(context, learnerModeIsNight)
        if (learnerMode != app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.OFF) {
            val d      = DFLearner.getD(context)
            val f      = DFLearner.getF(context)
            val vExtra = DFLearner.getVExtra(context)
            val refWmd = DFLearner.getRefWmd(context)
            val refWff = DFLearner.getRefWff(context)
            val refEb  = DFLearner.getRefEb(context)
            val refPeakBias = DFLearner.getRefPeakBias(context)
            val refLcd = DFLearner.getRefLcd(context)
            val agg    = DFLearner.getAggressiveness(context)
            val reason = "D=${"%.3f".format(d)} " +
                "F=${"%.3f".format(f)} " +
                "wmd=${"%.2f".format(refWmd)} " +
                "wff=${"%.2f".format(refWff)} " +
                "eb=${"%.2f".format(refEb)} " +
                "peakBias=${"%.2f".format(refPeakBias)} " +
                "lcd=${"%.2f".format(refLcd)}"

            if (learnerMode == app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.AUTO) {
                val po = app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFMapping
                    .toParamOverrides(
                        d = d, f = f,
                        refWmd = refWmd, refWff = refWff, refEb = refEb,
                        refPeakBias = refPeakBias, refLcd = refLcd,
                        vExtra = vExtra, aggLevel = agg
                    )
                val stvMap = app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFMapping
                    .toStvMap(d, f, 85.0, vExtra, aggLevel = agg)

                app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ConfigOverrideWriter
                    .writeWithStvAndParams(
                        stvMap         = stvMap,
                        paramOverrides = po,
                        reason         = "auto-learner: $reason",
                        context        = context,
                        episodeCount   = episodeMetrics.size
                    )

                // 27/07/2026 — ook hier de "laatst toegepast"-snapshot
                // bijwerken (zie FclLearnerPendingProposal.isMeaningfullyDifferent()
                // / Stap 7's MANUAL-tak hieronder): schakel je later van
                // Automatisch naar Handmatig, dan wordt het eerstvolgende
                // MANUAL-voorstel vergeleken tegen wat AUTO hier het laatst
                // heeft geschreven — niet tegen een oude, mogelijk allang
                // ingehaalde handmatige goedkeuring van weken terug.
                app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.FclLearnerPendingProposal.saveLastApplied(
                    context,
                    app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.FclLearnerPendingProposal.Proposal(
                        tsMs = System.currentTimeMillis(),
                        d = d, f = f, vExtra = vExtra,
                        refWmd = refWmd, refWff = refWff, refEb = refEb,
                        refPeakBias = refPeakBias, refLcd = refLcd,
                        agg = agg, episodeCount = episodeMetrics.size, reason = reason
                    )
                )

                // 10/07/2026 — zachte convergentie voor de 7 AI-aanpasbare
                // parameters zonder eigen dedicated evaluator (zie kdoc bij
                // DFLearner.convergeTrackedParams). Hergebruikt de po die hierboven
                // toch al vers is berekend — geen dubbele DFMapping-aanroep nodig.
                DFLearner.convergeTrackedParams(
                    context,
                    mapOf(
                        "watchingMinDeltaToTarget" to (po.watchingMinDeltaToTarget ?: 0.0),
                        "commitCooldownMinutes"    to (po.commitCooldownMinutes?.toDouble() ?: 0.0),
                        "earlyBoostMinConfidence"  to (po.earlyBoostMinConfidence ?: 0.0),
                        "earlyBoostMaxCommits"     to (po.earlyBoostMaxCommits?.toDouble() ?: 0.0),
                        "earlyRiseFracMin"         to (po.earlyRiseFracMin ?: 0.0),
                        "lateCommitDecayThreshold" to (po.lateCommitDecayThreshold ?: 0.0),
                        "sustainedRiseSlopeMin"    to (po.sustainedRiseSlopeMin ?: 0.0)
                    ),
                    isNight = learnerModeIsNight
                )
            } else {
                // MANUAL (10/07/2026, de gebruiker — Fase 2): voorstel opslaan i.p.v.
                // direct toepassen, en de gebruiker via dezelfde native-
                // notificatie-methode als de AI-adviseur op de hoogte stellen.
                //
                // BUGFIX (27/07/2026): dit draaide voorheen ONVOORWAARDELIJK
                // bij elke episode — ook vlak na een Goedkeuring, wanneer d/f/etc.
                // nog nauwelijks van de zojuist toegepaste waarde waren afgeweken.
                // Gevolg: "die bleef een paar keer komen met hetzelfde advies ook
                // nadat ik het had geaccepteerd" — een technisch NIEUW voorstel
                // met bijna dezelfde getallen, dat de kaart/notificatie opnieuw
                // deed verschijnen. isMeaningfullyDifferent() vergelijkt tegen de
                // laatst toegepaste snapshot (Goedkeuren, of de laatste
                // AUTO-toepassing hierboven) en slaat een niet-wezenlijk-ander
                // voorstel gewoon over — geen save(), geen notificatie. Zodra
                // evaluate() écht iets nieuws heeft geleerd (buiten de kleine
                // marges), komt het voorstel gewoon weer terug.
                if (app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.FclLearnerPendingProposal.isMeaningfullyDifferent(
                        context, d, f, vExtra, refWmd, refWff, refEb, refPeakBias, refLcd
                    )) {
                    app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.FclLearnerPendingProposal.save(
                        context,
                        app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.FclLearnerPendingProposal.Proposal(
                            tsMs = System.currentTimeMillis(),
                            d = d, f = f, vExtra = vExtra,
                            refWmd = refWmd, refWff = refWff, refEb = refEb,
                            refPeakBias = refPeakBias, refLcd = refLcd,
                            agg = agg, episodeCount = episodeMetrics.size,
                            reason = reason,
                            // 10/07/2026 — voor de leesbare uitleg in de kaart
                            // (FclLearnerUitleg). learningStep is null als evaluate()
                            // deze cyclus geblokkeerd werd (cooldown/manual-correction/
                            // te weinig episodes) — dan vallen oldD/oldF terug op de
                            // huidige d/f (geen zichtbare verandering te tonen).
                            diagnose = learningStep?.diagnose ?: "",
                            oldD = learningStep?.oldD ?: d,
                            oldF = learningStep?.oldF ?: f
                        )
                    )
                    app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.FclLearnerNotificationHelper
                        .showPendingProposal(context)
                }
            }
        }

        // ── Stap 8: NachtLearner (NF-schaal, 1-9) ───────────────────────────
        // Evalueert alleen wanneer het NU overdag is (dus ná een afgeronde
        // nacht) — de eigen dagelijkse cooldown in NachtLearner zorgt dat dit
        // ondanks de uurlijkse aanroepfrequentie maar 1x per dag daadwerkelijk
        // een aanpassing doet. Toepassing op AAPS gebeurt binnen NachtLearner
        // zelf, sinds 26/07/2026 gegated door de eigen NACHT-as
        // (DFLearner.isAutoEnabled(context, isNight=true)) i.p.v. de dag-as —
        // zie kdoc in NachtLearner.kt.
        val isNightNow = app.aaps.plugins.aps.openAPSFCL.vnext.FCLvNextDayNightHelper(preferences).isNightNow()
        if (!isNightNow) {
            val laasteEpisodeEndMs = completedEpisodes.lastOrNull()?.end?.toEpochMilli()
            app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.NachtLearner.maybeLearnNacht(
                context             = context,
                preferences         = preferences,
                repository          = this,
                laasteEpisodeEndMs  = laasteEpisodeEndMs
            )
        }
    }

    /**
     * Bereken de CGP/PGR-score elke 2 uur over een 14-daags schuifvenster.
     * Het dagpunt van vandaag wordt telkens ververst (upsert).
     * Consistent met de backfill: alle punten zijn 14-daagse vensters.
     */
    /**
     * Bereken elke 2 uur twee reeksen:
     * - 14d-reeks: PGR over 14-daags schuifvenster → lijn + bovenste blok
     * - 24h-reeks: PGR over alleen de afgelopen 24 uur → stippen in grafiek
     */
    private suspend fun maybeCgpCalc() {
        val currentHour = java.util.Calendar.getInstance()
            .get(java.util.Calendar.HOUR_OF_DAY)
        if (currentHour == lastCgpCalcHour) return

        val today = java.time.LocalDate.now().toString()
        val existing14d = app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.CgpHistory
            .get14dScores(context)
        val heeftVandaag14d = existing14d.any { s ->
            try {
                java.time.Instant.parse(s.tsUtc)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate().toString() == today
            } catch (_: Exception) { false }
        }

        val isEvenHour = currentHour % 2 == 0
        if (!isEvenHour && heeftVandaag14d) return
        lastCgpCalcHour = currentHour

        // Backfill als er nog weinig punten zijn
        if (existing14d.size < 3) backfillCgpHistory()

        val now = System.currentTimeMillis()
        val tsNow = java.time.Instant.now().toString()

        // ── 14-daags schuifvenster ────────────────────────────────────────
        val rows14d = dao.getSince(now - 14L * 24 * 60 * 60 * 1000L)
        val bg14d = rows14d.map { it.glucoseIob.bg }.filter { it > 0.0 }
        if (bg14d.size >= 48) {
            val score14d = app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.CgpScoreCalculator
                .calculateFromBg(bg14d, tsNow)
            if (score14d != null) {
                app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.CgpHistory
                    .upsert14dScore(context, score14d)
            }
        }

        // ── 24-uurs dagpunt ───────────────────────────────────────────────
        val rows24h = dao.getSince(now - 24L * 60 * 60 * 1000L)
        val bg24h = rows24h.map { it.glucoseIob.bg }.filter { it > 0.0 }
        if (bg24h.size >= 24) {
            val score24h = app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.CgpScoreCalculator
                .calculateFromBg(bg24h, tsNow)
            if (score24h != null) {
                app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.CgpHistory
                    .upsert24hScore(context, score24h)
            }
        }
    }

    /**
     * Backfill: per kalenderdag zowel het 14d-venster als het 24u-dagpunt.
     */
    private suspend fun backfillCgpHistory() {
        val allRows = dao.getAll()
        if (allRows.isEmpty()) return

        val msPerDay = 24L * 60 * 60 * 1000L
        val windowMs14d = 14L * msPerDay

        val days = allRows
            .map { row ->
                java.time.Instant.ofEpochMilli(row.timestampMs)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()
            }
            .distinct().sorted()

        days.forEach { date ->
            val dayEndMs = date.plusDays(1)
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            val dayStartMs = dayEndMs - msPerDay
            val ts = date.atTime(23, 59, 59)
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant().toString()

            // 14d-venster
            val bg14d = allRows
                .filter { it.timestampMs in (dayEndMs - windowMs14d) until dayEndMs }
                .map { it.glucoseIob.bg }.filter { it > 0.0 }
            if (bg14d.size >= 48) {
                val s14d = app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.CgpScoreCalculator
                    .calculateFromBg(bg14d, ts)
                if (s14d != null)
                    app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.CgpHistory
                        .upsert14dScore(context, s14d)
            }

            // 24u-dagpunt
            val bg24h = allRows
                .filter { it.timestampMs in dayStartMs until dayEndMs }
                .map { it.glucoseIob.bg }.filter { it > 0.0 }
            if (bg24h.size >= 24) {
                val s24h = app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.CgpScoreCalculator
                    .calculateFromBg(bg24h, ts)
                if (s24h != null)
                    app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.CgpHistory
                        .upsert24hScore(context, s24h)
            }
        }
    }



    fun triggerLearnersNow() {
        scope.launch { runLearners() }
    }

    // ── Schrijflocatie + crash-hardening (20/08/2026) ────────────
    // AANLEIDING: overzet naar nieuwe telefoon — de app crashte op ELKE
    // BG-update (FATAL EXCEPTION, hele proces gekilld) met
    // FileNotFoundException: EACCES (Permission denied) op
    // Environment.getExternalStorageDirectory()/Documents/AAPS/ANALYSE.
    // Uit de AndroidManifest.xml bleek de app READ/WRITE_EXTERNAL_STORAGE
    // bewust te verwijderen ("App uses scoped storage / SAF (minSdk 31)") en
    // MANAGE_EXTERNAL_STORAGE nergens aan te vragen.
    // HERZIEN (20/08/2026, uitdrukkelijk verzoek): de gedeelde Documents-map
    // blijft bewust de schrijflocatie — dat is precies waarom dit bestand
    // met een gewone bestandsverkenner te vinden is, voor deze en elke
    // toekomstige gebruiker van de app. De juiste, structurele oplossing zit
    // dus niet hier, maar in de AndroidManifest.xml (buiten deze plugin-map,
    // dus niet in deze levering): MANAGE_EXTERNAL_STORAGE moet daar alsnog
    // worden aangevraagd. Zie de leveringsnotitie voor de exacte
    // manifest-snippet en de resterende stap (permissie via Instellingen of
    // adb toekennen na de rebuild) — zonder die manifest-wijziging zal dit
    // pad op een verse installatie (zoals nu) nog steeds EACCES geven.
    //
    // Daarnaast: de HELE functie blijft in try/catch. Dit bestand is
    // puur diagnostisch (voor de losse CSV-analyse) — nooit dosis-bepalend —
    // dus een schrijffout (schijf vol, onverwachte permissiewijziging, etc.)
    // mag nooit meer de hele closed loop meesleuren in een crash zoals nu
    // gebeurde. Bij een fout wordt alleen deze ene export overgeslagen, met
    // een regel in logcat; de rest van de cyclus (inclusief dosering) gaat
    // gewoon door.
    private suspend fun exportCsvLast7Days() {
        try {
            exportCsvLast7DaysInternal()
        } catch (e: Exception) {
            android.util.Log.e(
                "FCLCycleLogRepository",
                "exportCsvLast7Days mislukt — export overgeslagen, rest van de cyclus gaat door",
                e
            )
        }
    }

    private suspend fun exportCsvLast7DaysInternal() {
        val sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000L
        val rows = dao.getSince(sevenDaysAgo)
        if (rows.isEmpty()) return

        // 26/08/2026 -- post-hypo-brake-diagnostiek staat in een eigen tabel
        // (zie kdoc bij PostHypoBrakeLogEntity); hier op timestampMs samen-
        // voegen tot dezelfde CSV-regel als voorheen, zodat er voor de
        // gebruiker maar een bestand blijft. Elke cyclus die niet vroegtijdig
        // terugkeert (zie FCLvNext.kt) schrijft exact dezelfde now.millis naar
        // beide tabellen, dus een directe match op timestampMs volstaat.
        // Ontbreekt een match (vroegtijdige return, of de rij is ouder dan
        // deze feature) dan is de rem per definitie niet geevalueerd -> false/-1.
        val brakeByTs = postHypoBrakeDao.getSince(sevenDaysAgo).associateBy { it.timestampMs }

        val dir = File(
            android.os.Environment.getExternalStorageDirectory(),
            "Documents/AAPS/ANALYSE"
        )
        dir.mkdirs()
        // 11/07/2026: +2 kolommen (bg_stijgt_nog_fors, commit_nr_used).
        // HERZIEN (12/07/2026): eerder hier hernoemd naar v9, zoals de
        // vaste discipline bij een schema-wijziging voorschrijft (zie
        // FCLvNext_ActivityLog_v1→v2). Op de gebruikers toestel bleek de daadwerkelijk
        // geïnstalleerde app echter gewoon op v8 te zijn blijven schrijven —
        // waarschijnlijk doordat niet elke gewijzigde bestand uit die levering
        // is toegepast, of een eerdere build actief bleef. Op uitdrukkelijk
        // verzoek TERUGGEZET naar v8: nu alsnog naar v9 hernoemen zou de net
        // ontstane nieuwe v8-geschiedenis (vannacht/vanochtend) opnieuw
        // versnipperen over een derde bestand. De twee nieuwe kolommen hierboven
        // blijven wel gewoon actief in de v8-header — alleen de bestandsnaam zelf
        // is niet meegegaan.
        // v9->v10 (16/08/2026) — 3 nieuwe kolommen (aigf_b_pct/
        // aigf_b_active/aigf_b_reason, zie csvHeader() hieronder) bij het
        // HERONTWERP van AIGF component B (dosis-drempel-freeze -> live).
        // LET OP (zie kdoc iets hierboven, 12/07/2026-incident): bij een
        // eerdere v8->v9-hernoeming bleek de daadwerkelijk geinstalleerde
        // app soms toch op de oude bestandsnaam te blijven schrijven — hou
        // hier rekening mee bij het verifieren dat deze build ook echt actief is.
        // v10->v11 (26/08/2026) -- +2 kolommen (post_hypo_brake_active/
        // post_hypo_brake_armed_min, zie csvHeader() hieronder), afkomstig
        // uit de nieuwe, aparte post_hypo_brake_log-tabel (samengevoegd op
        // timestampMs, zie exportCsvLast7DaysInternal() hierboven). LET OP
        // (zelfde punt als bij v9->v10 hierboven): verifieer dat de nieuwe
        // build ook echt actief is, anders blijft het toestel op v10 schrijven.
        val file = File(dir, "FCLvNext_Log_v11.csv")

        val sep = ";"
        // 23/07/2026 — ts_utc blijft de bron van waarheid (ondubbelzinnig,
        // geen zomer/wintertijd-verwarring in de data zelf); ts_local is puur een
        // extra, leesbare kolom ernaast. Europe/Amsterdam schakelt automatisch
        // CET/CEST — geen handmatige +1/+2-aanpassing nodig.
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
        val fmtLocal = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneId.of("Europe/Amsterdam"))

        file.bufferedWriter().use { writer ->
            writer.write(csvHeader(sep))
            writer.newLine()
            rows.forEach { row ->
                val brake = brakeByTs[row.timestampMs]
                writer.write(row.toCsvLine(sep, fmt, fmtLocal, brake?.active ?: false, brake?.armedMinutes ?: -1))
                writer.newLine()
            }
        }
    }

    suspend fun getRecent(limit: Int) = dao.getRecent(limit)
    suspend fun getSince(fromMs: Long) = dao.getSince(fromMs)
    suspend fun getRowsInRange(fromMs: Long, toMs: Long) = dao.getInRange(fromMs, toMs)
    suspend fun count() = dao.count()
    suspend fun getAll() = dao.getAll()
}

// ── CSV header — exact gelijk aan FCLvNextCsvLogger ──────────────────────
// 05/07/2026: FCLCycleLogEntity is herstructureerd in @Embedded-
// groepen (zie doc-comment bij FCLCycleLogEntity.kt) — de kolomnamen in de
// CSV blijven ONGEWIJZIGD (Room "plat" de groepen terug uit tot dezelfde
// kolommen), alleen de Kotlin-veldtoegang hieronder gaat nu via de
// group-objecten, bijv. `row.slope` is nu `row.trends.slope`.

private fun csvHeader(sep: String): String = listOf(
    // ── META / TIJD ──
    "schema_version", "ts_utc", "ts_local", "code_version", "app_restart_this_cycle",
    // ── GLUCOSE / CONTEXT ──
    "bg_mmol", "target_mmol", "delta_target", "iob", "iob_ratio", "bg_zone", "is_night",
    "dose_access",
    // ── DOSIS-OVERZICHT (vooraan voor snel screenen) — pre-guards t/m reserve ──
    "desired_dose_pre_guards", "final_dose", "commanded_dose", "delivered_total", "bolus",
    "basal_u_h", "real_delivered_basal_u", "real_delivered_bolus_u", "profile_basal_u_h",
    "should_deliver", "external_bolus_u", "reserve_u", "reserve_action", "reserve_delta_u",
    "reserve_age_min",
    // ── TRENDS ──
    "slope", "accel", "recent_slope", "recent_delta5m", "consistency", "curve_fit_r2",
    "curve_acceleration", "topping_out_boost",
    // ── MAALTIJD-EPISODE IDENTITEIT ──
    "meal_episode_id", "minutes_since_meal_start", "rise_since_meal_start", "meal_state",
    "commit_fraction", "minutes_since_commit", "decision_reason",
    // ── PIEK-SCHATTER ──
    "peak_state", "predicted_peak", "peak_iob_boost", "effective_iob_ratio", "peak_max_slope",
    "peak_momentum", "peak_rise_since_start", "peak_episode_active", "suppress_for_peak",
    "absorption_active", "reentry_signal", "predicted_peak_ballistic", "future_drop_60",
    "peak_floor_active", "peak_floor_value", "h_eff", "iob_scale_used", "v_used",
    // ── WATCHING FRONTLOAD ──
    "watching_frontload_triggered", "watching_frontload_target_u", "watching_slope_ok",
    "watching_delta_ok", "watching_peak_rise_ok", "watching_iob_ok", "pred_margin_to_watching",
    // ── EARLY BOOST ──
    "early_stage", "early_confidence", "early_target_u", "sustained_high_slope_min",
    "early_boost_active", "early_boost_count", "early_boost_factor", "early_reset_this_cycle",
    // ── DOSERINGSBESLISSING (kern) — energiemodel t/m commit-uitkomst ──
    "normal_dose", "raw_dose", "iob_factor", "energy_base", "energy_total", "effective_isf",
    "gain", "commit_allowed", "effective_commit_allowed", "base_commit_fraction",
    "commit_zone_factor", "commit_iob_factor", "commit_postpeak_factor",
    "commit_raw_plateau_penalty", "commit_aggression_mul", "commit_dose_raw", "commit_dose_final",
    "late_decay_mul", "episode_commit_nr", "commit_nr_used", "episode_peak_commit_u",
    "bg_stijgt_nog_fors",
    // ── GUARDS & AFTERLOAD-REMMEN — alles wat de dosis ná de commit-beslissing nog kan afremmen ──
    "guard_iob_limited", "guard_peak_limited", "guard_maxsmb_limited", "guard_mindeliver_clipped",
    "guard_zone_limited", "afterload_fd60_scale", "afterload_high_iob_scale",
    "iob_overshoot_factor", "peak_approach_factor", "peak_approach_active",
    "peak_iob_brake_active", "topguard_active", "topguard_cap_factor", "top_plateau_confirmed",
    "burst_delivered_10m", "burst_cap_10m", "burst_remaining_10m", "iob_margin_to_brake",
    "iob_margin_to_lockout", "pred_margin_to_target", "slope_margin_to_brake", "suppress_reason",
    "lockout_reason", "commit_block_reason", "dose_suppressed_u", "downtrend_locked",
    "sensor_blip_active", "stagnation_active", "stagnation_boost",
    // ── HYPO / VEILIGHEID ──
    "hypo_active", "hypo_projected_bg", "hypo_debt_u", "post_hypo_brake_active",
    "post_hypo_brake_armed_min",
    // ── TRAJECTORY ──
    "trajectory_factor", "trajectory_hard_block",
    // ── RESCUE ──
    "pred60", "rescue_state", "rescue_confidence", "rescue_reason",
    // ── AGRESSIE-TUNING / STIJL ──
    "meal_aggression_a", "meal_aggression_mul", "meal_aggression_reason", "sterkte_pct",
    "timing_pct", "volhoudendheid_pct", "nacht_factor_pct", "doseDistributionStyle",
    "nightResponseStyle",
    // ── NACHT-SPECIFIEK ──
    "nf_level_geleerd", "nf_level_effectief", "nacht_aggressiviteit", "night_stagnation_delta_min",
    "night_stagnation_energy_boost", "night_persistent_aggression_mul", "night_cooldown_min",
    "night_correction_hold_delta_max", "night_absorption_dose_factor",
    "accel_decline_since_uncertain",
    // ── AIGF ──
    "aigf_pct", "aigf_active", "aigf_reason", "aigf_b_pct", "aigf_b_active", "aigf_b_reason",
    "aaps_multiplier",
    // ── ACTIVITEIT (stappen) ──
    "activity_active", "activity_insulin_pct", "activity_target_adjust",
    // ── DOSEERRUIMTE ──
    "iob_headroom"
).joinToString(sep)

// ── CSV regel — delta_target afgeleid als bg - target ────────────────────

private fun FCLCycleLogEntity.toCsvLine(
    sep: String,
    fmt: DateTimeFormatter,
    fmtLocal: DateTimeFormatter,
    postHypoBrakeActive: Boolean,
    postHypoBrakeArmedMinutes: Int
): String {
    val ts = fmt.format(Instant.ofEpochMilli(timestampMs))
    val tsLocal = fmtLocal.format(Instant.ofEpochMilli(timestampMs))
    val deltaTarget = glucoseIob.bg - glucoseIob.target

    fun d2(v: Double) = "%.2f".format(v)
    fun d3(v: Double) = "%.3f".format(v)
    fun bg1(v: Double) = "%.1f".format(v)
    fun bool(v: Boolean) = v.toString()

    return listOf(
        // ── META / TIJD ──
        schemaVersion, ts, tsLocal, context.codeVersion, bool(context.appRestartThisCycle),
        // ── GLUCOSE / CONTEXT ──
        bg1(glucoseIob.bg), bg1(glucoseIob.target), d2(deltaTarget), d2(glucoseIob.iob),
        d2(glucoseIob.iobRatio), glucoseIob.bgZone, bool(context.isNight), glucoseIob.doseAccess,
        // ── DOSIS-OVERZICHT (vooraan voor snel screenen) — pre-guards t/m reserve ──
        d2(model.desiredDosePreGuards), d2(delivery.finalDose), d2(delivery.commandedDose),
        d2(delivery.deliveredTotal), d2(delivery.bolus), d2(delivery.basalRate),
        d2(delivery.realDeliveredBasalU), d2(delivery.realDeliveredBolusU), d2(delivery.profileBasalUH),
        bool(delivery.shouldDeliver), d2(delivery.externalBolusU), d2(reserve.reserveU),
        reserve.reserveAction, d2(reserve.reserveDeltaU), reserve.reserveAgeMin,
        // ── TRENDS ──
        d2(trends.slope), d2(trends.accel), d2(trends.recentSlope), d2(trends.recentDelta5m),
        d2(trends.consistency), d3(trends.curveFitR2), d2(trends.curveAcceleration),
        d3(trends.toppingOutBoost),
        // ── MAALTIJD-EPISODE IDENTITEIT ──
        "fcl_intern", mealEpisode.minutesSinceMealStart, d2(mealEpisode.riseSinceMealStart),
        mealEpisode.mealState, d2(mealEpisode.commitFraction), mealEpisode.minutesSinceCommit,
        peak.decisionReason,
        // ── PIEK-SCHATTER ──
        peak.peakState, bg1(peak.predictedPeak), d2(peak.peakIobBoost), d2(peak.effectiveIobRatio),
        d2(peak.peakMaxSlope), d2(peak.peakMomentum), d2(peak.peakRiseSinceStart),
        bool(peak.peakEpisodeActive), bool(peak.suppressForPeak), bool(peak.absorptionActive),
        bool(peak.reentrySignal), bg1(peakInternals.predictedPeakBallistic), bg1(peakInternals.futureDrop60),
        bool(peakInternals.peakFloorActive), bg1(peakInternals.peakFloorValue), d2(peakInternals.hEff),
        d2(peakInternals.iobScaleUsed), d2(peakInternals.vUsed),
        // ── WATCHING FRONTLOAD ──
        bool(watching.watchingFrontloadTriggered), d2(watching.watchingFrontloadTargetU),
        bool(watching.watchingSlopeOk), bool(watching.watchingDeltaOk), bool(watching.watchingPeakRiseOk),
        bool(watching.watchingIobOk), d2(marges.predMarginToWatching),
        // ── EARLY BOOST ──
        mealEpisode.earlyStage, d2(mealEpisode.earlyConfidence), d2(mealEpisode.earlyTargetU),
        d2(mealEpisode.sustainedHighSlopeMinutes), bool(mealEpisode.earlyBoostActive),
        mealEpisode.earlyBoostCount, d2(mealEpisode.earlyBoostFactor),
        bool(doseerruimte.earlyResetThisCycle),
        // ── DOSERINGSBESLISSING (kern) — energiemodel t/m commit-uitkomst ──
        d2(model.normalDose), d2(model.rawDose), d2(model.iobFactor), d2(model.energyBase),
        d2(model.energyTotal), d2(model.effectiveISF), d2(model.gain), bool(forensic.commitAllowed),
        bool(forensic.effectiveCommitAllowed), d2(forensic.baseCommitFraction),
        d2(forensic.commitZoneFactor), d2(forensic.commitIobFactor), d2(forensic.commitPostPeakFactor),
        d2(forensic.commitRawPlateauPenalty), d2(forensic.commitAggressionMul), d2(forensic.commitDoseRaw),
        d2(forensic.commitDoseFinal), d2(forensic.lateDecayMul), forensic.episodeCommitNr,
        forensic.commitNrUsed, d2(forensic.episodePeakCommitU), bool(forensic.bgStijgtNogFors),
        // ── GUARDS & AFTERLOAD-REMMEN — alles wat de dosis ná de commit-beslissing nog kan afremmen ──
        bool(guards.guardIobLimited), bool(guards.guardPeakLimited), bool(guards.guardMaxSmbLimited),
        bool(guards.guardMinDeliverClipped), bool(guards.guardZoneLimited),
        d2(peakBenadering.afterloadFutureDrop60Scale), d2(peakBenadering.afterloadHighIobLateScale),
        d2(forensic.iobOvershootFactor), d2(peakBenadering.peakApproachFactor),
        bool(doseerruimte.peakApproachActive), bool(peakBenadering.peakIobBrakeActive),
        bool(topGuard.topGuardActive), d2(topGuard.topGuardCapFactor), bool(topGuard.topPlateauConfirmed),
        d2(burst.burstDelivered10m), d2(burst.burstCap10m), d2(burst.burstRemaining10m),
        d2(marges.iobMarginToBrake), d2(marges.iobMarginToLockout), d2(marges.predMarginToTarget),
        d2(marges.slopeMarginToBrake), suppress.suppressReason, suppress.lockoutReason,
        suppress.commitBlockReason, d2(doseerruimte.doseSuppressedU), bool(doseerruimte.downtrendLocked),
        bool(doseerruimte.sensorBlipActive), bool(stagnation.stagnationActive),
        d2(stagnation.stagnationBoost),
        // ── HYPO / VEILIGHEID ──
        bool(hypo.hypoActive), bg1(hypo.hypoProjectedBg), d2(hypo.hypoDebtU), bool(postHypoBrakeActive),
        postHypoBrakeArmedMinutes,
        // ── TRAJECTORY ──
        d2(forensic.trajectoryFactor), bool(forensic.trajectoryHardBlock),
        // ── RESCUE ──
        d2(rescue.pred60), rescue.rescueState, d2(rescue.rescueConfidence), rescue.rescueReason,
        // ── AGRESSIE-TUNING / STIJL ──
        d2(aggression.mealAggressionA), d2(aggression.mealAggressionMul),
        aggression.mealAggressionReason.replace(";", ","), context.sterktePct, context.timingPct,
        context.volhoudendheidPct, context.nachtFactorPct, context.doseDistributionStyle,
        context.nightResponseStyle,
        // ── NACHT-SPECIFIEK ──
        d2(delivery.nfLevelGeleerd), d2(delivery.nfLevelEffectief), delivery.nachtAggressiviteit,
        d3(delivery.nightStagnationDeltaMin), d3(delivery.nightStagnationEnergyBoost),
        d3(delivery.nightPersistentAggressionMul), delivery.nightCooldownMinutes,
        d2(delivery.nightCorrectionHoldDeltaMax), d3(delivery.nightAbsorptionDoseFactor),
        d3(delivery.accelDeclineSinceUncertain),
        // ── AIGF ──
        d2(delivery.aigfPct), bool(delivery.aigfActive), delivery.aigfReason, d2(delivery.aigfBPct),
        bool(delivery.aigfBActive), delivery.aigfBReason, d2(delivery.aapsMultiplier),
        // ── ACTIVITEIT (stappen) ──
        bool(delivery.activityActive), d2(delivery.activityInsulinPct), d2(delivery.activityTargetAdjust),
        // ── DOSEERRUIMTE ──
        d2(doseerruimte.iobHeadroom)
    ).joinToString(sep)
}