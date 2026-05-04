package app.aaps.plugins.aps.openAPSFCL

import android.content.Context
import android.content.SharedPreferences
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import org.joda.time.DateTime
import kotlin.math.*

class FCLResistance(
    private val preferences: Preferences,
    private val persistenceLayer: PersistenceLayer,
    private val context: Context  // ← NIEUW: context toegevoegd
) {

    data class ResistentieResult(val resistentie: Double, val log: String)

    private data class ResistancePreset(
        val strengthPercent: Int,   // “gedrag”
        val days: Int,              // stabiliteit
        val hours: Double           // stabiliteit
    )

    // ★★★ RESISTENTIE STATE TRACKING ★★★
    private var currentResistentieFactor: Double = 1.0
    private var currentResistentieLog: String = ""
    private var lastResistentieCalculation: DateTime? = null
    private val RESISTENTIE_CALCULATION_INTERVAL = 15 * 60 * 1000L // 15 minuten
    private var currentTargetMmol: Double = 5.5

    // ★★★ RESISTENTIE OPSLAG ★★★
    private val prefs: SharedPreferences = context.getSharedPreferences("FCL_Learning_Data", Context.MODE_PRIVATE)

    // ★★★ INIT: LAAD OPGESLAGEN RESISTENTIE BIJ OPSTARTEN ★★★
    init {
        currentResistentieFactor = loadCurrentResistance()
    }


    // ★★★ PUBLIC FUNCTIES VOOR STATE ACCESS ★★★
    fun getCurrentResistanceFactor(): Double = currentResistentieFactor
    fun getCurrentResistanceLog(): String = currentResistentieLog

    fun updateTargetMmol(target: Double) {
        currentTargetMmol = target
    }

    fun updateResistentieIndienNodig(isNachtTime: Boolean) {
        val now = DateTime.now()
        val shouldUpdate = lastResistentieCalculation?.let {
            now.millis - it.millis > RESISTENTIE_CALCULATION_INTERVAL
        } ?: true

        if (shouldUpdate) {
            val result = calculateResistentie(isNachtTime, currentTargetMmol)
            currentResistentieFactor = result.resistentie
            currentResistentieLog = result.log
            lastResistentieCalculation = now

            // Opslaan voor externe toegang
            saveCurrentResistance(currentResistentieFactor)
        }
    }

    // ★★★ RESISTENTIE OPSLAG FUNCTIES ★★★
    fun saveCurrentResistance(resistance: Double) {
        try {
            prefs.edit().putFloat("current_resistance", resistance.toFloat()).apply()
        } catch (e: Exception) {
            // Silent fail
        }
    }

    fun loadCurrentResistance(): Double {
        return try {
            prefs.getFloat("current_resistance", 1.0f).toDouble()
        } catch (e: Exception) {
            1.0
        }
    }

    private fun behaviorStrength(behavior: String): Int =
        when (behavior) {
            "OFF" -> 100        // maar we schakelen hem straks echt uit
            "LIGHT" -> 80
            "NORMAL" -> 90
            "STRONG" -> 100
            "AGGRESSIVE" -> 110
            else -> 90
        }

    private fun stabilityWindow(stability: String): Pair<Int, Double> =
        when (stability) {
            "VERY_STABLE" -> 5 to 4.0
            "STANDARD" -> 3 to 3.0
            "RESPONSIVE" -> 2 to 2.0
            else -> 3 to 3.0
        }
    private fun resolveResistancePreset(
        behavior : String,
        stability : String
     ): ResistancePreset {

        val strength = behaviorStrength(behavior)
        val (days, hours) = stabilityWindow(stability)

        return ResistancePreset(
            strengthPercent = strength,
            days = days,
            hours = hours
        )
    }

    private fun resolveDynamicCaps(
        behavior: String,
        stability: String
    ): Pair<Double, Double> {

        val baseCap = when (behavior) {
            "LIGHT"      -> 1.10
            "NORMAL"     -> 1.20
            "STRONG"     -> 1.30
            "AGGRESSIVE" -> 1.40
            else         -> 1.20
        }

        val stabilityBonus = when (stability) {
            "VERY_STABLE" -> +0.10   // ✔ meer vertrouwen → meer ruimte
            "STANDARD"    -> +0.00
            "RESPONSIVE"  -> -0.10   // ✔ minder data → strakker
            else          -> 0.0
        }

        val maxCap = baseCap + stabilityBonus
        val minCap = 2.0 - maxCap   // symmetrisch rond 1.0

        return minCap to maxCap
    }




    // ... (hier komen de bestaande calculateResistentie, calculateCorrectionFactor, getBgHistoryWithStdDev functies) ...
    // ★★★ BESTAANDE FUNCTIES BLIJVEN STAAN ★★★
    fun calculateResistentie(
        isNachtTime: Boolean,
        targetMmol: Double
    ): ResistentieResult {
        val behavior =
            preferences.get(StringKey.fcl_vnext_resistance_behavior)

        val stability =
            preferences.get(StringKey.fcl_vnext_resistance_stability)

        var log_resistentie = ""
        val MinutenDelayresistentie = 30

        val preset = resolveResistancePreset(behavior, stability)

        if (behavior == "OFF") {
            return ResistentieResult(1.0, " → AutoSens: ❌ UIT\n")
        }

        val resistentie_percentage = preset.strengthPercent
        val Dagenresistentie = preset.days
        val Urenresistentie = preset.hours

        val (minRes, maxRes) = resolveDynamicCaps(
            behavior = behavior,
            stability = stability
        )


        val effectiveTarget =
            if (isNachtTime) targetMmol + 0.3
            else targetMmol

        val now = DateTime.now()
        val uurVanDag = now.hourOfDay
        val minuten = now.minuteOfHour
        val minuutTxt = String.format("%02d", minuten)

        log_resistentie +=
            " ● AutoSens preset:\n" +
                "      → Gedrag: $behavior\n" +
                "      → Stabiliteit: $stability\n" +
                "      → Sterkte: ${resistentie_percentage}%\n" +
                "      → Window: $Dagenresistentie dagen × $Urenresistentie uur\n"


        val macht = Math.pow(resistentie_percentage.toDouble(), 1.4) / 2800


        val baseStartToday = now
            .withSecondOfMinute(0)
            .withMillisOfSecond(0)
            .plusMinutes(MinutenDelayresistentie)

        val baseEndToday = baseStartToday
            .plusMinutes((Urenresistentie * 60).toInt())

        val correctionFactors = mutableListOf<Double>()
        val formatter = org.joda.time.format.DateTimeFormat.forPattern("dd-MM")
        val today = DateTime.now()

        var totalBgSum = 0.0
        var totalBgCount = 0

        for (dayOffset in 1..Dagenresistentie) {

            val dayStart = baseStartToday.minusDays(dayOffset)
            val dayEnd   = baseEndToday.minusDays(dayOffset)

            val startTime = dayStart.millis
            val endTime   = dayEnd.millis


            val (bgGem, bgStdDev) = getBgHistoryWithStdDev(startTime, endTime, Urenresistentie.toLong())

            if (bgGem > 0) {
                totalBgSum += bgGem
                totalBgCount++
                val rel_std = (bgStdDev / bgGem * 100).toInt()
                val cf = calculateCorrectionFactor(bgGem, effectiveTarget, macht, rel_std)

                correctionFactors.add(cf)
            }
        }
        val avgBgOverPeriod =
            if (totalBgCount > 0) totalBgSum / totalBgCount else 0.0

        var ResistentieCfEff = 1.0
        if (correctionFactors.isNotEmpty()) {
            var tot_gew_gem = 0
            val weights = listOf(70, 25, 5, 3, 2)

            for (i in correctionFactors.indices) {
                val weight = if (i < weights.size) weights[i] else 1
                ResistentieCfEff += correctionFactors[i] * weight
                tot_gew_gem += weight
            }
            ResistentieCfEff /= tot_gew_gem.toDouble()
        }



        ResistentieCfEff = ResistentieCfEff.coerceIn(minRes, maxRes)



        log_resistentie +=
            "\n ● AutoSens samenvatting:\n" +
                "      → Gem. BG referentie: ${round(avgBgOverPeriod, 1)} mmol/L\n" +
                "      → Target gebruikt: ${round(effectiveTarget, 1)} mmol/L\n" +
                "      → AutoSens factor: ${(ResistentieCfEff * 100).toInt()}%\n" +
                "      → Begrenzing: ${(minRes * 100).toInt()}% – ${(maxRes * 100).toInt()}%\n"

        return ResistentieResult(ResistentieCfEff, log_resistentie)
    }

    private fun calculateCorrectionFactor(bgGem: Double, targetProfiel: Double, macht: Double, rel_std: Int): Double {
        var rel_std_cf = 1.0
        if (bgGem > targetProfiel && rel_std > 0) {
            rel_std_cf = 1.0 / rel_std + 1.0
        }
        var cf = Math.pow(bgGem / targetProfiel, macht) * rel_std_cf
        if (cf < 0.1) cf = 1.0
        return cf
    }

    private fun getBgHistoryWithStdDev(startMillis: Long, endMillis: Long, uren: Long): Pair<Double, Double> {
        val MIN_READINGS_PER_HOUR = 8.0
        val MG_DL_TO_MMOL_L_CONVERSION = 18.0

        // Zorg dat start < end
        val from = min(startMillis, endMillis)
        val to = max(startMillis, endMillis)

        val bgReadings = kotlinx.coroutines.runBlocking {
            persistenceLayer.getBgReadingsDataFromTimeToTime(from, to, false)
        }

        // Verwachte hoeveelheid readings in dit window
        // uren kan 2.0/3.0 zijn maar hier komt Long binnen: use uren als "uren" voor ondergrens
        val minExpected = (MIN_READINGS_PER_HOUR * uren).coerceAtLeast(MIN_READINGS_PER_HOUR)

        if (bgReadings.size < minExpected) {
            return Pair(0.0, 0.0)
        }

        val totalBgValue = bgReadings.sumOf { it.value }
        val bgAverage = (totalBgValue / bgReadings.size) / MG_DL_TO_MMOL_L_CONVERSION

        val variance = bgReadings.sumOf {
            val bgInMmol = it.value / MG_DL_TO_MMOL_L_CONVERSION
            (bgInMmol - bgAverage) * (bgInMmol - bgAverage)
        } / bgReadings.size

        val stdDev = sqrt(variance)
        return Pair(bgAverage, stdDev)
    }


    private fun round(value: Double, digits: Int): Double {
        val scale = Math.pow(10.0, digits.toDouble())
        return Math.round(value * scale) / scale
    }
}