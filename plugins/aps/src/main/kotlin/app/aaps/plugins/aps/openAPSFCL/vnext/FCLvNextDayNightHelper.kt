package app.aaps.plugins.aps.openAPSFCL.vnext

import org.joda.time.DateTime
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey

class FCLvNextDayNightHelper(
    private val preferences: Preferences
) {

    fun isNightNow(): Boolean {
        val now = DateTime.now()
        val currentHour = now.hourOfDay
        val currentMinute = now.minuteOfHour
        val currentDayOfWeek = now.dayOfWeek

        val isWeekend = isWeekendDay(currentDayOfWeek)

        val ochtendStart = if (isWeekend) {
            preferences.get(StringKey.OchtendStartWeekend)
        } else {
            preferences.get(StringKey.OchtendStart)
        }

        val nachtStart = preferences.get(StringKey.NachtStart)

        val (ochtendUur, ochtendMin) = parseTime(ochtendStart)
        val (nachtUur, nachtMin) = parseTime(nachtStart)

        return isInTijdBereik(
            currentHour, currentMinute,
            nachtUur, nachtMin,
            ochtendUur, ochtendMin
        )
    }

    /** Instance-gemak: leest de WeekendDagen-instelling zelf uit preferences. */
    fun isWeekendDay(dayOfWeek: Int): Boolean =
        isWeekendDay(dayOfWeek, preferences.get(StringKey.WeekendDagen))

    // ── Geleidelijke nacht-overgang (17/07/2026, Ecko) ───────────────────────
    // Aanleiding: drie avonden op rij (14/15, 15/16, 16/17 juli) at Ecko rond
    // 23:00 iets tegen een aan-de-lage-kant BG voor het slapen. De BG-stijging
    // was nog volop bezig toen de klok NachtStart passeerde — isNightNow()
    // sprong in één cyclus van false naar true, en ALLE nacht-instellingen
    // (gain, maxSMB, de zes door applyNightResponseStyle geraakte parameters,
    // iobPower, de persistent-correction-drempel, de AAPS-multiplier) klapten
    // in dezelfde cyclus mee om — met een onnodig hoge piek (12-14 mmol) tot
    // gevolg, terwijl de nog actieve maaltijd-episode niet meer goed
    // gecorrigeerd kon worden.
    //
    // Ecko koos bewust voor een zuiver tijd-gebaseerde geleidelijke overgang
    // (i.p.v. wachten tot de episode is afgerond): het risico van "vasthouden
    // tot einde episode" is een grotere, plotselinge terugval in kracht na
    // afloop, met kans op een net-te-agressieve correctie die alsnog
    // correctie-koolhydraten nodig maakt om een hypo te voorkomen. Een
    // geleidelijke overgang verkleint die kans, met als geaccepteerde
    // afruil dat de BG soms iets hoger uitkomt dan strikt noodzakelijk was.
    //
    // Deze methode geeft alleen de RUWE minuten sinds de overgang terug —
    // bewust geen kant-en-klare fractie/duur hier, zodat de aanroepende code
    // (DetermineBasalFCL.kt) zelf de ingestelde duur erbij kan halen en de
    // 0.0..1.0-overgangsfractie berekent, zonder dat deze klasse een nieuwe
    // Context/SharedPreferences-afhankelijkheid nodig heeft.
    /**
     * Minuten sinds de meest recente NachtStart-overgang, of 0 als het nu
     * geen nacht is (of als NachtStart in de toekomst ligt binnen dezelfde
     * dag-berekening — dan is er per definitie nog geen nacht).
     */
    fun minutesSinceNightStart(): Int {
        if (!isNightNow()) return 0

        val now = DateTime.now()
        val currentHour = now.hourOfDay
        val currentMinute = now.minuteOfHour

        val nachtStart = preferences.get(StringKey.NachtStart)
        val (nachtUur, nachtMin) = parseTime(nachtStart)

        val nachtStartTotalMin = nachtUur * 60 + nachtMin
        val nowTotalMin = currentHour * 60 + currentMinute

        return if (nowTotalMin >= nachtStartTotalMin) {
            nowTotalMin - nachtStartTotalMin
        } else {
            // Nacht liep middernacht voorbij: rest van gisteren + minuten van vandaag.
            (1440 - nachtStartTotalMin) + nowTotalMin
        }
    }

    companion object {
        // 05/07/2026 (Ecko): losgetrokken uit de instance-methode zodat andere
        // modules (FclMealTimeAnticipation.kt) exact dezelfde weekend-definitie
        // kunnen hergebruiken zonder een eigen Preferences-afhankelijkheid nodig
        // te hebben en zonder de dag-afkortingen-mapping te dupliceren.
        fun isWeekendDay(dayOfWeek: Int, weekendDagenCsv: String): Boolean {
            val dayMapping = mapOf(
                1 to "ma", 2 to "di", 3 to "wo", 4 to "do",
                5 to "vr", 6 to "za", 7 to "zo"
            )
            val currentDayAbbr = dayMapping[dayOfWeek] ?: return false
            return weekendDagenCsv.split(",").any {
                it.trim().equals(currentDayAbbr, ignoreCase = true)
            }
        }
    }

    private fun parseTime(timeStr: String): Pair<Int, Int> {
        return try {
            val parts = timeStr.split(":")
            val uur = parts[0].toInt()
            val minuut = if (parts.size > 1) parts[1].toInt() else 0
            Pair(uur, minuut)
        } catch (_: Exception) {
            Pair(6, 0)
        }
    }

    private fun isInTijdBereik(
        hh: Int, mm: Int,
        startUur: Int, startMinuut: Int,
        eindUur: Int, eindMinuut: Int
    ): Boolean {
        val start = startUur * 60 + startMinuut
        val end = eindUur * 60 + eindMinuut
        val now = hh * 60 + mm

        return if (end < start) {
            now >= start || now < end
        } else {
            now in start..end
        }
    }
}
