package app.aaps.plugins.aps.openAPSFCL.vnext.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity die exact overeenkomt met FCLvNext_Log_v8.csv (135 kolommen).
 * Bewaartermijn: 90 dagen. Bewuste keuze om alle velden te bewaren zodat
 * de geïntegreerde FCL Analyzer volledig kan werken zonder informatieverlies.
 *
 * ────────────────────────────────────────────────────────────────────────
 * WAAROM DEZE STRUCTUUR — @Embedded-groepering (05/07/2026)
 * ────────────────────────────────────────────────────────────────────────
 * Deze entity had eerder ~150 velden als ÉÉN platte constructor. Dat
 * veroorzaakte een java.lang.VerifyError ("invalid arg count" in
 * invoke-direct/range) zodra er 3 velden bijkwamen: elke Double/Long-
 * parameter telt dubbel mee in het aantal "registers" dat de Dalvik/DEX
 * invoke-direct/range-instructie in ÉÉN aanroep kan verwerken, en bij
 * 150+ velden zat die aanroep al dicht tegen die grens aan. Dit trof niet
 * alleen het wegschrijven (FCLCycleLogMapperKt.toEntity), maar in principe
 * ook Room's EIGEN gegenereerde uitleescode (getAll/getRecent/getSince) —
 * die roept namelijk ook gewoon de primaire constructor aan met alle
 * velden, of ze nu `val` of `var` zijn. Een mutable-var-bouwpatroon lost
 * dus alleen het schrijfpad op, niet het leespad.
 *
 * DE FIX: velden zijn opgesplitst in kleine, logische groepen (elk een
 * @Embedded sub-data-class hieronder). Room "plat" deze bij het opslaan/
 * uitlezen automatisch terug uit tot dezelfde kolomnamen als voorheen —
 * de DAO-queries (allemaal `SELECT *`) hoeven dus NIET te wijzigen. Wat
 * wél verandert: in Kotlin-code wordt bijv. `row.slope` nu `row.trends.slope`.
 *
 * REGEL VOOR TOEKOMSTIGE UITBREIDINGEN: voeg een nieuw veld toe aan het
 * meest passende bestaande group-object hieronder (of maak een nieuwe
 * kleine group-class voor een nieuw onderwerp) — voeg NOOIT losse velden
 * rechtstreeks toe aan de top-level FCLCycleLogEntity-constructor. Zolang
 * elke groep klein blijft (< 30 velden), kan dit registerprobleem niet
 * meer terugkomen, ook niet over jaren van uitbreiding.
 *
 * Kolommen waren gegroepeerd conform de CSV header van FCLvNextCsvLogger — sinds
 * 2/9/2026 is de CSV-kolomvolgorde in FCLCycleLogRepository.kt (csvHeader()/
 * toCsvLine()) losgekoppeld en herschikt naar leesbare thema-groepen (o.a.
 * doseringsbeslissing en guards/afterload-remmen aaneengesloten), puur voor
 * menselijke/analyse-leesbaarheid. Deze @Embedded-volgorde (en dus de
 * Room-tabelkolomvolgorde) is BEWUST ongewijzigd gelaten — CSV-volgorde en
 * DB-kolomvolgorde hoeven niet meer 1-op-1 gelijk te lopen.
 *
 * v7→v8 (05/07/2026): +curveFitR2, +curveAcceleration, +toppingOutBoost
 * in TrendsFields (curve-fit confidence-gate, zie FCLvNextTrends.kt /
 * FCLvNext.kt). Versiebump 13→15 (NIET 14 — die versie is al eerder kortstondig
 * gedeclareerd bij de mislukte platte-constructor-poging; Room vergelijkt
 * alleen het versienummer, dus 14 hergebruiken zou de destructive-migratie
 * kunnen laten overslaan als het toestel al ergens op user_version=14 staat).
 * fallbackToDestructiveMigration() in FCLAnalyzerDatabase.kt dropt en
 * hermaakt alle tabellen — geen handmatige Migration nodig.
 *
 * v8→v9 (16/07/2026): +codeVersion, +appRestartThisCycle (in
 * ContextFields), +aigfPct/+aigfActive/+aigfReason (in DeliveryFields),
 * +episodePeakCommitU (in ForensicFields) — zie FCL_CODE_VERSION in
 * FCLvNext.kt voor de aanleiding. ANDERS DAN v7→v8/v13→16: dit is de eerste
 * ZUIVER ADDITIEVE schema-wijziging (alleen nieuwe kolommen, niets hernoemd/
 * verwijderd), dus FCLAnalyzerDatabase.kt gebruikt hiervoor een echte,
 * niet-destructieve Migration(16, 17) i.p.v. fallbackToDestructiveMigration —
 * zie de kdoc daar voor het risico (kon niet op een echt toestel getest
 * worden) en waarom fallbackToDestructiveMigration daarnaast als vangnet
 * blijft staan voor eventuele TOEKOMSTIGE, niet-additieve wijzigingen.
 */
