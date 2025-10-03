package app.aaps.plugins.aps.openAPSFCL

import android.annotation.SuppressLint
import android.content.Context
import android.os.Environment
import com.google.gson.Gson
import org.joda.time.DateTime
import org.joda.time.Hours
import org.joda.time.Minutes
import org.joda.time.Days
import kotlin.math.*
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Date

class FCL(context: Context) {

    // Storage interface voor learning data
    interface FCLStorage {

        fun saveLearningProfile(profile: FCLLearningProfile)
        fun loadLearningProfile(): FCLLearningProfile?
        fun saveMealResponseData(mealData: MealResponseData)
        fun loadHistoricalMealData(): List<MealResponseData>
        fun savePendingLearningUpdate(update: LearningUpdate)
        fun loadPendingLearningUpdates(): List<LearningUpdate>
        fun clearPendingLearningUpdates()
        fun savePeakDetectionData(peakData: PeakDetectionData)
        fun loadPeakDetectionData(): List<PeakDetectionData>
        fun savePendingCorrectionUpdate(update: CorrectionUpdate)
        fun loadPendingCorrectionUpdates(): List<CorrectionUpdate>
        fun clearPendingCorrectionUpdates()
        fun saveMealPerformanceResult(result: MealPerformanceResult)
        fun loadMealPerformanceResults(): List<MealPerformanceResult>
        fun saveCorrectionPerformanceResult(result: CorrectionPerformanceResult)
        fun loadCorrectionPerformanceResults(): List<CorrectionPerformanceResult>
        fun resetAllLearningData()
        fun saveCurrentCOB(cob: Double)
    }

    // Android implementatie met geïsoleerde storage
// Vervang de volledige inhoud van AndroidFCLStorage met deze veilige serialisatie-versie
    class AndroidFCLStorage(private val context: Context ) : FCLStorage {
        private val prefs = context.getSharedPreferences("FCL_Learning_Data", Context.MODE_PRIVATE)
        private val gson = Gson()
        private val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS/")
        private val backupFile = File(externalDir, "ANALYSE/fcl_learning_backup.json")
        private val backupMeta = File(externalDir, "ANALYSE/fcl_learning_backup.meta")
        private val backupIntervalMs = 4 * 60 * 60 * 1000L

        // serializable DTOs (timestamp in millis)
        private data class SProfile(
            val personalCarbRatio: Double,
            val personalISF: Double,
            val mealTimingFactors: Map<String, Double>,
            val sensitivityPatterns: Map<Int, Double>,
            val lastUpdatedMillis: Long,
            val learningConfidence: Double,
            val totalLearningSamples: Int,
        )

        private data class SMealResponse(
            val timestamp: Long,
            val carbs: Double,
            val insulinGiven: Double,
            val predictedPeak: Double,
            val actualPeak: Double,
            val timeToPeak: Int,
            val bgStart: Double,
            val bgEnd: Double
        )

        private data class SPendingUpdate(
            val timestamp: Long,
            val detectedCarbs: Double,
            val givenDose: Double,
            val startBG: Double,
            val expectedPeak: Double,
            val mealType: String
        )

        private data class SPeakData(
            val timestamp: Long,
            val bg: Double,
            val trend: Double,
            val acceleration: Double,
            val isPeak: Boolean
        )

        // PERFORMANCE TRACKING STORAGE
        private data class SMealPerformance(
            val timestamp: Long,
            val detectedCarbs: Double,
            val givenDose: Double,
            val startBG: Double,
            val predictedPeak: Double,
            val actualPeak: Double,
            val timeToPeak: Int,
            val bolusPercEarly: Double,
            val bolusPercDay: Double,
            val bolusPercNight: Double,
            val peakDampingFactor: Double,
            val hypoRiskFactor: Double,
            val outcome: String,
            val peakConfidence: Double, // ★★★ NIEUW
            val mealType: String = "unknown" // ★★★ OPTIONEEL
        )


        private data class SCorrectionPerformance(
            val timestamp: Long,
            val givenDose: Double,
            val startBG: Double,
            val predictedDrop: Double,
            val actualDrop: Double,
            val outcome: String
        )

        override fun saveMealPerformanceResult(result: MealPerformanceResult) {
            val current = loadMealPerformanceResultsSerializable().toMutableList()
            current.add(
                SMealPerformance(
                    timestamp = result.timestamp.millis,
                    detectedCarbs = result.detectedCarbs,
                    givenDose = result.givenDose,
                    startBG = result.startBG,
                    predictedPeak = result.predictedPeak,
                    actualPeak = result.actualPeak,
                    timeToPeak = result.timeToPeak,
                    bolusPercEarly = result.parameters.bolusPercEarly,
                    bolusPercDay = result.parameters.bolusPercDay,
                    bolusPercNight = result.parameters.bolusPercNight,
                    peakDampingFactor = result.parameters.peakDampingFactor,
                    hypoRiskFactor = result.parameters.hypoRiskFactor,
                    outcome = result.outcome,
                    peakConfidence = result.peakConfidence, // ★★★ NIEUW
                    mealType = result.mealType // ★★★ OPTIONEEL
                )
            )
            val limited = current.takeLast(100)
            prefs.edit().putString("meal_performance_data", gson.toJson(limited)).apply()
        }


