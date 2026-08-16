package app.aaps.plugins.aps.openAPSFCL.vnext

import org.joda.time.DateTime
import org.joda.time.DateTimeZone

/**
 * ============================================================================
 * FclWakeDetector — "dag begint bij eerste stappen" (28/07/2026)
 * ============================================================================
 *
 * AANLEIDING: component B van de AIGF (zie FclActivitySensitivity.kt) moet
 * weten hoeveel van de laatste 4 uur vóór een maaltijd daadwerkelijk binnen
 * wakkere uren viel. Een vaste kloktijd (bijv. de bestaande `ochtend_start`-
 * instelling) is daarvoor niet geschikt — de gebruikers eigen dagritme doordeweeks
 * vs. weekend wisselt te veel, en een ochtendwandeling met de hond kan al
 * vóór die vaste grens beginnen.
 *
 * GEKOZEN SIGNAAL: de eerste keer per kalenderdag dat de ruwe stappenteller
 * (via PersistenceLayer, hetzelfde pad als EstimatedCaloriesCalculator al
 * gebruikt) STEP_THRESHOLD stappen optekent binnen een glijdend venster van
 * STEP_WINDOW_MIN minuten. Gecalibreerd door de gebruiker op zijn eigen looptempo
 * (~1200-1400 stappen/10 min): 150 stappen binnen 10 minuten is met dat
 * tempo al binnen ~1-1,5 minuut lopen bereikt, ruim boven wat een
 * nachtelijk toiletbezoek aan stappen oplevert (enkele tientallen), dus een
 * scherp, betrouwbaar onderscheid.
 *
 * GEEN losse "rustperiode ervoor"-eis nodig: omdat dit sowieso alleen de
 * EERSTE overschrijding per kalenderdag vastlegt, is die overschrijding per
 * definitie de eerste noemenswaardige activiteit van de dag — er hoeft niet
 * apart gecontroleerd te worden wat ervoor kwam.
 *
 * WERKWIJZE: de aanroeper (DetermineBasalFCL.kt, dat als enige plek in de
 * pipeline directe PersistenceLayer-toegang heeft voor stappen) roept
 * `checkAndUpdate()` elke cyclus aan met het stappental van de laatste
 * STEP_WINDOW_MIN minuten. Bij de eerste overschrijding van vandaag wordt
 * dat moment gepersisteerd; latere aanroepen dezelfde dag geven dat
 * bewaarde moment terug (geen overschrijven door een tweede, latere
 * uitschieter). Bij een nieuwe kalenderdag (lokale tijdzone) begint de
 * detectie vanzelf opnieuw.
 *
 * FALLBACK (28/07/2026): op een dag met beduidend minder stappen (geen
 * hondenwandeling, ziek, etc.) zou de stappen-trigger de hele dag nooit
 * afgaan en zou component B tot middernacht op "onbekend" (wakeOverlapFrac
 * =0, dus AIGF-B altijd neutraal) blijven staan. Daarom accepteert
 * `checkAndUpdate()` optioneel een `fallbackDeadlineMs` — de bestaande vaste
 * `ochtend_start`-instelling + 1 uur, door de aanroeper zelf berekend (deze
 * klasse kent geen Preferences). Is die kloktijd gepasseerd zonder dat de
 * stappen-trigger al afging, dan wordt dát moment als dagstart vastgelegd.
 * De stappen-trigger blijft de voorkeursroute en wint altijd als hij eerder
 * afgaat (bijv. de wandeling om 06:00, ruim vóór ochtend_start+1u).
 */
object FclWakeDetector {

    private const val PREFS = "fcl_wake_detector"
    private const val KEY_DAY = "day_start_epoch_day"
    private const val KEY_MS = "day_start_ms"

    const val STEP_THRESHOLD = 150
    const val STEP_WINDOW_MIN = 10

    private fun localEpochDay(nowMs: Long): Long {
        val dt = DateTime(nowMs, DateTimeZone.getDefault())
        // Aantal dagen sinds epoch, in lokale tijdzone — simpele, stabiele
        // daggrens-sleutel (geen rekening met DST-uur-sprongen nodig: we
        // gebruiken 'm alleen om "is dit dezelfde kalenderdag" te bepalen).
        return dt.withTimeAtStartOfDay().millis / (24L * 60L * 60L * 1000L)
    }

    /**
     * @param stepsInWindow      stappental van de laatste STEP_WINDOW_MIN
     *                           minuten (door de aanroeper zelf opgehaald via
     *                           PersistenceLayer — deze functie doet geen
     *                           eigen sensortoegang).
     * @param fallbackDeadlineMs optioneel: `ochtend_start` + 1 uur (in ms,
     *                           voor vandaag), door de aanroeper berekend.
     *                           Null = geen fallback (oud gedrag). Als deze
     *                           kloktijd al gepasseerd is zonder dat de
     *                           stappen-trigger afging, wordt dát moment als
     *                           dagstart vastgelegd.
     * @return het gepersisteerde "dag begon om"-moment voor VANDAAG, of null
     *         als dat nog niet is vastgesteld (nog geen 150 stappen/10 min
     *         gezien sinds middernacht, en de fallback-deadline is ook nog
     *         niet gepasseerd).
     */
    fun checkAndUpdate(
        context: android.content.Context,
        nowMs: Long,
        stepsInWindow: Int,
        fallbackDeadlineMs: Long? = null
    ): Long? {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val today = localEpochDay(nowMs)
        val storedDay = prefs.getLong(KEY_DAY, -1L)

        if (storedDay == today) {
            // Al vastgesteld vandaag — bewaarde moment teruggeven, niet
            // overschrijven door een latere, eventueel drukkere periode.
            val storedMs = prefs.getLong(KEY_MS, -1L)
            return if (storedMs > 0L) storedMs else null
        }

        // Nieuwe kalenderdag (of nog nooit vastgesteld): kijk of dit de
        // eerste overschrijding is.
        if (stepsInWindow >= STEP_THRESHOLD) {
            prefs.edit().putLong(KEY_DAY, today).putLong(KEY_MS, nowMs).apply()
            return nowMs
        }

        // Stappen-trigger nog niet afgegaan — kijk of de tijd-fallback
        // (ochtend_start + 1u) al gepasseerd is. Zo ja: dit moment (dicht bij
        // de deadline, binnen één cyclus ervan) vastleggen als dagstart.
        if (fallbackDeadlineMs != null && nowMs >= fallbackDeadlineMs) {
            prefs.edit().putLong(KEY_DAY, today).putLong(KEY_MS, nowMs).apply()
            return nowMs
        }
        return null
    }

    /**
     * Alleen-lezen variant (geen update) — voor plekken die het al
     * vastgestelde moment willen weten zonder zelf een stappental aan te
     * leveren. Retourneert null als vandaag nog niets is vastgesteld.
     */
    fun getTodayDayStart(context: android.content.Context, nowMs: Long): Long? {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val today = localEpochDay(nowMs)
        val storedDay = prefs.getLong(KEY_DAY, -1L)
        if (storedDay != today) return null
        val storedMs = prefs.getLong(KEY_MS, -1L)
        return if (storedMs > 0L) storedMs else null
    }
}