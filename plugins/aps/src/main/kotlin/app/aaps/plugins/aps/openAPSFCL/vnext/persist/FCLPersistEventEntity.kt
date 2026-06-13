package app.aaps.plugins.aps.openAPSFCL.vnext.persist

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Eén regel per cyclus waarin PersistentCorrectionController.tickAndMaybeFire()
 * `active == true` retourneert — dus elke cyclus binnen een persistent-cluster,
 * zowel de cycli waarin daadwerkelijk gevuurd werd (fired=true) als de
 * cooldown-cycli ertussen (fired=false).
 *
 * Losstaande tabel/database t.o.v. fcl_analyzer.db: een toekomstige schema-
 * wijziging hier mag de hoofd-CSV-data (7 dagen cyclus-log) niet wegvallen.
 *
 * De V-learner leest deze tabel, groepeert opeenvolgende rijen (geen gat
 * groter dan cooldownCycles+1 cycli) tot clusters, en evalueert per cluster
 * het FORWARD/BACK/NONE-signaal op basis van de slope 10 minuten na elke fire.
 */
@Entity(tableName = "fcl_persist_event")
data class FCLPersistEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val timestampMs: Long,

    // ── Toestand op het moment van deze cyclus ─────────────────────────
    val bgMmol: Double,
    val targetMmol: Double,
    val deltaToTarget: Double,
    val slope: Double,
    val iobRatio: Double,

    // ── Resultaat van tickAndMaybeFire() ────────────────────────────────
    val fired: Boolean,
    val doseU: Double,
    val cooldownLeft: Int,
    val persistentCounter: Int,
    val escalationFactor: Double,

    // ── Effectieve detectieparameters op dit moment (voor V-learner) ───
    // Nodig om de respons-drempel (-stableSlopeAbs * responseFactor en -0.60)
    // te herberekenen zonder afhankelijk te zijn van de actuele config.
    val effectiveMinDelta: Double,
    val stableSlopeAbs: Double,

    // ── Huidige geleerde V-waarde op moment van deze cyclus ─────────────
    // (voor traceability: welke vExtra was actief toen dit gevuurd werd)
    val vExtraAtFire: Double
)
