package app.aaps.plugins.aps.openAPSFCL

import android.content.Context
import android.content.SharedPreferences
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import org.joda.time.DateTime

/**
 * FCL Activity Module
 *
 * Verantwoordelijkheden:
 * - uitlezen stappen uit persistenceLayer
 * - retentiebeheer
 * - berekening ISF-percentage en target-aanpassing
 *
 * Instelling: AAN of UIT (StringKey.fcl_vnext_activity_behavior = "ON" / "OFF").
 * De vroegere vier standen (OFF / LIGHT / NORMAL / STRONG) zijn samengevoegd
 * tot één AAN-stand met vaste parameters (29/06/2026, Ecko):
 *
 *   maxRetention   = 5    (voorheen NORMAL)
 *   insulinPercent = 60%  (voorheen NORMAL)
 *   targetAdjust   = 2.0  (voorheen NORMAL)
 *
 * Reden: de intensiteitsdetectie ([3] hieronder) regelt het effect al
 * automatisch op basis van het werkelijke aantal stappen — een aparte
 * LIGHT/STRONG instelling voegde geen betekenisvolle extra sturing toe.
 * Een rustige wandeling bereikt maxRetention toch niet volledig en krijgt
 * daardoor automatisch een proportioneel kleiner ISF- en target-effect.
 *
 * Wijzigingen 28/06/2026 (Ecko):
 *
 * [1] GELEIDELIJKE ISF-AFBOUW NA WANDELING
 *     Na een wandeling werkt insuline tijdelijk sterker dan normaal door
 *     verhoogde doorbloeding en glycogeenverbruik. De ISF-aanpassing
 *     (insulinPercentage) bouwt nu geleidelijk af via de retentieteller:
 *
 *       retention=5/5 → 60%   retention=3/5 → 72%
 *       retention=1/5 → 88%   retention=0/5 → 100%
 *
 *     Het target valt direct terug naar 0.0 zodra retention=0.
 *
 * [2] IOB-AFHANKELIJK TARGET BIJ AFBOUW
 *     Direct na uitdoven retentie: +0.5 mmol target als iobRatio > 0.25
 *     en de retentie < POST_ACTIVITY_BUFFER_MIN geleden is gestopt.
 *     Voorkomt dat het systeem agressief bijgeeft terwijl insuline nog
 *     extra werkt door verhoogde post-wandeling gevoeligheid.
 *
 * [3] TWEE STAP-INTENSITEITEN: RUSTIG vs ACTIEF
 *     Rustige wandeling (125–300 stap/5min): trager opbouwen, sneller afbouwen.
 *     Actieve wandeling (>300 stap/5min): sneller opbouwen, normaal afbouwen.
 */
