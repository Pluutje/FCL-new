package app.aaps.plugins.aps.openAPSFCL.vnext.logging

import android.os.Environment
import app.aaps.core.interfaces.db.PersistenceLayer
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * ============================================================================
 * FCL Activity Logger — stappen-per-uur logger voor activiteitsonderzoek
 * ============================================================================
 *
 * Doel: vastleggen van de stappen-per-uur-historie bij elke episodestart,
 * zodat achteraf onderzocht kan worden of er een causaal verband bestaat
 * tussen voorafgaande activiteit en insulinebehoefte/hyposcore.
 *
 * Bewust los van FCLvNext_LearnerLog_v1.csv — als het onderzoek geen
 * bruikbaar verband aantoont, kan deze log zonder gevolgen worden verwijderd.
 *
 * Schema v1 (02/07/2026, Ecko — activiteitsonderzoek fase 1):
 *
 * Elke rij = één episodestart-snapshot. Velden:
 *
 * [Identiteit]
 *   ts_utc              — timestamp episode-aanvang (UTC ISO-8601)
 *   ts_local            — lokale tijd (dd-MM-yyyy HH:mm) voor patroonanalyse
 *   episode_id          — intern episode-ID (koppelbaar aan LearnerLog)
 *   is_night            — was het nacht op dit moment (true/false)
 *   day_of_week         — 1=ma … 7=zo (weekdag vs weekend)
 *
 * [BG-context bij aanvang]
 *   bg_start_mmol       — BG bij episodestart
 *   target_mmol         — actief target
 *   iob_ratio           — IOB/maxIOB bij episodestart
 *   iob_abs_u           — absolute IOB in units
 *
 * [Stappen-historie 8 uur terug]
 * Elke stap_hN = totale stappen in dat uur (N=1 = meest recent, N=8 = oudste).
 * Bij een wandeling van 06:00-08:00 die om 08:30 een episode triggert:
 *   stap_h1 = stappen 07:30-08:30 (inclusief einde wandeling)
 *   stap_h2 = stappen 06:30-07:30
 *   stap_h3 = stappen 05:30-06:30 (bevat begin wandeling)
 *   stap_h4..h8 = inactief
 *
 *   stap_h1             — stappen in uur 0-60 min vóór episodestart
 *   stap_h2             — stappen in uur 60-120 min vóór episodestart
 *   stap_h3             — stappen 120-180 min vóór episodestart
 *   stap_h4             — stappen 180-240 min vóór episodestart
 *   stap_h5             — stappen 240-300 min vóór episodestart
 *   stap_h6             — stappen 300-360 min vóór episodestart
 *   stap_h7             — stappen 360-420 min vóór episodestart
 *   stap_h8             — stappen 420-480 min vóór episodestart
 *
 * [Afgeleide statistieken]
 *   stap_totaal_4h      — som stap_h1..stap_h4 (meest relevante venster)
 *   stap_totaal_8h      — som stap_h1..stap_h8
 *   stap_piek_uur       — hoeveelste uur had de meeste stappen (1-8; 0=geen)
 *   stap_piek_waarde    — aantal stappen in het drukste uur
 *   laatste_actief_uur  — hoeveelste uur geleden was het laatste uur met >300 stappen (0=geen)
 *
 * [Huidige activiteitsstatus]
 *   activity_module_actief  — is FCLActivityModule momenteel actief (ON/OFF)
 *   activity_retention      — huidige retentieteller (0-5)
 *   activity_insulin_pct    — huidige insulinepercentage uit ActivityModule
 *
 * Analysevragen waarvoor deze log is bedoeld:
 *   - Correlatie stap_totaal_4h met hyposcore (uit LearnerLog via episode_id)
 *   - Drempeleffect: is er een stapgetal/uur waaronder effect verwaarloosbaar is?
 *   - Tijdsvenster: hoe lang na activiteit is effect nog meetbaar?
 *   - Weekdag/nacht: zijn er confounders die het effect maskeren?
 */
object FclActivityLogger {

    private const val FILE_NAME      = "FCLvNext_ActivityLog_v1.csv"
    private const val RELATIVE_PATH  = "Documents/AAPS/ANALYSE"
    private const val SEP            = ";"

    private val LOCAL_FMT = DateTimeFormatter
        .ofPattern("dd-MM-yyyy HH:mm")
        .withZone(ZoneId.systemDefault())

    private val DOW_FMT = DateTimeFormatter
        .ofPattern("u")   // 1=Monday … 7=Sunday (ISO)
        .withZone(ZoneId.systemDefault())

