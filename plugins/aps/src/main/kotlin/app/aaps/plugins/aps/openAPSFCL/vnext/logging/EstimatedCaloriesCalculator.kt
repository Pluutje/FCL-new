package app.aaps.plugins.aps.openAPSFCL.vnext.logging

import app.aaps.core.interfaces.db.PersistenceLayer

/**
 * EstimatedCaloriesCalculator (06/07/2026, Ecko) — vervangt de eerdere
 * HealthConnect-route (HealthConnectCaloriesIngest.kt/
 * HealthConnectCaloriesPermissionActivity.kt, verwijderd).
 *
 * AANLEIDING: Health Connect bleek voor dit toestel een doodlopende weg — de
 * OnePlus Watch synct via de fabrikants-app ("O Health"), niet via Google
 * Health Connect, en stappen/hartslag komen bij AAPS al binnen via een heel
 * ANDER, allang werkend pad: AAPS' eigen Wear OS-app (StepCountListener.kt/
 * HeartRateListener.kt, package app.aaps.wear) leest de kale
 * Sensor.TYPE_STEP_COUNTER/TYPE_HEART_RATE rechtstreeks op het horloge uit en
 * stuurt ze via IntentWearToMobile naar de telefoon, waar ze in
 * PersistenceLayer belanden (getStepsCountFromTime / getHeartRatesFromTime).
 *
 * Bovendien bestaat er in Android's kale sensor-API sowieso geen "calorieën"-
 * sensortype (in tegenstelling tot stappen/hartslag) — calorieën zijn ALTIJD
 * een afgeleide berekening, ook bij Health Connect/Google Fit zelf. In plaats
 * van een afgeleide waarde bij een derde partij op te vragen die er op dit
 * toestel toch nooit komt, berekenen we die schatting nu zelf, rechtstreeks
 * uit de stappen- en hartslagdata die al binnenkomt.
 *
 * METHODE — bewust eenvoudig gehouden voor een shadow/onderzoekssignaal, geen
 * medisch-precieze calorimetrie:
 *   - Is er hartslagdata in het venster? → MET-schatting op basis van
 *     gemiddelde hartslag (grove, niet-gepersonaliseerde HR→MET-vuisttabel,
 *     dus GEEN rust-HR/max-HR/leeftijd nodig) × standaard lichaamsgewicht ×
 *     duur. kcal = MET × gewicht(kg) × uren.
 *   - Geen hartslagdata in het venster (horloge niet gedragen, geen signaal)?
 *     → terugvallen op een vaste kcal-per-stap-vuistregel.
 *
 * AANNAMES DIE BEWUST ZICHTBAAR ZIJN GEHOUDEN (pas gerust aan):
 *   DEFAULT_WEIGHT_KG en de HR→MET-tabel zijn generieke vuistwaarden, niet aan
 *   jouw profiel gekoppeld — FCLvNext heeft nergens een lichaamsgewicht-
 *   instelling. Voor een eerste correlatie-onderzoek (relatieve verschillen
 *   tussen actieve/inactieve periodes) is dat prima; voor absolute nauwkeurigheid
 *   zou dit gekoppeld moeten worden aan een echt profielgewicht.
 *
 * LET OP — nog te verifiëren: HR.beatsPerMinute is overgenomen naar analogie
 * van EventData.ActionHeartRate.beatsPerMinute (HeartRateListener.kt); ik heb
 * de exacte HR-entiteit (PersistenceLayer.kt) zelf niet gezien. Geeft de
 * compiler hier een "Unresolved reference" op deze ene regel, dan is dat het
 * enige wat moet worden aangepast aan de echte veldnaam.
 */
object EstimatedCaloriesCalculator {

    private const val DEFAULT_WEIGHT_KG = 75.0
    private const val KCAL_PER_STEP = 0.045   // grove vuistregel, gebruikt als ondergrens/aanvulling

    // ── Bugfix 06/07/2026 (Ecko) ──────────────────────────────────────────
    // PROBLEEM: bij een rustige hartslag (bijv. 63 bpm, gewoon aanwezig omdat
    // het horloge gedragen wordt) gaf de oude tabel al 1.5 MET — een
    // ABSOLUTE waarde die, keer gewicht keer een heel uur, alsnog ~112 kcal
    // opleverde. Omdat de HR-tak de stappen-tak volledig verdrong (else-pad),
    // gaf zowel een uur met 1.400 stappen als een vrijwel stilzittend uur
    // bijna hetzelfde getal — de rust-MET domineerde alles.
    //
    // OPLOSSING: de tabel drukt nu EXCESS-MET uit (boven een lichte rust-
    // baseline van ~1,2 MET, afgerond eraf getrokken), dus rust ≈ 0,3 i.p.v.
    // 1,5. En in plaats van "HR ÓF stappen" wordt nu altijd het MAXIMUM van
    // beide schattingen genomen — zo kan een stappenpiek zonder sterke HR-
    // reactie (rustig wandelen) nooit meer wegvallen tegen een torenhoge
    // vlakke rust-waarde, en andersom kan een HR-piek zonder stappen
    // (bijv. hometrainer) nog steeds gewoon meetellen.
    private fun metExcessFromAvgHr(avgHr: Double): Double = when {
        avgHr < 90.0  -> 0.3   // rust / vrijwel geen inspanning
        avgHr < 110.0 -> 1.8   // licht (wandelen)
        avgHr < 130.0 -> 3.8   // matig
        avgHr < 150.0 -> 5.8   // stevig
        else          -> 7.8   // intensief
    }

    /**
     * Kcal-schatting voor het venster startMs tot endMs (exclusief endMs). [stepsInWindow] wordt hergebruikt
     * uit de toch al berekende stap_hN-waarden in FclActivityLogger.kt — geen
     * dubbele opvraag nodig.
     */
    fun caloriesInWindow(
        persistenceLayer: PersistenceLayer,
        stepsInWindow: Int,
        startMs: Long,
        endMs: Long
    ): Double {
        if (endMs <= startMs) return 0.0
        val hours = (endMs - startMs) / 3_600_000.0

        val heartRates = try {
            kotlinx.coroutines.runBlocking {
                persistenceLayer.getHeartRatesFromTimeToTime(startMs, endMs)
            }
        } catch (e: Exception) {
            emptyList()
        }

        val stepsEstimate = stepsInWindow * KCAL_PER_STEP
        val hrEstimate = if (heartRates.isNotEmpty()) {
            val avgHr = heartRates.map { it.beatsPerMinute }.average()
            metExcessFromAvgHr(avgHr) * DEFAULT_WEIGHT_KG * hours
        } else {
            0.0
        }

        // Altijd het maximum — nooit laten domineren, zie toelichting hierboven.
        return maxOf(stepsEstimate, hrEstimate)
    }
}