@Entity(
    tableName = "fcl_cycle_log",
    indices = [Index("timestampMs")]
)
data class FCLCycleLogEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // ── META ──────────────────────────────────────────────────────────────
    val schemaVersion: String = "8",
    val timestampMs: Long,              // ts_utc als epochMillis

    @Embedded val context: ContextFields,
    @Embedded val glucoseIob: GlucoseIobFields,
    @Embedded val delivery: DeliveryFields,
    @Embedded val trends: TrendsFields,
    @Embedded val model: ModelFields,
    @Embedded val stagnation: StagnationFields,
    @Embedded val guards: GuardsFields,
    @Embedded val mealEpisode: MealEpisodeFields,
    @Embedded val peak: PeakFields,
    @Embedded val watching: WatchingFields,
    @Embedded val rescue: RescueFields,
    @Embedded val reserve: ReserveFields,
    @Embedded val forensic: ForensicFields,
    @Embedded val burst: BurstFields,
    @Embedded val hypo: HypoFields,
    @Embedded val topGuard: TopGuardFields,
    @Embedded val aggression: AggressionFields,
    @Embedded val peakBenadering: PeakBenaderingFields,
    @Embedded val suppress: SuppressFields,
    @Embedded val marges: MargesFields,
    @Embedded val peakInternals: PeakInternalsFields,
    @Embedded val doseerruimte: DoseerruimteFields
)

// ── CONTEXT ───────────────────────────────────────────────────────────────
data class ContextFields(
    val isNight: Boolean,
    val sterktePct: Int,
    val timingPct: Int,
    val volhoudendheidPct: Int,
    val nachtFactorPct: Int,
    val doseDistributionStyle: String,
    val nightResponseStyle: String,
    // v8→v9 (16/07/2026) — zie FCL_CODE_VERSION/isFirstCycleSinceInit
    // in FCLvNext.kt. Historische rijen van vóór deze migratie krijgen "" / false.
    val codeVersion: String = "",
    val appRestartThisCycle: Boolean = false
)

// ── GLUCOSE / IOB ─────────────────────────────────────────────────────────
data class GlucoseIobFields(
    val bg: Double,
    val target: Double,
    // delta_target is afgeleid (bg - target) — wordt berekend bij CSV export
    val iob: Double,
    val iobRatio: Double,
    val bgZone: String,
    val doseAccess: String
)

