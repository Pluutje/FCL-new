package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Post-hypo-brake-diagnostiek (26/08/2026) — eigen, kleine tabel i.p.v. een
 * uitbreiding van FCLCycleLogEntity.
 *
 * AANLEIDING: dezelfde 2 velden (postHypoBrakeActive/postHypoBrakeArmedMinutes)
 * rechtstreeks aan FCLCycleLogEntity toevoegen (v82, via
 * "ALTER TABLE fcl_cycle_log ADD COLUMN ...") veroorzaakte op het toestel een
 * reproduceerbare java.lang.VerifyError bij het opstarten van de app, op
 * FCLvNext.getAdvice() ("Rejecting invocation, expected 1 argument registers,
 * method signature has 2 or more"). Uitgebreid en gecontroleerd getest
 * (verse kopie, volledige pc-reboot, Invalidate Caches, Rebuild Project,
 * Generate APK) — bleef reproduceren, dus geen build-/cache-probleem.
 * Bevestigd via een directe A/B-test op het toestel zelf: dezelfde bestanden
 * zonder deze 2 kolommen draaien wél probleemloos.
 *
 * FCLCycleLogEntity werd al eerder (05/07/2026) herstructureerd naar
 * @Embedded-groepen om precies dit type crash (registerlimiet bij een te
 * grote platte constructor, destijds bij ~150 parameters) uit te sluiten —
 * zie de doc-comment bij FCLCycleLogEntity. Met inmiddels 167 velden in die
 * entity lijkt een vergelijkbare grens opnieuw geraakt te zijn, ondanks de
 * @Embedded-groepering. Om FCLCycleLogEntity niet verder te laten groeien,
 * staan deze 2 diagnostische velden daarom in deze eigen, kleine tabel —
 * hetzelfde bewezen patroon als isf_auto_adjust_log en
 * profile_auto_adjust_log (beide simpele CREATE TABLE-migraties, nooit een
 * crash veroorzaakt).
 *
 * Bij CSV-export (zie FCLCycleLogRepository.exportCsvLast7DaysInternal())
 * worden beide tabellen samengevoegd op timestampMs tot één regel, zodat de
 * gebruiker met precies één CSV-bestand blijft werken. fcl_cycle_log zelf
 * blijft in deze en toekomstige migraties onaangeroerd.
 */
@Entity(
    tableName = "post_hypo_brake_log",
    indices = [
        Index(value = ["timestampMs"])
    ]
)
data class PostHypoBrakeLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val timestampMs: Long,
    val active: Boolean,
    val armedMinutes: Int
)
