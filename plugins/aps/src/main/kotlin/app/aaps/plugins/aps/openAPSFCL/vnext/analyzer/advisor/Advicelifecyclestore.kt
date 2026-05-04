package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor

import android.content.Context
import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLAnalyzerDatabase

object AdviceLifecycleStore {

    const val STATE_NEW = "NEW"
    const val STATE_IN_LAST_RECOMMENDATION = "IN_LAST_RECOMMENDATION"
    const val STATE_CONSUMED = "CONSUMED_AFTER_PROFILE_CHANGE"

    private const val PREFS_NAME = "advisor_lifecycle"
    private const val KEY_LAST_PROFILE = "last_profile"

    /**
     * Aanroepen zodra een nieuwe sync is verwerkt.
     * Als de S/T/V combinatie is veranderd t.o.v. de vorige keer worden
     * episodes die bij het vorige profiel hoorden als CONSUMED gemarkeerd.
     *
     * Stappen worden afgerond op 5% zodat kleine fluctuaties geen onnodige
     * resets veroorzaken.
     */
    suspend fun onProfileObserved(
        context: Context,
        sterkte: Int,
        timing: Int,
        volhoudendheid: Int
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val currentProfile = profileKey(sterkte, timing, volhoudendheid)
        val previousProfile = prefs.getString(KEY_LAST_PROFILE, null)

        if (previousProfile == null) {
            prefs.edit().putString(KEY_LAST_PROFILE, currentProfile).apply()
            return
        }

        if (previousProfile == currentProfile) return

        // Profiel is veranderd — markeer episodes van het oude profiel als consumed
        val parts = previousProfile.split("|")
        if (parts.size == 3) {
            val prevSterkte        = parts[0].toIntOrNull() ?: 100
            val prevTiming         = parts[1].toIntOrNull() ?: 100
            val prevVolhoudendheid = parts[2].toIntOrNull() ?: 100
            FCLAnalyzerDatabase.getInstance(context)
                .episodeDao()
                .consumeProfileEpisodes(
                    sterkte        = prevSterkte,
                    timing         = prevTiming,
                    volhoudendheid = prevVolhoudendheid,
                    newState       = STATE_CONSUMED,
                    consumedState  = STATE_CONSUMED
                )
        }

        prefs.edit().putString(KEY_LAST_PROFILE, currentProfile).apply()
    }

    private fun profileKey(sterkte: Int, timing: Int, volhoudendheid: Int): String =
        "${roundTo5(sterkte)}|${roundTo5(timing)}|${roundTo5(volhoudendheid)}"

    private fun roundTo5(value: Int): Int = ((value + 2) / 5) * 5
}