package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import android.content.Context

/**
 * FclLearnerAdvies (10/07/2026) — puur informatief, niets wordt hier
 * automatisch toegepast. Onderdeel van de "F eerst, D als vluchtoptie"-
 * hiërarchie: als F structureel tegen zijn plafond (DFMapping.F_MAX) aan
 * zit terwijl er nog steeds meer potentie nodig is, is dat het signaal dat
 * het plafond zelf wellicht verhoogd zou mogen worden.
 *
 * Bewust géén eigen aanpasmechanisme (F_MAX is een compile-time constante,
 * geen instelling) — dit logt alleen WANNEER en HOE VAAK dit voorkomt, zodat
 * duidelijk wordt of het incidenteel is of structureel. Komt dit regelmatig
 * voor, dan is dat de aanleiding om er later een echte, handmatige
 * Expert-modus-knop voor te bouwen (de gebruikers expliciete voorkeur: niet direct
 * automatiseren, wel zichtbaar maken).
 */
object FclLearnerAdvies {

    private const val PREFS = "fcl_learner_advies"
    private const val KEY_TS = "f_ceiling_ts"
    private const val KEY_F = "f_ceiling_f_waarde"
    private const val KEY_COUNT = "f_ceiling_episodes"

    fun logFCeilingAdvies(context: Context, currentF: Double, aaneengeslotenEpisodes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_TS, System.currentTimeMillis())
            .putFloat(KEY_F, currentF.toFloat())
            .putInt(KEY_COUNT, aaneengeslotenEpisodes)
            .apply()
    }

    data class Advies(val tsMs: Long, val f: Double, val episodes: Int)

    fun getLatest(context: Context): Advies? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ts = p.getLong(KEY_TS, 0L)
        if (ts == 0L) return null
        return Advies(ts, p.getFloat(KEY_F, 0f).toDouble(), p.getInt(KEY_COUNT, 0))
    }
}
