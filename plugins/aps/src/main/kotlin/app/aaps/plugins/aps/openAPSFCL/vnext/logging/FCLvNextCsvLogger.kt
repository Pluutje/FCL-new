package app.aaps.plugins.aps.openAPSFCL.vnext.logging

import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import java.io.File
import java.util.Locale

data class FCLvNextCsvLogRow(

    // ── Context ──
    var ts: DateTime = DateTime.now(),
    var isNight: Boolean = false,
    var bg: Double = 0.0,
    var target: Double = 0.0,

    // ── S/T/V/N — vervangt de 5 enum-stijl kolommen ──
    var sterktePct: Int = 100,
    var timingPct: Int = 100,
    var volhoudendheidPct: Int = 100,
    var nachtFactorPct: Int = 85,

    // ── Log-only stijl labels (voor achterwaartse leesbaarbaarheid) ──
    var doseDistributionStyle: String = "",
    var nightResponseStyle: String = "",

    // ── Trends ──
    var slope: Double = 0.0,
    var accel: Double = 0.0,
    var recentSlope: Double = 0.0,
    var recentDelta5m: Double = 0.0,
    var consistency: Double = 0.0,
    // Curve-fit lane (04/07/2026) — zie FCLvNextTrends.kt. curveFitR2 is
    // de fit-kwaliteit (0..1) van de parabool over de laatste ~45 min ruwe BG;
    // curveAcceleration is de acceleratie (mmol/L/uur²) uit diezelfde fit.
    var curveFitR2: Double = 0.0,
    var curveAcceleration: Double = 0.0,

    // ── IOB ──
    var iob: Double = 0.0,
    var iobRatio: Double = 0.0,
    var bgZone: String = "",
    var doseAccess: String = "",

    // ── Model ──
    var effectiveISF: Double = 0.0,
    var gain: Double = 0.0,
    var energyBase: Double = 0.0,
    var energyTotal: Double = 0.0,

    // ── Meal episode ──
    var mealEpisodeId: Long = -1,
    var minutesSinceMealStart: Int = -1,
    var riseSinceMealStart: Double = 0.0,

    // ── Stagnation ──
    var stagnationActive: Boolean = false,
    var stagnationBoost: Double = 0.0,

    // ── Dose math ──
    var rawDose: Double = 0.0,
    var iobFactor: Double = 0.0,
    var normalDose: Double = 0.0,
    var desiredDosePreGuards: Double = 0.0,

    // ── Guards ──
    var guardIobLimited: Boolean = false,
    var guardPeakLimited: Boolean = false,
    var guardMaxSmbLimited: Boolean = false,
    var guardMinDeliverClipped: Boolean = false,
    var guardZoneLimited: Boolean = false,

    // ── Early ──
    var earlyStage: Int = 0,
    var earlyConfidence: Double = 0.0,
    var earlyTargetU: Double = 0.0,
    var sustainedHighSlopeMinutes: Double = 0.0,
    var earlyBoostActive: Boolean = false,   // was boost actief in deze cyclus?
    var earlyBoostCount: Int = 0,            // aantal boost-commits tot nu toe in episode
    var earlyBoostFactor: Double = 1.0,      // effectieve boost-factor die gebruikt is

    // ── Decision / phase ──
    var mealState: String = "",
    var commitFraction: Double = 0.0,
    var minutesSinceCommit: Int = -1,

    // ── Peak ──
    var peakState: String = "",
    var predictedPeak: Double = 0.0,
    var peakIobBoost: Double = 1.0,
    var effectiveIobRatio: Double = 0.0,
    var peakMaxSlope: Double = 0.0,
    var peakMomentum: Double = 0.0,
    var peakRiseSinceStart: Double = 0.0,
    var peakEpisodeActive: Boolean = false,
    var suppressForPeak: Boolean = false,
    var absorptionActive: Boolean = false,
    var reentrySignal: Boolean = false,
    var decisionReason: String = "",

    // ── Watching frontload ──
    var watchingFrontloadTriggered: Boolean = false,
    var watchingFrontloadTargetU: Double = 0.0,
    var watchingSlopeOk: Boolean = false,
    var watchingDeltaOk: Boolean = false,
    var watchingPeakRiseOk: Boolean = false,
    var watchingIobOk: Boolean = false,

    // ── Rescue ──
    var pred60: Double = 0.0,
    var rescueState: String = "",
    var rescueConfidence: Double = 0.0,
    var rescueReason: String = "",

    // ── Pre-bolus ──

    // ── Execution ──
    var finalDose: Double = 0.0,
    var commandedDose: Double = 0.0,
    var deliveredTotal: Double = 0.0,
    var bolus: Double = 0.0,
    var basalRate: Double = 0.0,
    // Werkelijk afgegeven insuline sinds de vorige cyclus, uit AAPS'
    // behandelhistorie (FclRealDoseTracker) — i.t.t. deliveredTotal/bolus/
    // basalRate hierboven, die alleen FCL's EIGEN bijdrage zijn.
    var realDeliveredBasalU: Double = 0.0,
    var realDeliveredBolusU: Double = 0.0,
    // Geprogrammeerde profiel-basaalstand (U/h) — referentie om
    // realDeliveredBasalU tegen af te zetten.
    var profileBasalUH: Double = 0.0,
    // Activiteit (stappen) — beïnvloedt sensMgdl/targetMgdl elke cyclus.
    var activityActive: Boolean = false,
    var activityInsulinPct: Double = 100.0,
    var activityTargetAdjust: Double = 0.0,
    var aapsMultiplier: Double = 1.0,
    // NF geleerd vs effectief (incl. handmatige Nacht-Agressiviteit-offset),
    // en de 6 sub-parameters die NF 's nachts afleidt — zie
    // FCLvNextConfig.applyNightResponseStyle(). Overdag staan de
    // sub-parameters op hun ongemoeide basiswaarde.
    var nfLevelGeleerd: Double = 5.0,
    var nfLevelEffectief: Double = 5.0,
    var nachtAggressiviteit: Int = 5,
    var nightStagnationDeltaMin: Double = 0.0,
    var nightStagnationEnergyBoost: Double = 0.0,
    var nightPersistentAggressionMul: Double = 0.0,
    var nightCooldownMinutes: Int = 0,
    var nightCorrectionHoldDeltaMax: Double = 0.0,
    var nightAbsorptionDoseFactor: Double = 0.0,
    // Acceleratie-afname t.o.v. ~18 min geleden — positief = decelererend
    // (stijging topt uit), 0/negatief = aanhoudend/versnellend. Zie kdoc bij
    // FCLvNext.updateAccelHistoryAndGetDecline().
    var accelDeclineSinceUncertain: Double = 0.0,
    var shouldDeliver: Boolean = false,
    var externalBolusU: Double = 0.0,

    // ── Reserve ──
    var reserveU: Double = 0.0,
    var reserveAction: String = "NONE",
    var reserveDeltaU: Double = 0.0,
    var reserveAgeMin: Int = -1,

    // ── Trajectory ──
    var trajectoryFactor: Double = 1.0,
    var trajectoryHardBlock: Boolean = false,

    // ── Commit causality ──
    var commitAllowed: Boolean = false,
    var effectiveCommitAllowed: Boolean = false,
    var baseCommitFraction: Double = 0.0,
    var commitZoneFactor: Double = 0.0,
    var commitIobFactor: Double = 0.0,
    var commitPostPeakFactor: Double = 0.0,
    var commitRawPlateauPenalty: Double = 0.0,
    var commitAggressionMul: Double = 0.0,
    var commitDoseRaw: Double = 0.0,
    var commitDoseFinal: Double = 0.0,
    var lateDecayMul: Double = 1.0,
    var episodeCommitNr: Int = 0,
    // 11/07/2026 — puur diagnostisch, geen invloed op dosering. Zie kdoc
    // bij lastBgStijgtNogFors in FCLvNext.kt — legt vast of het vluchtventiel
    // voor de late-commit-afbouw deze cyclus actief was, en met welke commitNr
    // die beslissing werd genomen (los van eventuele afronding elders).
    var bgStijgtNogFors: Boolean = false,
    var commitNrUsed: Int = 0,
    // Extra decay-steilheid door bevestigde "topping out" (04/07/2026) —
    // 0.0 zolang de curve-fit dit niet bevestigt, dus geen effect op bestaand
    // gedrag tenzij expliciet aangetoond dat de piek ruim onder 10 mmol blijft.
    var toppingOutBoost: Double = 0.0,

    // ── IOB overshoot ──
    var iobOvershootFactor: Double = 1.0,

    // ── Burst cap ──
    var burstDelivered10m: Double = 0.0,
    var burstCap10m: Double = 0.0,
    var burstRemaining10m: Double = 0.0,

    // ── Hypo ──
    var hypoActive: Boolean = false,
    var hypoProjectedBg: Double = 0.0,
    var hypoDebtU: Double = 0.0,          // opgebouwde schuld door hypo-rem in huidige episode

    // ── Top guard ──
    var topGuardActive: Boolean = false,
    var topGuardCapFactor: Double = 1.0,
    var topPlateauConfirmed: Boolean = false,

    // ── Aggression ──
    var mealAggressionA: Double = 0.0,
    var mealAggressionMul: Double = 0.0,
    var mealAggressionReason: String = "",

    // ── Peak benadering ──
    var peakIobBrakeActive: Boolean = false,
    var peakApproachFactor: Double = 1.0,
    var afterloadFutureDrop60Scale: Double = 1.0,
    var afterloadHighIobLateScale: Double = 1.0,

    // ── Suppress/lockout reden ──
    var suppressReason: String = "NONE",
    var lockoutReason: String = "NONE",
    var commitBlockReason: String = "NONE",

    // ── Marges tot drempels ──
    var iobMarginToBrake: Double = 0.0,
    var iobMarginToLockout: Double = 0.0,
    var predMarginToWatching: Double = 0.0,
    var predMarginToTarget: Double = 0.0,
    var slopeMarginToBrake: Double = 0.0,

    // ── Peak internals ──
    var predictedPeakBallistic: Double = 0.0,
    var futureDrop60: Double = 0.0,
    var peakFloorActive: Boolean = false,
    var peakFloorValue: Double = 0.0,
    var hEff: Double = 0.0,
    var iobScaleUsed: Double = 0.0,
    var vUsed: Double = 0.0,

    // ── Doseerruimte context ──
    var iobHeadroom: Double = 0.0,
    var doseSuppressedU: Double = 0.0,
    var peakApproachActive: Boolean = false,
    var earlyResetThisCycle: Boolean = false,
    var downtrendLocked: Boolean = false,
    var sensorBlipActive: Boolean = false
    // 03/08/2026: een CSV-kolom voor de nieuwe auto-disarm (post-hypo-
    // brake) is BEWUST NIET toegevoegd — dit veld is al 155 constructor-
    // parameters groot (allemaal met default-waarde), en het 156e veld
    // veroorzaakte een VerifyError bij opstarten (invoke-direct/range over de
    // DEX-verifier-grens, zelfde bugklasse als ooit bij FCLCycleLogEntity, zie
    // de kdoc daar over @Embedded-groepering). Zichtbaar in de logs voorlopig
    // alleen via de vrije-tekst status-regel "POST-HYPO BRAKE AUTO-DISARM: ...".
    // Een eigen CSV-kolom vereist eerst dezelfde @Embedded-herstructurering
    // hier als bij FCLCycleLogEntity — apart, zorgvuldig doorgerekend traject,
    // geen los-eind-toevoeging.
) {
    // ── Diagnose-uitbreiding (16/07/2026) — v8→v9 schema-bump ────────
    // BEWUST BUITEN de primaire constructor (i.t.t. alle velden hierboven):
    // FCLvNextCsvLogRow had al ~70 constructor-parameters. Kotlin genereert
    // voor een constructor met default-waarden één grote "$default"-bridge-
    // methode die ALTIJD alle parameters + bitmasks + marker-object meekrijgt,
    // ongeacht hoeveel er bij de aanroep expliciet worden meegegeven — en die
    // bridge wordt aangeroepen in FCLvNext.getAdvice(). Deze 6 velden ALS
    // constructor-parameters duwden die aanroep over een DEX-verifier-
    // registerlimiet (java.lang.VerifyError: "Rejecting invocation, expected
    // 6 argument registers, method signature has 8 or more" — exact dezelfde
    // bugklasse als eerder gedocumenteerd bij FCLCycleLogEntity.kt, "invalid
    // arg count" in invoke-direct/range). Als gewone class-body-var's tellen
    // ze NIET mee in die bridge-aanroep — precies zoals de meeste velden
    // hierboven toch al ná constructie via `logRow.veld = waarde` worden
    // gezet, niet via de constructor. GEVOLG: deze 6 velden mogen NOOIT terug
    // de constructor-parameterlijst in — nieuwe velden hier altijd op
    // dezelfde manier (class body) toevoegen.
    //
    // codeVersion: welke FCLvNext.kt-versie (FCL_CODE_VERSION) deze rij heeft
    // geproduceerd — ANDERS dan het schemaVersion-veld in de Room-laag, dat
    // volgt de KOLOM-lay-out, dit veld de DOSIS-LOGICA.
    var codeVersion: String = ""
    // appRestartThisCycle: true op precies de eerste cyclus na het
    // (her)starten van het app-proces — zie isFirstCycleSinceInit in
    // FCLvNext.kt. Aanleiding: 14/07 20:07- en 15/07 19:42-incidenten.
    var appRestartThisCycle: Boolean = false
    // AIGF (14/07/2026) — stond tot nu toe in GEEN kolom, ondanks actieve
    // invloed op dosering (aigfCommitBoost/aigfAfterloadScale in FCLvNext.kt).
    // aigfPct is de daadwerkelijk TOEGEPASTE (gladgestreken) waarde.
    var aigfPct: Double = 100.0
    var aigfActive: Boolean = false
    var aigfReason: String = ""
    // 16/08/2026 — component B apart loggen, i.p.v. alleen impliciet
    // via aigf_pct (dat is uitsluitend component A). Nodig om de HERONTWERP
    // van component B (dosis-drempel-freeze -> live, elke cyclus) te kunnen
    // valideren tegen toekomstige data — zie kdoc bij lastSmoothedAigfBPct
    // in FCLvNext.kt.
    var aigfBPct: Double = 100.0
    var aigfBActive: Boolean = false
    var aigfBReason: String = ""
    // episodePeakCommitU: de taper-clamp-ankerwaarde zelf (zie
    // PEAK_ANCHOR_THRESHOLD_FRAC in FCLvNext.kt) — tot nu toe alleen indirect
    // af te leiden uit commit_dose_final/late_decay_mul.
    var episodePeakCommitU: Double = 0.0
}