class FCLActivityModule(
    private val preferences: Preferences,
    private val persistenceLayer: PersistenceLayer,
    context: Context
) {

    // ─────────────────────────────────────────
    // VASTE PARAMETERS (voorheen NORMAL preset)
    // ─────────────────────────────────────────

    private val MAX_RETENTION    = 5
    private val INSULIN_PERCENT  = 60.0   // % bij volledig actieve retentie
    private val TARGET_ADJUST    = 2.0    // mmol/L bij volledig actieve retentie

    // ─────────────────────────────────────────
    // RESULT
    // ─────────────────────────────────────────

    data class ActivityResult(
        val insulinPercentage: Double,  // bijv 80 = 80% (lager = meer ISF)
        val targetAdjust: Double,       // mmol/L extra op target
        val isActive: Boolean,
        val log: String
    )

    // ─────────────────────────────────────────
    // STATE
    // ─────────────────────────────────────────

    private var consecutiveStepTriggers = 0
    private var consecutiveLowTriggers  = 0

    private val prefs: SharedPreferences =
        context.getSharedPreferences("FCL_Activity_State", Context.MODE_PRIVATE)

    private val STEP_DATA_TIMEOUT_MIN    = 15L
    private val POST_ACTIVITY_BUFFER_MIN = 30L

    // Stap-drempels [3]
    private val THRESHOLD_LIGHT  = 125   // onder deze waarde: inactief
    private val THRESHOLD_ACTIVE = 300   // boven deze waarde: actieve wandeling

    // ─────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────

    /**
     * @param iobRatio  huidige IOB / maxIOB, meegegeven door DetermineBasalFCL.
     *                  Geef 0.0 mee als niet beschikbaar (veilige fallback).
     */
    /**
     * Getters voor FclActivityLogger — geven de huidige interne toestand terug
     * zonder een volledige evaluate()-cyclus te triggeren.
     * (02/07/2026, Ecko — activiteitslogger fase 1)
     */
    fun isCurrentlyActive(): Boolean = loadRetention() > 0
    fun getCurrentRetention(): Int   = loadRetention()
    fun getCurrentInsulinPct(): Double {
        val retention = loadRetention()
        if (retention == 0) return 100.0
        return INSULIN_PERCENT + (100.0 - INSULIN_PERCENT) * (1.0 - retention.toDouble() / MAX_RETENTION)
    }

    fun evaluate(iobRatio: Double = 0.0): ActivityResult {

        val enabled = preferences.get(StringKey.fcl_vnext_activity_behavior) != "OFF"
        if (!enabled) {
            reset()
            return ActivityResult(
                insulinPercentage = 100.0,
                targetAdjust      = 0.0,
                isActive          = false,
                log               = "Activity UIT"
            )
        }

        return try {
            val now   = System.currentTimeMillis()
            val since = now - 60 * 60 * 1000

            val stepData = kotlinx.coroutines.runBlocking {
                persistenceLayer.getStepsCountFromTime(since)
            }

            if (stepData.isEmpty()) {
                return safeFallback("Geen stapdata beschikbaar")
            }

            val latest = stepData.last()

            calculateActivity(
                steps5min      = latest.steps5min,
                lastUpdateTime = latest.timestamp,
                iobRatio       = iobRatio
            )

        } catch (e: Exception) {
            safeFallback("Activity fout: ${e.message}")
        }
    }

    // ─────────────────────────────────────────
    // CORE LOGICA
    // ─────────────────────────────────────────

    private fun calculateActivity(
        steps5min:      Int,
        lastUpdateTime: Long,
        iobRatio:       Double
    ): ActivityResult {
        val log       = StringBuilder()
        var retention = loadRetention()

        val minutesOld = (DateTime.now().millis - lastUpdateTime) / (1000 * 60)
        if (minutesOld > STEP_DATA_TIMEOUT_MIN) {
            log.append("⚠️ Stapdata te oud ($minutesOld min)\n")
            return fallbackFromRetention(retention, iobRatio, log.toString())
        }

        // ── [3] Intensiteitsdetectie ─────────────────────────────────────
        val isActiveWalk = steps5min > THRESHOLD_ACTIVE
        val isLightWalk  = steps5min in (THRESHOLD_LIGHT + 1)..THRESHOLD_ACTIVE

        log.append("Stappen 5min=$steps5min " +
                       "(rustig=$isLightWalk actief=$isActiveWalk)\n")
        log.append("Retentie=$retention/$MAX_RETENTION\n")

        if (steps5min > THRESHOLD_LIGHT) {
            consecutiveStepTriggers++
            consecutiveLowTriggers = 0

            val needed = when {
                retention == 0 && isActiveWalk -> 2   // actief: 2 bevestigingen bij start
                retention == 0 && isLightWalk  -> 3   // rustig: 3 bevestigingen bij start
                isLightWalk                    -> 2   // rustig vervolg: 2 per stap
                else                           -> 1   // actief vervolg: 1 per stap
            }

            if (consecutiveStepTriggers >= needed && retention < MAX_RETENTION) {
                retention++
                consecutiveStepTriggers = 0
                saveRetention(retention)
                log.append("↗ Retentie verhoogd naar $retention (needed=$needed)\n")
            }
        } else {
            consecutiveStepTriggers = 0
            consecutiveLowTriggers++

            // Rustige wandeling stopt: sneller afbouwen (1 cyclus zonder stappen)
            // Actieve wandeling stopt: normaal afbouwen (2 cycli zonder stappen)
            val lowNeeded = if (isLightWalk) 1 else 2
            if (retention > 0 && consecutiveLowTriggers >= lowNeeded) {
                retention--
                saveRetention(retention)
                if (retention == 0) saveRetentionEndTime(DateTime.now().millis)
                log.append("↘ Retentie verlaagd naar $retention (lowNeeded=$lowNeeded)\n")
            }
        }

        val isActive = retention > 0

        // ── [1] Geleidelijke ISF-afbouw ──────────────────────────────────
        // insulinPerc = INSULIN_PERCENT + (100 - INSULIN_PERCENT) * (1 - ret/max)
        // retention=5/5→60%  retention=3/5→72%  retention=1/5→88%  retention=0→100%
        val insulinPerc = if (retention > 0) {
            val fraction = retention.toDouble() / MAX_RETENTION.toDouble()
            INSULIN_PERCENT + (100.0 - INSULIN_PERCENT) * (1.0 - fraction)
        } else {
            100.0
        }

        // ── [2] IOB-afhankelijke target-buffer na wandeling ───────────────
        val retentionEndTime     = loadRetentionEndTime()
        val minSinceRetentionEnd = if (retentionEndTime > 0L)
            (DateTime.now().millis - retentionEndTime) / (1000 * 60)
        else
            Long.MAX_VALUE

        val postActivityBuffer = !isActive &&
            iobRatio > 0.25 &&
            minSinceRetentionEnd < POST_ACTIVITY_BUFFER_MIN

        val targetAdj = when {
            isActive           -> TARGET_ADJUST
            postActivityBuffer -> 0.5
            else               -> 0.0
        }

        log.append(
            "ISF-afbouw: ret=$retention/$MAX_RETENTION → " +
                "${"%.1f".format(insulinPerc)}%\n"
        )
        log.append(
            "Target: +${"%.1f".format(targetAdj)} mmol/L" +
                (if (postActivityBuffer)
                    " (post-buffer iobRatio=${"%.2f".format(iobRatio)} ${minSinceRetentionEnd}min geleden)"
                else "") + "\n"
        )
        log.append(
            if (isActive)
                "Activiteit AAN → ISF ${"%.1f".format(insulinPerc)}% Target +${"%.1f".format(targetAdj)} mmol/L\n"
            else
                "Activiteit UIT${if (postActivityBuffer) " (post-buffer actief)" else ""}\n"
        )

        return ActivityResult(
            insulinPercentage = insulinPerc,
            targetAdjust      = targetAdj,
            isActive          = isActive || postActivityBuffer,
            log               = log.toString()
        )
    }

    // ─────────────────────────────────────────
    // FALLBACKS & STATE
    // ─────────────────────────────────────────

    private fun safeFallback(reason: String): ActivityResult =
        ActivityResult(
            insulinPercentage = 100.0,
            targetAdjust      = 0.0,
            isActive          = false,
            log               = reason
        )

    private fun fallbackFromRetention(
        retention: Int,
        iobRatio:  Double,
        prefix:    String
    ): ActivityResult {
        val active      = retention > 0
        val insulinPerc = if (active) {
            val fraction = retention.toDouble() / MAX_RETENTION.toDouble()
            INSULIN_PERCENT + (100.0 - INSULIN_PERCENT) * (1.0 - fraction)
        } else 100.0

        val retentionEndTime     = loadRetentionEndTime()
        val minSinceRetentionEnd = if (retentionEndTime > 0L)
            (DateTime.now().millis - retentionEndTime) / (1000 * 60)
        else Long.MAX_VALUE
        val postBuffer = !active && iobRatio > 0.25 &&
            minSinceRetentionEnd < POST_ACTIVITY_BUFFER_MIN

        return ActivityResult(
            insulinPercentage = insulinPerc,
            targetAdjust      = if (active) TARGET_ADJUST else if (postBuffer) 0.5 else 0.0,
            isActive          = active || postBuffer,
            log               = prefix
        )
    }

    private fun loadRetention(): Int              = prefs.getInt("retention", 0)
    private fun saveRetention(value: Int)         { prefs.edit().putInt("retention", value).apply() }
    private fun loadRetentionEndTime(): Long       = prefs.getLong("retention_end_time", 0L)
    private fun saveRetentionEndTime(ts: Long)    { prefs.edit().putLong("retention_end_time", ts).apply() }

    private fun reset() {
        saveRetention(0)
        consecutiveStepTriggers = 0
        consecutiveLowTriggers  = 0
    }
}