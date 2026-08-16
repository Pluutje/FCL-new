package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.night

import android.content.Context
import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLAnalyzerDatabase
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.NightWindowEntity
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Verzamelt en aggregeert NightWindowEntity-data tot een compact rapport
 * per klok-uur, klaar om als payload naar het AI-model te sturen.
 *
 * Bewust GEEN ruwe rij-per-rij data — zelfde aanpak als de bestaande
 * dag-adviseur (FclAiAdvisorDataCollector): alleen samengevatte
 * gemiddelden, zodat de payload klein en overzichtelijk blijft en het
 * model niet zelf hoeft te aggregeren (foutgevoelig, duur).
 */
object FclNightAiAdvisorDataCollector {

    /** Hoeveel recente nachten meewegen — zelfde orde van grootte als het
     *  bestaande Nacht-tabblad zelf gebruikt om genoeg data te hebben zonder
     *  te oude, mogelijk niet meer relevante nachten mee te nemen. */
    private const val NIGHTS_WINDOW = 14

    fun collect(context: Context): FclNightReportPayload {
        val allWindows: List<NightWindowEntity> = runBlocking {
            FCLAnalyzerDatabase.getInstance(context).nightWindowDao().getAllNightWindows()
        }

        val cutoff = Instant.now().minusSeconds(NIGHTS_WINDOW.toLong() * 24 * 3600).toString()
        val recent = allWindows.filter { it.startTs >= cutoff }

        val nightsAnalyzed = recent.map { it.localDate }.distinct().size

        val hourly = recent
            .groupBy { it.effectHour }
            .toSortedMap()
            .map { (hour, windows) ->
                val n = windows.size.coerceAtLeast(1)
                NightHourAggregate(
                    effectHour = hour,
                    effectHourLabel = windows.first().effectHourLabel,
                    nightsCount = windows.map { it.localDate }.distinct().size,
                    avgBg = windows.sumOf { it.avgBg } / n,
                    avgTarget = windows.sumOf { it.avgTarget } / n,
                    avgBgSlopePerHour = windows.sumOf { it.bgSlopePerHour } / n,
                    avgIob = windows.sumOf { it.avgIob } / n,
                    avgIobDelta = windows.sumOf { it.iobDelta } / n,
                    // BUGFIX (23/07/2026): windows komt al DESC gesorteerd
                    // uit de DAO (ORDER BY startTs DESC, zie NightWindowDao) — dus
                    // windows.first() is het MEEST RECENTE venster voor dit uur,
                    // windows.last() juist het OUDSTE (tot 14 nachten terug). Met
                    // .last() kon een oud venster van vóórdat het profiel bekend
                    // was (activeProfileKnown=false) een basaalstand van 0.0 U/h
                    // opleveren, ook als de huidige stand allang > 0 is — vandaar
                    // "nu 0.0 U/h" in de kaart. Fix: het meest recente venster met
                    // een bekende, positieve stand, met de kale first() als vangnet.
                    currentBasalUph = windows.firstOrNull {
                        it.activeProfileKnown && it.activeProfileBasalUph > 0.0
                    }?.activeProfileBasalUph ?: windows.first().activeProfileBasalUph,
                    classificationCounts = windows
                        .groupingBy { it.classification }
                        .eachCount()
                )
            }

        val fmt = DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("UTC"))
        return FclNightReportPayload(
            generatedAtUtc = fmt.format(Instant.now()),
            nightsAnalyzed = nightsAnalyzed,
            hourlyData = hourly
        )
    }
}
