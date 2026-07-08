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
 * medisch-precieze calorimetrie. Drie schattingen, altijd het MAXIMUM:
 *   1. Stappen × vuistregel per stap.
 *   2. Hartslag → MET-excess-tabel (zie hieronder waarom "excess").
 *   3. Gedetecteerd activiteitstype (ActivityTypeListener.kt, Google Play
 *      Services' Activity Recognition op het horloge) → een per-type
 *      MET-tabel, ALS er een recente, voldoende betrouwbare classificatie is.
 *
 * WAAROM ALTIJD HET MAXIMUM, NOOIT ÉÉN BRON LATEN DOMINEREN (bugfix
 * 06/07/2026, Ecko): bij een rustige hartslag (bijv. 63 bpm, gewoon aanwezig
 * omdat het horloge gedragen wordt) gaf een eerdere versie al een vaste,
 * ABSOLUTE MET-waarde — die, keer gewicht keer een heel uur, alsnog een
 * fors kcal-getal opleverde ONGEACHT de daadwerkelijke inspanning. Met een
 * "OF stappen OF hartslag"-aanpak domineerde die vlakke rust-waarde alles:
 * een uur met 1.400 stappen en een vrijwel stilzittend uur gaven bijna
 * hetzelfde resultaat. Vandaar: de HR-tabel drukt nu EXCESS uit boven een
 * lichte rust-baseline (rust ≈ 0,3 MET i.p.v. 1,5), én het maximum van alle
 * drie de schattingen wordt genomen — zo kan een stappenpiek zonder sterke
 * HR-reactie (rustig wandelen) nooit meer wegvallen tegen een torenhoge
 * vlakke rust-waarde, en een activiteitstype-classificatie (bijv. fietsen,
 * dat stappen/hartslag beide onderschatten) kan de andere twee overstijgen
 * zonder dat er een aparte "of-of"-knop nodig is.
 *
 * FIETS-VOORBEELD (de aanleiding, maar dus niet de enige toepassing): stappen
 * en hartslag onderschatten fietsen fors (nauwelijks stappen, vaak maar een
 * bescheiden hartslagstijging). Ditzelfde mechanisme werkt nu voor élk door
 * ActivityRecognitionClient onderscheiden type (RUNNING, WALKING, ON_FOOT,
 * IN_VEHICLE, STILL, TILTING) — niet uitsluitend ON_BICYCLE.
 *
 * AANNAMES DIE BEWUST ZICHTBAAR ZIJN GEHOUDEN (pas gerust aan):
 *   DEFAULT_WEIGHT_KG en beide MET-tabellen zijn generieke vuistwaarden, niet
 *   aan jouw profiel gekoppeld — FCLvNext heeft nergens een lichaamsgewicht-
 *   instelling. Voor een eerste correlatie-onderzoek (relatieve verschillen
 *   tussen actieve/inactieve periodes) is dat prima; voor absolute
 *   nauwkeurigheid zou dit gekoppeld moeten worden aan een echt profielgewicht.
 *
 * HR.beatsPerMinute is geverifieerd tegen de echte PersistenceLayer.kt/
 * HeartRate.kt-entiteit (06/07/2026) — klopt, geen aanpassing nodig.
 */
object EstimatedCaloriesCalculator {

    private const val DEFAULT_WEIGHT_KG = 75.0
    private const val KCAL_PER_STEP = 0.045   // grove vuistregel, gebruikt als ondergrens/aanvulling

    // Minimale betrouwbaarheid en maximale leeftijd van een classificatie om
    // 'm nog te laten meewegen — een oude of onzekere classificatie mag de
    // schatting niet verstoren.
    private const val ACTIVITY_MIN_CONFIDENCE_PCT = 60
    private const val ACTIVITY_MAX_AGE_MS = 20L * 60_000L

    // Absolute MET-waarden per gangbare Activity Recognition-classificatie
    // (Ainsworth e.a. Compendium of Physical Activities, grove vuistwaarden —
    // niet gepersonaliseerd). STILL/TILTING/UNKNOWN krijgen geen eigen MET:
    // die situaties worden al voldoende gedekt door de HR/stappen-schattingen
    // (geen inspanning te "redden" die de andere twee zouden missen), dus
    // IN_VEHICLE/STILL/TILTING/UNKNOWN worden hieronder bewust weggelaten —
    // caloriesInWindow() valt voor die typen automatisch terug op stappen/HR.
    private val ACTIVITY_TYPE_MET = mapOf(
        "ON_BICYCLE" to 6.5,   // matig fietsen
        "RUNNING"    to 9.0,
        "WALKING"    to 3.5,
        "ON_FOOT"    to 3.5    // Google onderscheidt soms ON_FOOT i.p.v. WALKING specifiek
    )

    // ── HR → MET-excess-tabel (zie klasse-kdoc voor "waarom excess") ──────
    private fun metExcessFromAvgHr(avgHr: Double): Double = when {
        avgHr < 90.0  -> 0.3   // rust / vrijwel geen inspanning
        avgHr < 110.0 -> 1.8   // licht (wandelen)
        avgHr < 130.0 -> 3.8   // matig
        avgHr < 150.0 -> 5.8   // stevig
        else          -> 7.8   // intensief
    }

    /**
     * Kcal-schatting voor het venster startMs tot endMs (exclusief endMs).
     * [stepsInWindow] wordt hergebruikt uit de toch al berekende stap_hN-
     * waarden in FclActivityLogger.kt — geen dubbele opvraag nodig. [context]
     * is nodig om FclActivityTypeCache te kunnen raadplegen.
     */
    fun caloriesInWindow(
        context: android.content.Context,
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

        val activityEstimate = activityTypeEstimate(context, endMs, hours)

        // Altijd het maximum — nooit laten domineren, zie klasse-kdoc.
        return maxOf(stepsEstimate, hrEstimate, activityEstimate)
    }

    /**
     * Schatting op basis van de meest recente, voldoende betrouwbare
     * activiteitsclassificatie (t.o.v. [nowMs]) — 0.0 als er geen recente
     * classificatie is, of als het type geen eigen MET-waarde heeft
     * (zie ACTIVITY_TYPE_MET hierboven).
     */
    private fun activityTypeEstimate(context: android.content.Context, nowMs: Long, hours: Double): Double {
        val latest = FclActivityTypeCache.latest(context) ?: return 0.0
        if (latest.confidencePct < ACTIVITY_MIN_CONFIDENCE_PCT) return 0.0
        if (nowMs - latest.tsMs !in 0..ACTIVITY_MAX_AGE_MS) return 0.0
        val met = ACTIVITY_TYPE_MET[latest.activityType] ?: return 0.0
        return met * DEFAULT_WEIGHT_KG * hours
    }
}
