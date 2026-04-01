package app.aaps.plugins.aps.openAPSFCL.vnext

import android.os.Environment
import java.io.File

/**
 * Leest FCLvNext_config_override.json uit de gedeelde ANALYSE-map.
 *
 * Dit bestand wordt geschreven door de FCL Analyzer app na handmatige goedkeuring
 * door de gebruiker. Het overschrijft:
 *   1. De 5 as-instellingen (profile, mealDetectSpeed, correctionStyle,
 *      mealHandlingStyle, hypoProtectionStyle) vóór de rest van de config-keten.
 *   2. Individuele param_overrides (Groep A fijnafstelling) NA de as-keten,
 *      als laatste stap in loadFCLvNextConfig().
 *
 * Veiligheidsgaranties:
 * - Als het bestand niet bestaat → null → loadFCLvNextConfig valt terug op defaults
 * - Als het bestand corrupt/onleesbaar is → null → idem
 * - Alle numerieke overrides worden geclamped binnen veilige grenzen
 * - Groep C parameters (veiligheidslogica) zijn NOOIT extern aanpasbaar
 */
object FCLvNextConfigOverride {

    private const val RELATIVE_PATH = "Documents/AAPS/ANALYSE"
    private const val FILENAME       = "FCLvNext_config_override.json"

    // ── As-overrides (bestaand) ─────────────────────────────────────────

    data class Override(
        val profile:             String? = null,
        val mealDetectSpeed:     String? = null,
        val correctionStyle:     String? = null,
        val mealHandlingStyle:   String? = null,
        val hypoProtectionStyle: String? = null,
        val writtenAt:           String? = null,
        val basedOnEpisodes:     Int?    = null,
        val reason:              String? = null,

        // ── Groep A: individuele param fijnafstelling ───────────────────
        val paramOverrides:      ParamOverrides? = null
    )

    /**
     * Individuele parameter-overrides (Groep A).
     * Null = gebruik de waarde die uit de as-keten rolt (geen override).
     * Alle waarden worden geclamped op veilige grenzen in applyParamOverrides().
     */
    data class ParamOverrides(
        val peakPredictionThreshold:  Double? = null,  // 9.5 – 14.0 mmol
        val watchingFrontloadFrac:    Double? = null,  // 0.40 – 0.90
        val watchingMinDeltaToTarget: Double? = null,  // 0.5 – 3.5 mmol
        val commitCooldownMinutes:    Int?    = null,  // 5 – 25 min
        val peakPredictionHorizonH:   Double? = null,  // 0.8 – 1.8 uur
        val iobStart:                 Double? = null   // 0.25 – 0.55
    )

    /**
     * Laad het override-bestand. Geeft null terug als het niet bestaat of niet leesbaar is.
     */
    fun load(): Override? {
        return try {
            val file = File(
                Environment.getExternalStorageDirectory(),
                "$RELATIVE_PATH/$FILENAME"
            )
            if (!file.exists()) return null
            val text = file.readText(Charsets.UTF_8)
            parse(text)
        } catch (_: Exception) {
            null
        }
    }

    // ── Validatie-helpers voor as-overrides ─────────────────────────────

    private val validProfiles = setOf(
        "VERY_STRICT", "STRICT", "BALANCED", "AGGRESSIVE", "VERY_AGGRESSIVE"
    )
    private val validSpeeds = setOf(
        "VERY_SLOW", "SLOW", "MODERATE", "FAST", "VERY_FAST"
    )
    private val validCorrection = setOf(
        "VERY_CAUTIOUS", "CAUTIOUS", "NORMAL", "PERSISTENT", "VERY_PERSISTENT"
    )
    private val validMealHandling = setOf(
        "VERY_CONSERVATIVE", "CONSERVATIVE", "BALANCED", "ANTICIPATORY", "AGGRESSIVE"
    )
    private val validHypo = setOf(
        "MINIMAL", "RELAXED", "BALANCED", "SAFE", "ULTRA_SAFE"
    )

    fun Override.safeProfile(fallback: String)             = profile?.takeIf { it in validProfiles }             ?: fallback
    fun Override.safeMealDetectSpeed(fallback: String)     = mealDetectSpeed?.takeIf { it in validSpeeds }      ?: fallback
    fun Override.safeCorrectionStyle(fallback: String)     = correctionStyle?.takeIf { it in validCorrection }   ?: fallback
    fun Override.safeMealHandlingStyle(fallback: String)   = mealHandlingStyle?.takeIf { it in validMealHandling } ?: fallback
    fun Override.safeHypoProtectionStyle(fallback: String) = hypoProtectionStyle?.takeIf { it in validHypo }    ?: fallback

    // ── Interne JSON parser ──────────────────────────────────────────────

    private fun parse(json: String): Override? {
        return try {
            val settings      = extractBlock(json, "settings")
            val paramOverBlock = extractBlock(json, "param_overrides")

            Override(
                profile             = settings?.let { extractString(it, "fcl_vnext_profile") },
                mealDetectSpeed     = settings?.let { extractString(it, "fcl_vnext_meal_detect_speed") },
                correctionStyle     = settings?.let { extractString(it, "fcl_vnext_correction_style") },
                mealHandlingStyle   = settings?.let { extractString(it, "fcl_vnext_meal_handling_style") },
                hypoProtectionStyle = settings?.let { extractString(it, "fcl_vnext_hypo_protection_style") },
                writtenAt           = extractString(json, "written_at"),
                basedOnEpisodes     = extractString(json, "based_on_episodes")?.toIntOrNull(),
                reason              = extractString(json, "reason"),
                paramOverrides      = paramOverBlock?.let { parseParamOverrides(it) }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseParamOverrides(block: String): ParamOverrides? {
        return try {
            ParamOverrides(
                peakPredictionThreshold  = extractDouble(block, "peakPredictionThreshold"),
                watchingFrontloadFrac    = extractDouble(block, "watchingFrontloadFrac"),
                watchingMinDeltaToTarget = extractDouble(block, "watchingMinDeltaToTarget"),
                commitCooldownMinutes    = extractString(block, "commitCooldownMinutes")?.toIntOrNull(),
                peakPredictionHorizonH   = extractDouble(block, "peakPredictionHorizonH"),
                iobStart                 = extractDouble(block, "iobStart")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractDouble(json: String, key: String): Double? =
        extractString(json, key)?.toDoubleOrNull()

    private fun extractString(json: String, key: String): String? {
        val pattern = """"$key"\s*:\s*"?([^",}\n]+)"?""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.trim()?.removeSurrounding("\"")
    }

    private fun extractBlock(json: String, key: String): String? {
        val start = json.indexOf("\"$key\"")
        if (start < 0) return null
        val blockStart = json.indexOf('{', start)
        if (blockStart < 0) return null
        var depth = 0
        var i = blockStart
        while (i < json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return json.substring(blockStart, i + 1) }
            }
            i++
        }
        return null
    }
}
