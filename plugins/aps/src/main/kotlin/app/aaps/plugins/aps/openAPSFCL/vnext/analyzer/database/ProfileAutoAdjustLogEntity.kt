package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Logboek van elke run van FclNightBasalAutoAdjuster (24/07/2026) —
 * zowel niet-toegepaste voorstellen (MANUAL) als daadwerkelijk toegepaste
 * automatische profielwijzigingen (AUTO). Eén rij per nacht-run die de
 * auto-adjuster daadwerkelijk heeft doorlopen (dus niet als modus UIT staat,
 * of als er die run geen bruikbare AI-suggesties waren).
 *
 * [oldBasalJson]/[newBasalJson] zijn volledige snapshots (JSON, {"0": 0.96, ...})
 * — dit is wat de "ongedaan maken"-knop in de UI teruglegt via
 * ProfileRepository.replace()/ProfileFunction.createProfileSwitch(), en wat het
 * vergelijkingstabblad toont.
 *
 * DAG/NACHT-HERSTRUCTURERING (26/07/2026): [mode] bevat sinds dan de
 * naam van de gedeelde FclSystemMode ("OFF"/"AUTO"/"MANUAL", zie
 * FclNightBasalAutoAdjustStore.getMode()) i.p.v. de oude eigen
 * "DRY_RUN"/"AUTO"-enum. De MEEST RECENTE rij met mode=="MANUAL" &&
 * applied==false && skipReason=="" is het openstaande voorstel — zie
 * FclNightBasalAutoAdjuster.applyPending()/rejectPending() en de
 * Accepteren/Afwijzen-knoppen in Advisorscreen.kt.
 */
@Entity(
    tableName = "profile_auto_adjust_log",
    indices = [
        Index(value = ["localDate"]),
        Index(value = ["timestampMs"])
    ]
)
data class ProfileAutoAdjustLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val timestampMs: Long,
    val localDate: String,       // "2026-07-24" (Europe/Amsterdam) — voor de dagelijkse dedup-check
    val mode: String,            // "DRY_RUN" | "AUTO" (FclNightBasalAutoAdjustStore.Mode.name)
    val applied: Boolean,        // true = daadwerkelijk naar de pomp geactiveerd; false = alleen berekend/gelogd
    val skipReason: String,      // leeg als applied/gewoon gelogd; anders reden (validatie, confidence, etc.)

    val oldBasalJson: String,    // {"0": 0.96, "1": 1.02, ...} — huidig profiel vóór deze run
    val newBasalJson: String,    // {"0": 0.92, "1": 0.98, ...} — berekend nieuw profiel (met caps toegepast)
    val perHourShiftJson: String, // ruwe AI-suggesties die als invoer dienden, {"0": -4.0, ...}

    val hoursAtCapCount: Int,    // aantal uren deze run tegen de ±25%-drift-cap aangelopen
    val nightsAnalyzed: Int,     // uit FclNightReportPayload, voor de confidence-gate
    val avgConfidence: Double    // gemiddelde van suggestion.confidence over de aangeraakte uren
)
