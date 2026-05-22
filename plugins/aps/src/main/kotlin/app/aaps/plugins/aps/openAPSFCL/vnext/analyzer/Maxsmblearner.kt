package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import android.content.Context
import kotlin.math.abs

/**
 * MaxSmbLearner — leert maxSmbDay en peakIobBrakeSuppressThreshold op basis van
 * maaltijdepisode-uitkomsten.
 *
 * ── Vier triggers in prioriteitsvolgorde ────────────────────────────────────
 *
 * 1. HYPO_VERLAGEN        → maxSMB -0.10U (altijd, ongeacht S)
 * 2. CAP_STRUCTUREEL_TAR  → maxSMB +0.05U (cap ≥ 3× + piek > 12 + earlyBoost actief)
 * 3. S_MINIMUM_VANGNET    → maxSMB -0.05U (S ≤ 86% + maxSMB boven default + geen hoge piek)
 * 4. SKIP                 → geen actie (piek hoog maar cap niet structureel, of boost inactief)
 *
 * ── Ontkoppeling van DFLearner ───────────────────────────────────────────────
 * Trigger 2 vereist earlyBoost actief — zo versterken DFLearner en MaxSmbLearner
 * elkaar niet automatisch. DFLearner verhoogt S, MaxSmbLearner verhoogt cap alleen
 * als de cap daarna nog steeds de limiterende factor is.
 */
object MaxSmbLearner {

    private const val PREFS_NAME = "max_smb_learner_prefs"

    private const val KEY_MAX_SMB_DAY   = "max_smb_day_learned"
    // Geleerde fractie t.o.v. manualMaxSmb (1.0 = 100% van handmatige instelling)
    private const val KEY_MAX_SMB_FRAC  = "max_smb_day_frac"
    private const val KEY_IOB_BRAKE     = "iob_brake_learned"
    private const val KEY_LAST_SMB_TS   = "max_smb_last_ts"
    private const val KEY_LAST_BRAKE_TS = "iob_brake_last_ts"
    private const val KEY_AUTO          = "max_smb_auto_enabled"
    private const val KEY_HISTORY       = "max_smb_history"
    private const val KEY_LAST_MANUAL   = "max_smb_last_manual"  // detecteer profiel-wijzigingen

    // Vaste grenzen voor IOB-remdrempel — die schalen niet mee met gebruiker
    const val IOB_BRAKE_MIN     = 0.35
    const val IOB_BRAKE_MAX     = 0.55
    const val IOB_BRAKE_DEFAULT = 0.42

    // MaxSMB: absolute vloer en plafond als vangnet (normaal overschreven door dynamische grenzen)
    const val MAX_SMB_ABSOLUTE_MIN = 0.10
    const val MAX_SMB_ABSOLUTE_MAX = 8.00
    const val MAX_SMB_DEFAULT      = 1.25  // fallback als geen handmatige waarde bekend

    // Percentages voor dynamische grenzen relatief aan handmatige instelling
    const val MAX_SMB_LOWER_FRACTION = 0.50   // ondergrens = 50% van handmatig
    const val MAX_SMB_UPPER_FRACTION = 1.25   // bovengrens = 125% van handmatig

    /** Bereken grenzen dynamisch op basis van handmatige instelling. */
    fun dynamicMin(manualMaxSmb: Double): Double =
        (manualMaxSmb * MAX_SMB_LOWER_FRACTION).coerceIn(MAX_SMB_ABSOLUTE_MIN, MAX_SMB_ABSOLUTE_MAX)

    fun dynamicMax(manualMaxSmb: Double): Double =
        (manualMaxSmb * MAX_SMB_UPPER_FRACTION).coerceIn(MAX_SMB_ABSOLUTE_MIN, MAX_SMB_ABSOLUTE_MAX)

    const val SMB_STEP_UP        = 0.05
    const val SMB_STEP_DOWN_HYPO = 0.10
    private const val SMB_STEP_DOWN_VGNT = 0.05
    const val BRAKE_STEP         = 0.02

    const val PEAK_TOO_HIGH      = 12.0
    private const val CAP_CYCLES_MIN    = 3
    const val HYPO_THRESHOLD     = 4.0
    private const val S_MIN_DREMPEL     = 86
    const val MIN_HOURS_BETWEEN  = 48

