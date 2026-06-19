package app.aaps.plugins.aps.openAPSFCL.vnext.logging

import app.aaps.core.keys.*
import app.aaps.core.keys.interfaces.Preferences

/**
 * Bevat ALLE parameters die via de UI instelbaar zijn.
 * Doel:
 * - Eén CSV voor backup / restore bij herinstallatie
 * - Geen vaste constants
 * - Geen learning-interne parameters
 */
object FCLvNextProfileParameterSnapshot {

    fun collect(preferences: Preferences): Map<String, Any> = mapOf(

        // =================================================
        // ⚡ Gedrag — S / T / V / N
        // Vervangt de oude gain_day / gain_night / 5 enum-assen
        // =================================================
        "sterkte_pct"        to preferences.get(IntKey.fcl_vnext_sterkte),
        "timing_pct"         to preferences.get(IntKey.fcl_vnext_timing),
        "volhoudendheid_pct" to preferences.get(IntKey.fcl_vnext_volhoudendheid),
        "nf_level"           to preferences.get(app.aaps.core.keys.DoubleKey.fcl_vnext_nf_level),
        "max_bolus_dag"      to preferences.get(DoubleKey.max_bolus_day),
        "max_bolus_nacht"    to preferences.get(DoubleKey.max_bolus_night),
        "max_iob"            to preferences.get(DoubleKey.fcl_vnext_MaxIOB),

        // =================================================
        // 💉 Distributie & nacht respons
        // =================================================
        "dose_distribution"  to preferences.get(StringKey.fcl_vnext_dose_distribution_style),
        // night_response verwijderd (18/06/2026) — nu afgeleid van nf_level,

        // =================================================
        // 🌙 Dag / nacht & tijd
        // =================================================
        "ochtend_start"         to preferences.get(StringKey.OchtendStart),
        "ochtend_start_weekend" to preferences.get(StringKey.OchtendStartWeekend),
        "nacht_start"           to preferences.get(StringKey.NachtStart),
        "weekend_dagen"         to preferences.get(StringKey.WeekendDagen),
    )
}