// ── DELIVERY / EXECUTION ──────────────────────────────────────────────────
data class DeliveryFields(
    val finalDose: Double,
    val commandedDose: Double,
    val deliveredTotal: Double,
    val bolus: Double,
    val basalRate: Double,
    val realDeliveredBasalU: Double = 0.0,
    val realDeliveredBolusU: Double = 0.0,
    val profileBasalUH: Double = 0.0,
    val activityActive: Boolean = false,
    val activityInsulinPct: Double = 100.0,
    val activityTargetAdjust: Double = 0.0,
    val aapsMultiplier: Double = 1.0,
    val nfLevelGeleerd: Double = 5.0,
    val nfLevelEffectief: Double = 5.0,
    val nachtAggressiviteit: Int = 5,
    val nightStagnationDeltaMin: Double = 0.0,
    val nightStagnationEnergyBoost: Double = 0.0,
    val nightPersistentAggressionMul: Double = 0.0,
    val nightCooldownMinutes: Int = 0,
    val nightCorrectionHoldDeltaMax: Double = 0.0,
    val nightAbsorptionDoseFactor: Double = 0.0,
    val accelDeclineSinceUncertain: Double = 0.0,
    val shouldDeliver: Boolean,
    // Externe insuline (handmatige bolus of AAPS SMB) gedetecteerd via IOB-delta.
    // Berekend als max(0, currentIOB - prevIOB + expectedDecay - fclOwnDose).
    // 0.0 als geen externe bolus gedetecteerd. Gebruikt door analyzer voor
    // correcte totalInsulinDelivered en hasManualCorrection markering.
    val externalBolusU: Double = 0.0,
    // v8→v9 (16/07/2026) — AIGF stond tot nu toe in geen enkele kolom,
    // ondanks actieve invloed op dosering. aigfPct is de daadwerkelijk
    // TOEGEPASTE (gladgestreken) waarde, zie aigfSmoothedPct in FCLvNext.kt.
    val aigfPct: Double = 100.0,
    val aigfActive: Boolean = false,
    val aigfReason: String = "",
    // v9->v10 (16/08/2026) — component B apart, zie kdoc bij
    // FCLvNextCsvLogRow.aigfBPct.
    val aigfBPct: Double = 100.0,
    val aigfBActive: Boolean = false,
    val aigfBReason: String = ""
)

// ── TRENDS ────────────────────────────────────────────────────────────────
data class TrendsFields(
    val slope: Double,
    val accel: Double,
    val recentSlope: Double,
    val recentDelta5m: Double,
    val consistency: Double,
    // Curve-fit lane (05/07/2026) — zie FCLvNextTrends.kt. curveFitR2 is
    // de fit-kwaliteit (0..1) van de parabool over de laatste ~45 min ruwe BG;
    // curveAcceleration is de acceleratie (mmol/L/uur²) uit diezelfde fit.
    // toppingOutBoost is de extra decay-steilheid (0..0.25) die is toegepast
    // op effectiveDecay wanneer een bevestigde, veilige afvlakking is gedetecteerd.
    val curveFitR2: Double = 0.0,
    val curveAcceleration: Double = 0.0,
    val toppingOutBoost: Double = 0.0
)

// ── MODEL ─────────────────────────────────────────────────────────────────
data class ModelFields(
    val effectiveISF: Double,
    val gain: Double,
    val energyBase: Double,
    val energyTotal: Double,
    val rawDose: Double,
    val iobFactor: Double,
    val normalDose: Double,
    val desiredDosePreGuards: Double
)

// ── STAGNATION ────────────────────────────────────────────────────────────
data class StagnationFields(
    val stagnationActive: Boolean,
    val stagnationBoost: Double
)

// ── GUARDS ────────────────────────────────────────────────────────────────
data class GuardsFields(
    val guardIobLimited: Boolean,
    val guardPeakLimited: Boolean,
    val guardMaxSmbLimited: Boolean,
    val guardMinDeliverClipped: Boolean,
    val guardZoneLimited: Boolean
)

// ── MEAL EPISODE ──────────────────────────────────────────────────────────
data class MealEpisodeFields(
    val mealEpisodeId: Long,
    val minutesSinceMealStart: Int,
    val riseSinceMealStart: Double,
    val earlyStage: Int,
    val earlyConfidence: Double,
    val earlyTargetU: Double,
    val sustainedHighSlopeMinutes: Double,
    val earlyBoostActive: Boolean,
    val earlyBoostCount: Int,
    val earlyBoostFactor: Double,
    val mealState: String,
    val commitFraction: Double,
    val minutesSinceCommit: Int
)

// ── PEAK / PREDICTION ─────────────────────────────────────────────────────
data class PeakFields(
    val peakState: String,
    val predictedPeak: Double,
    val peakIobBoost: Double,
    val effectiveIobRatio: Double,
    val peakMaxSlope: Double,
    val peakMomentum: Double,
    val peakRiseSinceStart: Double,
    val peakEpisodeActive: Boolean,
    val suppressForPeak: Boolean,
    val absorptionActive: Boolean,
    val reentrySignal: Boolean,
    val decisionReason: String
)

// ── WATCHING FRONTLOAD ────────────────────────────────────────────────────
data class WatchingFields(
    val watchingFrontloadTriggered: Boolean,
    val watchingFrontloadTargetU: Double,
    val watchingSlopeOk: Boolean,
    val watchingDeltaOk: Boolean,
    val watchingPeakRiseOk: Boolean,
    val watchingIobOk: Boolean
)