    data class LearningResult(
        val oldMaxSmb:   Double,
        val newMaxSmb:   Double,
        val oldIobBrake: Double,
        val newIobBrake: Double,
        val reason:      String,
        val diagnose:    String,
        val tsUtc:       String
    ) {
        val hasChange get() =
            abs(newMaxSmb - oldMaxSmb) > 0.001 ||
                abs(newIobBrake - oldIobBrake) > 0.001
    }

    /**
     * Geeft de geleerde maxSMB als absolute waarde.
     * Intern opgeslagen als fractie van manualMaxSmb zodat verhoging van de
     * handmatige instelling automatisch de geleerde waarde meeschaalt.
     *
     * @param manualMaxSmb de handmatige instelling uit AAPS profiel
     */
    fun getMaxSmbDay(context: Context, manualMaxSmb: Double = MAX_SMB_DEFAULT): Double {
        val frac = prefs(context).getFloat(KEY_MAX_SMB_FRAC, -1f).toDouble()
        return if (frac > 0.0) {
            // Fractie opgeslagen → schaal mee met handmatige instelling
            (frac * manualMaxSmb).coerceIn(dynamicMin(manualMaxSmb), dynamicMax(manualMaxSmb))
        } else {
            // Nog geen fractie opgeslagen → gebruik oude absolute waarde als fallback
            val abs = prefs(context).getFloat(KEY_MAX_SMB_DAY, MAX_SMB_DEFAULT.toFloat()).toDouble()
            abs.coerceIn(dynamicMin(manualMaxSmb), dynamicMax(manualMaxSmb))
        }
    }

    fun getIobBrake(context: Context): Double =
        prefs(context).getFloat(KEY_IOB_BRAKE, IOB_BRAKE_DEFAULT.toFloat()).toDouble()

