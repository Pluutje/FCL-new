package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database

import androidx.room.Entity
import androidx.room.PrimaryKey

// ⚠️ DB SCHEMA GEWIJZIGD: verhoog de versie in FCLAnalyzerDatabase.kt naar 4
// Toegevoegd: rescueAutoState, rescueAutoConfidence, rescueUserConfirmed,
//             rescueArmedIobRatio, rescueArmedSlope, rescueArmedBg, rescueArmedMinAfterPeak

@Entity(tableName = "episodes")
data class EpisodeEntity(

    @PrimaryKey
    val startTs: String,

    val endTs: String,
    val durationMinutes: Long,

    val peakBg: Double,
    val nadirBg: Double,

    val tirPercent: Double,

    val hyper: Boolean,
    val hypoEarly: Boolean,
    val hypoLate: Boolean,

    val earlyAxisDir: Int,
    val lateAxisDir: Int,

    val earlyConfidence: Double,
    val lateConfidence: Double,

    val meetsGoal: Boolean,

    // S/T/V
    val sterktePct: Int,
    val timingPct: Int,
    val volhoudendheidPct: Int,
    val doseDistribution: String,

    val totalInsulinDelivered: Double,
    val advisorWeight: Double,
    val adviceStatus: String,

    // ── Reddingskoolhydraten — detectiestatus ─────────────────────────────
    // Automatische detectie door FCLvNext: "NONE" | "ARMED" | "CONFIRMED"
    val rescueAutoState: String = "NONE",
    // Confidence van de automatische detectie (0.0-1.0)
    val rescueAutoConfidence: Double = 0.0,
    // Handmatige bevestiging door gebruiker: "UNSET" | "YES" | "NO"
    val rescueUserConfirmed: String = "UNSET",

    // ── Reddingskoolhydraten — ARM-context voor calibratie ────────────────
    // Opgeslagen op het moment dat de detector naar ARMED gaat.
    // Gebruikt om drempels te leren op basis van gebruikersbevestigingen.
    val rescueArmedIobRatio: Double = 0.0,       // IOB-ratio op ARM-moment (0.0-1.0)
    val rescueArmedSlope: Double = 0.0,           // slope op ARM-moment (mmol/5min, negatief = dalend)
    val rescueArmedBg: Double = 0.0,              // BG op ARM-moment (mmol/L)
    val rescueArmedMinAfterPeak: Int = 0,         // minuten na maaltijdpiek op ARM-moment

    // ── Maaltijdtype ─────────────────────────────────────────────────────
    val mealType: String = "GEMENGD",             // "SNEL" | "TRAAG" | "GEMENGD" | "ONBEKEND"
    val mealTypeSlope0_15: Double = 0.0,          // gemiddelde slope 0-15 min
    val mealTypeSlope15_30: Double = 0.0          // gemiddelde slope 15-30 min
)