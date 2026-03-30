package app.aaps.plugins.aps.openAPSFCL.vnext

import android.os.Environment
import java.io.File

/**
 * Leest FCLvNext_config_override.json uit de gedeelde ANALYSE-map.
 *
 * Dit bestand wordt geschreven door de FCL Analyzer app na handmatige goedkeuring
 * door de gebruiker. Het overschrijft de 5 as-instellingen (profile, mealDetectSpeed,
 * correctionStyle, mealHandlingStyle, hypoProtectionStyle) vóór de rest van de
 * config-keten (applyProfileDoseStrength, applyMealDetectSpeed, etc.) deze verwerkt.
 *
 * Veiligheidsgaranties:
 * - Als het bestand niet bestaat → null → loadFCLvNextConfig valt terug op prefs
 * - Als het bestand corrupt/onleesbaar is → null → idem
 * - Onbekende waarden worden nooit doorgegeven; loadFCLvNextConfig gebruikt ze
 *   alleen als ze overeenkomen met bekende enum-waarden
 * - De rest van de FCLvNextConfig (hardcoded veiligheidslogica) is NOOIT extern aanpasbaar
 */
object FCLvNextConfigOverride {

    private const val RELATIVE_PATH = "Documents/AAPS/ANALYSE"
    private const val FILENAME       = "FCLvNext_config_override.json"

    data class Override(
        val profile:             String? = null,
        val mealDetectSpeed:     String? = null,
        val correctionStyle:     String? = null,
        val mealHandlingStyle:   String? = null,
        val hypoProtectionStyle: String? = null,
        val writtenAt:           String? = null,
        val basedOnEpisodes:     Int?    = null,
        val reason:              String? = null
    )

    /**
     * Laad het override-bestand. Geeft null terug als het niet bestaat of niet leesbaar is.
     * Wordt aangeroepen aan het begin van loadFCLvNextConfig().
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
            // Nooit een crash veroorzaken in het algoritme-pad
            null
        }
    }

    // ── Validatie-helpers voor gebruik in loadFCLvNextConfig ────────────

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

    /** Geeft de override-waarde terug als geldig, anders de fallback. */
    fun Override.safeProfile(fallback: String)             = profile?.takeIf { it in validProfiles }             ?: fallback
    fun Override.safeMealDetectSpeed(fallback: String)     = mealDetectSpeed?.takeIf { it in validSpeeds }      ?: fallback
    fun Override.safeCorrectionStyle(fallback: String)     = correctionStyle?.takeIf { it in validCorrection }   ?: fallback
    fun Override.safeMealHandlingStyle(fallback: String)   = mealHandlingStyle?.takeIf { it in validMealHandling } ?: fallback
    fun Override.safeHypoProtectionStyle(fallback: String) = hypoProtectionStyle?.takeIf { it in validHypo }    ?: fallback

    // ── Interne JSON parser (geen extra dependency) ──────────────────────

    private fun parse(json: String): Override? {
        return try {
            val settings = extractBlock(json, "settings")

            Override(
                profile             = settings?.let { extractString(it, "fcl_vnext_profile") },
                mealDetectSpeed     = settings?.let { extractString(it, "fcl_vnext_meal_detect_speed") },
                correctionStyle     = settings?.let { extractString(it, "fcl_vnext_correction_style") },
                mealHandlingStyle   = settings?.let { extractString(it, "fcl_vnext_meal_handling_style") },
                hypoProtectionStyle = settings?.let { extractString(it, "fcl_vnext_hypo_protection_style") },
                writtenAt           = extractString(json, "written_at"),
                basedOnEpisodes     = extractString(json, "based_on_episodes")?.toIntOrNull(),
                reason              = extractString(json, "reason")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractString(json: String, key: String): String? {
        // Matcht zowel "key": "value" als "key": 123
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