// ── RESCUE ────────────────────────────────────────────────────────────────
data class RescueFields(
    val pred60: Double,
    val rescueState: String,
    val rescueConfidence: Double,
    val rescueReason: String
)

// ── RESERVE ───────────────────────────────────────────────────────────────
data class ReserveFields(
    val reserveU: Double,
    val reserveAction: String,
    val reserveDeltaU: Double,
    val reserveAgeMin: Int
)

// ── FORENSIC / TRAJECTORY ─────────────────────────────────────────────────
data class ForensicFields(
    val trajectoryFactor: Double,
    val trajectoryHardBlock: Boolean,
    val commitAllowed: Boolean,
    val effectiveCommitAllowed: Boolean,
    val baseCommitFraction: Double,
    val commitZoneFactor: Double,
    val commitIobFactor: Double,
    val commitPostPeakFactor: Double,
    val commitRawPlateauPenalty: Double,
    val commitAggressionMul: Double,
    val commitDoseRaw: Double,
    val commitDoseFinal: Double,
    val lateDecayMul: Double,
    val episodeCommitNr: Int,
    val iobOvershootFactor: Double,
    // 11/07/2026 — puur diagnostisch, geen invloed op dosering. Zie
    // kdoc bij lastBgStijgtNogFors in FCLvNext.kt.
    val bgStijgtNogFors: Boolean = false,
    val commitNrUsed: Int = 0,
    // v8→v9 (16/07/2026) — de taper-clamp-ankerwaarde zelf, zie
    // PEAK_ANCHOR_THRESHOLD_FRAC in FCLvNext.kt. Tot nu toe alleen indirect
    // af te leiden uit commitDoseFinal/lateDecayMul.
    val episodePeakCommitU: Double = 0.0
)

// ── BURST CAP ─────────────────────────────────────────────────────────────
data class BurstFields(
    val burstDelivered10m: Double,
    val burstCap10m: Double,
    val burstRemaining10m: Double
)

// ── HYPO ──────────────────────────────────────────────────────────────────
data class HypoFields(
    val hypoActive: Boolean,
    val hypoProjectedBg: Double,
    val hypoDebtU: Double
)

// ── TOP GUARD ─────────────────────────────────────────────────────────────
data class TopGuardFields(
    val topGuardActive: Boolean,
    val topGuardCapFactor: Double,
    val topPlateauConfirmed: Boolean
)

// ── AGGRESSION ────────────────────────────────────────────────────────────
data class AggressionFields(
    val mealAggressionA: Double,
    val mealAggressionMul: Double,
    val mealAggressionReason: String = ""
)

// ── PEAK BENADERING ───────────────────────────────────────────────────────
data class PeakBenaderingFields(
    val peakIobBrakeActive: Boolean,
    val peakApproachFactor: Double,
    val afterloadFutureDrop60Scale: Double,
    val afterloadHighIobLateScale: Double
)

// ── SUPPRESS / LOCKOUT ────────────────────────────────────────────────────
data class SuppressFields(
    val suppressReason: String,
    val lockoutReason: String,
    val commitBlockReason: String
)

// ── MARGES TOT DREMPELS ───────────────────────────────────────────────────
data class MargesFields(
    val iobMarginToBrake: Double,
    val iobMarginToLockout: Double,
    val predMarginToWatching: Double,
    val predMarginToTarget: Double,
    val slopeMarginToBrake: Double
)

// ── PEAK INTERNALS ────────────────────────────────────────────────────────
data class PeakInternalsFields(
    val predictedPeakBallistic: Double,
    val futureDrop60: Double,
    val peakFloorActive: Boolean,
    val peakFloorValue: Double,
    val hEff: Double,
    val iobScaleUsed: Double,
    val vUsed: Double
)

// ── DOSEERRUIMTE CONTEXT ──────────────────────────────────────────────────
data class DoseerruimteFields(
    val iobHeadroom: Double,
    val doseSuppressedU: Double,
    val peakApproachActive: Boolean,
    val earlyResetThisCycle: Boolean,
    val downtrendLocked: Boolean,
    val sensorBlipActive: Boolean
)