        private fun loadMealPerformanceResultsSerializable(): List<SMealPerformance> {
            val json = prefs.getString("meal_performance_data", null) ?: return emptyList()
            return try {
                gson.fromJson(json, Array<SMealPerformance>::class.java)?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        override fun loadMealPerformanceResults(): List<MealPerformanceResult> {
            val serial = loadMealPerformanceResultsSerializable()
            return serial.map {
                MealPerformanceResult(
                    timestamp = DateTime(it.timestamp),
                    detectedCarbs = it.detectedCarbs,
                    givenDose = it.givenDose,
                    startBG = it.startBG,
                    predictedPeak = it.predictedPeak,
                    actualPeak = it.actualPeak,
                    timeToPeak = it.timeToPeak,
                    parameters = MealParameters(
                        bolusPercEarly = it.bolusPercEarly,
                        bolusPercDay = it.bolusPercDay,
                        bolusPercNight = it.bolusPercNight,
                        peakDampingFactor = it.peakDampingFactor,
                        hypoRiskFactor = it.hypoRiskFactor,
                        timestamp = DateTime(it.timestamp)
                    ),
                    outcome = it.outcome,
                    peakConfidence = it.peakConfidence, // ★★★ NIEUW
                    mealType = it.mealType // ★★★ OPTIONEEL
                )
            }
        }

        override fun saveCorrectionPerformanceResult(result: CorrectionPerformanceResult) {
            val current = loadCorrectionPerformanceResultsSerializable().toMutableList()
            current.add(
                SCorrectionPerformance(
                    timestamp = result.timestamp.millis,
                    givenDose = result.givenDose,
                    startBG = result.startBG,
                    predictedDrop = result.predictedDrop,
                    actualDrop = result.actualDrop,
                    outcome = result.outcome
                )
            )
            val limited = current.takeLast(100)
            prefs.edit().putString("correction_performance_data", gson.toJson(limited)).apply()
        }

        override fun saveCurrentCOB(cob: Double) {
            try {
                prefs.edit().putFloat("current_cob", cob.toFloat()).apply()
                android.util.Log.d("FCL_COB", "COB saved to prefs: ${cob}g")
            } catch (e: Exception) {
                android.util.Log.e("FCL_COB", "Error saving COB to prefs", e)
            }
        }


        private fun loadCorrectionPerformanceResultsSerializable(): List<SCorrectionPerformance> {
            val json = prefs.getString("correction_performance_data", null) ?: return emptyList()
            return try {
                gson.fromJson(json, Array<SCorrectionPerformance>::class.java)?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        override fun loadCorrectionPerformanceResults(): List<CorrectionPerformanceResult> {
            val serial = loadCorrectionPerformanceResultsSerializable()
            return serial.map {
                CorrectionPerformanceResult(
                    timestamp = DateTime(it.timestamp),
                    givenDose = it.givenDose,
                    startBG = it.startBG,
                    predictedDrop = it.predictedDrop,
                    actualDrop = it.actualDrop,
                    outcome = it.outcome
                )
            }
        }


        override fun saveLearningProfile(profile: FCLLearningProfile) {
            try {
                val s = SProfile(
                    personalCarbRatio = profile.personalCarbRatio,
                    personalISF = profile.personalISF,
                    mealTimingFactors = profile.mealTimingFactors,
                    sensitivityPatterns = profile.sensitivityPatterns,
                    lastUpdatedMillis = profile.lastUpdated.millis,
                    learningConfidence = profile.learningConfidence,
                    totalLearningSamples = profile.totalLearningSamples
                )
                val json = gson.toJson(s)
                prefs.edit().putString("learning_profile", json).apply()

                // Backup: alleen opslaan als laatste backup ouder is dan interval
                val now = System.currentTimeMillis()
                val lastBackup = if (backupMeta.exists()) backupMeta.readText().toLongOrNull() ?: 0 else 0
                if (now - lastBackup > backupIntervalMs) {
                    backupFile.writeText(json)
                    backupMeta.writeText(now.toString())
                    android.util.Log.d("FCL", "Backup updated at ${Date(now)}")
                }
            } catch (e: Exception) {
                android.util.Log.e("FCL", "Error saving learning profile", e)
            }
        }



        override fun loadLearningProfile(): FCLLearningProfile? {
            return try {
                val json = prefs.getString("learning_profile", null)
                    ?: if (backupFile.exists()) {
                        android.util.Log.w("FCL", "Prefs empty, loading backup")
                        backupFile.readText()
                    } else return null

                val s = gson.fromJson(json, SProfile::class.java)
                FCLLearningProfile(
                    personalCarbRatio = s.personalCarbRatio,
                    personalISF = s.personalISF,
                    mealTimingFactors = s.mealTimingFactors,
                    sensitivityPatterns = s.sensitivityPatterns,
                    lastUpdated = org.joda.time.DateTime(s.lastUpdatedMillis),
                    learningConfidence = s.learningConfidence,
                    totalLearningSamples = s.totalLearningSamples,
                )
            } catch (e: Exception) {
                android.util.Log.e("FCL", "Error loading learning profile", e)
                null
            }
        }



        // ----- Meal response persistence -----
        private fun loadHistoricalMealDataSerializable(): List<SMealResponse> {
            val json = prefs.getString("meal_response_data", null) ?: return emptyList()
            return try {
                gson.fromJson(json, Array<SMealResponse>::class.java)?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        override fun saveMealResponseData(mealData: MealResponseData) {
            val current = loadHistoricalMealDataSerializable().toMutableList()
            current.add(
                SMealResponse(
                    timestamp = mealData.timestamp.millis,
                    carbs = mealData.carbs,
                    insulinGiven = mealData.insulinGiven,
                    predictedPeak = mealData.predictedPeak,
                    actualPeak = mealData.actualPeak,
                    timeToPeak = mealData.timeToPeak,
                    bgStart = mealData.bgStart,
                    bgEnd = mealData.bgEnd
                )
            )
            val limited = current.takeLast(1000)
            prefs.edit().putString("meal_response_data", gson.toJson(limited)).apply()
        }

        override fun loadHistoricalMealData(): List<MealResponseData> {
            val serial = loadHistoricalMealDataSerializable()
            return serial.map {
                MealResponseData(
                    timestamp = DateTime(it.timestamp),
                    carbs = it.carbs,
                    insulinGiven = it.insulinGiven,
                    predictedPeak = it.predictedPeak,
                    actualPeak = it.actualPeak,
                    timeToPeak = it.timeToPeak,
                    bgStart = it.bgStart,
                    bgEnd = it.bgEnd
                )
            }
        }

        // ----- Pending learning updates persistence -----
        private fun loadPendingLearningUpdatesSerializable(): List<SPendingUpdate> {
            val json = prefs.getString("pending_learning_updates", null) ?: return emptyList()
            return try {
                gson.fromJson(json, Array<SPendingUpdate>::class.java)?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        override fun savePendingLearningUpdate(update: LearningUpdate) {
            val current = loadPendingLearningUpdatesSerializable().toMutableList()
            current.add(
                SPendingUpdate(
                    timestamp = update.timestamp.millis,
                    detectedCarbs = update.detectedCarbs,
                    givenDose = update.givenDose,
                    startBG = update.startBG,
                    expectedPeak = update.expectedPeak,
                    mealType = update.mealType
                )
            )
            prefs.edit().putString("pending_learning_updates", gson.toJson(current)).apply()
        }

        override fun loadPendingLearningUpdates(): List<LearningUpdate> {
            val serial = loadPendingLearningUpdatesSerializable()
            return serial.map {
                LearningUpdate(
                    timestamp = DateTime(it.timestamp),
                    detectedCarbs = it.detectedCarbs,
                    givenDose = it.givenDose,
                    startBG = it.startBG,
                    expectedPeak = it.expectedPeak,
                    mealType = it.mealType
                )
            }
        }

        override fun clearPendingLearningUpdates() {
            prefs.edit().remove("pending_learning_updates").apply()
        }

        override fun savePendingCorrectionUpdate(update: CorrectionUpdate) {
            val key = "pending_correction_updates"
            val current = loadPendingCorrectionUpdates().toMutableList()
            current.add(update)
            val json = gson.toJson(current)
            prefs.edit().putString(key, json).apply()
        }

        override fun loadPendingCorrectionUpdates(): List<CorrectionUpdate> {
            val key = "pending_correction_updates"
            val json = prefs.getString(key, null) ?: return emptyList()
            return try {
                val type = object : TypeToken<List<CorrectionUpdate>>() {}.type
                gson.fromJson(json, type)
            } catch (e: Exception) {
                emptyList()
            }
        }

        override fun clearPendingCorrectionUpdates() {
            val key = "pending_correction_updates"
            prefs.edit().remove(key).apply()
        }


        // ----- Peak detection persistence -----
        private fun loadPeakDetectionDataSerializable(): List<SPeakData> {
            val json = prefs.getString("peak_detection_data", null) ?: return emptyList()
            return try {
                gson.fromJson(json, Array<SPeakData>::class.java)?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        override fun savePeakDetectionData(peakData: PeakDetectionData) {
            val current = loadPeakDetectionDataSerializable().toMutableList()
            current.add(
                SPeakData(
                    timestamp = peakData.timestamp.millis,
                    bg = peakData.bg,
                    trend = peakData.trend,
                    acceleration = peakData.acceleration,
                    isPeak = peakData.isPeak
                )
            )
            val limited = current.takeLast(500)
            prefs.edit().putString("peak_detection_data", gson.toJson(limited)).apply()
        }

        override fun loadPeakDetectionData(): List<PeakDetectionData> {
            val serial = loadPeakDetectionDataSerializable()
            return serial.map {
                PeakDetectionData(
                    timestamp = DateTime(it.timestamp),
                    bg = it.bg,
                    trend = it.trend,
                    acceleration = it.acceleration,
                    isPeak = it.isPeak
                )
            }
        }

        override fun resetAllLearningData() {
            try {
                // Verwijder alle shared preferences keys
                prefs.edit().clear().apply()

                // Verwijder backup files
                if (backupFile.exists()) {
                    backupFile.delete()
                }
                if (backupMeta.exists()) {
                    backupMeta.delete()
                }

                android.util.Log.d("FCL", "All learning data reset successfully")
            } catch (e: Exception) {
                android.util.Log.e("FCL", "Error resetting learning data", e)
            }
        }

    }




    data class MealResponseData(
        val timestamp: DateTime,
        val carbs: Double,
        val insulinGiven: Double,
        val predictedPeak: Double,
        val actualPeak: Double,
        val timeToPeak: Int,
        val bgStart: Double,
        val bgEnd: Double
    )

    data class LearningUpdate(
        val timestamp: DateTime,
        val detectedCarbs: Double,
        val givenDose: Double,
        val startBG: Double,
        val expectedPeak: Double,
        val mealType: String
    )

    data class CorrectionUpdate(
        val insulinGiven: Double,
        val predictedDrop: Double,
        val bgStart: Double,
        val timestamp: DateTime
    )

    data class PeakDetectionData(
        val timestamp: DateTime,
        val bg: Double,
        val trend: Double,
        val acceleration: Double,
        val isPeak: Boolean
    )

    // PERFORMANCE TRACKING DATA CLASSES
    data class MealPerformanceResult(
        val timestamp: DateTime,
        val detectedCarbs: Double,
        val givenDose: Double,
        val startBG: Double,
        val predictedPeak: Double,
        val actualPeak: Double,
        val timeToPeak: Int, // minutes
        val parameters: MealParameters,
        val outcome: String,     // "SUCCESS", "TOO_HIGH", "TOO_LOW"
        val peakConfidence: Double, // ★★★ NIEUW TOEGEVOEGD
        val mealType: String = "unknown" // ★★★ OPTIONEEL: ook handig!
    )

    data class CorrectionPerformanceResult(
        val timestamp: DateTime,
        val givenDose: Double,
        val startBG: Double,
        val predictedDrop: Double,
        val actualDrop: Double,
        val outcome: String // "SUCCESS", "TOO_AGGRESSIVE", "TOO_CONSERVATIVE"
    )

    data class MealParameters(
        val bolusPercEarly: Double,
        val bolusPercDay: Double,
        val bolusPercNight: Double,
        val peakDampingFactor: Double,
        val hypoRiskFactor: Double,
        val timestamp: DateTime
    )

    data class FCLLearningProfile(
        val personalCarbRatio: Double = 1.0,
        val personalISF: Double = 1.0,
        val mealTimingFactors: Map<String, Double> = emptyMap(),
        val sensitivityPatterns: Map<Int, Double> = emptyMap(),
        val lastUpdated: DateTime = DateTime.now(),
        val learningConfidence: Double = 0.0,
        val totalLearningSamples: Int = 0
    ) {


        fun getMealTimeFactor(hour: Int): Double {
            val mealType = when (hour) {
                in 6..10 -> "breakfast"
                in 11..14 -> "lunch"
                in 17..21 -> "dinner"
                else -> "other"
            }
            return mealTimingFactors[mealType]?.takeIf { it.isFinite() && it > 0 } ?: 1.0
        }

        fun getHourlySensitivity(hour: Int): Double {
            return sensitivityPatterns[hour]?.takeIf { it.isFinite() && it > 0 } ?: 1.0
        }

    }


    data class EnhancedInsulinAdvice(
        val dose: Double,
        val reason: String,
        val confidence: Double,
        val predictedValue: Double? = null,
        val mealDetected: Boolean = false,
        val detectedCarbs: Double = 0.0,
        val shouldDeliverBolus: Boolean = false,
        val phase: String = "auto",
        val learningMetrics: LearningMetrics? = null,
        val reservedDose: Double = 0.0,
        val carbsOnBoard: Double = 0.0
    )

    data class LearningMetrics(
        val confidence: Double,
        val samples: Int,
        val carbRatioAdjustment: Double,
        val isfAdjustment: Double,
        val mealTimeFactors: Map<String, Double>,
        val hourlySensitivities: Map<Int, Double>,
       )

    data class BGDataPoint(
        val timestamp: DateTime,
        val bg: Double,
        val iob: Double
    )

    data class ActiveCarbs(
        val timestamp: DateTime,      // start van de maaltijd
        var totalCarbs: Double,       // totaal geschatte koolhydraten
        var absorbedCarbs: Double = 0.0, // reeds opgenomen
        val tau: Double = 40.0        // tijdconstante in minuten (≈ hoe snel opname verloopt)
    ) {
        fun getActiveCarbs(now: DateTime): Double {
            val minutes = (now.millis - timestamp.millis) / 60000.0
            val absorbed = totalCarbs * (1 - exp(-minutes / tau))
            return absorbed.coerceAtMost(totalCarbs)
        }

        fun getRemainingCarbs(now: DateTime): Double {
            return totalCarbs - getActiveCarbs(now)
        }
    }


    data class PredictionResult(
        val value: Double,
        val trend: Double,
        val mealDetected: Boolean,
        val mealInProgress: Boolean,
        val phase: String
    )

    data class InsulinAdvice(
        val dose: Double,
        val reason: String,
        val confidence: Double,
        val predictedValue: Double? = null,
        val mealDetected: Boolean = false,
        val phase: String = "stable"
    )

    data class TrendAnalysis(
        val recentTrend: Double,
        val shortTermTrend: Double,
        val acceleration: Double
    )

    // Configuration properties
    private var NightTime: Boolean = true
    private var resetlearning: Boolean = false
    private var currentBg: Double = 5.5
    private var bolusPercDay: Double = 100.0
    private var bolusPercNight: Double = 100.0
    private var bolusPercEarly: Double = 100.0
    private var bolusPercLate: Double = 100.0
    private var carbSensitivity: Double = 70.0
    private var maxBolus: Double = 1.25
    private var peakDampingPercentage: Double = 50.0
    private var peakDampingFactor: Double = 0.5
    private var tauAbsorptionMinutes: Int = 40
    private var hypoRiskPercentage: Double = 35.0
    private var hypoRiskFactor: Double = 0.35
    private var mealDetectionSensitivity: Double = 0.2
    private var currentCR: Double = 7.0
    private var currentISF: Double = 8.0
    private var maxCrISFCf: Double = 1.1
    private var minCrISFCf: Double = 0.9
    private var hypoThresholdDay: Double = 4.2
    private var hypoThresholdNight: Double = 4.5
    private var hypoRecoveryBGRange: Double = 2.5
    private var hypoRecoveryMinutes: Int = 90
    // ★★★ NIEUW: Hypo recovery learning properties ★★★
    private var hypoRecoveryAggressiveness: Double = 0.7 // 0-1, hoe agressiever herstel
    private var minRecoveryDays: Int = 2
    private var maxRecoveryDays: Int = 7


    private val minIOBForEffect = 0.3
    private val insulinSensitivityFactor = 3.0
    private val dailyReductionFactor = 0.7


    // State tracking
    private var lastMealTime: DateTime? = null
    private var mealInProgress = false
    private var peakDetected = false
    private var mealDetectionState = MealDetectionState.NONE
    private var lastMealDetectionTime: DateTime? = null
    private var lastRiseDetectionTime: DateTime? = null

    // ★★★ NIEUW: Reserved bolus tracking ★★★
    private var pendingReservedBolus: Double = 0.0
    private var pendingReservedCarbs: Double = 0.0
    private var pendingReservedTimestamp: DateTime? = null
    private var pendingReservedPhase: String = "stable"

    private val MEAL_DETECTION_COOLDOWN_MINUTES = 45
    private var lastBolusTime: DateTime? = null




    // Progressieve bolus tracking
    private val activeMeals = mutableListOf<ActiveCarbs>()
    private val pendingLearningUpdates = mutableListOf<LearningUpdate>()
    private val pendingCorrectionUpdates = mutableListOf<CorrectionUpdate>()

    // Learning system
    private val storage: FCLStorage = AndroidFCLStorage(context)
    private var learningProfile: FCLLearningProfile = storage.loadLearningProfile() ?: FCLLearningProfile()


    init {
        loadPreferences(context)

        // ★★★  Reset learning data EERST als nodig ★★★
        resetLearningDataIfNeeded()
        // Robuust laden van learning profile
        try {
            val loadedProfile = storage.loadLearningProfile()
            learningProfile = loadedProfile ?: FCLLearningProfile()

            // NIEUW: Controleer op stagnatie bij opstarten
            checkAndResetConfidenceStagnation()
            // NIEUW: Recalculate confidence bij opstarten
            if (learningProfile.totalLearningSamples > 0) {
                val recalculatedConfidence = calculateLearningConfidence()
                learningProfile = learningProfile.copy(learningConfidence = recalculatedConfidence)}

        } catch (e: Exception) {

            learningProfile = FCLLearningProfile()
        }

        // Laad andere data
        try {
            pendingLearningUpdates.clear()
            pendingLearningUpdates.addAll(storage.loadPendingLearningUpdates())

        } catch (ex: Exception) {

        }

        processPendingLearningUpdates()
        processPendingCorrectionUpdates()
        detectPeaksFromHistoricalData()
        processFallbackLearning()
    }

    private fun resetLearningDataIfNeeded() {
        if (resetlearning) {

            try {
                // Reset storage
                storage.resetAllLearningData()

                // Reset in-memory profile
                learningProfile = FCLLearningProfile()

                // Clear pending updates
                pendingLearningUpdates.clear()
                pendingCorrectionUpdates.clear()
                activeMeals.clear()



            } catch (e: Exception) {

            }
        }
    }


    private fun loadPreferences(context: Context) {
        try {
            val prefs = context.getSharedPreferences("androidaps", Context.MODE_PRIVATE)

            val ResetLearning = prefs.getBoolean("ResetLearning", false)
             setResetLearning(ResetLearning)
            val min_crisf_cf = prefs.getFloat("CarbISF_min_Factor", 0.9F)
             setMinCrISFCf(min_crisf_cf)
            val max_crisf_cf = prefs.getFloat("CarbISF_max_Factor", 1.1F)
             setMaxCrISFCf(max_crisf_cf)
            val perc_day = prefs.getInt("bolus_perc_day", 100)
             setbolusPercDay(perc_day)
            val perc_night = prefs.getInt("bolus_perc_night", 100)
             setbolusPercNight(perc_night)
            val perc_early = prefs.getInt("bolus_perc_early", 100)
             setbolusPercEarly(perc_early)
            val perc_late = prefs.getInt("bolus_perc_late", 100)
             setbolusPercLate(perc_late)
            val carbPercentage = prefs.getInt("carb_percentage", 60)
             setCarbSensitivity(carbPercentage)
            val maxbolus = prefs.getFloat("max_bolus", 1.25F)
             setMaxBolus(maxbolus)
            val mealDetectionSensitivity = prefs.getFloat("meal_detection_sensitivity", 0.25F)
             setMealDetectionSensitivity(mealDetectionSensitivity)
            val peakDampingPercentage = prefs.getInt("peak_damping_percentage", 50)
             setPeakDampingPercentage(peakDampingPercentage)
            val hypoRiskPercentage = prefs.getInt("hypo_risk_percentage", 35)
             setHypoRiskPercentage(hypoRiskPercentage)
            val tauAbsorptionMinutes = prefs.getInt("tau_absorption_minutes", 40)
             setTauAbsorptionMinutes(tauAbsorptionMinutes)


            val hypo_ThresholdDay = prefs.getFloat("hypoThresholdDay", 4.2F)
            sethypoThresholdDay(hypo_ThresholdDay)
            val hypo_ThresholdNight = prefs.getFloat("hypoThresholdNight", 4.5F)
            sethypoThresholdNight(hypo_ThresholdNight)
            val hypo_RecoveryMinutes = prefs.getInt("hypoRecoveryMinutes", 90)
            sethypoRecoveryMinutes(hypo_RecoveryMinutes)
            val hypo_RecoveryBGRange = prefs.getFloat("hypoRecoveryBGRange", 2.5F)
            sethypoRecoveryBGRange(hypo_RecoveryBGRange)
            val hypoRecoveryAggressiveness = prefs.getFloat("hypo_recovery_aggressiveness", 0.7F)
            setHypoRecoveryAggressiveness(hypoRecoveryAggressiveness)
            val minRecoveryDaysPref = prefs.getInt("min_recovery_days", 2)
            setMinRecoveryDays(minRecoveryDaysPref)
            val maxRecoveryDaysPref = prefs.getInt("max_recovery_days", 7)
            setMaxRecoveryDays(maxRecoveryDaysPref)

        } catch (e: Exception) {

           // setCarbSensitivity(70)
        }
    }
    fun setResetLearning(value: Boolean) {
        resetlearning = value
    }
    fun setMinCrISFCf(value: Float) {
        minCrISFCf = value.toDouble()
    }
    fun setMaxCrISFCf(value: Float) {
        maxCrISFCf = value.toDouble()
    }
    fun setCurrentCR(value: Double) {
        currentCR = value
    }
    fun setCurrentISF(value: Double) {
        currentISF = value
    }
    fun setNightTime(value: Boolean) {
        NightTime = value
    }
    fun setbolusPercDay(value: Int) {
        bolusPercDay = value.toDouble()
    }
    fun setbolusPercNight(value: Int) {
        bolusPercNight = value.toDouble()
    }
    private fun getCurrentBolusAggressiveness(): Double {
        return if (NightTime) bolusPercNight else bolusPercDay
    }
    fun setbolusPercEarly(value: Int) {
        bolusPercEarly = value.toDouble()
    }
    fun setbolusPercLate(value: Int) {
        bolusPercLate = value.toDouble()
    }
    fun setCarbSensitivity(value: Int) {
        carbSensitivity = value.toDouble()
    }
    fun setMaxBolus(value: Float) {
        maxBolus = value.toDouble()
    }
    fun setPeakDampingPercentage(value: Int) {
        peakDampingPercentage = value.toDouble()
        peakDampingFactor = (peakDampingPercentage/100)
    }
    fun setTauAbsorptionMinutes(value: Int) {
        tauAbsorptionMinutes = value
    }
    fun setHypoRiskPercentage(value: Int) {
        hypoRiskPercentage = value.toDouble()
        hypoRiskFactor = (hypoRiskPercentage/100)
    }
    fun setMealDetectionSensitivity(value: Float) {
        mealDetectionSensitivity = value.toDouble()
    }

    fun sethypoThresholdDay(value: Float) {
        hypoThresholdDay = value.toDouble()
    }
    fun sethypoThresholdNight(value: Float) {
        hypoThresholdNight = value.toDouble()
    }
    private fun gethypoThreshold(): Double {
        return if (NightTime) hypoThresholdNight else hypoThresholdDay
    }
    fun sethypoRecoveryBGRange(value: Float) {
        hypoRecoveryBGRange = value.toDouble()
    }
    fun sethypoRecoveryMinutes(value: Int) {
        hypoRecoveryMinutes = value
    }
    fun setHypoRecoveryAggressiveness(value: Float) {
        hypoRecoveryAggressiveness = value.toDouble().coerceIn(0.1, 1.0)
    }
    fun setMinRecoveryDays(value: Int) {
        minRecoveryDays = value.coerceAtLeast(1)
    }
    fun setMaxRecoveryDays(value: Int) {
        maxRecoveryDays = value.coerceAtLeast(minRecoveryDays)
    }



    fun getLearningStatus(): String {
        val Day_Night = if (NightTime) "NightTime" else "DayTime"
        val currentHour = DateTime.now().hourOfDay

        // ★★★ NIEUW: Haal recente meal performance data op ★★★
        val recentMeals = storage.loadMealPerformanceResults().takeLast(5).reversed()
        val mealPerformanceSummary = if (recentMeals.isNotEmpty()) {
            recentMeals.joinToString("\n    ") { meal ->
                "${meal.timestamp.toString("HH:mm")} | ${meal.mealType.padEnd(10)} | " +
                    "${meal.detectedCarbs.toInt()}g | Peak: ${"%.1f".format(meal.actualPeak)} | " +
                    "Conf: ${(meal.peakConfidence * 100).toInt()}% | ${meal.outcome}"
            }
        } else {
            "    No meal data yet"
        }

        // ★★★ NIEUW: Bereken gemiddelde peak confidence ★★★
        val avgPeakConfidence = if (recentMeals.isNotEmpty()) {
            recentMeals.map { it.peakConfidence }.average()
        } else {
            0.0
        }

        return """
    FCL Learning Status:
    - Last Updated: ${learningProfile.lastUpdated.toString("dd-MM-yyyy  HH:mm")}
    - Confidence: ${(learningProfile.learningConfidence * 100).toInt()}%
    - Samples: ${learningProfile.totalLearningSamples}
    - Reset Learning: ${resetlearning}
    
    - Pending Reserved Bolus: ${"%.2f".format(pendingReservedBolus)}U
    - Pending Reserved Carbs: ${"%.1f".format(pendingReservedCarbs)}g
    - Reserved Since: ${pendingReservedTimestamp?.toString("HH:mm") ?: "None"}
    
    - Avg Peak Confidence: ${(avgPeakConfidence * 100).toInt()}%
        
    - Carb Ratio Adjustment: ${round(learningProfile.personalCarbRatio, 2)}
    - ISF Adjustment: ${round(learningProfile.personalISF, 2)}
    - Current Meal Factor: ${round(learningProfile.getMealTimeFactor(currentHour), 2)}
    - Current Sensitivity: ${round(learningProfile.getHourlySensitivity(currentHour), 2)}
   
   -- Settings ---
    - CR ISF Adjustement range:  ${round(minCrISFCf,2)} - ${round(maxCrISFCf,2)} 
    - Max Bolus: ${round(maxBolus,2)}U
    - Bolus %: ${Day_Night} → ${getCurrentBolusAggressiveness().toInt()}% 
        
    - Early Rise %:${bolusPercEarly.toInt()}%
    - Late Rise %:${bolusPercLate.toInt()}%
    - Peak Damping %: ${peakDampingPercentage.toInt()}%
    - Hypo Risk %: ${hypoRiskPercentage.toInt()}%
                
    - Carb Calc %: ${carbSensitivity.toInt()}%
    - Carb abs. time: ${tauAbsorptionMinutes}min
    - mealDetection Sens: ${round(mealDetectionSensitivity,2)} mmol/l/5min
    
    - Hypo threshold: ${round(gethypoThreshold(),1)}mmol/l
    - Hypo recover range: ${round(gethypoThreshold(),1)}-${round(gethypoThreshold()+hypoRecoveryBGRange,1)}mmol/l
    - Hypo recovery time: ${hypoRecoveryMinutes}min
    - Hypo Recovery Aggressiveness: ${(hypoRecoveryAggressiveness * 100).toInt()}%
    - Recovery Days Range: $minRecoveryDays-$maxRecoveryDays days
    
    -- Recent Meal Performance --
    $mealPerformanceSummary
                
    Meal Time Factors:
    ${learningProfile.mealTimingFactors.entries.joinToString("\n    ") {"${it.key.padEnd(10)}: ${round(it.value, 2)}" }}
    
    Hourly Sensitivities:
    ${learningProfile.sensitivityPatterns.entries.sortedBy { it.key }.joinToString("\n    ") {"${it.key.toString().padStart(2)}:00: ${round(it.value, 2)}" }}
    
    """.trimIndent()
    }


    // ★★★ VERVANG DE BESTAANDE PIEKDETECTIE ★★★
    private fun detectPeaksFromHistoricalData() {
        val historicalData = storage.loadPeakDetectionData()
        if (historicalData.isEmpty()) return
        if (pendingLearningUpdates.isEmpty()) return

        // Gebruik de nieuwe geavanceerde piekdetectie
        val peaks = advancedPeakDetection(
            historicalData.map {
                BGDataPoint(timestamp = it.timestamp, bg = it.bg, iob = 0.0)
            }
        )

        if (peaks.isEmpty()) return

        val processed = mutableListOf<LearningUpdate>()

        // Loop over een kopie van pendingLearningUpdates
        for (update in ArrayList(pendingLearningUpdates)) {
            var bestMatch: PeakDetectionData? = null
            var bestScore = 0.0

            for (peak in peaks) {
                val minutesDiff = Minutes.minutesBetween(update.timestamp, peak.timestamp).minutes
                val timeScore = when {
                    minutesDiff in 45..150 -> 1.0  // Optimale timing
                    minutesDiff in 30..180 -> 0.7  // Acceptabele timing
                    else -> 0.0
                }

                // BG patroon matching
                val bgScore = 1.0 - min(1.0, abs(peak.bg - update.expectedPeak) / 5.0)

                val totalScore = timeScore * 0.6 + bgScore * 0.4

                if (totalScore > bestScore && totalScore > 0.5) {
                    bestScore = totalScore
                    bestMatch = peak
                }
            }

            bestMatch?.let { peak ->
                try {
                    updateLearningFromMealResponse(
                        detectedCarbs = update.detectedCarbs,
                        givenDose = update.givenDose,
                        predictedPeak = update.expectedPeak,
                        actualPeak = peak.bg,
                        bgStart = update.startBG,
                        bgEnd = peak.bg,
                        mealType = update.mealType,
                        startTimestamp = update.timestamp,
                        peakTimestamp = peak.timestamp
                    )
                    processed.add(update)
                } catch (ex: Exception) {
                    android.util.Log.e("FCL", "Error in peak-based learning", ex)
                }
            }
        }

        if (processed.isNotEmpty()) {
            pendingLearningUpdates.removeAll(processed.toSet())
            storage.clearPendingLearningUpdates()
            pendingLearningUpdates.forEach { storage.savePendingLearningUpdate(it) }
        }
    }

    // ★★★ NIEUWE GEAVANCEERDE PIEKDETECTIE ★★★
    private fun advancedPeakDetection(historicalData: List<BGDataPoint>): List<PeakDetectionData> {
        if (historicalData.size < 5) return emptyList()

        val smoothedData = applyExponentialSmoothing(historicalData, alpha = 0.3)
        val peaks = mutableListOf<PeakDetectionData>()

        for (i in 2 until smoothedData.size - 2) {
            val window = smoothedData.subList(i-2, i+3)
            val (isPeak, confidence) = analyzePeakCharacteristics(window)

            if (isPeak && confidence > 0.7) {
                val originalData = historicalData[i]
                peaks.add(PeakDetectionData(
                    timestamp = originalData.timestamp,
                    bg = originalData.bg,
                    trend = calculateTrendBetweenPoints(historicalData[i-1], historicalData[i]),
                    acceleration = calculateAcceleration(historicalData, 2),
                    isPeak = true
                ))
            }
        }

        return filterFalsePeaks(peaks, historicalData)
    }

    private fun applyExponentialSmoothing(data: List<BGDataPoint>, alpha: Double): List<BGDataPoint> {
        if (data.isEmpty()) return emptyList()

        val smoothed = mutableListOf<BGDataPoint>()
        var smoothedBG = data.first().bg

        data.forEach { point ->
            smoothedBG = alpha * point.bg + (1 - alpha) * smoothedBG
            smoothed.add(point.copy(bg = smoothedBG))
        }

        return smoothed
    }

    private fun analyzePeakCharacteristics(window: List<BGDataPoint>): Pair<Boolean, Double> {
        if (window.size < 5) return Pair(false, 0.0)

        val preRise = window[2].bg - window[0].bg
        val postDecline = window[2].bg - window[4].bg

        // Vermijd deling door nul
        val maxChange = max(preRise, postDecline).coerceAtLeast(0.1)
        val symmetry = abs(preRise - postDecline) / maxChange

        // Echte pieken hebben symmetrische opbouw en afbouw
        val isSymmetric = symmetry < 0.6
        val hasAdequateRise = preRise > 1.2  // Minstens 1.2 mmol/L stijging
        val hasAdequateDecline = postDecline > 0.6  // Minstens 0.6 mmol/L daling

        val confidence = when {
            isSymmetric && hasAdequateRise && hasAdequateDecline -> 0.9
            isSymmetric && hasAdequateRise -> 0.75
            hasAdequateRise && hasAdequateDecline -> 0.7
            else -> 0.3
        }

        val isPeak = confidence > 0.6 && window[2].bg > window[1].bg && window[2].bg > window[3].bg

        return Pair(isPeak, confidence)
    }

    private fun filterFalsePeaks(
        detectedPeaks: List<PeakDetectionData>,
        historicalData: List<BGDataPoint>
    ): List<PeakDetectionData> {
        val filtered = mutableListOf<PeakDetectionData>()
        val timeThreshold = 30 // minuten tussen pieken

        detectedPeaks.forEach { peak ->
            val isTooClose = filtered.any { existing ->
                Minutes.minutesBetween(existing.timestamp, peak.timestamp).minutes < timeThreshold
            }

            val isSignificant = peak.bg > (historicalData.firstOrNull()?.bg ?: 0.0) + 1.0

            if (!isTooClose && isSignificant) {
                filtered.add(peak)
            }
        }

        return filtered
    }


    private fun processFallbackLearning() {
        // synchroniseer in-memory met storage
        try {
            pendingLearningUpdates.clear()
            pendingLearningUpdates.addAll(storage.loadPendingLearningUpdates())
        } catch (ex: Exception) {

            return
        }

        val now = DateTime.now()
        val historicalData = storage.loadPeakDetectionData()
        if (pendingLearningUpdates.isEmpty() || historicalData.size < 5) return

        val processed = mutableListOf<LearningUpdate>()

        for (update in ArrayList(pendingLearningUpdates)) {
            val minutesSinceUpdate = Minutes.minutesBetween(update.timestamp, now).minutes

            if (minutesSinceUpdate > 120 && minutesSinceUpdate < 360) {
                // zoek hoogste BG in conservatieve window (60..180)
                val bgWindow = historicalData.filter { data ->
                    val m = Minutes.minutesBetween(update.timestamp, data.timestamp).minutes
                    m in 60..180
                }
                val peakEntry = bgWindow.maxByOrNull { it.bg }

                val actualPeak = peakEntry?.bg ?: (update.startBG + 3.0)
                val peakTimestamp = peakEntry?.timestamp ?: update.timestamp.plusMinutes(90)

                try {
                    updateLearningFromMealResponse(
                        detectedCarbs = update.detectedCarbs,
                        givenDose = update.givenDose,
                        predictedPeak = update.expectedPeak,
                        actualPeak = actualPeak,
                        bgStart = update.startBG,
                        bgEnd = actualPeak,
                        mealType = update.mealType,
                        startTimestamp = update.timestamp,
                        peakTimestamp = peakTimestamp,

                    )

                    processed.add(update)
                } catch (ex: Exception) {

                }
            }
        }

        if (processed.isNotEmpty()) {
            pendingLearningUpdates.removeAll(processed.toSet())
            storage.clearPendingLearningUpdates()
            pendingLearningUpdates.forEach { storage.savePendingLearningUpdate(it) }
        }
    }


    // Meal detection functie
    fun detectMealFromBG(
        historicalData: List<BGDataPoint>,
        currentBG: Double,
        mealDetectionSensitivity: Double,
        carbRatio: Double,
        currentISF: Double,
        targetBG: Double
    ): Pair<Double, MealDetectionState> {

        val trends = analyzeTrends(historicalData)
        if (shouldBlockMealDetectionForHypoRecovery(currentBG, historicalData, trends)) {
            return Pair(0.0, MealDetectionState.NONE)
        }


        // ★★★ AANGEPAST: Cooldown alleen wanneer BG niet te hoog is ★★★
        if (currentBG <= targetBG + 1.0) {
            lastMealDetectionTime?.let { lastTime ->
                val minutesSinceLast = Minutes.minutesBetween(lastTime, DateTime.now()).minutes
                if (minutesSinceLast < MEAL_DETECTION_COOLDOWN_MINUTES) {
                    return Pair(0.0, MealDetectionState.NONE)
                }
            }
        }
        if (historicalData.size < 4) return Pair(0.0, MealDetectionState.NONE)


        val bg15minAgo = historicalData[historicalData.size - 4].bg
        val delta15 = currentBG - bg15minAgo
        val slope15 = delta15 / 15.0 * 60.0 // mmol/L per uur
        val effectiveCR = getEffectiveCarbRatio()


        val predictedRiseFromCOB = estimateRiseFromCOB(
            effectiveCR = effectiveCR,
            tauAbsorptionMinutes = tauAbsorptionMinutes,
            detectionWindowMinutes = 60  // kan parameteriseerbaar gemaakt worden
        )

        val unexplainedDelta = delta15 - predictedRiseFromCOB

        var localDetectedCarbs = 0.0
        var detectedState = MealDetectionState.NONE

        // === 1. Normale meal-detectie ===
        if (unexplainedDelta > mealDetectionSensitivity) {
            localDetectedCarbs = unexplainedDelta * 10.0  // vuistregel: 1 mmol extra ≈ 10g carbs
            detectedState = MealDetectionState.RISING

        }

        // === 2. Trage stijging extensie ===
        if (slope15 > 0.2 && delta15 > 0.5 && unexplainedDelta <= mealDetectionSensitivity) {
            val cobNow = getCarbsOnBoard()
            if (cobNow > 10.0) {
                localDetectedCarbs += 10.0
                detectedState = MealDetectionState.RISING

            }
        }

        // === 3. Fallback meal-detectie bij stevige stijging ===
        if (detectedState == MealDetectionState.NONE &&
            slope15 > 0.5 &&
            currentBG > targetBG + 2.0
        ) {
            localDetectedCarbs = slope15 * 10.0
            detectedState = MealDetectionState.RISING

        }

        // === 4. COB-correctie ===
        val cobNow = getCarbsOnBoard()
        if (cobNow < 10.0 && slope15 > 0.5) {
            localDetectedCarbs += 20.0
            detectedState = MealDetectionState.RISING

        }

        // ★★★ NIEUW: Update detection time bij succes ★★★
        if (detectedState != MealDetectionState.NONE && localDetectedCarbs > 15) {
            lastMealDetectionTime = DateTime.now()
        }

        return Pair(localDetectedCarbs, detectedState)
    }

    // ★★★ NIEUW: Safety check voor meal detectie boven target ★★★
    private fun canDetectMealAboveTarget(
        currentBG: Double,
        targetBG: Double,
        trends: TrendAnalysis,
        currentIOB: Double
    ): Boolean {
        return when {
            currentBG > targetBG + 3.0 -> false  // Te hoog voor meal detectie
            trends.recentTrend < -1.0 -> false    // Sterk dalend
            currentIOB > 2.5 -> false             // Te veel IOB
            else -> true
        }
    }


    fun estimateRiseFromCOB(
        effectiveCR: Double,
        tauAbsorptionMinutes: Int,
        detectionWindowMinutes: Int = 60  // horizon voor meal detection
    ): Double {
        val now = DateTime.now()
        val remainingCarbs = activeMeals.sumOf { it.getRemainingCarbs(now) }

        // omzetten koolhydraten -> mmol/L stijging
        val mmolPerGram = if (effectiveCR > 0.0) (1.0 / effectiveCR) else 0.0

        // fractie van resterende carbs die in de detectionWindow absorbeert
        val absorptionFraction = min(
            1.0,
            detectionWindowMinutes.toDouble() / tauAbsorptionMinutes.toDouble()
        )

        return remainingCarbs * mmolPerGram * absorptionFraction
    }


    // Store peak detection data for later analysis
    private fun storePeakDetectionData(currentData: BGDataPoint, trends: TrendAnalysis) {
        val deltaThreshold = 0.5 // mmol verschil om op te slaan
        val minMinutes = 5

        val previous = storage.loadPeakDetectionData().lastOrNull()
        val shouldSave = when {
            previous == null -> true
            abs(currentData.bg - previous.bg) >= deltaThreshold -> true
            Minutes.minutesBetween(previous.timestamp, currentData.timestamp).minutes >= minMinutes -> true
            else -> false
        }

        if (!shouldSave) return

        val peakData = PeakDetectionData(
            timestamp = currentData.timestamp,
            bg = currentData.bg,
            trend = trends.recentTrend,
            acceleration = trends.acceleration,
            isPeak = false
        )
        storage.savePeakDetectionData(peakData)
    }


    // Learning functions
    private fun storeMealForLearning(detectedCarbs: Double, givenDose: Double, startBG: Double, expectedPeak: Double, mealType: String) {
        val learningUpdate = LearningUpdate(
            timestamp = DateTime.now(),
            detectedCarbs = detectedCarbs,
            givenDose = givenDose,
            startBG = startBG,
            expectedPeak = expectedPeak,
            mealType = mealType
        )
        // In-memory queue + persistent opslag
        try {
            pendingLearningUpdates.add(learningUpdate)
            storage.savePendingLearningUpdate(learningUpdate)

        } catch (ex: Exception) {

        }
    }


    private fun processPendingLearningUpdates() {
        // synchroniseer in-memory met storage (bestaande logica)
        try {
            pendingLearningUpdates.clear()
            pendingLearningUpdates.addAll(storage.loadPendingLearningUpdates())
        } catch (ex: Exception) {

            return
        }

        val now = DateTime.now()
        // Verwijder updates ouder dan 6 uur (te oud)
        val expired = pendingLearningUpdates.filter { update ->
            Minutes.minutesBetween(update.timestamp, now).minutes > 360
        }
        if (expired.isNotEmpty()) {
            pendingLearningUpdates.removeAll(expired.toSet())
            // persist remaining
            storage.clearPendingLearningUpdates()
            pendingLearningUpdates.forEach { storage.savePendingLearningUpdate(it) }
        }

        // --- NIEUW: probeer meteen pending updates te koppelen aan al aanwezige piek-data ---
        try {
            detectPeaksFromHistoricalData()
        } catch (ex: Exception) {
        }

        // --- NIEUW: probeer fallback-matching voor oudere updates (bestaande functie hergebruiken) ---
        try {
            processFallbackLearning()
        } catch (ex: Exception) {
        }

        // Herlaad in-memory pending list, omdat detectPeaks/processFallback mogelijk storage heeft aangepast
        try {
            pendingLearningUpdates.clear()
            pendingLearningUpdates.addAll(storage.loadPendingLearningUpdates())
        } catch (ex: Exception) {
        }
    }

    private fun processPendingCorrectionUpdates() {
        try {
            pendingCorrectionUpdates.clear()
            pendingCorrectionUpdates.addAll(storage.loadPendingCorrectionUpdates())
        } catch (ex: Exception) {

            return
        }

        val now = DateTime.now()
        val toRemove = mutableListOf<CorrectionUpdate>()

        for (update in pendingCorrectionUpdates) {
            val elapsed = Minutes.minutesBetween(update.timestamp, now).minutes

            // wacht 2–4 uur voor effectmeting
            if (elapsed in 120..240) {
                val bgNow = currentBg ?: continue
                val actualDrop = update.bgStart - bgNow

                updateISFFromCorrectionResponse(
                    givenCorrectionInsulin = update.insulinGiven,
                    predictedDrop = update.predictedDrop,
                    actualDrop = actualDrop,
                    bgStart = update.bgStart,
                    bgEnd = bgNow,
                    startTimestamp = update.timestamp,
                    endTimestamp = now
                )

                toRemove.add(update)
            }

            // opruimen als te oud (>6h)
            if (elapsed > 360) {
                toRemove.add(update)
            }
        }

        if (toRemove.isNotEmpty()) {
            pendingCorrectionUpdates.removeAll(toRemove.toSet())
            storage.clearPendingCorrectionUpdates()
            pendingCorrectionUpdates.forEach { storage.savePendingCorrectionUpdate(it) }
        }

    }

    private fun adaptiveUpdate(
        oldValue: Double,
        observedValue: Double,
        confidence: Double,
        baseAlpha: Double,
        minValue: Double? = null,
        maxValue: Double? = null
    ): Double {
        // VERBETERDE OUTLIER DETECTIE
        if (observedValue <= 0 || observedValue > oldValue * 2.5 || observedValue < oldValue / 2.5) {

            return oldValue
        }

        // DYNAMISCHE ALPHA OP BASIS VAN CONFIDENCE
        val dynamicAlpha = baseAlpha * confidence.coerceIn(0.1, 1.0)

        // EXPONENTIËLE GLADING
        var updated = oldValue * (1 - dynamicAlpha) + observedValue * dynamicAlpha

        // BOUNDARIES
        if (minValue != null) updated = maxOf(minValue, updated)
        if (maxValue != null) updated = minOf(maxValue, updated)

        return updated
    }

    private fun updateLearningFromMealResponse(
        detectedCarbs: Double,
        givenDose: Double,
        predictedPeak: Double,
        actualPeak: Double,
        bgStart: Double,
        bgEnd: Double,
        mealType: String,
        startTimestamp: DateTime,
        peakTimestamp: DateTime,
    ) {
        val adjustedGivenDose = givenDose * (getCurrentBolusAggressiveness() / 100.0)
        val actualRise = actualPeak - bgStart

        // VERBETERDE EFFECTIVITEIT BEREKENING
        val expectedRiseFromCarbs = if (currentCR > 0) detectedCarbs / currentCR else 0.0
        val expectedRiseWithLearning = expectedRiseFromCarbs * learningProfile.personalCarbRatio
        val effectiveness = if (expectedRiseWithLearning > 0)
            actualRise / expectedRiseWithLearning
        else 1.0

        // VERBETERDE CARB RATIO UPDATE
        val observedCarbRatioEffectiveness = if (expectedRiseFromCarbs > 0)
            actualRise / expectedRiseFromCarbs
        else 1.0

        val newCarbRatio = adaptiveUpdate(
            oldValue = learningProfile.personalCarbRatio,
            observedValue = observedCarbRatioEffectiveness,
            confidence = min(1.0, abs(observedCarbRatioEffectiveness - 1.0)),
            baseAlpha = 0.05,
            minValue = minCrISFCf,
            maxValue = maxCrISFCf
        )

        // VERBETERDE ISF UPDATE
        val expectedDropFromInsulin = if (currentISF > 0) adjustedGivenDose * currentISF else 0.0
        val observedISFEffectiveness = if (expectedDropFromInsulin > 0 && actualRise < expectedRiseWithLearning)
            (expectedRiseWithLearning - actualRise) / expectedDropFromInsulin
        else 1.0

        val newISF = adaptiveUpdate(
            oldValue = learningProfile.personalISF,
            observedValue = observedISFEffectiveness,
            confidence = 0.3,
            baseAlpha = 0.02,
            minValue = minCrISFCf,
            maxValue = maxCrISFCf
        )

        // Rest van de functie blijft hetzelfde...
        val newMealFactors = learningProfile.mealTimingFactors.toMutableMap()
        val currentFactor = newMealFactors[mealType] ?: 1.0
        newMealFactors[mealType] = (currentFactor + (effectiveness - 1.0) * 0.1).coerceIn(0.7, 1.3)

        // Sensitivity patterns
        val currentHour = DateTime.now().hourOfDay
        val newSensitivity = learningProfile.sensitivityPatterns.toMutableMap()
        val currentSensitivity = newSensitivity[currentHour] ?: 1.0
        newSensitivity[currentHour] = (currentSensitivity + (effectiveness - 1.0) * 0.05).coerceIn(0.6, 1.4)

        // Confidence

        val newConfidence = calculateLearningConfidence()

        // Update profile
        learningProfile = FCLLearningProfile(
            personalCarbRatio = newCarbRatio,
            personalISF = newISF,
            mealTimingFactors = newMealFactors,
            sensitivityPatterns = newSensitivity,
            lastUpdated = DateTime.now(),
            learningConfidence = newConfidence,
            totalLearningSamples = learningProfile.totalLearningSamples + 1
        )


        storage.saveLearningProfile(learningProfile)

        // LOGGING TOEVOEGEN
        logLearningUpdate(
            detectedCarbs = detectedCarbs,
            effectiveness = effectiveness,
            oldCarbRatio = learningProfile.personalCarbRatio,
            newCarbRatio = newCarbRatio,
            oldISF = learningProfile.personalISF,
            newISF = newISF
        )

        // Save meal response data
        val timeToPeakMinutes = Minutes.minutesBetween(startTimestamp, peakTimestamp).minutes.coerceAtLeast(0)
        val mealData = MealResponseData(
            timestamp = DateTime.now(),
            carbs = detectedCarbs,
            insulinGiven = adjustedGivenDose,
            predictedPeak = predictedPeak,
            actualPeak = actualPeak,
            timeToPeak = timeToPeakMinutes,
            bgStart = bgStart,
            bgEnd = bgEnd
        )
        storage.saveMealResponseData(mealData)
    }

    private fun logLearningUpdate(
        detectedCarbs: Double,

        effectiveness: Double,
        oldCarbRatio: Double,
        newCarbRatio: Double,
        oldISF: Double,
        newISF: Double
    ) {
        println("DEBUG: Learning Update - Carbs: ${"%.1f".format(detectedCarbs)}g")
        println("DEBUG: Effectiveness: ${"%.2f".format(effectiveness)}")
        println("DEBUG: CarbRatio: ${"%.3f".format(oldCarbRatio)} -> ${"%.3f".format(newCarbRatio)}")
        println("DEBUG: ISF: ${"%.3f".format(oldISF)} -> ${"%.3f".format(newISF)}")
        println("DEBUG: Effective CR: ${"%.3f".format(getEffectiveCarbRatio())}")
        println("DEBUG: Effective ISF: ${"%.3f".format(getEffectiveISF())}")
    }


    private fun calculateLearningConfidence(): Double {
        val mealResults = storage.loadMealPerformanceResults()
        val correctionResults = storage.loadCorrectionPerformanceResults()

        // Verlengde periode voor recente samples: 21 dagen i.p.v. 14
        val recentMeals = mealResults.filter {
            Days.daysBetween(it.timestamp, DateTime.now()).days <= 21
        }
        val recentCorrections = correctionResults.filter {
            Days.daysBetween(it.timestamp, DateTime.now()).days <= 21
        }

        val totalRecentSamples = recentMeals.size + recentCorrections.size

        // EXPLICIETE SAMPLE LOGICA
        return when {
            learningProfile.totalLearningSamples < 10 -> {
                // Beginnende fase - lineaire groei van 0% naar 30%
                val linearGrowth = learningProfile.totalLearningSamples / 10.0 * 0.3
                min(0.3, linearGrowth).coerceAtLeast(0.05) // Minimaal 5%
            }

            totalRecentSamples < 5 -> {
                // Behoud bestaande confidence bij weinig recente samples, maar met vloer
                val maintainedConfidence = learningProfile.learningConfidence * 0.8
                max(0.3, maintainedConfidence) // Minimaal 30%
            }

            else -> {
                // Gebruik de bestaande gedetailleerde berekening
                calculateDetailedConfidence(recentMeals, recentCorrections, totalRecentSamples)
            }
        }
    }

    private fun calculateDetailedConfidence(
        recentMeals: List<MealPerformanceResult>,
        recentCorrections: List<CorrectionPerformanceResult>,
        totalRecentSamples: Int
    ): Double {
        var totalScore = 0.0
        var totalWeight = 0.0

        // Meal performance scoring (behoud bestaande logica)
        if (recentMeals.isNotEmpty()) {
            val successRate = recentMeals.count { it.outcome == "SUCCESS" }.toDouble() / recentMeals.size
            val avgPeakError = recentMeals.map { abs(it.actualPeak - it.predictedPeak) }.average()
            val peakAccuracy = max(0.0, 1.0 - (avgPeakError / 3.0)) // Max 3.0 mmol/L error

            val mealScore = (successRate * 0.6) + (peakAccuracy * 0.4)
            totalScore += mealScore * recentMeals.size
            totalWeight += recentMeals.size
        }

        // Correction performance scoring (behoud bestaande logica)
        if (recentCorrections.isNotEmpty()) {
            val successRate = recentCorrections.count { it.outcome == "SUCCESS" }.toDouble() / recentCorrections.size
            val dropAccuracy = recentCorrections.map {
                val relativeError = abs(it.actualDrop - it.predictedDrop) / max(1.0, it.predictedDrop)
                max(0.0, 1.0 - relativeError)
            }.average()

            val correctionScore = (successRate * 0.7) + (dropAccuracy * 0.3)
            totalScore += correctionScore * recentCorrections.size
            totalWeight += recentCorrections.size
        }

        val baseConfidence = if (totalWeight > 0) totalScore / totalWeight else 0.0

        // NIEUWE: Sample bonus berekening
        val sampleBonus = calculateSampleBonus(totalRecentSamples, learningProfile.totalLearningSamples)

        // NIEUWE: Time decay compensation - minder agressieve decay
        val timeCompensation = if (learningProfile.totalLearningSamples > 50) 0.1 else 0.0

        val finalConfidence = (baseConfidence + sampleBonus + timeCompensation)
            .coerceIn(0.0, 1.0)

        // DEBUG LOGGING
        android.util.Log.d("FCL_Confidence_Detailed",
                           "Base: ${(baseConfidence * 100).toInt()}% " +
                               "SampleBonus: ${(sampleBonus * 100).toInt()}% " +
                               "RecentSamples: $totalRecentSamples " +
                               "TotalSamples: ${learningProfile.totalLearningSamples} " +
                               "Final: ${(finalConfidence * 100).toInt()}%"
        )

        return finalConfidence
    }

    private fun calculateSampleBonus(recentSamples: Int, totalSamples: Int): Double {
        // Bonus gebaseerd op recente samples
        val recentBonus = when {
            recentSamples >= 20 -> 0.3
            recentSamples >= 15 -> 0.25
            recentSamples >= 10 -> 0.2
            recentSamples >= 5 -> 0.15
            else -> 0.1
        }

        // Bonus gebaseerd op totale samples (minder streng)
        val totalBonus = min(0.2, totalSamples / 200.0) // Bij 200 samples = 20% bonus

        return recentBonus + totalBonus
    }

    // NIEUWE FUNCTIE: Controleer en reset confidence stagnatie
    private fun checkAndResetConfidenceStagnation() {
        val currentConfidence = learningProfile.learningConfidence
        val totalSamples = learningProfile.totalLearningSamples

        // Als we veel samples hebben maar lage confidence, reset de berekening
        if (totalSamples > 30 && currentConfidence < 0.3) {
            android.util.Log.w("FCL", "Confidence stagnation detected - recalculating")

            // Forceer herberekening met huidige data
            val recalculatedConfidence = calculateLearningConfidence()

            if (recalculatedConfidence > currentConfidence) {
                learningProfile = learningProfile.copy(learningConfidence = recalculatedConfidence)
                storage.saveLearningProfile(learningProfile)
                android.util.Log.d("FCL", "Confidence reset to ${(recalculatedConfidence * 100).toInt()}%")
            }
        }
    }


    private fun updateISFFromCorrectionResponse(
        givenCorrectionInsulin: Double,
        predictedDrop: Double,
        actualDrop: Double,
        bgStart: Double,
        bgEnd: Double,
        startTimestamp: DateTime,
        endTimestamp: DateTime
    ) {
        if (givenCorrectionInsulin <= 0.0) return

        val oldISFFactor = learningProfile.personalISF
        val effectiveness = if (predictedDrop > 0.0) actualDrop / predictedDrop else 1.0

        // VERBETERDE ISF BEREKENING
        val expectedDropFromISF = givenCorrectionInsulin * currentISF
        val observedISFEffectiveness = if (expectedDropFromISF > 0)
            actualDrop / expectedDropFromISF
        else 1.0

        val newISFFactor = adaptiveUpdate(
            oldValue = oldISFFactor,
            observedValue = observedISFEffectiveness,
            confidence = min(1.0, abs(observedISFEffectiveness - 1.0)),
            baseAlpha = 0.05,
            minValue = minCrISFCf,
            maxValue = maxCrISFCf
        )

        val newConfidence = calculateLearningConfidence()

        learningProfile = learningProfile.copy(
            personalISF = newISFFactor,
            lastUpdated = DateTime.now(),
            totalLearningSamples = learningProfile.totalLearningSamples + 1,
            learningConfidence = newConfidence
        )

        storage.saveLearningProfile(learningProfile)


        try {
            val outcome = when {
                actualDrop > predictedDrop * 1.5 -> "TOO_AGGRESSIVE"
                actualDrop < predictedDrop * 0.5 -> "TOO_CONSERVATIVE"
                else -> "SUCCESS"
            }

            val correctionResult = CorrectionPerformanceResult(
                timestamp = DateTime.now(),
                givenDose = givenCorrectionInsulin,
                startBG = bgStart,
                predictedDrop = predictedDrop,
                actualDrop = actualDrop,
                outcome = outcome
            )

            storage.saveCorrectionPerformanceResult(correctionResult)

        } catch (e: Exception) {

        }
    }

    // ★★★ NIEUWE HYPO LEARNING FUNCTIES ★★★

    private fun updateLearningFromHypoAfterMeal(
        mealType: String,
        hour: Int,
        detectedCarbs: Double,
        givenDose: Double,
        actualPeak: Double,
        bgEnd: Double
    ) {
        val severity = when {
            bgEnd < 4.0 -> 0.3  // Ernstige hypo
            bgEnd < 4.5 -> 0.2  // Matige hypo
            else -> 0.1          // Milde hypo
        }

        // Pas meal timing factor aan
        val newMealFactors = learningProfile.mealTimingFactors.toMutableMap()
        val currentFactor = newMealFactors[mealType] ?: 1.0
        newMealFactors[mealType] = (currentFactor * (1.0 - severity)).coerceIn(0.7, 1.3)

        // Pas hourly sensitivity aan
        val newSensitivity = learningProfile.sensitivityPatterns.toMutableMap()
        val currentSensitivity = newSensitivity[hour] ?: 1.0
        newSensitivity[hour] = (currentSensitivity * (1.0 - severity * 0.5)).coerceIn(0.6, 1.4)

        // Update profile
        learningProfile = learningProfile.copy(
            mealTimingFactors = newMealFactors,
            sensitivityPatterns = newSensitivity,
            lastUpdated = DateTime.now()
        )

        storage.saveLearningProfile(learningProfile)

        android.util.Log.d("FCL_HYPO_LEARNING",
                           "Hypo learning: $mealType at $hour:00, severity: $severity, " +
                               "factor: ${currentFactor} -> ${newMealFactors[mealType]}")
    }

    private fun getTimeBasedRecoveryFactor(mealType: String, hour: Int): Double {
        val lastHypoTime = storage.loadMealPerformanceResults()
            .filter { it.mealType == mealType && it.outcome == "TOO_LOW" }
            .maxByOrNull { it.timestamp }?.timestamp

        if (lastHypoTime == null) return 1.0 // No hypo history

        val daysSinceLastHypo = Days.daysBetween(lastHypoTime, DateTime.now()).days

        // ★★★ GEBRUIK minRecoveryDays en maxRecoveryDays ★★★
        return when {
            daysSinceLastHypo < minRecoveryDays -> 0.7  // Binnen minimale recovery periode
            daysSinceLastHypo < minRecoveryDays + 1 -> 0.8
            daysSinceLastHypo < minRecoveryDays + 2 -> 0.9
            daysSinceLastHypo < maxRecoveryDays -> 0.95
            else -> 1.0 // Na maxRecoveryDays - volledig hersteld
        }
    }

    private fun getPerformanceBasedRecovery(mealType: String): Double {
        val recentMeals = storage.loadMealPerformanceResults()
            .filter {
                it.mealType == mealType &&
                    Days.daysBetween(it.timestamp, DateTime.now()).days <= maxRecoveryDays // ★★★ Gebruik maxRecoveryDays ★★★
            }

        if (recentMeals.isEmpty()) return 1.0

        // Bereken success ratio
        val successCount = recentMeals.count { it.outcome == "SUCCESS" }
        val totalCount = recentMeals.size
        val successRatio = successCount.toDouble() / totalCount

        // ★★★ STRENGERE CRITERIA BIJ KORTE minRecoveryDays ★★★
        val requiredSuccessRatio = when (minRecoveryDays) {
            1 -> 0.9  // 90% success bij snelle recovery
            2 -> 0.8  // 80% success bij normale recovery
            3 -> 0.7  // 70% success bij langzame recovery
            else -> 0.6 // 60% success bij zeer langzame recovery
        }

        // Bereken gemiddelde piek voor successen
        val successfulPeaks = recentMeals
            .filter { it.outcome == "SUCCESS" }
            .map { it.actualPeak }

        val avgPeak = if (successfulPeaks.isNotEmpty()) successfulPeaks.average() else 8.0

        // ★★★ DYNAMISCH HERSTEL OP BASIS VAN PERFORMANCE EN INSTELLINGEN ★★★
        return when {
            successRatio >= requiredSuccessRatio && avgPeak in 7.0..9.0 -> 1.0    // Perfect
            successRatio >= requiredSuccessRatio * 0.8 && avgPeak in 6.5..10.0 -> 0.95
            successRatio >= requiredSuccessRatio * 0.6 -> 0.9
            else -> 0.8
        }
    }

    private fun getAggressivenessAdjustedRecovery(baseRecovery: Double): Double {
        // Langzamer herstel bij lage aggressiveness
        return when (hypoRecoveryAggressiveness) {
            in 0.8..1.0 -> baseRecovery // Snel herstel
            in 0.5..0.8 -> baseRecovery * 0.8 // Gemiddeld herstel
            else -> baseRecovery * 0.6 // Langzaam herstel
        }
    }

    private fun getHypoAdjustedMealFactor(mealType: String, hour: Int): Double {
        val baseFactor = learningProfile.getMealTimeFactor(hour)
        val hourlySensitivity = learningProfile.getHourlySensitivity(hour)

        // Check recente hypo's voor deze maaltijd
        val recentMeals = storage.loadMealPerformanceResults()
            .filter {
                it.mealType == mealType &&
                    Days.daysBetween(it.timestamp, DateTime.now()).days <= maxRecoveryDays // ★★★ Gebruik maxRecoveryDays ★★★
            }

        val recentHypoCount = recentMeals.count { it.outcome == "TOO_LOW" }
        val recentSuccessCount = recentMeals.count { it.outcome == "SUCCESS" }
        val totalRecentMeals = recentMeals.size

        if (recentHypoCount == 0) {
            // ★★★ GEEN HYPO'S - geleidelijk herstel gebaseerd op minRecoveryDays ★★★
            val recoverySpeed = when (minRecoveryDays) {
                1 -> 0.95  // Snel herstel bij 1 dag
                2 -> 0.90  // Normaal herstel bij 2 dagen
                3 -> 0.85  // Langzaam herstel bij 3 dagen
                else -> 0.8 // Zeer langzaam bij 4+ dagen
            }

            val baseReduction = when (totalRecentMeals) {
                0 -> 1.0  // Geen data, geen aanpassing
                1 -> recoverySpeed // Eerste succes
                2 -> recoverySpeed * 0.95 // Tweede succes
                3 -> recoverySpeed * 0.90 // Derde succes
                else -> recoverySpeed * 0.85 // Verdere successen
            }

            // Sneller herstel bij consistente successen
            val consecutiveSuccessBonus = if (recentSuccessCount >= minRecoveryDays) 0.95 else 1.0
            val recoveryFactor = baseReduction * consecutiveSuccessBonus

            val adjustedFactor = baseFactor * hourlySensitivity * recoveryFactor

            android.util.Log.d("FCL_HYPO_RECOVERY",
                               "Recovery: $mealType, $recentSuccessCount successes in ${maxRecoveryDays}d -> ${(recoveryFactor * 100).toInt()}% recovery")

            return adjustedFactor.coerceIn(0.7, 1.3)
        }

        // ★★★ HYPO GEDETECTEERD - reductie toepassen ★★★
        val reductionFactor = when (recentHypoCount) {
            1 -> 0.9   // 10% reductie
            2 -> 0.8   // 20% reductie
            3 -> 0.7   // 30% reductie
            else -> 0.6 // 40% reductie bij 4+ hypo's
        }

        // Combineer met time-based recovery voor geleidelijk herstel
        val timeRecovery = getTimeBasedRecoveryFactor(mealType, hour)
        val performanceRecovery = getPerformanceBasedRecovery(mealType)
        val aggressivenessRecovery = getAggressivenessAdjustedRecovery(1.0)

        // Neem de meest conservatieve (laagste) recovery factor
        val overallRecovery = min(timeRecovery, min(performanceRecovery, aggressivenessRecovery))

        val finalFactor = baseFactor * hourlySensitivity * reductionFactor * overallRecovery

        android.util.Log.d("FCL_HYPO_RECOVERY",
                           "Reduction: $mealType, $recentHypoCount hypos in ${maxRecoveryDays}d -> ${(reductionFactor * 100).toInt()}% reduction, " +
                               "Time recovery: ${(timeRecovery * 100).toInt()}% (${minRecoveryDays}-${maxRecoveryDays}d), " +
                               "Overall: ${(overallRecovery * 100).toInt()}%")

        return finalFactor.coerceIn(0.5, 1.5)
    }

    private fun getEffectiveCarbRatio(): Double {
        return currentCR * learningProfile.personalCarbRatio
    }

    private fun getEffectiveISF(): Double {
        val hourlySensitivity = learningProfile.getHourlySensitivity(DateTime.now().hourOfDay)
        return currentISF * learningProfile.personalISF * hourlySensitivity
    }


    private fun getSafeDoseWithLearning(
        calculatedDose: Double,
        learnedDose: Double?,
        confidence: Double,
        currentIOB: Double,
        trends: TrendAnalysis,
        phase: String = "stable"
    ): Double {
        val base = when {
            confidence > 0.8 -> learnedDose ?: calculatedDose
            confidence > 0.6 -> (learnedDose ?: calculatedDose) * 0.85
            else -> calculatedDose * 0.7
        }

        // Combinatie van bestaande en nieuwe correcties
        val phaseFactor = getPhaseSpecificAggressiveness(phase)

        val iobFactor = when {
            currentIOB > 2.0 -> 0.45
            currentIOB > 1.0 -> 0.7
            else -> 1.0
        }

        val accelPenalty = if (trends.acceleration > 1.0) 1.1 else 1.0
        val trendPenalty = if (trends.recentTrend > 2.5) 0.95 else 1.0

        return (base * iobFactor / accelPenalty * trendPenalty * phaseFactor).coerceAtLeast(0.0)
    }


    fun getCarbsOnBoard(): Double {
        val now = DateTime.now()
        return activeMeals.sumOf { it.getRemainingCarbs(now) }
    }
    private fun cleanUpMeals() {
        val now = DateTime.now()
        activeMeals.removeIf { it.getRemainingCarbs(now) < 0.1 }
    }


    // ★★★ NIEUWE FUNCTIE VOOR COB MANAGEMENT ★★★
    private fun addOrUpdateActiveMeal(detectedCarbs: Double, timestamp: DateTime) {
        val now = DateTime.now()

        // Opruimen van oude maaltijden
        cleanUpMeals()

        // Zoek of er een recente maaltijd is (binnen 30 minuten)
        val recentMeal = activeMeals.firstOrNull {
            Minutes.minutesBetween(it.timestamp, now).minutes < 30
        }

        if (recentMeal == null) {
            // Nieuwe maaltijd toevoegen
            val newMeal = ActiveCarbs(
                timestamp = timestamp,
                totalCarbs = detectedCarbs,
                tau = tauAbsorptionMinutes.toDouble()
            )
            activeMeals.add(newMeal)
            android.util.Log.d("FCL_COB", "Nieuwe maaltijd: ${detectedCarbs}g om ${timestamp.toString("HH:mm")}")
        } else if (abs(recentMeal.totalCarbs - detectedCarbs) > 5.0) {
            // Significant andere hoeveelheid - bijwerken
            recentMeal.totalCarbs = detectedCarbs
            android.util.Log.d("FCL_COB", "Maaltijd bijgewerkt: ${recentMeal.totalCarbs}g -> ${detectedCarbs}g")
        }

        // Debug info
        val currentCOB = getCarbsOnBoard()
        android.util.Log.d("FCL_COB", "Aantal activeMeals: ${activeMeals.size}, Totaal COB: ${currentCOB}g")
    }


    private fun getMealTypeFromHour(): String {
        val hour = DateTime.now().hourOfDay
        return when (hour) {
            in 6..10 -> "breakfast"
            in 11..14 -> "lunch"
            in 17..21 -> "dinner"
            else -> "other"
        }
    }

    // Trend analysis functions
    private fun analyzeTrends(data: List<BGDataPoint>): TrendAnalysis {
        if (data.isEmpty()) return TrendAnalysis(0.0, 0.0, 0.0)

        // Gebruik smoothing voor trendcalculatie
        val smoothed = smoothBGSeries(data, alpha = 0.35)
        // Bouw een tijdelijk BGDataPoint-list met smoothed values maar behoud timestamps
        val smoothPoints = smoothed.map { (ts, bg) -> BGDataPoint(timestamp = ts, bg = bg, iob = data.find { it.timestamp == ts }?.iob ?: 0.0) }

        val recentTrend = calculateRecentTrend(smoothPoints, 4)  // Langere termijn trend
        val shortTermTrend = calculateShortTermTrend(smoothPoints)  // Nieuwe korte-termijn functie
        val acceleration = calculateAcceleration(smoothPoints, 3)

        // Store peak-detection data only on meaningful events to reduce noise
        val lastPeakSave = storage.loadPeakDetectionData().lastOrNull()
        val shouldSave = lastPeakSave == null || Minutes.minutesBetween(lastPeakSave.timestamp, data.last().timestamp).minutes >= 5 || acceleration < -0.5
        if (shouldSave) {
            storePeakDetectionData(data.last(), TrendAnalysis(recentTrend, shortTermTrend, acceleration))
            // probeer meteen pending learning updates te matchen met nieuw opgeslagen peak data
            try {
                detectPeaksFromHistoricalData()
            } catch (ex: Exception) {

            }
        }


        return TrendAnalysis(recentTrend, shortTermTrend, acceleration)
    }


    private fun smoothBGSeries(data: List<BGDataPoint>, alpha: Double = 0.3): List<Pair<DateTime, Double>> {
        if (data.isEmpty()) return emptyList()
        val res = mutableListOf<Pair<DateTime, Double>>()
        var s = data.first().bg
        res.add(Pair(data.first().timestamp, s))
        for (i in 1 until data.size) {
            s = alpha * data[i].bg + (1 - alpha) * s
            res.add(Pair(data[i].timestamp, s))
        }
        return res
    }


    // Detecteer sensorfouten inclusief compression lows.
   // Geeft null terug als er geen fout is.
    private fun detectSensorIssue(historicalData: List<BGDataPoint>): SensorIssueType? {
        if (historicalData.size < 3) return null

        // --- Grote sprongen ---
        val recent3 = historicalData.takeLast(3)
        val d1 = recent3[1].bg - recent3[0].bg
        val d2 = recent3[2].bg - recent3[1].bg
        if (abs(d1) > 3.0 || abs(d2) > 3.0) {

            return SensorIssueType.JUMP_TOO_LARGE
        }

        // --- Oscillaties ---
        val oscillation = (
            (recent3[0].bg < recent3[1].bg && recent3[2].bg < recent3[1].bg) || // piek
                (recent3[0].bg > recent3[1].bg && recent3[2].bg > recent3[1].bg)    // dal
            ) && (abs(d1) >= 0.5 && abs(d2) >= 0.5)

        if (oscillation) {

            return SensorIssueType.OSCILLATION
        }

        // --- Compression lows ---
        if (historicalData.size >= 5) {
            val recent5 = historicalData.takeLast(5)
            val first = recent5.first()
            val minPoint = recent5.minByOrNull { it.bg } ?: return null
            val drop = first.bg - minPoint.bg
            val minutesToMin = Minutes.minutesBetween(first.timestamp, minPoint.timestamp).minutes
            val rapidDrop = drop > 2.0 && minutesToMin in 5..15 && minPoint.bg < 4.0

            val last = recent5.last()
            val rebound = last.bg - minPoint.bg
            val minutesToLast = Minutes.minutesBetween(minPoint.timestamp, last.timestamp).minutes
            val rapidRebound = rebound > 1.5 && minutesToLast in 5..20

            if (rapidDrop && rapidRebound) {

                return SensorIssueType.COMPRESSION_LOW
            }
        }

        return null
    }


    // ★★★ NIEUW: Meal pattern validation ★★★
    private fun validateMealPattern(historicalData: List<BGDataPoint>): MealConfidenceLevel {
        if (historicalData.size < 6) return MealConfidenceLevel.SUSPECTED

        val recent = historicalData.takeLast(6)

        // Check op consistent stijgend patroon (minimaal 4 van 5 metingen)
        val risingCount = recent.zipWithNext { a, b ->
            b.bg > a.bg + 0.1
        }.count { it }

        // Check op geleidelijke stijging (lage variantie in slopes)
        val slopes = recent.zipWithNext { a, b ->
            val minutesDiff = Minutes.minutesBetween(a.timestamp, b.timestamp).minutes.toDouble()
            if (minutesDiff > 0) (b.bg - a.bg) / minutesDiff * 60.0 else 0.0 // mmol/L per uur
        }

        val slopeVariance = if (slopes.size > 1) {
            val average = slopes.average()
            slopes.map { (it - average) * (it - average) }.average()
        } else 0.0

        // Bepaal confidence level
        return when {
            risingCount >= 4 && slopeVariance < 0.1 -> {

                MealConfidenceLevel.HIGH_CONFIDENCE
            }
            risingCount >= 3 -> {

                MealConfidenceLevel.CONFIRMED
            }
            else -> {

                MealConfidenceLevel.SUSPECTED
            }
        }
    }

    // ★★★ NIEUWE FUNCTIES VOOR BETERE MEAL DETECTION ★★★
    private fun hasSustainedRisePattern(historicalData: List<BGDataPoint>): Boolean {
        if (historicalData.size < 6) return false

        val recent = historicalData.takeLast(6)
        val risingCount = recent.zipWithNext { a, b ->
            b.bg > a.bg + 0.1
        }.count { it }

        // Minimaal 4 van 5 metingen stijgend
        return risingCount >= 4
    }


    private fun calculateVariance(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return values.map { (it - mean) * (it - mean) }.average()
    }

    private fun calculatePeakConfidence(
        historicalData: List<BGDataPoint>,
        detectedCarbs: Double
    ): Double {
        // Gebruik dezelfde logica als calculatePeakConfidenceForMeal maar voor recente data
        if (historicalData.size < 6) return 0.5

        val recentData = historicalData.takeLast(6)
        val rises = recentData.zipWithNext { a, b -> b.bg - a.bg }
        val risingCount = rises.count { it > 0.1 }
        val riseConsistency = risingCount.toDouble() / rises.size

        val riseVariance = calculateVariance(rises)

        // ZELFDE CONFIDENCE BEREKENING ALS calculatePeakConfidenceForMeal
        return when {
            riseConsistency > 0.7 && riseVariance < 0.1 && detectedCarbs > 30 -> 0.9
            riseConsistency > 0.6 && detectedCarbs > 20 -> 0.75
            detectedCarbs > 15 -> 0.6
            else -> 0.5
        }
    }

    private fun calculateMealConfidence(
        historicalData: List<BGDataPoint>,
        detectedCarbs: Double
    ): Double {
        var confidence = 0.5 // Baseline

        if (historicalData.size < 4) return if (detectedCarbs > 20) 0.6 else 0.3

        val recent = historicalData.takeLast(4)
        val rises = recent.zipWithNext { a, b -> b.bg - a.bg }
        val totalRise = recent.last().bg - recent.first().bg

        // Bonus voor consistente stijging
        if (hasSustainedRisePattern(historicalData)) {
            confidence += 0.3

        }

        // Bonus voor substantiële carbs
        if (detectedCarbs > 25) {
            confidence += 0.2

        }

        // Bonus voor gelijkmatige stijging (lage variantie)
        if (rises.size > 1) {
            val variance = calculateVariance(rises)
            if (variance < 0.05) {
                confidence += 0.2

            }
        }

        // Bonus voor significante totale stijging
        if (totalRise > 2.0) {
            confidence += 0.1

        }

        val finalConfidence = confidence.coerceIn(0.0, 1.0)

        return finalConfidence
    }



    // ★★★ VERBETERDE SNACK/MEAL DETECTION ★★★
    private fun distinguishMealFromSnack(
        historicalData: List<BGDataPoint>,
        detectedCarbs: Double
    ): Boolean {
        if (historicalData.size < 4) return detectedCarbs > 20 // Bij twijfel, conservatief

        val recent = historicalData.takeLast(4)
        val totalRise = recent.last().bg - recent.first().bg

        // Verbeterde detectie: focus op consistentie i.p.v. absolute grenzen
        val rises = recent.zipWithNext { a, b -> b.bg - a.bg }
        val consistentRise = rises.all { it > 0.1 } // Alle metingen stijgend
        val riseVariance = if (rises.size > 1) calculateVariance(rises) else 1.0

        // Nieuwe, slimmere heuristiek:
        val isLikelyMeal = when {
            // Duidelijke maaltijd: substantiële carbs + consistente stijging
            detectedCarbs > 30 && consistentRise && totalRise > 1.5 -> {

                true
            }

            // Waarschijnlijke maaltijd: matige carbs + lage variantie in stijging
            detectedCarbs > 20 && riseVariance < 0.1 && totalRise > 1.0 -> {

                true
            }

            // Twijfelgeval: gebruik trendanalyse voor betere beslissing
            detectedCarbs > 15 && hasSustainedRisePattern(historicalData) -> {

                true
            }

            // Waarschijnlijk snack: kleine hoeveelheid + onregelmatige stijging
            else -> {

                false
            }
        }

        return isLikelyMeal
    }



    // ★★★ NIEUW: Real-time bijsturing ★★★
    private fun shouldAdjustOrCancelBolus(
        historicalData: List<BGDataPoint>,
        initialDetection: MealDetectionState
    ): Boolean {
        if (historicalData.size < 4) return false

        val recent = historicalData.takeLast(4)

        // Stijging stopt of keert om
        val plateauOrDecline = recent.zipWithNext { a, b ->
            b.bg <= a.bg + 0.1
        }.count { it } >= 2

        // Onverwachte daling na initiële stijging
        val unexpectedDrop = recent.last().bg < recent[recent.size - 2].bg - 0.5

        val shouldAdjust = plateauOrDecline || unexpectedDrop

        if (shouldAdjust) {

        }

        return shouldAdjust
    }


    private fun calculateRecentTrend(data: List<BGDataPoint>, pointsBack: Int): Double {
        if (data.size <= pointsBack) return 0.0

        // Gebruik echte tijdverschillen i.p.v. aannames over interval
        val currentIndex = data.lastIndex
        val pastIndex = max(0, currentIndex - pointsBack)

        val currentPoint = data[currentIndex]
        val pastPoint = data[pastIndex]

        val timeDiffMinutes = Minutes.minutesBetween(pastPoint.timestamp, currentPoint.timestamp).minutes
        val timeDiffHours = timeDiffMinutes / 60.0
        if (timeDiffHours <= 0) return 0.0

        val bgDiff = currentPoint.bg - pastPoint.bg
        return bgDiff / timeDiffHours  // mmol/L per uur
    }

    // Specifieke functie voor korte-termijn trend (15-20 minuten)
    private fun calculateShortTermTrend(data: List<BGDataPoint>): Double {
        if (data.size < 4) return 0.0

        val current = data.last()

        // Zoek het punt ongeveer 15-20 minuten geleden
        val twentyMinAgo = current.timestamp.minusMinutes(20)
        val fifteenMinAgo = current.timestamp.minusMinutes(15)

        // Zoek het dichtstbijzijnde punt in dit tijdvenster
        val pastPoint = data.findLast {
            it.timestamp.isAfter(twentyMinAgo) && it.timestamp.isBefore(fifteenMinAgo)
        } ?: data.findLast {
            it.timestamp.isBefore(current.timestamp.minusMinutes(10))
        } ?: return 0.0

        val timeDiffMinutes = Minutes.minutesBetween(pastPoint.timestamp, current.timestamp).minutes
        val timeDiffHours = timeDiffMinutes / 60.0
        if (timeDiffHours <= 0) return 0.0

        val bgDiff = current.bg - pastPoint.bg
        return bgDiff / timeDiffHours  // mmol/L per uur
    }

    // Check op consistente daling over recente metingen
    private fun checkConsistentDecline(data: List<BGDataPoint>): Boolean {
        if (data.size < 3) return false

        val recentPoints = data.takeLast(3)
        var declineCount = 0

        for (i in 0 until recentPoints.size - 1) {
            if (recentPoints[i + 1].bg < recentPoints[i].bg - 0.1) {
                declineCount++
            }
        }

        return declineCount >= 2  // Minimaal 2 van de laatste 3 metingen dalend
    }

    private fun shouldBlockBolusForShortTermTrend(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        trends: TrendAnalysis
    ): Boolean {
        if (historicalData.size < 4) return false

        // Korte-termijn trend (laatste 15-20 minuten)
        val shortTermTrend = calculateShortTermTrend(historicalData)

        // Check op consistente daling
        val isConsistentDecline = checkConsistentDecline(historicalData)

        return when {
            // Sterke daling in korte termijn
            shortTermTrend < -3.0 -> true

            // Matige daling + consistente daling over metingen
            shortTermTrend < -2.0 && isConsistentDecline -> true

            // Dalende trend + hoge IOB
            shortTermTrend < -1.0 && currentData.iob > 2.0 -> true

            // Zeer consistente daling (3 van 3 metingen dalend)
            isConsistentDecline && shortTermTrend < -0.5 -> true

            else -> false
        }
    }



    // ★★★ HYPO RECOVERY DETECTION ★★★
    private fun isLikelyHypoRecovery(
        currentBG: Double,
        historicalData: List<BGDataPoint>,
        trends: TrendAnalysis
    ): Boolean {
        if (historicalData.size < 4) return false

        // ★★★ STRENGERE HYPO DEFINITIE: Alleen bij echte hypo's (<4.0) ★★★
        val recentHypo = historicalData.any {
            it.bg < gethypoThreshold() &&
                Minutes.minutesBetween(it.timestamp, DateTime.now()).minutes <= hypoRecoveryMinutes
        }

        if (!recentHypo) return false

        // ★★★ DYNAMISCHE BEREKENING HERSTEL RANGE ★★★
        val recoveryRangeMin = gethypoThreshold()
        val recoveryRangeMax = gethypoThreshold() + hypoRecoveryBGRange
        val isInRecoveryPhase = currentBG in recoveryRangeMin..recoveryRangeMax

        val hasRapidRise = hasRapidRisePatternFromLow(historicalData)
        val isStableHighBG = currentBG > recoveryRangeMax && trends.recentTrend < 1.0

        return recentHypo && isInRecoveryPhase && hasRapidRise && !isStableHighBG
    }

    private fun hasRapidRisePatternFromLow(historicalData: List<BGDataPoint>): Boolean {
        if (historicalData.size < 4) return false

        // ★★★ GEBRUIK INSTELBARE TIJD ★★★
        val recoveryWindowAgo = DateTime.now().minusMinutes(hypoRecoveryMinutes)
        val recentData = historicalData.filter { it.timestamp.isAfter(recoveryWindowAgo) }

        val minPoint = recentData.minByOrNull { it.bg }
        val current = historicalData.last()

        minPoint?.let { lowPoint ->
            val minutesSinceLow = Minutes.minutesBetween(lowPoint.timestamp, current.timestamp).minutes
            val totalRise = current.bg - lowPoint.bg

            // ★★★ INSTELBARE TIJD EN STIJGING ★★★
            val minRecoveryTime = 15
            val maxRecoveryTime = hypoRecoveryMinutes
            val minRiseRequired = 2.0

            val isRapidRecovery = minutesSinceLow in minRecoveryTime..maxRecoveryTime && totalRise > minRiseRequired

            val pointsAfterLow = recentData.filter { it.timestamp.isAfter(lowPoint.timestamp) }
            if (pointsAfterLow.size >= 3) {
                val risingCount = pointsAfterLow.zipWithNext { a, b ->
                    b.bg > a.bg + 0.1
                }.count { it }

                val isConsistentRise = risingCount >= pointsAfterLow.size * 0.6
                return isRapidRecovery && isConsistentRise
            }
        }

        return false
    }

    private fun shouldBlockMealDetectionForHypoRecovery(
        currentBG: Double,
        historicalData: List<BGDataPoint>,
        trends: TrendAnalysis
    ): Boolean {
        // 1. Directe hypo-herstel detectie
        if (isLikelyHypoRecovery(currentBG, historicalData, trends)) {
            android.util.Log.d("FCL_SAFETY", "Meal detection blocked: hypo recovery detected")
            return true
        }

        // 2. ★★★ GEBRUIK hypoThreshold VOOR EXTRA CONSERVATIEVE CHECK ★★★
        if (currentBG < gethypoThreshold() + 1.5 && trends.recentTrend > 1.0) {
            val recentLow = historicalData.any {
                it.bg < gethypoThreshold() + 0.5 &&
                    Minutes.minutesBetween(it.timestamp, DateTime.now()).minutes <= hypoRecoveryMinutes
            }
            if (recentLow) {
                android.util.Log.d("FCL_SAFETY", "Meal detection blocked: recent low + rising")
                return true
            }
        }

        return false
    }



    private fun isTrendReversingToDecline(data: List<BGDataPoint>, trends: TrendAnalysis): Boolean {
        if (data.size < 5) return false

        // Check of de versnelling negatief wordt (afremming)
        val isDecelerating = trends.acceleration < -0.3

        // Check of de korte-termijn trend daalt terwijl lange-termijn nog stijgt
        val shortTermTrend = calculateShortTermTrend(data)
        val isDiverging = shortTermTrend < 0 && trends.recentTrend > 1.0

        // Check of de laatste metingen consistent dalen
        val lastThree = data.takeLast(3)
        val decliningCount = lastThree.zipWithNext().count { (first, second) ->
            second.bg < first.bg - 0.1
        }

        return isDecelerating || isDiverging || decliningCount >= 2
    }

    private fun shouldBlockCorrectionForTrendReversal(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        trends: TrendAnalysis
    ): Boolean {
        if (!isTrendReversingToDecline(historicalData, trends)) return false

        // Bij trend-omkering: veel strengere IOB limieten
        return when {
            currentData.iob > 2.0 -> true  // Blokkeer bij IOB > 2.0
            currentData.iob > 1.5 && trends.recentTrend < 1.0 -> true
            currentData.iob > 1.0 && trends.recentTrend < 0.5 -> true
            else -> false
        }
    }

    private fun isAtPeakOrDeclining(historicalData: List<BGDataPoint>, trends: TrendAnalysis): Boolean {
        if (historicalData.size < 4) return false

        val recentPoints = historicalData.takeLast(4)

        // Peak detectie: hoogste punt gevolgd door daling
        val maxIndex = recentPoints.withIndex().maxByOrNull { it.value.bg }?.index ?: -1
        val isPeak = maxIndex in 1..2 && // Peak ergens in het midden
            recentPoints.last().bg < recentPoints[maxIndex].bg - 0.3

        // Versnellings-based peak detectie
        val isDecelerating = trends.acceleration < -0.5 && trends.recentTrend < 2.0

        return isPeak || isDecelerating
    }

    private fun calculateAcceleration(data: List<BGDataPoint>, points: Int): Double {
        if (data.size <= points * 2) return 0.0

        val currentIndex = data.lastIndex

        // Recente trend (laatste 2 punten)
        val recentSlice = if (data.size >= 2) data.subList(data.lastIndex - 1, data.lastIndex + 1) else emptyList()
        val recentTrend = if (recentSlice.size >= 2) {
            calculateTrendBetweenPoints(recentSlice[0], recentSlice[1])
        } else {
            0.0
        }

        // Vorige trend (punten verder terug)
        val prevStart = max(0, currentIndex - points)
        val prevEnd = min(data.size, prevStart + 2)
        val prevSlice = if (prevEnd > prevStart) data.subList(prevStart, prevEnd) else emptyList()
        val previousTrend = if (prevSlice.size >= 2) {
            calculateTrendBetweenPoints(prevSlice[0], prevSlice[1])
        } else {
            0.0
        }

        val timeDiffMinutes = if (prevSlice.isNotEmpty() && recentSlice.isNotEmpty()) {
            Minutes.minutesBetween(prevSlice.last().timestamp, recentSlice.last().timestamp).minutes.toDouble()
        } else {
            0.0
        }

        if (timeDiffMinutes <= 0) return 0.0

        return (recentTrend - previousTrend) / (timeDiffMinutes / 60.0)
    }

    // Hulpfunctie voor trend tussen twee punten
    private fun calculateTrendBetweenPoints(point1: BGDataPoint, point2: BGDataPoint): Double {
        val timeDiffHours = Minutes.minutesBetween(point1.timestamp, point2.timestamp).minutes / 60.0
        if (timeDiffHours <= 0) return 0.0
        return (point2.bg - point1.bg) / timeDiffHours
    }


    private fun checkConsistentRise(data: List<BGDataPoint>, points: Int): Boolean {
        if (data.size < points + 1) return false
        var risingCount = 0
        val startIndex = maxOf(0, data.size - points - 1)
        for (i in startIndex until data.size - 1) {
            if (data[i + 1].bg > data[i].bg + 0.1) {
                risingCount++
            }
        }
        return risingCount >= points - 1
    }

    private fun predictMealResponse(currentBG: Double, trends: TrendAnalysis, phase: String, minutesAhead: Int): Double {
        val dynamicMaxRise = calculateDynamicMaxRise(currentBG)
        val predictedRise = when (phase) {
            "early_rise" -> min(dynamicMaxRise * 0.6, trends.recentTrend * 0.8)
            "mid_rise" -> min(dynamicMaxRise * 0.8, trends.recentTrend * 0.6)
            "late_rise" -> min(dynamicMaxRise * 0.4, trends.recentTrend * 0.3)
            "peak" -> trends.recentTrend * 0.1
            else -> trends.recentTrend * 0.2
        }
        return currentBG + (predictedRise * minutesAhead / 60)
    }

    private fun predictIOBEffect(currentBG: Double, iob: Double, isf: Double, minutesAhead: Int): Double {
        if (iob <= 0.0 || isf <= 0.0) return currentBG
        val hours = minutesAhead / 60.0
        val baseDrop = (iob / max(1.0, isf)) * insulinSensitivityFactor
        val effectiveDrop = baseDrop * (1 - exp(-hours / 1.5))
        return currentBG - effectiveDrop
    }




    private fun isHypoRiskWithin(minutesAhead: Int, currentBG: Double, iob: Double, isf: Double, thresholdMmol: Double = 4.0): Boolean {
        val predicted = predictIOBEffect(currentBG, iob, isf, minutesAhead)
        return predicted < thresholdMmol
    }

    private fun predictBasalResponse(currentBG: Double, trends: TrendAnalysis, minutesAhead: Int): Double {
        return currentBG + (trends.recentTrend * minutesAhead / 60 * 0.3)
    }

    // Hoofdfunctie voor real-time voorspelling
    fun predictRealTime(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        currentISF: Double,
        minutesAhead: Int = 60,
        carbRatio: Double,
        targetBG: Double
    ): PredictionResult {
        val currentHour = DateTime.now().hourOfDay
        val hourlySensitivity = learningProfile.getHourlySensitivity(currentHour)
        val adjustedISF = currentISF * hourlySensitivity

        val trends = analyzeTrends(historicalData)

        val (detectedCarbs, mealState) = detectMealFromBG(historicalData, currentData.bg, mealDetectionSensitivity, carbRatio, currentISF, targetBG)
        val mealDetected = (mealState != MealDetectionState.NONE)

        val phase = determineMealPhase(trends.recentTrend, trends.shortTermTrend, trends.acceleration)


        updateMealStatusAutomatically(currentData, historicalData, trends, phase, mealState)

        val prediction = when {
            mealInProgress -> predictMealResponse(currentData.bg, trends, phase, minutesAhead)
            currentData.iob > minIOBForEffect -> predictIOBEffect(currentData.bg, currentData.iob, adjustedISF, minutesAhead)
            else -> predictBasalResponse(currentData.bg, trends, minutesAhead)
        }

        return PredictionResult(
            value = prediction.coerceIn(3.5, 20.0),
            trend = trends.recentTrend,
            mealDetected = mealDetected,
            mealInProgress = mealInProgress,
            phase = phase
        )
    }

    private fun getPhaseSpecificAggressiveness(phase: String): Double {
        // Behoud bestaande correcties als basis, maak ze instelbaar
        val baseFactor = when (phase) {
            "early_rise" -> bolusPercEarly / 100.0 * 0.8  // 0.8 was vast, nu instelbaar
            "mid_rise" -> getCurrentBolusAggressiveness() / 100.0 * 0.6  // behoud relatieve verhouding
            "late_rise" -> bolusPercLate / 100.0 * 0.4    // 0.4 was vast, nu instelbaar
            "peak" -> bolusPercLate / 100.0 * 0.3         // 0.3 was vast, nu instelbaar
            else -> getCurrentBolusAggressiveness() / 100.0
        }
        return baseFactor.coerceIn(0.1, 2.0)
    }


    // ★★★ VERBETERDE GEFASEERDE BOLUS MET PIEK-CONFIDENCE ★★★
    fun calculateStagedBolus(
        detectedCarbs: Double,
        carbRatio: Double,
        currentISF: Double,
        currentIOB: Double,
        currentBG: Double,
        targetBG: Double,
        maxBolus: Double,
        phase: String = "stable",
        peakConfidence: Double = 0.5  // Nieuwe parameter: betrouwbaarheid piekdetectie
    ): Triple<Double, Double, String> {

        // ★★★ NIEUW: Pas hypo-adjusted factor toe ★★★
        val mealType = getMealTypeFromHour()
        val currentHour = DateTime.now().hourOfDay
        val hypoAdjustedFactor = getHypoAdjustedMealFactor(mealType, currentHour)

        val effectiveCR = getEffectiveCarbRatio() * hypoAdjustedFactor  // ★★★ Aanpassing hier
        val totalCarbBolus = detectedCarbs / effectiveCR

        var reason = "Carb bolus=${"%.2f".format(totalCarbBolus)}U for ${"%.1f".format(detectedCarbs)}g (effCR=${"%.1f".format(effectiveCR)})"

        // ★★★ DYNAMISCH PERCENTAGE OP BASIS VAN PIEK-CONFIDENCE ★★★
        val baseImmediatePercentage = when {
            peakConfidence > 0.8 -> 0.35  // Hoge confidence: 35% direct
            peakConfidence > 0.6 -> 0.25  // Medium confidence: 25% direct
            else -> 0.15                  // Lage confidence: 15% direct (standaard)
        }

        // ★★★ LEER VAN HISTORISCHE SUCCESSEN ★★★
        val historicalSuccessRate = calculateHistoricalSuccessRate()
        val learningBonus = (historicalSuccessRate - 0.7).coerceIn(-0.2, 0.2) // Max ±20% bonus/malus

        val adjustedPercentage = (baseImmediatePercentage * (1.0 + learningBonus)).coerceIn(0.15, 0.5)

        var immediateBolus = totalCarbBolus * adjustedPercentage
        var reservedBolus = totalCarbBolus * (1 - adjustedPercentage)

        reason += " | PeakConf:${(peakConfidence*100).toInt()}% Hist:${(historicalSuccessRate*100).toInt()}% -> ${(adjustedPercentage*100).toInt()}% direct"

        // ★★★ BG-BASED FACTORS (BEHOUD CONSERVATIEVE AANPAK) ★★★
        val bgBelowTargetFactor = when {
            currentBG < targetBG - 1.0 -> 0.0
            currentBG < targetBG - 0.5 -> 0.2
            currentBG < targetBG -> 0.4
            currentBG < targetBG + 0.4 -> 0.6
            else -> 0.8
        }

        immediateBolus *= bgBelowTargetFactor
        reservedBolus = totalCarbBolus - immediateBolus

        reason += " | BG=${"%.1f".format(currentBG)} -> ${(bgBelowTargetFactor * 100).toInt()}% direct"

        // ★★★ IOB LIMIETEN ★★★
        val dynamicMaxReserved = maxBolus * 1.0
        val currentTotalReserved = pendingReservedBolus + reservedBolus

        if (currentTotalReserved > dynamicMaxReserved) {
            val excess = currentTotalReserved - dynamicMaxReserved
            reservedBolus = max(0.0, reservedBolus - excess)
            immediateBolus += excess
            reason += " | Reserved cap (${"%.2f".format(dynamicMaxReserved)}U): +${"%.2f".format(excess)}U direct"
        }

        // ★★★ IOB ADJUSTMENT ★★★
        if (currentIOB > 0.0) {
            val iobAdjustment = when {
                currentIOB > 2.0 -> currentIOB * 0.9
                currentIOB > 1.0 -> currentIOB * 0.7
                else -> currentIOB * 0.5
            }
            immediateBolus = (immediateBolus - iobAdjustment).coerceAtLeast(0.0)
            reason += " | IOB adj -${"%.2f".format(iobAdjustment)}U"
        }

        // Phase factor
        val phaseFactor = getPhaseSpecificAggressiveness(phase)
        immediateBolus *= phaseFactor
        reservedBolus *= phaseFactor
        reason += " | Phase $phase (x${"%.2f".format(phaseFactor)})"

        // ★★★ CONSERVATIEVE INITIAL BOLUS CAP ★★★
        val initialBolusCap = maxBolus * 0.3
        if (immediateBolus > initialBolusCap) {
            val excess = immediateBolus - initialBolusCap
            immediateBolus = initialBolusCap
            reservedBolus += excess
            reason += " | Initial capped at ${"%.2f".format(initialBolusCap)}U"
        }

        return Triple(roundDose(immediateBolus), roundDose(reservedBolus), reason)
    }

    // ★★★ NIEUWE FUNCTIE: HISTORISCHE SUCCESSRATE ★★★
    private fun calculateHistoricalSuccessRate(): Double {
        val mealResults = storage.loadMealPerformanceResults()
        if (mealResults.isEmpty()) return 0.7 // Standaard bij geen data

        val recentResults = mealResults.takeLast(20) // Laatste 20 maaltijden

        val successCount = recentResults.count { result ->
            when (result.outcome) {
                "SUCCESS" -> true
                "TOO_HIGH" -> result.actualPeak < 12.0 // Niet te hoog
                "TOO_LOW" -> result.actualPeak > 5.0   // Niet te laag
                else -> false
            }
        }

        return successCount.toDouble() / recentResults.size.coerceAtLeast(1)
    }



    private fun shouldReleaseReservedBolus(
        currentBG: Double,
        targetBG: Double,
        trends: TrendAnalysis,
        historicalData: List<BGDataPoint>
    ): Boolean {
        if (historicalData.size < 3) return false

        // ★★★ NIEUW: Check op RECENTE stijging (laatste 2 metingen) ★★★
        val recentPoints = historicalData.takeLast(3)
        val isRecentlyRising = recentPoints.size >= 2 &&
            recentPoints.last().bg > recentPoints[recentPoints.size - 2].bg + 0.1

        // ★★★ NIEUW: Korte-termijn trend check ★★★
        val shortTermTrend = calculateShortTermTrend(historicalData)

        val bgCondition = currentBG > targetBG + 0.7
        val trendCondition = trends.recentTrend > 0.3 && shortTermTrend > 0.1 // Strengere voorwaarden
        val notDeclining = !isAtPeakOrDeclining(historicalData, trends)
        val recentlyRisingCondition = isRecentlyRising && shortTermTrend > 0.0

        // ★★★ COMBINATIE: Alleen vrijgegeven bij CONSISTENTE stijging ★★★
        return bgCondition && (trendCondition || recentlyRisingCondition) && notDeclining
    }

    private fun calculateReservedBolusRelease(
        currentBG: Double,
        targetBG: Double,
        trends: TrendAnalysis,
        historicalData: List<BGDataPoint>  // ★★★ NIEUWE PARAMETER ★★★
    ): Double {
        if (pendingReservedBolus <= 0.0) return 0.0

        // ★★★ NIEUW: Check op recente daling ★★★
        val recentPoints = historicalData.takeLast(3)
        val isRecentlyFalling = recentPoints.size >= 2 &&
            recentPoints.last().bg < recentPoints[recentPoints.size - 2].bg - 0.1

        // ★★★ VEILIGHEID: Geen release bij recente daling ★★★
        if (isRecentlyFalling) {
            android.util.Log.d("FCL_SAFETY", "Reserved bolus blocked: recent decline detected")
            return 0.0
        }

        val bgAboveTarget = currentBG - targetBG

        val releasePercentage = when {
            bgAboveTarget > 3.0 -> 0.8
            bgAboveTarget > 2.0 -> 0.6
            bgAboveTarget > 1.0 -> 0.4
            bgAboveTarget > 0.5 -> 0.2
            else -> 0.1
        }

        // ★★★ STRENGERE TREND VOORWAARDEN ★★★
        val trendBonus = when {
            trends.recentTrend > 2.0 && !isRecentlyFalling -> 0.3
            trends.recentTrend > 1.0 && !isRecentlyFalling -> 0.15
            trends.recentTrend < 0.0 -> -0.3  // ★★★ GROTERE MALUS BIJ DALING ★★★
            else -> 0.0
        }

        val finalReleasePercentage = (releasePercentage + trendBonus).coerceIn(0.05, 0.8)

        // ★★★ EXTRA VEILIGHEID: Verminder release bij dalende korte-termijn trend ★★★
        val shortTermTrend = calculateShortTermTrend(historicalData)
        val shortTermFactor = if (shortTermTrend < 0) 0.3 else 1.0

        val releaseAmount = pendingReservedBolus * finalReleasePercentage * shortTermFactor

        pendingReservedBolus -= releaseAmount
        pendingReservedCarbs = pendingReservedCarbs * (1 - finalReleasePercentage)

        if (pendingReservedBolus < 0.1) {
            pendingReservedBolus = 0.0
            pendingReservedCarbs = 0.0
            pendingReservedTimestamp = null
        }

        return releaseAmount
    }



    private fun decayReservedBolusOverTime() {
        val now = DateTime.now()
        pendingReservedTimestamp?.let { reservedTime ->
            val minutesPassed = Minutes.minutesBetween(reservedTime, now).minutes

            // ✅ NIEUW: Harde timeout na 90 minuten
            if (minutesPassed > 90) {
                pendingReservedBolus = 0.0
                pendingReservedCarbs = 0.0
                pendingReservedTimestamp = null
            } else {
                // Milde afbouw alleen in de eerste 90 minuten
                val hoursPassed = minutesPassed / 60.0
                val decayFactor = exp(-hoursPassed * 0.5) // 50% per uur
                pendingReservedBolus *= decayFactor
                pendingReservedCarbs *= decayFactor

                // Cleanup als bijna verdwenen
                if (pendingReservedBolus < 0.1) {
                    pendingReservedBolus = 0.0
                    pendingReservedCarbs = 0.0
                    pendingReservedTimestamp = null
                }
            }
        }
    }




    // Insulin advice voor closed loop
    fun getInsulinAdvice(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        currentISF: Double,
        targetBG: Double,
        carbRatio: Double,
        currentIOB: Double,
        maxIOB: Double
    ): InsulinAdvice {
        val currentHour = DateTime.now().hourOfDay
        val hourlySensitivity = learningProfile.getHourlySensitivity(currentHour)
        val adjustedISF = currentISF * hourlySensitivity
        if (historicalData.size < 10) {
            return InsulinAdvice(0.0, "Insufficient data", 0.0)
        }

        val trends = analyzeTrends(historicalData)
        val realTimePrediction = predictRealTime(currentData, historicalData, adjustedISF, 60, carbRatio, targetBG)

        if (shouldWithholdInsulin(currentData, trends, targetBG, maxIOB, historicalData)) {
            return InsulinAdvice(0.0, "Safety: BG too low or falling", 0.9)
        }

        if (checkForCarbCorrection(historicalData)) {
            return InsulinAdvice(0.0, "Likely carb correction rise", 0.7)
        }

        if (realTimePrediction.value > targetBG) {
            val dose = calculateDynamicDose(currentData, realTimePrediction.value, currentISF, targetBG)
            if (dose > 0) {
                return InsulinAdvice(
                    dose = dose,
                    reason = "Preventive dose for predicted high: ${"%.1f".format(realTimePrediction.value)} mmol/L",
                    confidence = calculateConfidence(trends),
                    predictedValue = realTimePrediction.value,
                    mealDetected = realTimePrediction.mealDetected,
                    phase = realTimePrediction.phase
                )
            }
        }

        return InsulinAdvice(0.0, "No action needed - within target range", 0.8)
    }

    private fun calculateDynamicDose(
        currentData: BGDataPoint,
        predictedValue: Double,
        currentISF: Double,
        targetBG: Double
    ): Double {
        val excess = predictedValue - targetBG
        val requiredCorrection = excess / currentISF
        val effectiveIOB = max(0.0, currentData.iob - 0.5)
        val netCorrection = max(0.0, requiredCorrection - effectiveIOB)
        val conservativeDose = netCorrection * 0.6 * dailyReductionFactor
        val limitedDose = min(conservativeDose, maxBolus)
        return roundDose(limitedDose)
    }

    private fun shouldWithholdInsulin(
        currentData: BGDataPoint,
        trends: TrendAnalysis,
        targetBG: Double,
        maxIOB: Double,
        historicalData: List<BGDataPoint>
    ): Boolean {

        // ★★★ NIEUWE HYPO RECOVERY CHECK ★★★
        if (isLikelyHypoRecovery(currentData.bg, historicalData, trends, )) {
            android.util.Log.d("FCL_SAFETY", "Insulin withheld: hypo recovery in progress")
            return true
        }

        return when {
            // Absolute hypo-veiligheid
            currentData.bg < gethypoThreshold() + 1.2 -> true

            // Daling + nog in de buurt van hypo
            currentData.bg < gethypoThreshold() + 2.0 && trends.recentTrend < -0.3 -> true
            currentData.bg < gethypoThreshold() + 2.7 && trends.recentTrend < -0.5 -> true

            // Hoge IOB → alleen blokkeren als BG niet duidelijk boven target zit
          //  currentData.iob > 1.8 && currentData.bg < targetBG + 1.0 -> true
            currentData.iob > maxIOB * 0.45 && currentData.bg < targetBG + 1.0 -> true // 45% van maxIOB
            // Matige IOB + duidelijke daling
          //  currentData.iob > 1.0 && trends.recentTrend < -0.3 -> true
            currentData.iob > maxIOB * 0.25 && trends.recentTrend < -0.3 -> true // 25% van maxIOB
            else -> false
        }
    }

    // ★★★ IOB-BASED BOLUS REDUCTION ★★★
    private fun calculateIOBBolusReductionFactor(currentIOB: Double, maxIOB: Double, detectedCarbs: Double): Double {
        val iobRatio = currentIOB / maxIOB

        return when {
            iobRatio >= 1.0 -> 0.0  // Geen bolus als IOB >= max IOB
            iobRatio > 0.8 -> 0.3   // 70% reductie bij zeer hoge IOB
            iobRatio > 0.6 -> 0.5   // 50% reductie bij hoge IOB
            iobRatio > 0.4 -> 0.7   // 30% reductie bij matige IOB
            iobRatio > 0.2 -> 0.9   // 10% reductie bij lage IOB
            else -> 1.0             // Geen reductie
        }
    }

    private fun explainWithholdReason(currentData: BGDataPoint, trends: TrendAnalysis, targetBG: Double): String {
        return when {
            currentData.bg < 5.0 ->
                "Withheld: BG ${"%.1f".format(currentData.bg)} < 5.0 mmol/L (hypo risk)"

            currentData.bg < 5.8 && trends.recentTrend < -0.3 ->
                "Withheld: BG ${"%.1f".format(currentData.bg)} and falling fast (${String.format("%.2f", trends.recentTrend)} mmol/L/h)"

            currentData.bg < 6.5 && trends.recentTrend < -0.5 ->
                "Withheld: BG ${"%.1f".format(currentData.bg)} with strong downward trend (${String.format("%.2f", trends.recentTrend)} mmol/L/h)"

            currentData.iob > 1.8 && currentData.bg < targetBG + 1.0 ->
                "Withheld: IOB ${"%.2f".format(currentData.iob)} > 1.8U and BG ${"%.1f".format(currentData.bg)} < target+1.0 (${String.format("%.1f", targetBG + 1.0)})"

            currentData.iob > 1.0 && trends.recentTrend < -0.3 ->
                "Withheld: IOB ${"%.2f".format(currentData.iob)} and falling trend (${String.format("%.2f", trends.recentTrend)} mmol/L/h)"

            else -> "Withheld: unspecified safety condition"
        }
    }


    private fun checkForCarbCorrection(historicalData: List<BGDataPoint>): Boolean {
        if (historicalData.size < 6) return false
        // ★★★ GEBRUIK hypoThreshold ★★★
        val recentLow = historicalData.takeLast(6).any { it.bg < gethypoThreshold() }
        val currentRising = calculateRecentTrend(historicalData, 2) > 2.0
        return recentLow && currentRising
    }

    private fun calculateConfidence(trends: TrendAnalysis): Double {
        return when {
            abs(trends.recentTrend) > 1.0 && abs(trends.acceleration) < 0.5 -> 0.85
            abs(trends.acceleration) > 1.0 -> 0.6
            else -> 0.7
        }
    }

    private fun determineMealPhase(recentTrend: Double, shortTermTrend: Double, acceleration: Double): String {
        return when {
            recentTrend > 2.0 && acceleration > 0.1 -> "early_rise"
            recentTrend > 1.0 && acceleration > 0 -> "mid_rise"
            recentTrend > 0.3 && acceleration < 0 -> "late_rise"
            abs(recentTrend) < 0.5 -> "peak"
            else -> "stable"
        }
    }

 /*   private fun calculateCurrentPeakConfidence(
        historicalData: List<BGDataPoint>,
        detectedCarbs: Double
    ): Double {
        if (historicalData.size < 6) return 0.5

        val recent = historicalData.takeLast(6)

        // Analyseer het huidige patroon voor piek-voorspelbaarheid
        val rises = recent.zipWithNext { a, b -> b.bg - a.bg }
        val consistentRise = rises.count { it > 0.1 } >= 3 // Minstens 3 van 5 stijgingen
        val riseVariance = calculateVariance(rises)

        // Hogere confidence bij consistente stijging en substantiële carbs
        return when {
            consistentRise && riseVariance < 0.1 && detectedCarbs > 25 -> 0.9
            consistentRise && detectedCarbs > 15 -> 0.75
            detectedCarbs > 20 -> 0.6
            else -> 0.5
        }
    }    */

    private fun calculateDynamicMaxRise(startBG: Double): Double {
        return when {
            startBG <= 4.0 -> 6.5
            startBG <= 5.0 -> 6.0
            startBG <= 6.0 -> 5.5
            startBG <= 7.0 -> 5.0
            startBG <= 8.0 -> 4.5
            startBG <= 9.0 -> 4.0
            startBG <= 10.0 -> 3.5
            startBG <= 12.0 -> 3.0
            startBG <= 14.0 -> 2.5
            else -> 2.0
        }
    }


    // Automatische update van maaltijdstatus
    private fun updateMealStatusAutomatically(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        trends: TrendAnalysis,
        phase: String,
        currentMealState: MealDetectionState,

    ) {
        val currentTime = currentData.timestamp

        if (currentMealState != MealDetectionState.NONE) {
            mealDetectionState = currentMealState
            if (currentMealState == MealDetectionState.DETECTED) {
                lastMealDetectionTime = currentTime
            }
            if (currentMealState == MealDetectionState.EARLY_RISE || currentMealState == MealDetectionState.RISING) {
                lastRiseDetectionTime = currentTime
            }
        }

        if (peakDetected && trends.recentTrend < -0.5) {
            peakDetected = false
        }

        if (!mealInProgress && shouldStartMealPhase(historicalData, trends)) {
            mealInProgress = true
            lastMealTime = currentTime
            peakDetected = false
        }

        if (mealInProgress && !peakDetected && phase == "peak") {
            peakDetected = true
        }

        // NIEUW: Meal afronding detectie en performance logging
        if (mealInProgress && shouldEndMealPhase(currentData, historicalData, trends)) {
            val recentUpdate = pendingLearningUpdates.lastOrNull {
                it.timestamp.isAfter(currentTime.minusMinutes(180))
            }
            val detectedCarbs = recentUpdate?.detectedCarbs ?: 0.0
            val peakConfidence = calculatePeakConfidence(historicalData, detectedCarbs)
            val mealType = getMealTypeFromHour()
            logMealPerformanceResult(currentData, historicalData,peakConfidence,mealType)
            mealInProgress = false
            lastMealTime = null
            peakDetected = false
        } else if (!mealInProgress && shouldStartMealPhase(historicalData, trends)) {
            mealInProgress = true
            lastMealTime = currentTime
            peakDetected = false
        }
    }

    private fun logMealPerformanceResult(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        peakConfidence: Double = 0.5, // ★★★ NIEUWE PARAMETER
        mealType: String = "unknown"  // ★★★ NIEUWE PARAMETER
    ) {
        try {
            // Zoek de laatste pending learning update voor deze meal
            val recentUpdates = pendingLearningUpdates.sortedByDescending { it.timestamp }
            val latestUpdate = recentUpdates.firstOrNull {
                Minutes.minutesBetween(it.timestamp, DateTime.now()).minutes < 240
            } ?: return

            // Zoek de werkelijke piek BG tijdens deze meal
            val mealStart = latestUpdate.timestamp
            val peakData = storage.loadPeakDetectionData().filter {
                it.timestamp.isAfter(mealStart) && Minutes.minutesBetween(mealStart, it.timestamp).minutes < 240
            }.maxByOrNull { it.bg }

            val actualPeak = peakData?.bg ?: currentData.bg
            val timeToPeak = if (peakData != null) {
                Minutes.minutesBetween(mealStart, peakData.timestamp).minutes
            } else {
                Minutes.minutesBetween(mealStart, DateTime.now()).minutes
            }

            // Bepaal outcome
            val outcome = when {
                actualPeak > 11.0 -> "TOO_HIGH"
                actualPeak < 6.0 -> "TOO_LOW"
                else -> "SUCCESS"
            }


            // ★★★ BEREKEN PEAK CONFIDENCE ALS NIET MEEGEGEVEN ★★★
            val finalPeakConfidence = if (peakConfidence == 0.5) {
                calculatePeakConfidence(historicalData, latestUpdate.detectedCarbs)
            } else {
                peakConfidence
            }

            // ★★★ BEPAAL MEAL TYPE ALS NIET MEEGEGEVEN ★★★
            val finalMealType = if (mealType == "unknown") {
                latestUpdate.mealType // Gebruik het mealType uit de LearningUpdate
            } else {
                mealType
            }
            // ★★★ NIEUW: Roep hypo learning aan bij TOO_LOW ★★★
            if (outcome == "TOO_LOW") {
                updateLearningFromHypoAfterMeal(
                    mealType = finalMealType,
                    hour = latestUpdate.timestamp.hourOfDay,
                    detectedCarbs = latestUpdate.detectedCarbs,
                    givenDose = latestUpdate.givenDose,
                    actualPeak = actualPeak,
                    bgEnd = currentData.bg
                )
            }

            // Sla resultaat op
            val performanceResult = MealPerformanceResult(
                timestamp = DateTime.now(),
                detectedCarbs = latestUpdate.detectedCarbs,
                givenDose = latestUpdate.givenDose,
                startBG = latestUpdate.startBG,
                predictedPeak = latestUpdate.expectedPeak,
                actualPeak = actualPeak,
                timeToPeak = timeToPeak,
                parameters = MealParameters(
                    bolusPercEarly = bolusPercEarly,
                    bolusPercDay = bolusPercDay,
                    bolusPercNight = bolusPercNight,
                    peakDampingFactor = peakDampingFactor,
                    hypoRiskFactor = hypoRiskFactor,
                    timestamp = DateTime.now()
                ),
                outcome = outcome,
                peakConfidence = finalPeakConfidence, // ★★★ NIEUW
                mealType = finalMealType // ★★★ OPTIONEEL

            )

            storage.saveMealPerformanceResult(performanceResult)


        } catch (e: Exception) {
            android.util.Log.e("FCL", "Error logging meal performance", e)
        }
    }

 /*   private fun calculatePeakConfidenceForMeal(
        mealStart: DateTime,
        historicalData: List<BGDataPoint>,
        detectedCarbs: Double
    ): Double {
        try {
            // Filter historische data voor de maaltijdperiode
            val mealPeriodData = historicalData.filter {
                it.timestamp.isAfter(mealStart) &&
                    Minutes.minutesBetween(mealStart, it.timestamp).minutes <= 180
            }

            if (mealPeriodData.size < 4) return 0.5

            // Analyseer het patroon tijdens de maaltijd
            val rises = mealPeriodData.zipWithNext { a, b -> b.bg - a.bg }
            val risingCount = rises.count { it > 0.1 }
            val totalReadings = rises.size
            val riseConsistency = risingCount.toDouble() / totalReadings

            // Bereken variantie van de stijgingen
            val riseVariance = if (rises.size > 1) {
                val mean = rises.average()
                rises.map { (it - mean) * (it - mean) }.average()
            } else {
                0.0
            }

            // Confidence gebaseerd op consistentie en grootte van de maaltijd
            return when {
                riseConsistency > 0.7 && riseVariance < 0.1 && detectedCarbs > 30 -> 0.9
                riseConsistency > 0.6 && detectedCarbs > 20 -> 0.75
                detectedCarbs > 15 -> 0.6
                else -> 0.5
            }
        } catch (e: Exception) {
            android.util.Log.e("FCL", "Error calculating peak confidence", e)
            return 0.5
        }
    }   */



    private fun shouldStartMealPhase(historicalData: List<BGDataPoint>, trends: TrendAnalysis): Boolean {
        if (historicalData.size < 6) return false
        val consistentRise = checkConsistentRise(historicalData, 3)
        val strongRise = trends.recentTrend > 2.0 && trends.acceleration > 0.1
        val recentLow = historicalData.takeLast(6).any { it.bg < 4.0 }
        return (consistentRise || strongRise) && !recentLow
    }

    private fun shouldEndMealPhase(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        trends: TrendAnalysis
    ): Boolean {
        if (lastMealTime == null) return true
        val minutesSinceMeal = Minutes.minutesBetween(lastMealTime, currentData.timestamp).minutes

        if (minutesSinceMeal > 240) return true
        if (peakDetected && trends.recentTrend < -1.0 && minutesSinceMeal > 120) return true

        val startBG = historicalData.firstOrNull { it.timestamp == lastMealTime }?.bg ?: return false
        if (currentData.bg <= startBG && minutesSinceMeal > 90) return true
        if (minutesSinceMeal > 150 && abs(trends.recentTrend) < 0.3) return true

        return false
    }


    private fun roundDose(dose: Double): Double {
        return round(dose * 20) / 20
    }

    private fun round(value: Double, digits: Int): Double {
        val scale = Math.pow(10.0, digits.toDouble())
        return Math.round(value * scale) / scale
    }

    fun getEnhancedInsulinAdvice(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        currentISF: Double,
        targetBG: Double,
        carbRatio: Double,
        currentIOB: Double,
        maxBolus: Double,
        maxIOB: Double    // moet doorgegeven worden vanuit DetermineBasalFCL
    ): EnhancedInsulinAdvice {
        try {
            // VALIDATIE VAN LEARNING PARAMETERS TOEVOEGEN
            if (learningProfile.personalCarbRatio.isNaN() || learningProfile.personalCarbRatio <= 0) {
                learningProfile = learningProfile.copy(personalCarbRatio = 1.0)

            }
            if (learningProfile.personalISF.isNaN() || learningProfile.personalISF <= 0) {
                learningProfile = learningProfile.copy(personalISF = 1.0)

            }
            // ★★★  Check op reset bij elke advice call ★★★
            resetLearningDataIfNeeded()
            val trends = analyzeTrends(historicalData)


            processPendingLearningUpdates()
            processPendingCorrectionUpdates()
            val phase = determineMealPhase(trends.recentTrend, trends.shortTermTrend, trends.acceleration)
            // housekeeping
            cleanUpMeals()

            // COB
            val cobNow = getCarbsOnBoard()

            val basicAdvice = getInsulinAdvice(
                currentData, historicalData,
                currentISF, targetBG, carbRatio, currentIOB, maxIOB
            )

// Probeer openstaande learning updates af te handelen
            processPendingLearningUpdates()
            processPendingCorrectionUpdates()

// Sla profiel altijd op na wijzigingen
            storage.saveLearningProfile(learningProfile)


            val shortTermTrend = calculateShortTermTrend(historicalData)
            val isDeclining = checkConsistentDecline(historicalData)

            // placeholders
            var finalDose = 0.0
            var finalReason = ""
            var finalDeliver = false
            var finalMealDetected = false
            var finalDetectedCarbs = 0.0
            var finalReservedBolus = 0.0
            var finalPhase = "stable"
            var finalConfidence = learningProfile.learningConfidence
            var predictedPeak = basicAdvice.predictedValue ?: currentData.bg
            var finalCOB = cobNow



            // === Safety ===
            if (shouldWithholdInsulin(currentData, trends, targetBG, maxIOB, historicalData)) {
                val reasonDetail = explainWithholdReason(currentData, trends, targetBG)
                finalDose = 0.0
                finalReason = "Safety: $reasonDetail"
                finalDeliver = false
                finalPhase = "safety"
            }

            // === NIEUW: Korte-termijn trend safety check ===
            if (finalDeliver && finalDose > 0) {
                if (shouldBlockBolusForShortTermTrend(currentData, historicalData, trends)) {
                    val shortTermTrend = calculateShortTermTrend(historicalData)
                    finalDose = 0.0
                    finalDeliver = false
                    finalReason = "Safety: Strong short-term decline (${"%.1f".format(shortTermTrend)} mmol/L/h)"
                    finalPhase = "safety"
                }
            }

            // === NIEUW: Trend reversal detection ===
            if (finalDeliver && finalDose > 0) {
                if (shouldBlockCorrectionForTrendReversal(currentData, historicalData, trends)) {
                    finalDose = 0.0
                    finalDeliver = false
                    finalReason = "Safety: Trend reversing to decline (IOB=${"%.1f".format(currentData.iob)}U)"
                    finalPhase = "safety"
                }
            }

            // ★★★ HYPO RECOVERY SAFETY CHECK ★★★

            if (shouldBlockMealDetectionForHypoRecovery(currentData.bg, historicalData, trends)) {
                finalMealDetected = false
                finalDetectedCarbs = 0.0
                finalReason = "Safety: Meal detection blocked (hypo recovery)"
                finalPhase = "safety_monitoring"
                finalDeliver = false
            }

// === Meal detection (ALTIJD uitvoeren, ook boven target) ===
            val (detectedCarbs, mealState) = detectMealFromBG(
                historicalData, currentData.bg, mealDetectionSensitivity,
                carbRatio, currentISF, targetBG
            )

// ★★★ NIEUW: Safety checks voor false positives ★★★
            val sensorIssue = detectSensorIssue(historicalData)
            val sensorError = sensorIssue != null
            val mealConfidenceLevel = validateMealPattern(historicalData)
            val isLikelyMeal = distinguishMealFromSnack(historicalData, detectedCarbs)
            val shouldAdjust = shouldAdjustOrCancelBolus(historicalData, mealState)

// Safety: blokkeer bij sensor errors
            if (sensorIssue != null) {
                finalDose = 0.0
                finalDeliver = false
                finalMealDetected = false

                when (sensorIssue) {
                    SensorIssueType.COMPRESSION_LOW -> {
                        finalReason = "Safety: Compression low detected - withholding insulin"
                        finalPhase = "safety_compression_low"
                    }
                    SensorIssueType.JUMP_TOO_LARGE -> {
                        finalReason = "Safety: Sensor error (jump too large) - withholding insulin"
                        finalPhase = "safety_sensor_error"
                    }
                    SensorIssueType.OSCILLATION -> {
                        finalReason = "Safety: Sensor error (oscillation) - withholding insulin"
                        finalPhase = "safety_sensor_error"
                    }
                }
            }

// ★★★ NIEUW: Meal detection ook wanneer BG boven target is ★★★
            else if (mealState != MealDetectionState.NONE && !sensorError && finalDeliver) {
                val mealConfidence = calculateMealConfidence(historicalData, detectedCarbs)

                if (mealConfidence > 0.4 && isLikelyMeal && detectedCarbs > 15 &&
                    canDetectMealAboveTarget(currentData.bg, targetBG, trends, currentIOB)) {

                    // ★★★ TOEVOEGING: Voeg gedetecteerde koolhydraten toe aan COB ★★★
                    addOrUpdateActiveMeal(detectedCarbs, DateTime.now())
                    finalCOB = getCarbsOnBoard() // Update COB voor return waarde

                    // ★★★ COMBINEER meal met correction ★★★
                    val peakConfidence = calculatePeakConfidence(historicalData, detectedCarbs)
                    val (immediateBolus, reservedBolus, bolusReason) = calculateStagedBolus(
                        detectedCarbs, carbRatio, currentISF, currentIOB,
                        currentData.bg, targetBG, maxBolus, phase, peakConfidence
                    )

                    // Bereken correction component (30% van benodigde correction)
                    val correctionComponent = max(0.0, (currentData.bg - targetBG) / currentISF) * 0.3

                    finalDose = immediateBolus + correctionComponent
                    finalReason = "Meal+Correction: ${"%.1f".format(detectedCarbs)}g + BG=${"%.1f".format(currentData.bg)} | $bolusReason"
                    finalMealDetected = true
                    finalDetectedCarbs = detectedCarbs
                    finalPhase = "meal_correction_combination"

                    // Store voor learning
                    storeMealForLearning(
                        detectedCarbs = detectedCarbs,
                        givenDose = finalDose,
                        startBG = currentData.bg,
                        expectedPeak = predictedPeak,
                        mealType = getMealTypeFromHour()
                    )

                    // Reserveer bolus
                    if (reservedBolus > 0.1) {
                        pendingReservedBolus = reservedBolus
                        pendingReservedCarbs = detectedCarbs
                        pendingReservedTimestamp = DateTime.now()
                        pendingReservedPhase = phase
                        finalReason += " | Reserved: ${"%.2f".format(reservedBolus)}U"
                    }
                }
            }

// ★★★ VERBETERDE SAFETY CHECK ★★★
            else if (!isLikelyMeal && detectedCarbs < 10) {
                // Alleen blokkeren bij zeer kleine hoeveelheden + onduidelijk patroon
                finalDose = 0.0
                finalReason = "Safety: Small uncertain rise (${"%.1f".format(detectedCarbs)}g) - monitoring pattern"
                finalDeliver = false
                finalPhase = "safety_monitoring"
                finalMealDetected = false
            }

// Safety: pas aan bij tegenvallende stijging
            else if (shouldAdjust && mealInProgress) {
                finalMealDetected = true
                finalDetectedCarbs = detectedCarbs * 0.5 // Carb schatting aanpassen
            }

// ★★★ GEFASEERDE MEAL PROCESSING MET IOB REDUCTIE (voor BG onder target) ★★★
            else if (mealState != MealDetectionState.NONE && !sensorError && !finalMealDetected) {
                val mealConfidence = calculateMealConfidence(historicalData, detectedCarbs)

                if (mealConfidence > 0.4 && detectedCarbs > 10) {
                    // ★★★ TOEVOEGING: Voeg gedetecteerde koolhydraten toe aan COB ★★★
                    addOrUpdateActiveMeal(detectedCarbs, DateTime.now())
                    finalCOB = getCarbsOnBoard() // Update COB voor return waarde
                    // ★★★ IOB-BASED BOLUS REDUCTION ★★★
                    val iobReductionFactor = calculateIOBBolusReductionFactor(currentIOB, maxIOB, detectedCarbs)

                    if (iobReductionFactor <= 0.0) {
                        // Volledige blokkering alleen bij IOB >= maxIOB
                        finalDose = 0.0
                        finalReason = "Safety: Max IOB reached (${"%.1f".format(currentIOB)}U/${"%.1f".format(maxIOB)}U) - blocking meal bolus"
                        finalDeliver = false
                        finalPhase = "safety_max_iob"
                        finalMealDetected = false
                    } else {
                        // Gebruik staged bolus met IOB reductie
                        val (immediateBolus, reservedBolus, bolusReason) = calculateStagedBolus(
                            detectedCarbs, carbRatio, currentISF, currentIOB,
                            currentData.bg, targetBG, maxBolus, phase
                        )

                        // ★★★ APPLY IOB REDUCTION ★★★
                        val reducedImmediateBolus = immediateBolus * iobReductionFactor
                        val reducedReservedBolus = reservedBolus * iobReductionFactor

                        // Bewaar reserved bolus voor later
                        if (reducedReservedBolus > 0.1) {
                            pendingReservedBolus = reducedReservedBolus
                            pendingReservedCarbs = detectedCarbs
                            pendingReservedTimestamp = DateTime.now()
                            pendingReservedPhase = phase
                        }

                        // Alleen immediate bolus afgeven
                        finalDose = reducedImmediateBolus
                        finalReason = when {
                            iobReductionFactor < 1.0 -> "IOB-adjusted (${(iobReductionFactor * 100).toInt()}%): $bolusReason"
                            else -> bolusReason
                        }
                        finalDeliver = reducedImmediateBolus > 0.05 // Alleen leveren als significant
                        finalMealDetected = true
                        finalDetectedCarbs = detectedCarbs
                        finalPhase = if (mealConfidence > 0.7) "meal_high_confidence" else "meal_medium_confidence"
                        finalConfidence = mealConfidence

                        if (reducedReservedBolus > 0.1) {
                            finalReason += " | Reserved: ${"%.2f".format(reducedReservedBolus)}U"
                        }

                        // Store for learning (alleen de initiële bolus)
                        if (finalDeliver && finalDose > 0.0) {
                            storeMealForLearning(
                                detectedCarbs = detectedCarbs,
                                givenDose = finalDose,
                                startBG = currentData.bg,
                                expectedPeak = predictedPeak,
                                mealType = getMealTypeFromHour()
                            )
                        }
                    }
                } else {
                    // Laag vertrouwen - monitor alleen
                    finalDose = 0.0
                    finalReason = "Monitoring uncertain pattern (confidence: ${"%.0f".format(mealConfidence * 100)}%)"
                    finalDeliver = false
                    finalPhase = "safety_monitoring"
                    finalMealDetected = false
                }
            }

// ★★★ RESERVED BOLUS RELEASE LOGIC ★★★
            decayReservedBolusOverTime()

// Check of reserved bolus vrijgegeven moet worden
            if (pendingReservedBolus > 0.1 && shouldReleaseReservedBolus(currentData.bg, targetBG, trends, historicalData)) {
                val minutesSinceLastBolus = lastBolusTime?.let {
                    Minutes.minutesBetween(it, DateTime.now()).minutes
                } ?: Int.MAX_VALUE

                if (minutesSinceLastBolus >= 10) {  // Minimaal 15 minuten tussen boluses
                    val releasedBolus = calculateReservedBolusRelease(currentData.bg, targetBG, trends, historicalData)
                    if (releasedBolus > 0.05) {
                        finalDose += releasedBolus
                        finalDeliver = true
                        lastBolusTime = DateTime.now()  // ★★★ UPDATE TIJD ★★★
                        finalReason += if (finalReason.contains("Reserved")) {
                            " | +${"%.2f".format(releasedBolus)}U released"
                        } else {
                            " | Released reserved: ${"%.2f".format(releasedBolus)}U"
                        }

                        // Update learning met de vrijgegeven bolus
                        storeMealForLearning(
                            detectedCarbs = pendingReservedCarbs,
                            givenDose = releasedBolus,
                            startBG = currentData.bg,
                            expectedPeak = predictedPeak,
                            mealType = getMealTypeFromHour()
                        )
                    }
                }
            }

// === Correction (alleen als GEEN maaltijd gedetecteerd) ===
            else if (!finalMealDetected && currentData.bg > targetBG + 0.5) {
                var correctionDose = max(0.0, (currentData.bg - targetBG) / currentISF)

                if (trends.recentTrend > 0.2) {
                    val factor = 1.0 + (trends.recentTrend / 0.3).coerceAtMost(2.0)
                    correctionDose *= factor
                }

                correctionDose = getSafeDoseWithLearning(
                    correctionDose, null,
                    learningProfile.learningConfidence, currentIOB, trends, phase
                )

                if (trends.recentTrend <= 0.0 && currentData.bg < targetBG + 3.0) {
                    correctionDose *= peakDampingFactor
                }

                if (isHypoRiskWithin(120, currentData.bg, currentIOB, currentISF)) {
                    correctionDose *= hypoRiskFactor
                }

                val deltaCorr = (predictedPeak - currentData.bg).coerceAtLeast(0.0)
                val startBoostFactorCorr = 1.0 + (deltaCorr / 10.0).coerceIn(0.0, 0.3)
                if (startBoostFactorCorr > 1.0) {
                    correctionDose *= startBoostFactorCorr
                    finalReason += " | StartCorrectionBoost(x${"%.2f".format(startBoostFactorCorr)})"
                }

                val cappedCorrection = min(roundDose(correctionDose), maxBolus)

                val deliverCorrection = (trends.recentTrend > 0.5 && currentData.bg > targetBG + 1.0 &&
                    !isTrendReversingToDecline(historicalData, trends))

                finalDose = cappedCorrection
                finalPhase = "correction"
                finalDeliver = deliverCorrection
                finalReason =
                    "Correction: BG=${"%.1f".format(currentData.bg)} > target=${"%.1f".format(targetBG)}" +
                        if (!finalDeliver) " | Stable/decline -> no bolus" else ""

       //         if (parameterAdvisory.isNotEmpty()) {
        //            finalReason += " | $parameterAdvisory"
       //         }
                finalConfidence = basicAdvice.confidence

                if (currentIOB >= maxIOB * 0.9 && trends.recentTrend <= 0.0) {
                    finalDose = 0.0
                    finalDeliver = false
                    finalReason += " | Blocked by high IOB safety"
                }

                try {
                    if (finalDeliver && finalDose > 0.0) {
                        val predictedDrop = finalDose * currentISF   // mmol/L daling verwacht
                        val corrUpdate = CorrectionUpdate(
                            insulinGiven = finalDose,
                            predictedDrop = predictedDrop,
                            bgStart = currentData.bg,
                            timestamp = DateTime.now()
                        )
                        storage.savePendingCorrectionUpdate(corrUpdate)
                    }
                } catch (ex: Exception) {
                    // Logging
                }
            }

// === Geen actie ===
            else if (!finalMealDetected) {
                finalDose = 0.0
                finalReason = "No action: BG=${"%.1f".format(currentData.bg)} ~ target=${"%.1f".format(targetBG)}"
                finalPhase = "stable"
                finalDeliver = false
                finalConfidence = learningProfile.learningConfidence
            }

            // -------------------------------------------------------------------
            // Early Boost logic (dynamisch, meal én non-meal)
            // -------------------------------------------------------------------
            predictedPeak = basicAdvice.predictedValue ?: (currentData.bg + 2.0)

            if (currentData.bg in 8.0..9.9 && predictedPeak > 10.0) {

                val delta = (predictedPeak - currentData.bg).coerceAtLeast(0.0)
                val boostFactor = (getCurrentBolusAggressiveness()/100.0) * (1.0 + (delta / 10.0).coerceIn(0.0, 0.3))

                val proposed = (finalDose * boostFactor).coerceAtMost(maxBolus)

                val overTarget = (predictedPeak - 10.0).coerceAtLeast(0.0)
                val iobBoostPercent = (overTarget / 5.0).coerceIn(0.0, 0.5)
                val dynamicIOBcap = maxIOB * (1.0 + iobBoostPercent)

                if (currentIOB >= maxIOB * 0.9 && trends.recentTrend <= 0.0) {
                    finalReason += " | EarlyBoost blocked by high IOB safety"
                } else {
                    if (currentIOB + proposed <= dynamicIOBcap) {
                        finalDose = proposed
                        if (finalMealDetected) {
                            finalReason += " | EarlyMealBoost(x${"%.2f".format(boostFactor)}) peak=${"%.1f".format(predictedPeak)}"
                        } else {
                            finalReason += " | EarlyCorrectionBoost(x${"%.2f".format(boostFactor)}) peak=${"%.1f".format(predictedPeak)}"
                        }
                    } else {
                        val allowed = (dynamicIOBcap - currentIOB).coerceAtLeast(0.0)
                        finalDose = allowed.coerceAtMost(maxBolus)
                        finalReason += " | EarlyBoost capped by dynamic IOB cap"
                    }
                }
            }

            // === Voorspelling leegmaken bij dalende trend ===
            if (trends.recentTrend <= 0.0) {
                predictedPeak = currentData.bg
                finalReason += " | No prediction (falling/stable trend)"
            }

            // ★★★ SCHRIJF COB NAAR SHAREDPREFERENCES ★★★
            try {
                storage.saveCurrentCOB(finalCOB)

                // Optioneel: ook naar bestand schrijven voor backup

            } catch (e: Exception) {
                android.util.Log.e("FCL_COB", "Error writing COB", e)
            }



            // === Centrale return ===
            return EnhancedInsulinAdvice(
                dose = finalDose,
                reason = finalReason,
                confidence = finalConfidence,
                predictedValue = predictedPeak,
                mealDetected = finalMealDetected,
                detectedCarbs = finalDetectedCarbs,
                shouldDeliverBolus = finalDeliver,
                phase = finalPhase,
                learningMetrics = LearningMetrics(
                    confidence = learningProfile.learningConfidence,
                    samples = learningProfile.totalLearningSamples,
                    carbRatioAdjustment = learningProfile.personalCarbRatio,
                    isfAdjustment = learningProfile.personalISF,
                    mealTimeFactors = learningProfile.mealTimingFactors,
                    hourlySensitivities = learningProfile.sensitivityPatterns,
                ),
                reservedDose = finalReservedBolus,
                carbsOnBoard = finalCOB
            )
        } catch (e: Exception) {
            return EnhancedInsulinAdvice(
                dose = 0.0,
                reason = "FCL error: ${e.message}",
                confidence = 0.0,
                predictedValue = null,
                mealDetected = false,
                detectedCarbs = 0.0,
                shouldDeliverBolus = false,
                phase = "error",
                learningMetrics = LearningMetrics(
                    confidence = 0.0,
                    samples = 0,
                    carbRatioAdjustment = learningProfile.personalCarbRatio,
                    isfAdjustment = learningProfile.personalISF,
                    mealTimeFactors = learningProfile.mealTimingFactors,
                    hourlySensitivities = learningProfile.sensitivityPatterns
                ),
                reservedDose = 0.0,
                carbsOnBoard = 0.0
            )
        }
    }


}

enum class MealDetectionState { NONE, EARLY_RISE, RISING, PEAK, DECLINING, DETECTED }
enum class MealConfidenceLevel {
    SUSPECTED,      // Initiële stijging gedetecteerd
    CONFIRMED,      // Consistente stijging over meerdere metingen
    HIGH_CONFIDENCE // Patroon matcht maaltijdprofiel
}
enum class SensorIssueType {JUMP_TOO_LARGE, OSCILLATION, COMPRESSION_LOW}