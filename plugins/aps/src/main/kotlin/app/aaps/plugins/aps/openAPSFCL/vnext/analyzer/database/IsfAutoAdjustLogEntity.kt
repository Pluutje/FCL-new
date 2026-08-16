package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Logboek van elke run van FclIsfAutoAdjuster (16/08/2026) — de ISF-
 * tegenhanger van ProfileAutoAdjustLogEntity (die FclNightBasalAutoAdjuster
 * bedient). Zelfde patroon, bewust 1-op-1 gespiegeld: zowel niet-toegepaste
 * voorstellen (MANUAL) als daadwerkelijk toegepaste automatische
 * ISF-profielwijzigingen (AUTO) komen hier één rij per run in te staan.
 *
 * [oldIsfJson]/[newIsfJson] zijn volledige uur-snapshots (JSON,
 * {"0": 3.8, "1": 3.8, ...}, mmol/L per U) — dit is wat een toekomstige
 * "ongedaan maken"-actie zou teruglezen, en wat een vergelijkingstabblad
 * kan tonen (zelfde rol als oldBasalJson/newBasalJson daar).
 *
 * [mode] bevat de naam van de gedeelde FclSystemMode ("OFF"/"AUTO"/"MANUAL",
 * zie FclIsfAutoAdjustStore.getMode()). De MEEST RECENTE rij met
 * mode=="MANUAL" && applied==false && skipReason=="" is het openstaande
 * voorstel — zie FclIsfAutoAdjuster.computeCurrentProposal()/applyPending().
 *
 * ANDERS DAN de nacht-basaal-variant: de invoer komt (nog) niet van een AI-
 * adviseur maar van IsfLearner, een deterministieke berekening op basis van
 * PersistentCorrectionController-fires (fcl_persist_event, dezelfde tabel
 * die VLearner al gebruikt). [avgConfidence]/[samplesAnalyzed] hebben
 * daarom een iets andere betekenis dan bij de nacht-basaal-log: geen
 * AI-zekerheid, maar een datagedreven maat (spreiding × aantal schone
 * correctie-metingen) — zie kdoc bij IsfLearner.HourSuggestion. De
 * kolomnamen zijn bewust generiek gehouden zodat een latere AI-verrijking
 * (net als bij de nacht-basaal, zie FclNightAiAdvisorScheduler) zonder
 * schema-wijziging in dezelfde tabel kan landen.
 */
@Entity(
    tableName = "isf_auto_adjust_log",
    indices = [
        Index(value = ["localDate"]),
        Index(value = ["timestampMs"])
    ]
)
data class IsfAutoAdjustLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val timestampMs: Long,
    val localDate: String,       // "2026-08-16" (Europe/Amsterdam) — voor de dagelijkse dedup-check
    val mode: String,            // "OFF" | "AUTO" | "MANUAL" (FclIsfAutoAdjustStore.getMode().name)
    val applied: Boolean,        // true = daadwerkelijk in het AAPS-profiel geschreven; false = alleen berekend/gelogd
    val skipReason: String,      // leeg als applied/gewoon gelogd; anders reden (validatie, confidence-gate, etc.)

    val oldIsfJson: String,      // {"0": 3.8, "1": 3.8, ...} — huidig profiel (mmol/L per U) vóór deze run
    val newIsfJson: String,      // {"0": 3.5, "1": 3.8, ...} — berekend nieuw profiel (met caps toegepast)
    val perHourShiftJson: String, // ruwe IsfLearner-suggesties die als invoer dienden, {"0": -8.0, ...} (%)

    val hoursAtCapCount: Int,    // aantal uren deze run tegen de drift-cap aangelopen
    val samplesAnalyzed: Int,    // totaal aantal schone correctie-metingen over alle aangeraakte uren samen
    val avgConfidence: Double    // gemiddelde van suggestion.confidence over de aangeraakte uren
)
