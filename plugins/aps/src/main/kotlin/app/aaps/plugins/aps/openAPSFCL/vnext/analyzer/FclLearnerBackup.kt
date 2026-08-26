package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * ============================================================================
 * FCL Learner Backup (26/08/2026, n.a.v. de nieuwe-telefoon-diagnose)
 * ============================================================================
 *
 * AANLEIDING: bij de overstap naar de nieuwe telefoon (20/08/2026) bleek de
 * geleerde D/F-staat (sterkte/timing/volhoudendheid, via DFLearner) terug op
 * de standaardwaarden te staan — merkbaar als een algoritme dat "minder
 * reageert" en hogere nachten, dagenlang, tot de learner het vanzelf weer
 * had teruggeleerd. Oorzaak: DFLearner en de verwante stores (VLearner via
 * DFLearner, FrontloadLearner, ISF-auto-adjust, nacht-basaal-auto-adjust,
 * de AI-parameter-overrides) staan allemaal in eigen, plugin-interne
 * SharedPreferences-bestanden — volledig los van AAPS' eigen
 * instellingen-export/import. Een verse installatie op een nieuwe telefoon
 * (of een herinstallatie) verliest die staat gegarandeerd, ook als de
 * AAPS-instellingen zelf keurig hersteld worden.
 *
 * OPLOSSING (op verzoek van de gebruiker, 26/08/2026): één keer per dag een
 * datum-gestempeld JSON-backup-bestand wegschrijven (als dat voor vandaag
 * nog niet bestaat), plus handmatig terug kunnen zetten vanuit een door de
 * gebruiker gekozen datum via een knop in Expert-modus (zie
 * FclBackupRestoreCard.kt).
 *
 * BEWUST NIET MEEGENOMEN: "fcl_ai_advisor_prefs" (FclAiAdvisorSettingsStore)
 * — dat bestand bevat de Claude/Gemini API-sleutels in leesbare vorm. Die
 * horen niet in een JSON-bestand op de gedeelde opslag (Documents/AAPS/...)
 * te staan, waar elke bestandsverkenner-app bij kan. Model-keuze/aan-uit
 * verlies je dus bij een reset, maar dat is een kleine, niet
 * dosis-bepalende instelling die je zo terugzet — een bewuste, expliciete
 * afweging, geen omissie.
 *
 * Locatie: Documents/AAPS/FCL_BACKUP/FCLvNext_backup_yyyy-MM-dd.json —
 * dezelfde gedeelde, met een gewone bestandsverkenner bereikbare map-stijl
 * als Documents/AAPS/ANALYSE (zie v78, MANAGE_EXTERNAL_STORAGE).
 */
object FclLearnerBackup {

    // ── Welke plugin-interne SharedPreferences-bestanden worden meegenomen ──
    private val PREFS_TO_BACKUP = listOf(
        "df_learner_prefs",                  // D/F, nfLevel, aggressiviteit, tracked params, history
        "v_learner_prefs",                   // VLearner (volhoudendheid-leren)
        "fl_prefs",                          // FrontloadLearner
        "fcl_expert_prefs",                  // expert-modus, T1-versterking
        "fcl_isf_auto_adjust_prefs",         // ISF-auto-adjust baseline/modus
        "fcl_night_basal_auto_adjust_prefs", // nacht-basaal-auto-adjust baseline/modus
        "fcl_ai_param_store",                // AI-goedgekeurde parameter-overrides (hoogste prioriteit, zie kdoc daar)
        "fcl_night_ai_advisor_prefs"         // laatste nacht-AI-adviesresultaat (cache, niet gevoelig)
        // fcl_ai_advisor_prefs bewust UITGESLOTEN — bevat API-sleutels, zie kdoc hierboven.
    )

    private const val SCHEMA_VERSION = 1
    private const val FILE_PREFIX = "FCLvNext_backup_"
    private const val FILE_SUFFIX = ".json"
    private const val KEEP_DAYS = 30

    private val fileDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private fun backupDir(): File =
        File(android.os.Environment.getExternalStorageDirectory(), "Documents/AAPS/FCL_BACKUP")

    private fun fileForDate(date: LocalDate): File =
        File(backupDir(), "$FILE_PREFIX${date.format(fileDateFormatter)}$FILE_SUFFIX")

    // ── Schrijven ────────────────────────────────────────────────────────

    /**
     * Aan te roepen elke cyclus (net als exportCsvLast7Days): schrijft
     * alleen daadwerkelijk iets weg als het bestand van vandaag nog niet
     * bestaat. Nooit een crash naar buiten laten lekken — zelfde discipline
     * als exportCsvLast7Days sinds de crash-fix van 20/08/2026.
     */
    fun maybeWriteDailyBackup(context: Context) {
        try {
            val today = LocalDate.now()
            if (fileForDate(today).exists()) return
            writeBackupNow(context, today)
        } catch (e: Exception) {
            android.util.Log.e(
                "FclLearnerBackup",
                "maybeWriteDailyBackup mislukt — backup overgeslagen, rest van de cyclus gaat door",
                e
            )
        }
    }

