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
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.BasalProfileHistoryEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLAnalyzerDatabase
import kotlinx.coroutines.runBlocking

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
        private const val KEY_DB_BACKFILLED = "db_backfilled_v1"

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
        // BUGFIX (23/07/2026): deze logger schreef altijd al naar de CSV,
        // maar de Room-tabel basal_profile_history (waar Nightwindowanalyzer.kt
        // zijn activeProfileBasalUph vandaan haalt) werd NERGENS in de codebase
        // ooit gevuld — vandaar de aanhoudende "nu 0.00 U/h". Eenmalige backfill
        // vanuit de bestaande CSV, plus (verderop) een doorlopende DB-insert naast
        // elke CSV-regel, zodat de tabel vanaf nu bijblijft.
        maybeBackfillDb()

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
                persistToDb(
                    now = now,
                    localTs = localNow.format(localFormatter),
                    zoneId = zone.id,
                    eventType = EVENT_INIT,
                    isNight = isNight,
                    signature = signature,
                    hourlyBasalUnitsPerHour = hourlyBasal
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
                persistToDb(
                    now = now,
                    localTs = localNow.format(localFormatter),
                    zoneId = zone.id,
                    eventType = EVENT_PROFILE_CHANGED,
                    isNight = isNight,
                    signature = signature,
                    hourlyBasalUnitsPerHour = hourlyBasal
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


    // ── DB-synchronisatie (23/07/2026) ────────────────────────────────
    // Zie kdoc bij onFiveMinuteTick: schrijft dezelfde snapshot die ook naar
    // de CSV gaat, aanvullend naar de Room-tabel basal_profile_history — dat
    // is de tabel waar Nightwindowanalyzer.kt zijn activeProfileBasalUph uit
    // haalt. Best-effort: een DB-fout mag nooit de CSV-logging (en al
    // helemaal niet de dosering zelf) verstoren, vandaar de brede try/catch.
    private fun persistToDb(
        now: Instant,
        localTs: String,
        zoneId: String,
        eventType: String,
        isNight: Boolean,
        signature: String,
        hourlyBasalUnitsPerHour: List<Double>
    ) {
        try {
            val entity = BasalProfileHistoryEntity(
                tsUtc = now.toString(),
                tsLocal = localTs,
                timezone = zoneId,
                eventType = eventType,
                isNight = isNight,
                profileSignature = signature,
                basal00 = hourlyBasalUnitsPerHour[0],
                basal01 = hourlyBasalUnitsPerHour[1],
                basal02 = hourlyBasalUnitsPerHour[2],
                basal03 = hourlyBasalUnitsPerHour[3],
                basal04 = hourlyBasalUnitsPerHour[4],
                basal05 = hourlyBasalUnitsPerHour[5],
                basal06 = hourlyBasalUnitsPerHour[6],
                basal07 = hourlyBasalUnitsPerHour[7],
                basal08 = hourlyBasalUnitsPerHour[8],
                basal09 = hourlyBasalUnitsPerHour[9],
                basal10 = hourlyBasalUnitsPerHour[10],
                basal11 = hourlyBasalUnitsPerHour[11],
                basal12 = hourlyBasalUnitsPerHour[12],
                basal13 = hourlyBasalUnitsPerHour[13],
                basal14 = hourlyBasalUnitsPerHour[14],
                basal15 = hourlyBasalUnitsPerHour[15],
                basal16 = hourlyBasalUnitsPerHour[16],
                basal17 = hourlyBasalUnitsPerHour[17],
                basal18 = hourlyBasalUnitsPerHour[18],
                basal19 = hourlyBasalUnitsPerHour[19],
                basal20 = hourlyBasalUnitsPerHour[20],
                basal21 = hourlyBasalUnitsPerHour[21],
                basal22 = hourlyBasalUnitsPerHour[22],
                basal23 = hourlyBasalUnitsPerHour[23]
            )
            runBlocking {
                FCLAnalyzerDatabase.getInstance(context)
                    .basalProfileHistoryDao()
                    .insertAll(listOf(entity))
            }
        } catch (_: Exception) {
            // CSV blijft de primaire, altijd-werkende log; DB-tabel is alleen
            // een aanvulling t.b.v. de nacht-analyser.
        }
    }

    // Eenmalige backfill (23/07/2026): leest de al bestaande CSV-historie
    // in en vult de tot nu toe altijd lege DB-tabel in één keer met alle
    // eerder gelogde profiel-snapshots, zodat ook oudere nachtvensters direct
    // (i.p.v. pas na de eerstvolgende profielwijziging) een kloppende
    // activeProfileBasalUph krijgen. Vlag wordt VOORAF gezet — best-effort,
    // geen herhaalde pogingen elke cyclus als het een keer niet lukt; de
    // doorlopende persistToDb()-aanroepen houden de tabel vanaf nu sowieso bij.
    private fun maybeBackfillDb() {
        if (prefs.getBoolean(KEY_DB_BACKFILLED, false)) return
        prefs.edit().putBoolean(KEY_DB_BACKFILLED, true).apply()
        try {
            val file = getFile()
            if (!file.exists()) return
            val lines = file.readLines()
            if (lines.size <= 1) return
            val entities = lines.drop(1).mapNotNull { parseCsvLine(it) }
            if (entities.isNotEmpty()) {
                runBlocking {
                    FCLAnalyzerDatabase.getInstance(context)
                        .basalProfileHistoryDao()
                        .insertAll(entities)
                }
            }
        } catch (_: Exception) {
            // best-effort — CSV blijft intact; nieuwe profielwijzigingen vullen
            // de tabel vanaf nu gewoon verder via persistToDb()
        }
    }

    private fun parseCsvLine(line: String): BasalProfileHistoryEntity? {
        val parts = line.split(";")
        if (parts.size != 30) return null   // 6 vaste kolommen + 24 basaal-uren
        return try {
            BasalProfileHistoryEntity(
                tsUtc = parts[0],
                tsLocal = parts[1],
                timezone = parts[2],
                eventType = parts[3],
                isNight = parts[4].toBoolean(),
                profileSignature = parts[5],
                basal00 = parts[6].toDouble(),
                basal01 = parts[7].toDouble(),
                basal02 = parts[8].toDouble(),
                basal03 = parts[9].toDouble(),
                basal04 = parts[10].toDouble(),
                basal05 = parts[11].toDouble(),
                basal06 = parts[12].toDouble(),
                basal07 = parts[13].toDouble(),
                basal08 = parts[14].toDouble(),
                basal09 = parts[15].toDouble(),
                basal10 = parts[16].toDouble(),
                basal11 = parts[17].toDouble(),
                basal12 = parts[18].toDouble(),
                basal13 = parts[19].toDouble(),
                basal14 = parts[20].toDouble(),
                basal15 = parts[21].toDouble(),
                basal16 = parts[22].toDouble(),
                basal17 = parts[23].toDouble(),
                basal18 = parts[24].toDouble(),
                basal19 = parts[25].toDouble(),
                basal20 = parts[26].toDouble(),
                basal21 = parts[27].toDouble(),
                basal22 = parts[28].toDouble(),
                basal23 = parts[29].toDouble()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun getEffectiveProfile(timeMillis: Long): Profile? {
        val eps = kotlinx.coroutines.runBlocking {
            persistenceLayer.getEffectiveProfileSwitchActiveAt(timeMillis)
        } ?: return null
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
        val dir = File(context.getExternalFilesDir(null), "ANALYSE")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }
}