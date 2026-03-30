package app.aaps.plugins.aps.openAPSFCL.vnext.logging

import android.content.Context
import android.os.Environment
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.objects.profile.ProfileSealed
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class FclBasalProfileNightLogger(
    private val context: Context,
    private val persistenceLayer: PersistenceLayer,
    private val activePlugin: ActivePlugin
) {

    companion object {
        private const val FILE_NAME = "fcl_basal_profile_history.csv"

        private const val PREFS_NAME = "fcl_basal_profile_logger"
        private const val KEY_HAS_STATE = "has_state"
        private const val KEY_LAST_SIGNATURE = "last_signature"

        private const val EVENT_INIT = "INIT"
        private const val EVENT_PROFILE_CHANGED = "PROFILE_CHANGED"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val localFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun onFiveMinuteTick(
        currentTimeMillis: Long,
        isNight: Boolean
    ) {
        val file = getFile()
        val fileWasCreatedNow = ensureFileExistsWithHeader(file)

        val profile = getEffectiveProfile(currentTimeMillis) ?: return
        val hourlyBasal = readHourlyBasalProfile(profile)
        if (hourlyBasal.size != 24) return

        val now = Instant.ofEpochMilli(currentTimeMillis)
        val zone = ZoneId.of("Europe/Amsterdam")
        val localNow = now.atZone(zone)

        val signature = buildSignature(hourlyBasal)

        val hasState = prefs.getBoolean(KEY_HAS_STATE, false)
        val lastSignature = prefs.getString(KEY_LAST_SIGNATURE, null)

        when {
            fileWasCreatedNow -> {
                file.appendText(
                    buildRow(
                        now = now,
                        localTs = localNow.format(localFormatter),
                        zoneId = zone.id,
                        eventType = EVENT_INIT,
                        isNight = isNight,
                        signature = signature,
                        hourlyBasalUnitsPerHour = hourlyBasal
                    )
                )

                persistState(signature)
            }

            !hasState -> {
                // Bestand bestaat al, maar er is nog geen geheugenstatus.
                // Geen extra regel schrijven om dubbele logging te voorkomen.
                persistState(signature)
            }

            lastSignature != signature -> {
                file.appendText(
                    buildRow(
                        now = now,
                        localTs = localNow.format(localFormatter),
                        zoneId = zone.id,
                        eventType = EVENT_PROFILE_CHANGED,
                        isNight = isNight,
                        signature = signature,
                        hourlyBasalUnitsPerHour = hourlyBasal
                    )
                )

                persistState(signature)
            }

            else -> {
                // niets gewijzigd -> niets loggen
            }
        }
    }

    private fun persistState(signature: String) {
        prefs.edit()
            .putBoolean(KEY_HAS_STATE, true)
            .putString(KEY_LAST_SIGNATURE, signature)
            .apply()
    }

    private fun getEffectiveProfile(timeMillis: Long): Profile? {
        val eps = persistenceLayer.getEffectiveProfileSwitchActiveAt(timeMillis) ?: return null
        val sealed = ProfileSealed.EPS(eps, activePlugin)
        return sealed
    }

    private fun readHourlyBasalProfile(profile: Profile): List<Double> {
        return (0..23).map { hour ->
            profile.getBasalTimeFromMidnight(hour * 60 * 60)
        }
    }

    private fun buildSignature(hourlyBasalUnitsPerHour: List<Double>): String =
        hourlyBasalUnitsPerHour.joinToString("|") { formatValue(it) }

    private fun buildHeader(): String {
        val basalColumns = (0..23).joinToString(";") { hour ->
            "basal_${hour.toString().padStart(2, '0')}"
        }

        return "ts_utc;ts_local;timezone;event_type;is_night;profile_signature;$basalColumns\n"
    }

    private fun buildRow(
        now: Instant,
        localTs: String,
        zoneId: String,
        eventType: String,
        isNight: Boolean,
        signature: String,
        hourlyBasalUnitsPerHour: List<Double>
    ): String {
        val basalValues = hourlyBasalUnitsPerHour.joinToString(";") { value ->
            formatValue(value)
        }

        return listOf(
            now.toString(),
            localTs,
            zoneId,
            eventType,
            isNight.toString(),
            signature
        ).joinToString(";") + ";" + basalValues + "\n"
    }

    private fun formatValue(value: Double): String =
        String.format(Locale.US, "%.2f", value)

    private fun ensureFileExistsWithHeader(file: File): Boolean {
        if (file.exists() && file.length() > 0L) return false

        file.parentFile?.let { dir ->
            if (!dir.exists()) dir.mkdirs()
        }

        file.writeText(buildHeader())
        return true
    }

    private fun getFile(): File {
        val dir = File(
            Environment.getExternalStorageDirectory(),
            "Documents/AAPS/ANALYSE"
        )
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }
}