    /**
     * Schrijft direct een backup voor [date] (default vandaag), ongeacht of
     * er al een bestand voor die datum bestaat (overschrijft dan). Publiek
     * zodat de "Nu back-uppen"-knop in Expert-modus hem ook los kan
     * aanroepen.
     */
    fun writeBackupNow(context: Context, date: LocalDate = LocalDate.now()): File {
        val dir = backupDir()
        dir.mkdirs()

        val root = JSONObject()
        root.put("schema_version", SCHEMA_VERSION)
        root.put("written_at", LocalDateTime.now().toString())

        val storesJson = JSONObject()
        for (prefsName in PREFS_TO_BACKUP) {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            storesJson.put(prefsName, serializePrefs(prefs))
        }
        root.put("stores", storesJson)

        val file = fileForDate(date)
        file.writeText(root.toString(2))

        pruneOldBackups(dir)
        return file
    }

    private fun serializePrefs(prefs: SharedPreferences): JSONObject {
        val obj = JSONObject()
        for ((key, value) in prefs.all) {
            val entry = JSONObject()
            when (value) {
                is Boolean -> entry.put("t", "bool").put("v", value)
                is Int     -> entry.put("t", "int").put("v", value)
                is Long    -> entry.put("t", "long").put("v", value)
                is Float   -> entry.put("t", "float").put("v", value.toDouble())
                is String  -> entry.put("t", "string").put("v", value)
                is Set<*>  -> entry.put("t", "stringset").put("v", JSONArray(value.map { it.toString() }))
                else       -> continue // onbekend type: overslaan, breekt de rest van de backup niet
            }
            obj.put(key, entry)
        }
        return obj
    }

    private fun pruneOldBackups(dir: File) {
        val cutoff = LocalDate.now().minusDays(KEEP_DAYS.toLong())
        dir.listFiles { f -> f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
            ?.forEach { f ->
                parseDateFromFileName(f)?.let { d ->
                    if (d.isBefore(cutoff)) f.delete()
                }
            }
    }

    private fun parseDateFromFileName(f: File): LocalDate? {
        val dateStr = f.name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX)
        return runCatching { LocalDate.parse(dateStr, fileDateFormatter) }.getOrNull()
    }

    // ── Lezen / herstellen ──────────────────────────────────────────────

    data class BackupInfo(val date: LocalDate, val file: File, val writtenAt: String?)

    /** Alle beschikbare backups, nieuwste eerst. */
    fun listAvailableBackups(context: Context): List<BackupInfo> {
        val dir = backupDir()
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
            ?.mapNotNull { f ->
                val date = parseDateFromFileName(f) ?: return@mapNotNull null
                val writtenAt = runCatching { JSONObject(f.readText()).optString("written_at", null) }.getOrNull()
                BackupInfo(date, f, writtenAt)
            }
            ?.sortedByDescending { it.date }
            ?: emptyList()
    }

    sealed class RestoreResult {
        data class Success(val storesRestored: Int, val keysRestored: Int) : RestoreResult()
        data class Failure(val reason: String) : RestoreResult()
    }

    /**
     * Zet de inhoud van [file] terug in de bijbehorende SharedPreferences-
     * bestanden. Dit is een MERGE, geen volledige vervanging: bestaande
     * keys die niet in de backup voorkomen blijven gewoon staan. Zo kan een
     * oudere backup nooit per ongeluk een instelling wissen die na die
     * backup is toegevoegd, en blijft dit een lage-drempel, terugdraaibare
     * herstel-actie — precies wat je met een geleerde-staat-backup wilt.
     */
    fun restoreBackup(context: Context, file: File): RestoreResult {
        return try {
            val root = JSONObject(file.readText())
            val storesJson = root.optJSONObject("stores")
                ?: return RestoreResult.Failure("Geen 'stores'-blok in dit bestand — geen geldige FCL-backup.")

            var storesRestored = 0
            var keysRestored = 0

            for (prefsName in PREFS_TO_BACKUP) {
                val storeJson = storesJson.optJSONObject(prefsName) ?: continue
                val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                var wroteAny = false

                val keyIter = storeJson.keys()
                while (keyIter.hasNext()) {
                    val key = keyIter.next()
                    val entry = storeJson.optJSONObject(key) ?: continue
                    val ok = when (entry.optString("t")) {
                        "bool"      -> { editor.putBoolean(key, entry.optBoolean("v")); true }
                        "int"       -> { editor.putInt(key, entry.optInt("v")); true }
                        "long"      -> { editor.putLong(key, entry.optLong("v")); true }
                        "float"     -> { editor.putFloat(key, entry.optDouble("v").toFloat()); true }
                        "string"    -> { editor.putString(key, entry.optString("v")); true }
                        "stringset" -> {
                            val arr = entry.optJSONArray("v") ?: JSONArray()
                            val set = mutableSetOf<String>()
                            for (i in 0 until arr.length()) set.add(arr.optString(i))
                            editor.putStringSet(key, set)
                            true
                        }
                        else -> false
                    }
                    if (ok) { wroteAny = true; keysRestored++ }
                }

                if (wroteAny) {
                    editor.apply()
                    storesRestored++
                }
            }

            if (storesRestored == 0) {
                RestoreResult.Failure("Bestand gelezen, maar geen herkenbare gegevens gevonden om terug te zetten.")
            } else {
                RestoreResult.Success(storesRestored, keysRestored)
            }
        } catch (e: Exception) {
            RestoreResult.Failure(e.message ?: "Onbekende fout bij lezen van het backup-bestand")
        }
    }
}