    private val HEADERS = listOf(
        "ts_utc", "ts_local", "episode_id", "is_night", "day_of_week",
        "bg_start_mmol", "target_mmol", "iob_ratio", "iob_abs_u",
        "stap_h1", "stap_h2", "stap_h3", "stap_h4",
        "stap_h5", "stap_h6", "stap_h7", "stap_h8",
        "stap_totaal_4h", "stap_totaal_8h",
        "stap_piek_uur", "stap_piek_waarde", "laatste_actief_uur",
        "activity_module_actief", "activity_retention", "activity_insulin_pct"
    )

    private fun file(): File {
        val dir = File(Environment.getExternalStorageDirectory(), RELATIVE_PATH)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    /**
     * Aanroepen bij elke nieuwe episodestart vanuit FCLvNext.
     *
     * @param episodeId           intern episode-ID (van mealEpisodeCounter)
     * @param nowMs               System.currentTimeMillis() bij episodestart
     * @param bgStartMmol         BG bij aanvang episode
     * @param targetMmol          actief target
     * @param iobRatio            IOB/maxIOB
     * @param iobAbsU             absolute IOB in units
     * @param isNight             nachtstatus
     * @param activityModuleActief  FCLActivityModule actief?
     * @param activityRetention   retentieteller (0-5)
     * @param activityInsulinPct  insulinepercentage uit ActivityModule
     * @param persistenceLayer    voor stap-opvraag (per uur-venster)
     */
    fun logEpisodeStart(
        episodeId:              Long,
        nowMs:                  Long,
        bgStartMmol:            Double,
        targetMmol:             Double,
        iobRatio:               Double,
        iobAbsU:                Double,
        isNight:                Boolean,
        activityModuleActief:   Boolean,
        activityRetention:      Int,
        activityInsulinPct:     Double,
        persistenceLayer:       PersistenceLayer
    ) {
        try {
            val instant = Instant.ofEpochMilli(nowMs)
            val hourMs  = 3_600_000L

            // Stappen ophalen per uur-venster via getStepsCountFromTimeToTime —
            // DB-side filtering is efficiënter en correcter dan app-side filter.
            val stepsPerHour = (1..8).map { h ->
                val to   = nowMs - (h - 1) * hourMs
                val from = nowMs - h * hourMs
                try {
                    kotlinx.coroutines.runBlocking {
                        persistenceLayer.getStepsCountFromTimeToTime(from, to)
                            .sumOf { it.steps5min }
                    }
                } catch (_: Exception) { -1 }  // -1 = geen data beschikbaar
            }

            val totaal4h = stepsPerHour.take(4).filter { it >= 0 }.sum()
            val totaal8h = stepsPerHour.filter { it >= 0 }.sum()

            val (peekUur, peekWaarde) = stepsPerHour
                .mapIndexed { i, s -> (i + 1) to s }
                .filter { it.second >= 0 }
                .maxByOrNull { it.second }
                ?: (0 to 0)

            val ACTIEF_DREMPEL = 300  // zelfde als FCLActivityModule.THRESHOLD_ACTIVE
            val laatste = stepsPerHour
                .indexOfFirst { it >= ACTIEF_DREMPEL }
                .let { if (it == -1) 0 else it + 1 }

            val row = listOf(
                instant.toString(),
                LOCAL_FMT.format(instant),
                episodeId.toString(),
                isNight.toString(),
                DOW_FMT.format(instant),
                "%.2f".format(bgStartMmol),
                "%.2f".format(targetMmol),
                "%.3f".format(iobRatio),
                "%.2f".format(iobAbsU),
                stepsPerHour[0].toString(),
                stepsPerHour[1].toString(),
                stepsPerHour[2].toString(),
                stepsPerHour[3].toString(),
                stepsPerHour[4].toString(),
                stepsPerHour[5].toString(),
                stepsPerHour[6].toString(),
                stepsPerHour[7].toString(),
                totaal4h.toString(),
                totaal8h.toString(),
                peekUur.toString(),
                peekWaarde.toString(),
                laatste.toString(),
                if (activityModuleActief) "1" else "0",
                activityRetention.toString(),
                "%.1f".format(activityInsulinPct)
            )

            val f = file()
            f.appendText(buildString {
                if (!f.exists() || f.length() == 0L) {
                    append(HEADERS.joinToString(SEP))
                    append("\n")
                }
                append(row.joinToString(SEP))
                append("\n")
            })

        } catch (_: Exception) {
            // Logger mag nooit de doseringslogica verstoren — stil falen
        }
    }
}