    fun isAutoEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO, false)

    fun setAutoEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO, enabled).apply()

    fun setMaxSmbDay(context: Context, value: Double, manualMaxSmb: Double = MAX_SMB_DEFAULT) {
        val clamped = value.coerceIn(MAX_SMB_ABSOLUTE_MIN, MAX_SMB_ABSOLUTE_MAX)
        val frac = if (manualMaxSmb > 0.0) clamped / manualMaxSmb else 1.0
        prefs(context).edit()
            .putFloat(KEY_MAX_SMB_DAY, clamped.toFloat())
            .putFloat(KEY_MAX_SMB_FRAC, frac.toFloat())
            .apply()
    }

    fun setIobBrake(context: Context, value: Double) =
        prefs(context).edit()
            .putFloat(KEY_IOB_BRAKE, value.coerceIn(IOB_BRAKE_MIN, IOB_BRAKE_MAX).toFloat())
            .apply()

    fun getHistory(context: Context): List<LearningResult> {
        val raw = prefs(context).getString(KEY_HISTORY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { parseResult(it) }
    }

    fun evaluate(
        context: Context,
        metrics: EpisodeMetrics,
        manualMaxSmb: Double = MAX_SMB_DEFAULT,
        forceApply: Boolean = false
    ): LearningResult? {
        val p = prefs(context)

        // ── Detecteer profiel-wijziging (bijv. U200 correctie) ────────────
        // Als manualMaxSmb significant verschilt van de laatste bekende waarde,
        // reset de geleerde waarde naar het midden van het nieuwe bereik.
        val lastManual = p.getFloat(KEY_LAST_MANUAL, MAX_SMB_DEFAULT.toFloat()).toDouble()
        // Drempel verlaagd van 15% naar 8% — voorkomt dat stapsgewijze verhogingen
        // de reset omzeilen. Met fractie-opslag is reset nu minder kritisch want
        // getMaxSmbDay schaalt toch al mee, maar reset zorgt voor een nette herstart.
        if (kotlin.math.abs(manualMaxSmb - lastManual) > manualMaxSmb * 0.08) {
            // Profiel significant gewijzigd — reset naar midden van nieuw bereik
            val resetValue = (dynamicMin(manualMaxSmb) + dynamicMax(manualMaxSmb)) / 2.0
            val resetFrac = resetValue / manualMaxSmb
            p.edit()
                .putFloat(KEY_MAX_SMB_DAY,  resetValue.toFloat())
                .putFloat(KEY_MAX_SMB_FRAC, resetFrac.toFloat())
                .putFloat(KEY_LAST_MANUAL,  manualMaxSmb.toFloat())
                .apply()
            // Geef direct een resultaat terug zodat ConfigOverrideWriter
            // de nieuwe waarde naar AAPS schrijft via de bridge
            val oldVal = lastManual.coerceIn(dynamicMin(lastManual), dynamicMax(lastManual))
            return LearningResult(
                oldMaxSmb   = oldVal,
                newMaxSmb   = resetValue,
                oldIobBrake = getIobBrake(context),
                newIobBrake = getIobBrake(context),
                reason      = "Profiel gewijzigd (${"%+.2f".format(manualMaxSmb - lastManual)}U) " +
                    "→ reset naar ${"%+.2f".format(resetValue)}U",
                diagnose    = "PROFIEL_RESET",
                tsUtc       = java.time.Instant.now().toString()
            )
        } else {
            p.edit().putFloat(KEY_LAST_MANUAL, manualMaxSmb.toFloat()).apply()
        }

        val currentMaxSmb = getMaxSmbDay(context, manualMaxSmb)
        val currentBrake  = getIobBrake(context)

        // Dynamische grenzen gebaseerd op handmatige instelling
        val smbMin = dynamicMin(manualMaxSmb)
        val smbMax = dynamicMax(manualMaxSmb)
        val nowMs         = System.currentTimeMillis()

        val hypoNaEpisode  = metrics.minBgInWindow < HYPO_THRESHOLD
        val peakTooHigh    = metrics.peakBg > PEAK_TOO_HIGH
        val capStructureel = metrics.capReachedCycles >= CAP_CYCLES_MIN
        val boostWasActief = metrics.earlyBoostWasActive
        val sOpMinimum     = metrics.currentSterkte <= S_MIN_DREMPEL
        // "boven standaard" = boven de handmatige instelling (niet de hardcoded default)
        val maxSmbBovenStd = currentMaxSmb > manualMaxSmb * 0.95

        var newMaxSmb = currentMaxSmb
        var newBrake  = currentBrake
        val diagnose: String

        when {
            // 1. Hypo → altijd direct verlagen
            hypoNaEpisode -> {
                diagnose  = "HYPO_VERLAGEN"
                newMaxSmb = (currentMaxSmb - SMB_STEP_DOWN_HYPO).coerceIn(smbMin, smbMax)
                if (currentBrake > IOB_BRAKE_DEFAULT + 0.001)
                    newBrake = (currentBrake - BRAKE_STEP).coerceIn(IOB_BRAKE_MIN, IOB_BRAKE_MAX)
            }

            // 2. Cap structureel bereikt + piek te hoog + earlyBoost actief
            peakTooHigh && capStructureel && boostWasActief -> {
                if (currentMaxSmb < smbMax - 0.001) {
                    diagnose  = "CAP_STRUCTUREEL_TAR"
                    newMaxSmb = (currentMaxSmb + SMB_STEP_UP).coerceIn(smbMin, smbMax)
                } else {
                    diagnose = "CAP_MAX_SMB_PLAFOND"
                    newBrake = (currentBrake + BRAKE_STEP).coerceIn(IOB_BRAKE_MIN, IOB_BRAKE_MAX)
                }
            }

            // 3. S op minimum + maxSMB boven startwaarde + geen hoge piek
            // maxSMB als vangnet verlagen — S kan niet verder omlaag
            sOpMinimum && maxSmbBovenStd && !peakTooHigh -> {
                diagnose  = "S_MINIMUM_VANGNET"
                newMaxSmb = (currentMaxSmb - SMB_STEP_DOWN_VGNT).coerceIn(smbMin, smbMax)
            }

            // 4. Piek hoog maar cap niet structureel bereikt
            // → S/T/V probleem, niet maxSMB. DFLearner pakt dit op.
            peakTooHigh && !capStructureel -> {
                diagnose = "SKIP_CAP_NIET_STRUCTUREEL"
            }

            // 5. Piek hoog + cap actief maar earlyBoost niet actief
            peakTooHigh && capStructureel && !boostWasActief -> {
                diagnose = "SKIP_BOOST_NIET_ACTIEF"
            }

            else -> {
                diagnose = "OK_GEEN_AANPASSING"
            }
        }

        if (diagnose.startsWith("SKIP") || diagnose == "OK_GEEN_AANPASSING") return null

        val lastSmbTs   = prefs(context).getLong(KEY_LAST_SMB_TS, 0L)
        val lastBrakeTs = prefs(context).getLong(KEY_LAST_BRAKE_TS, 0L)

        val smbCoolOk   = (nowMs - lastSmbTs)   / 3_600_000L >= MIN_HOURS_BETWEEN
        val brakeCoolOk = (nowMs - lastBrakeTs) / 3_600_000L >= MIN_HOURS_BETWEEN

        val effMaxSmb = if (abs(newMaxSmb - currentMaxSmb) > 0.001 && smbCoolOk)
            newMaxSmb else currentMaxSmb
        val effBrake  = if (abs(newBrake - currentBrake) > 0.001 && brakeCoolOk)
            newBrake  else currentBrake

        if (abs(effMaxSmb - currentMaxSmb) < 0.001 &&
            abs(effBrake  - currentBrake)  < 0.001) return null

        val reden = buildString {
            append("[$diagnose] ")
            append("piek=${"%.1f".format(metrics.peakBg)}mmol ")
            append("cap=${metrics.capReachedCycles}× ")
            append("rem=${metrics.brakeActiveCycles}× ")
            append("boost=${metrics.earlyBoostWasActive} ")
            append("S=${metrics.currentSterkte}% ")
            if (hypoNaEpisode) append("hypo=${"%.1f".format(metrics.minBgInWindow)}mmol ")
            append("→ maxSMB ${"%+.2f".format(effMaxSmb - currentMaxSmb)}U ")
            append("brake ${"%+.3f".format(effBrake - currentBrake)}")
        }

        val result = LearningResult(
            oldMaxSmb   = currentMaxSmb,
            newMaxSmb   = effMaxSmb,
            oldIobBrake = currentBrake,
            newIobBrake = effBrake,
            reason      = reden,
            diagnose    = diagnose,
            tsUtc       = java.time.Instant.ofEpochMilli(nowMs).toString()
        )

        if (isAutoEnabled(context) || forceApply) {
            val edit = prefs(context).edit()
            if (abs(effMaxSmb - currentMaxSmb) > 0.001) {
                val newFrac = if (manualMaxSmb > 0.0) effMaxSmb / manualMaxSmb else 1.0
                edit.putFloat(KEY_MAX_SMB_DAY,  effMaxSmb.toFloat())
                edit.putFloat(KEY_MAX_SMB_FRAC, newFrac.toFloat())
                edit.putLong(KEY_LAST_SMB_TS,   nowMs)
            }
            if (abs(effBrake - currentBrake) > 0.001) {
                edit.putFloat(KEY_IOB_BRAKE,    effBrake.toFloat())
                edit.putLong(KEY_LAST_BRAKE_TS, nowMs)
            }
            edit.apply()
            appendHistory(context, result)
        }

        return result
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun appendHistory(context: Context, result: LearningResult) {
        val existing = getHistory(context).takeLast(19)
        val line = "${result.tsUtc}|${result.oldMaxSmb}|${result.newMaxSmb}|" +
            "${result.oldIobBrake}|${result.newIobBrake}|${result.diagnose}|${result.reason}"
        val all = (existing.map { serializeResult(it) } + line).joinToString("\n")
        prefs(context).edit().putString(KEY_HISTORY, all).apply()
    }

    private fun serializeResult(r: LearningResult) =
        "${r.tsUtc}|${r.oldMaxSmb}|${r.newMaxSmb}|${r.oldIobBrake}|${r.newIobBrake}|${r.diagnose}|${r.reason}"

    private fun parseResult(line: String): LearningResult? {
        return try {
            val p = line.split("|")
            if (p.size < 7) return null
            LearningResult(
                tsUtc       = p[0],
                oldMaxSmb   = p[1].toDouble(),
                newMaxSmb   = p[2].toDouble(),
                oldIobBrake = p[3].toDouble(),
                newIobBrake = p[4].toDouble(),
                diagnose    = p[5],
                reason      = p[6]
            )
        } catch (_: Exception) { null }
    }
}