package app.aaps.plugins.aps.openAPSFCL.vnext

import android.content.Context

/**
 * FclNachtOvergangSettings (17/07/2026, Ecko) — lichte, kale opslag (geen
 * officiële AAPS-preference/Room, zelfde patroon als de AIGF min/max %-
 * instellingen in FCLSettingsScreen.kt, "fcl_activity_sensitivity_settings")
 * voor de duur van de geleidelijke dag→nacht-overgang.
 *
 * AANLEIDING: drie avonden op rij (14/15, 15/16, 16/17 juli 2026) klapten
 * alle nacht-instellingen (gain, maxSMB, zes door applyNightResponseStyle
 * geraakte parameters, iobPower, de persistent-correction-drempel, de AAPS-
 * multiplier) in één cyclus om zodra de klok NachtStart passeerde, terwijl
 * een net-gestarte maaltijd-episode nog volop in de stijgende fase zat —
 * met een onnodig hoge piek (12-14 mmol) tot gevolg. Zie ook de kdoc bij
 * FCLvNextDayNightHelper.minutesSinceNightStart().
 *
 * BEWUST GEEN officiële AAPS-key: de enum-registraties (IntKey/DoubleKey/
 * StringKey) leven in AAPS-core, niet in deze plugin-map, en een nieuwe
 * corekey toevoegen zou een wijziging in code buiten dit project vereisen —
 * exact wat Ecko eerder al uitsloot voor vergelijkbare, puur-plugin-interne
 * instellingen (zie FclActivityTypeCache.kt).
 *
 * BEWUST GEEN 0 als default: Ecko's eigen inschatting ("2 uur een mooi
 * uitgangspunt") is de default. Bij 0 minuten is het gedrag identiek aan
 * de oude harde dag/nacht-knip (nightTransitionFraction springt direct
 * naar 1.0 zodra isNightNow()=true) — dus wie de oude situatie terug wil,
 * kan dat gewoon instellen.
 */
object FclNachtOvergangSettings {

    private const val PREFS = "fcl_nacht_overgang_settings"
    private const val KEY = "overgang_minuten"

    /** Instelbereik: 0..180 minuten, in stappen van 10 (UI-conventie, niet hier afgedwongen). */
    const val MIN_MINUTEN = 0
    const val MAX_MINUTEN = 180
    const val DEFAULT_MINUTEN = 120

    fun get(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY, DEFAULT_MINUTEN)
            .coerceIn(MIN_MINUTEN, MAX_MINUTEN)

    fun set(context: Context, minuten: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY, minuten.coerceIn(MIN_MINUTEN, MAX_MINUTEN))
            .apply()
    }
}
