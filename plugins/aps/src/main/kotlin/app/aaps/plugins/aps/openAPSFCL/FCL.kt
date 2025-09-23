package app.aaps.plugins.aps.openAPSFCL

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
            val loopCounter: Int
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
            val outcome: String
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
                    outcome = result.outcome
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
                    outcome = it.outcome
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
                    totalLearningSamples = profile.totalLearningSamples,
                    loopCounter = profile.loopCounter
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
                    loopCounter = s.loopCounter
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
        val outcome: String // "SUCCESS", "TOO_HIGH", "TOO_LOW"
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

    data class ParameterAdvice(
        val parameter: String, // "bolusPercEarly", "peakDampingFactor", etc.
        val currentValue: Double,
        val suggestedChange: Double, // +10, -5, etc. (percentage)
        val confidence: Double, // 0.0 - 1.0
        val reason: String,
        val samples: Int,
        val avgPeak: Double,
        val successRate: Double
    )



    data class FCLLearningProfile(
        val personalCarbRatio: Double = 1.0,
        val personalISF: Double = 1.0,
        val mealTimingFactors: Map<String, Double> = emptyMap(),
        val sensitivityPatterns: Map<Int, Double> = emptyMap(),
        val lastUpdated: DateTime = DateTime.now(),
        val learningConfidence: Double = 0.0,
        val totalLearningSamples: Int = 0,
        val loopCounter: Int = 0   // tijdelijk debug veld
    ) {
        // Alleen de functies die WEL worden gebruikt behouden

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

        // Backup/Restore functionaliteit (nodig voor Stap 3)
        fun toJson(gson: Gson): String = gson.toJson(this)

        companion object {
            fun fromJson(gson: Gson, json: String): FCLLearningProfile? {
                return try {
                    gson.fromJson(json, FCLLearningProfile::class.java)
                } catch (e: Exception) {
                    null
                }
            }
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
        val loopCounter: Int, // <<< nieuw veld
        val parameterAdvisory: String = "",
        val mealPerformanceStats: String = ""
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


    private val mealDetectionThreshold = 0.3
    private val minIOBForEffect = 0.3
    private val insulinSensitivityFactor = 3.0
    private val dailyReductionFactor = 0.7
    private val peakDetectionWindow = 180 // 3 uur voor peak detectie

    // State tracking
    private var lastMealTime: DateTime? = null
    private var mealInProgress = false
    private var peakDetected = false
    private var mealDetectionState = MealDetectionState.NONE
    private var lastMealDetectionTime: DateTime? = null
    private var lastRiseDetectionTime: DateTime? = null


    // Progressieve bolus tracking
    private val activeMeals = mutableListOf<ActiveCarbs>()
    private val pendingLearningUpdates = mutableListOf<LearningUpdate>()
    private val pendingCorrectionUpdates = mutableListOf<CorrectionUpdate>()

    // Learning system
    private val storage: FCLStorage = AndroidFCLStorage(context)
    private var learningProfile: FCLLearningProfile = storage.loadLearningProfile() ?: FCLLearningProfile()


    init {
        loadPreferences(context)

        // Robuust laden van learning profile
        try {
            val loadedProfile = storage.loadLearningProfile()
            learningProfile = loadedProfile ?: FCLLearningProfile()

            println("DEBUG: Learning profile loaded: samples=${learningProfile.totalLearningSamples}, confidence=${learningProfile.learningConfidence}")
        } catch (e: Exception) {
            println("DEBUG: Failed to load learning profile: ${e.message}")
            learningProfile = FCLLearningProfile()
        }

        // Laad andere data
        try {
            pendingLearningUpdates.clear()
            pendingLearningUpdates.addAll(storage.loadPendingLearningUpdates())
            println("DEBUG: Pending updates loaded: ${pendingLearningUpdates.size}")
        } catch (ex: Exception) {
            println("DEBUG: Failed to load pending updates: ${ex.message}")
        }

        processPendingLearningUpdates()
        processPendingCorrectionUpdates()
        detectPeaksFromHistoricalData()
        processFallbackLearning()
    }


    private fun loadPreferences(context: Context) {
        try {
            val prefs = context.getSharedPreferences("androidaps", Context.MODE_PRIVATE)

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



        } catch (e: Exception) {

            setCarbSensitivity(70)
        }
    }
    fun setCurrentBg(value: Double) {
        currentBg = value/18
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
    private fun getCurrentBolusAggressiveness(): Double {
        return if (NightTime) bolusPercNight else bolusPercDay
    }



    fun getLearningStatus(): String {
        val currentHour = DateTime.now().hourOfDay
        return """
        FCL Learning Status:
        - Confidence: ${round(learningProfile.learningConfidence * 100, 1)}%
        - Samples: ${learningProfile.totalLearningSamples}
        - Teller: ${learningProfile.loopCounter}
        - Carb Ratio Adjustment: ${round(learningProfile.personalCarbRatio, 2)}
        - ISF Adjustment: ${round(learningProfile.personalISF, 2)}
        - Current Meal Factor: ${round(learningProfile.getMealTimeFactor(currentHour), 2)}
        - Current Sensitivity: ${round(learningProfile.getHourlySensitivity(currentHour), 2)}
        - NightTime : ${NightTime},
        - Bolus %: ${getCurrentBolusAggressiveness().toInt()}%
        - Early Rise %:${bolusPercEarly.toInt()}%
        - Late Rise %:${bolusPercLate.toInt()}%
        - Carb Calc %: ${carbSensitivity.toInt()}%
        - Max Bolus: ${round(maxBolus,2)}eh
        - Peak Damping %: ${peakDampingPercentage.toInt()}%
        - Hypo Risk %: ${hypoRiskPercentage.toInt()}%
        - Carb abs. tijd: ${tauAbsorptionMinutes}min
        - mealDetection Sens: ${mealDetectionSensitivity} mmol/l/5min
        - Last Updated: ${learningProfile.lastUpdated.toString("yyyy-MM-dd HH:mm")}
        
        Meal Time Factors:
        ${learningProfile.mealTimingFactors.entries.joinToString("\n") { "  - ${it.key}: ${round(it.value, 2)}" }}
        
        Hourly Sensitivities:
        ${learningProfile.sensitivityPatterns.entries.sortedBy { it.key }.joinToString("\n") { "  - ${it.key}:00: ${round(it.value, 2)}" }}
  
       ${getParameterAdvisorySummary()}
    """.trimIndent()
    }

    // Automatic peak detection from historical data
    private fun detectPeaksFromHistoricalData() {
        val historicalData = storage.loadPeakDetectionData()
        if (historicalData.isEmpty()) return
        if (pendingLearningUpdates.isEmpty()) return

        // Vind pieken in opgeslagen peak-data
        val peaks = mutableListOf<PeakDetectionData>()
        for (i in 2 until historicalData.size - 2) {
            val current = historicalData[i]
            val prev1 = historicalData[i - 1]
            val prev2 = historicalData[i - 2]
            val next1 = historicalData[i + 1]
            val isPeak = when {
                current.bg > prev1.bg && current.bg > prev2.bg && current.bg > next1.bg && current.trend < 0 -> true
                current.bg > prev2.bg + 1.2 && current.trend < -0.4 -> true
                else -> false
            }
            if (isPeak) peaks.add(current.copy(isPeak = true))
        }

        if (peaks.isEmpty()) return

        val processed = mutableListOf<LearningUpdate>()
        // Loop over een kopie van pendingLearningUpdates (we muteren mogelijk)
        for (update in ArrayList(pendingLearningUpdates)) {
            for (peak in peaks) {
                val minutesDiff = Minutes.minutesBetween(update.timestamp, peak.timestamp).minutes
                // ruimer venster: 30 .. 240 minuten
                if (minutesDiff in 30..240) {
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
                        println("DEBUG: detectPeaksFromHistoricalData matched update @${update.timestamp} with peak @${peak.timestamp} (diff=${minutesDiff}m)")
                        processed.add(update)
                    } catch (ex: Exception) {
                        println("DEBUG: updateLearningFromMealResponse failed: ${ex.message}")
                    }
                    break
                }
            }
        }

        if (processed.isNotEmpty()) {
            // verwijder verwerkte updates uit geheugen en persist de rest
            pendingLearningUpdates.removeAll(processed.toSet())
            storage.clearPendingLearningUpdates()
            pendingLearningUpdates.forEach { storage.savePendingLearningUpdate(it) }
        }
    }



    private fun processFallbackLearning() {
        // synchroniseer in-memory met storage
        try {
            pendingLearningUpdates.clear()
            pendingLearningUpdates.addAll(storage.loadPendingLearningUpdates())
        } catch (ex: Exception) {
            println("DEBUG: failed to load pending updates in processFallbackLearning: ${ex.message}")
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
                        peakTimestamp = peakTimestamp
                    )
                    println("DEBUG: Fallback learning applied for meal at ${update.timestamp}")
                    processed.add(update)
                } catch (ex: Exception) {
                    println("DEBUG: Fallback updateLearningFromMealResponse failed: ${ex.message}")
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
        if (historicalData.size < 4) return Pair(0.0, MealDetectionState.NONE)

        val bg15minAgo = historicalData[historicalData.size - 4].bg
        val delta15 = currentBG - bg15minAgo
        val slope15 = delta15 / 15.0 * 60.0 // mmol/L per uur

        // Verwachte stijging uit COB
        val predictedRiseFromCOB = estimateRiseFromCOB(carbRatio, currentISF)
        val unexplainedDelta = delta15 - predictedRiseFromCOB

        var localDetectedCarbs = 0.0
        var detectedState = MealDetectionState.NONE

        // === 1. Normale meal-detectie ===
        if (unexplainedDelta > mealDetectionSensitivity) {
            localDetectedCarbs = unexplainedDelta * 10.0  // vuistregel: 1 mmol extra ≈ 10g carbs
            detectedState = MealDetectionState.RISING
            println("DEBUG: Meal detection -> +${"%.1f".format(localDetectedCarbs)}g (unexplainedDelta=${"%.2f".format(unexplainedDelta)})")
        }

        // === 2. Trage stijging extensie ===
        if (slope15 > 0.2 && delta15 > 0.5 && unexplainedDelta <= mealDetectionSensitivity) {
            val cobNow = getCarbsOnBoard()
            if (cobNow > 10.0) {
                localDetectedCarbs += 10.0
                detectedState = MealDetectionState.RISING
                println("DEBUG: Slow rise extension -> +10g (COB=${"%.1f".format(cobNow)})")
            }
        }

        // === 3. Fallback meal-detectie bij stevige stijging ===
        if (detectedState == MealDetectionState.NONE &&
            slope15 > 0.5 &&
            currentBG > targetBG + 2.0
        ) {
            localDetectedCarbs = slope15 * 10.0
            detectedState = MealDetectionState.RISING
            println("DEBUG: Fallback meal detection -> +${"%.1f".format(localDetectedCarbs)}g (slope=${"%.2f".format(slope15)})")
        }

        // === 4. COB-correctie ===
        val cobNow = getCarbsOnBoard()
        if (cobNow < 10.0 && slope15 > 0.5) {
            localDetectedCarbs += 20.0
            detectedState = MealDetectionState.RISING
            println("DEBUG: COB underestimation correction -> +20g (COB=${"%.1f".format(cobNow)})")
        }

        return Pair(localDetectedCarbs, detectedState)
    }





    fun estimateRiseFromCOB(carbRatio: Double, currentISF: Double): Double {
        val now = DateTime.now()
        val remainingCarbs = activeMeals.sumOf { it.getRemainingCarbs(now) }
        val carbsToRiseFactor = (1.0 / carbRatio) * currentISF
        return remainingCarbs * carbsToRiseFactor
    }




    private fun shouldDeliverBolusBasedOnState(): Boolean {
        val currentTime = DateTime.now()
        return when (mealDetectionState) {
            MealDetectionState.DETECTED -> lastMealDetectionTime?.let {
                Minutes.minutesBetween(it, currentTime).minutes < 60
            } ?: false
            MealDetectionState.RISING -> lastRiseDetectionTime?.let {
                Minutes.minutesBetween(it, currentTime).minutes < 30
            } ?: false
            MealDetectionState.EARLY_RISE -> lastRiseDetectionTime?.let {
                Minutes.minutesBetween(it, currentTime).minutes < 15
            } ?: false
            else -> false
        }
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
            println("DEBUG: storeMealForLearning stored update - carbs=${detectedCarbs}, dose=${givenDose}, startBG=${startBG}, expPeak=${expectedPeak}")
        } catch (ex: Exception) {
            println("DEBUG: storeMealForLearning failed: ${ex.message}")
        }
    }





    private fun processPendingLearningUpdates() {
        // synchroniseer in-memory met storage (bestaande logica)
        try {
            pendingLearningUpdates.clear()
            pendingLearningUpdates.addAll(storage.loadPendingLearningUpdates())
        } catch (ex: Exception) {
            println("DEBUG: failed to load pending updates in processPendingLearningUpdates: ${ex.message}")
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
            println("DEBUG: processPendingLearningUpdates removed expired=${expired.size}")
        }

        // --- NIEUW: probeer meteen pending updates te koppelen aan al aanwezige piek-data ---
        // detectPeaksFromHistoricalData() bestaat al en bevat matching + call naar updateLearningFromMealResponse(...)
        try {
            detectPeaksFromHistoricalData()
        } catch (ex: Exception) {
            println("DEBUG: detectPeaksFromHistoricalData failed inside processPendingLearningUpdates: ${ex.message}")
        }

        // --- NIEUW: probeer fallback-matching voor oudere updates (bestaande functie hergebruiken) ---
        try {
            processFallbackLearning()
        } catch (ex: Exception) {
            println("DEBUG: processFallbackLearning failed inside processPendingLearningUpdates: ${ex.message}")
        }

        // Herlaad in-memory pending list, omdat detectPeaks/processFallback mogelijk storage heeft aangepast
        try {
            pendingLearningUpdates.clear()
            pendingLearningUpdates.addAll(storage.loadPendingLearningUpdates())
        } catch (ex: Exception) {
            println("DEBUG: failed to reload pending updates after processing: ${ex.message}")
        }

        println("DEBUG: processPendingLearningUpdates complete -> pending=${pendingLearningUpdates.size}, samples=${learningProfile.totalLearningSamples}, profileLastUpdated=${learningProfile.lastUpdated}")
    }

    private fun processPendingCorrectionUpdates() {
        try {
            pendingCorrectionUpdates.clear()
            pendingCorrectionUpdates.addAll(storage.loadPendingCorrectionUpdates())
        } catch (ex: Exception) {
            println("DEBUG: failed to load pending correction updates: ${ex.message}")
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

        println("DEBUG: processPendingCorrectionUpdates complete -> pending=${pendingCorrectionUpdates.size}")
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
        peakTimestamp: DateTime
    ) {
        val adjustedGivenDose = givenDose * (getCurrentBolusAggressiveness() / 100.0)
        val actualRise = actualPeak - bgStart

        val expectedRise = if (learningProfile.personalCarbRatio > 0.0) {
            detectedCarbs / (learningProfile.personalCarbRatio * learningProfile.getMealTimeFactor(DateTime.now().hourOfDay))
        } else 0.0

        val effectiveness = if (expectedRise > 0.0) actualRise / expectedRise else 1.0

        // --- Additieve demping ---
        val learningRateCR = 0.1  // max 10% per update
        val learningRateISF = 0.05 // langzamer, en liefst met aparte correctie-data

        // A) Update Carb Ratio alleen via meals
        val crDelta = (effectiveness - 1.0) * learningRateCR
        val newCarbRatio = (learningProfile.personalCarbRatio + crDelta).coerceIn(0.5, 1.5)

        // B) ISF niet automatisch mee-updaten met meals (optioneel klein beetje)
        val isfDelta = (effectiveness - 1.0) * learningRateISF
        val newISF = (learningProfile.personalISF + isfDelta).coerceIn(0.5, 1.5)

        // --- Meal factors ---
        val newMealFactors = learningProfile.mealTimingFactors.toMutableMap()
        val currentFactor = newMealFactors[mealType] ?: 1.0
        newMealFactors[mealType] = (currentFactor + (effectiveness - 1.0) * 0.1).coerceIn(0.7, 1.3)

        // --- Sensitivity patterns ---
        val currentHour = DateTime.now().hourOfDay
        val newSensitivity = learningProfile.sensitivityPatterns.toMutableMap()
        val currentSensitivity = newSensitivity[currentHour] ?: 1.0
        newSensitivity[currentHour] = (currentSensitivity + (effectiveness - 1.0) * 0.05).coerceIn(0.6, 1.4)

        // --- Confidence ---
        val timeSinceUpdate = Hours.hoursBetween(learningProfile.lastUpdated, DateTime.now()).hours
        val newConfidence = (learningProfile.learningConfidence * exp(-timeSinceUpdate / 168.0) + 0.1).coerceIn(0.0, 1.0)

        // --- Update profile ---
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

        // --- Save meal response data ---
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

        println("DEBUG: updateLearningFromMealResponse applied. carbs=$detectedCarbs, actualRise=${"%.2f".format(actualRise)}, expectedRise=${"%.2f".format(expectedRise)}, eff=${"%.2f".format(effectiveness)}")
        println("DEBUG: new CR=${"%.3f".format(newCarbRatio)}, old CR=${"%.3f".format(learningProfile.personalCarbRatio)}, new ISF=${"%.3f".format(newISF)}")
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

        // Effectiveness = werkelijke daling / verwachte daling
        val effectiveness = if (predictedDrop > 0.0) {
            actualDrop / predictedDrop
        } else 1.0

        // Additieve demping (kleiner dan voor CR)
        val learningRateISF = 0.05
        val isfDelta = (effectiveness - 1.0) * learningRateISF

        var newISFFactor = (oldISFFactor + isfDelta).coerceIn(0.5, 1.5)

        learningProfile = learningProfile.copy(
            personalISF = newISFFactor,
            lastUpdated = DateTime.now(),
            totalLearningSamples = learningProfile.totalLearningSamples + 1,
            learningConfidence = (learningProfile.learningConfidence + 0.05).coerceAtMost(1.0)
        )

        storage.saveLearningProfile(learningProfile)

        // Logging & debug
        println("DEBUG: updateISFFromCorrectionResponse -> ins=$givenCorrectionInsulin U, " +
                    "predictedDrop=${"%.2f".format(predictedDrop)}, actualDrop=${"%.2f".format(actualDrop)}, " +
                    "eff=${"%.2f".format(effectiveness)}, oldISF=${"%.3f".format(oldISFFactor)}, newISF=${"%.3f".format(newISFFactor)}")
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
            println("DEBUG: Correction performance logged: ${correctionResult.outcome}")

        } catch (e: Exception) {
            println("DEBUG: Failed to log correction performance: ${e.message}")
        }
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
    fun getRemainingCarbs(): Double {
        val now = DateTime.now()
        return activeMeals.sumOf { it.getRemainingCarbs(now) }
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




    private fun handleProgressiveMealBolus(
        detectedCarbs: Double,
        carbRatio: Double,
        currentBG: Double,
        targetBG: Double,
        currentIOB: Double,
        currentISF: Double,
        maxBolus: Double,
        trends: TrendAnalysis

    ): Triple<Double, Double, String> {
        return calculateProgressiveBolus(
            detectedCarbs = detectedCarbs,
            carbRatio = carbRatio,
            currentISF = currentISF,
            currentIOB = currentIOB,
            currentBG = currentBG,
            targetBG = targetBG,
            maxBolus = maxBolus
        )
    }



    // Trend analysis functions
    private fun analyzeTrends(data: List<BGDataPoint>): TrendAnalysis {
        if (data.isEmpty()) return TrendAnalysis(0.0, 0.0, 0.0)

        // Gebruik smoothing voor trendcalculatie
        val smoothed = smoothBGSeries(data, alpha = 0.35)
        // Bouw een tijdelijk BGDataPoint-list met smoothed values maar behoud timestamps
        val smoothPoints = smoothed.map { (ts, bg) -> BGDataPoint(timestamp = ts, bg = bg, iob = data.find { it.timestamp == ts }?.iob ?: 0.0) }

        //  val recentTrend = calculateRecentTrend(smoothPoints, 4)
        //  val shortTermTrend = calculateShortTermTrend(smoothPoints, 2)
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
                println("DEBUG: detectPeaksFromHistoricalData failed: ${ex.message}")
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

    fun calculateProgressiveBolus(
        detectedCarbs: Double,
        carbRatio: Double,
        currentISF: Double,
        currentIOB: Double,
        currentBG: Double,
        targetBG: Double,
        maxBolus: Double,
        phase: String = "stable"
    ): Triple<Double, Double, String> {
        var reason = ""

        // 1. Basale carb-bolus
        val totalCarbBolus = detectedCarbs / carbRatio
        reason += "Carb bolus=${"%.2f".format(totalCarbBolus)}U for ${"%.1f".format(detectedCarbs)}g"

        // 2. Behoud bestaande verdeling immediate/reserved
        var immediateBolus = totalCarbBolus * 0.6
        var reservedBolus = totalCarbBolus * 0.4

        // 3. NIEUW: fase-specifieke agressiviteit (combinatie met bestaande correcties)
        val phaseFactor = getPhaseSpecificAggressiveness(phase)
        immediateBolus *= phaseFactor
        reservedBolus *= phaseFactor
        reason += " | Phase $phase (x${"%.2f".format(phaseFactor)})"

        // 4. Bestaande IOB correctie behouden
        if (currentIOB > 0.0) {
            val iobAdjustment = currentIOB / 2.0
            immediateBolus = (immediateBolus - iobAdjustment).coerceAtLeast(0.0)
            reason += " | IOB adj -${"%.2f".format(iobAdjustment)}U"
        }

        // 5. Bestaande peak damping behouden
        if (currentBG > targetBG + 2.0) {
            immediateBolus *= peakDampingFactor
            reason += " | Peak damping (x${"%.2f".format(peakDampingFactor)})"
        }

        // 6. Safety: max bolus limiter
        if (immediateBolus > maxBolus) {
            reason += " | CAPPED at ${"%.2f".format(maxBolus)}U"
            immediateBolus = maxBolus
        }

        return Triple(roundDose(immediateBolus), roundDose(reservedBolus), reason)
    }





    // Insulin advice voor closed loop
    fun getInsulinAdvice(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        currentISF: Double,
        targetBG: Double,
        carbRatio: Double,
        currentIOB: Double
    ): InsulinAdvice {
        val currentHour = DateTime.now().hourOfDay
        val hourlySensitivity = learningProfile.getHourlySensitivity(currentHour)
        val adjustedISF = currentISF * hourlySensitivity
        if (historicalData.size < 10) {
            return InsulinAdvice(0.0, "Insufficient data", 0.0)
        }

        val trends = analyzeTrends(historicalData)
        val realTimePrediction = predictRealTime(currentData, historicalData, adjustedISF, 60, carbRatio, targetBG)

        if (shouldWithholdInsulin(currentData, trends, targetBG)) {
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

    private fun shouldWithholdInsulin(currentData: BGDataPoint, trends: TrendAnalysis, targetBG: Double): Boolean {
        return when {
            // Absolute hypo-veiligheid
            currentData.bg < 5.0 -> true

            // Daling + nog in de buurt van hypo
            currentData.bg < 5.8 && trends.recentTrend < -0.3 -> true
            currentData.bg < 6.5 && trends.recentTrend < -0.5 -> true

            // Hoge IOB → alleen blokkeren als BG niet duidelijk boven target zit
            currentData.iob > 1.8 && currentData.bg < targetBG + 1.0 -> true

            // Matige IOB + duidelijke daling
            currentData.iob > 1.0 && trends.recentTrend < -0.3 -> true

            else -> false
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
        val recentLow = historicalData.takeLast(6).any { it.bg < 4.0 }
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
        currentMealState: MealDetectionState
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

        if (mealInProgress && shouldEndMealPhase(currentData, historicalData, trends)) {
            mealInProgress = false
            lastMealTime = null
            peakDetected = false
        }
        // NIEUW: Meal afronding detectie en performance logging
        if (mealInProgress && shouldEndMealPhase(currentData, historicalData, trends)) {
            logMealPerformanceResult(currentData, historicalData)
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
        historicalData: List<BGDataPoint>
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
                outcome = outcome
            )

            storage.saveMealPerformanceResult(performanceResult)
            println("DEBUG: Meal performance logged: ${performanceResult.outcome}, peak=${actualPeak}, expected=${latestUpdate.expectedPeak}")

        } catch (e: Exception) {
            println("DEBUG: Failed to log meal performance: ${e.message}")
        }
    }

    private fun analyzeMealPerformance(): ParameterAdvice? {
        try {
            val performanceData = storage.loadMealPerformanceResults()
            if (performanceData.size < 5) { // Minimaal 5 samples nodig
                return null
            }

            // Filter laatste 30 dagen
            val recentData = performanceData.filter {
                Days.daysBetween(it.timestamp, DateTime.now()).days <= 30
            }
            if (recentData.size < 3) return null

            // Bereken statistieken
            val totalMeals = recentData.size
            val successMeals = recentData.count { it.outcome == "SUCCESS" }
            val tooHighMeals = recentData.count { it.outcome == "TOO_HIGH" }
            val tooLowMeals = recentData.count { it.outcome == "TOO_LOW" }

            val successRate = successMeals.toDouble() / totalMeals
            val avgPeak = recentData.map { it.actualPeak }.average()

            // Analyseer of parameters aangepast moeten worden
            return when {
                // Te conservatief: >50% van meals te hoog
                tooHighMeals > totalMeals * 0.5 && avgPeak > 10.5 -> {
                    ParameterAdvice(
                        parameter = "bolusPercEarly",
                        currentValue = bolusPercEarly,
                        suggestedChange = +15.0,
                        confidence = (tooHighMeals.toDouble() / totalMeals).coerceIn(0.0, 1.0),
                        reason = "${tooHighMeals}/${totalMeals} meals >11mmol, avg peak: ${"%.1f".format(avgPeak)}",
                        samples = totalMeals,
                        avgPeak = avgPeak,
                        successRate = successRate
                    )
                }

                // Te agressief: >25% van meals te laag
                tooLowMeals > totalMeals * 0.25 && avgPeak < 7.0 -> {
                    ParameterAdvice(
                        parameter = "bolusPercEarly",
                        currentValue = bolusPercEarly,
                        suggestedChange = -10.0,
                        confidence = (tooLowMeals.toDouble() / totalMeals).coerceIn(0.0, 1.0),
                        reason = "${tooLowMeals}/${totalMeals} meals <6mmol, avg peak: ${"%.1f".format(avgPeak)}",
                        samples = totalMeals,
                        avgPeak = avgPeak,
                        successRate = successRate
                    )
                }

                // Peak damping te agressief
                avgPeak > 11.0 && recentData.all { it.parameters.peakDampingFactor <= 0.6 } -> {
                    ParameterAdvice(
                        parameter = "peakDampingFactor",
                        currentValue = peakDampingFactor,
                        suggestedChange = -10.0, // Verminder damping
                        confidence = 0.7,
                        reason = "High peaks with strong damping, consider reducing",
                        samples = totalMeals,
                        avgPeak = avgPeak,
                        successRate = successRate
                    )
                }

                else -> null
            }

        } catch (e: Exception) {
            println("DEBUG: analyzeMealPerformance failed: ${e.message}")
            return null
        }
    }

    private fun analyzeCorrectionPerformance(): ParameterAdvice? {
        try {
            val correctionData = storage.loadCorrectionPerformanceResults()
            if (correctionData.size < 5) return null

            val recentCorrections = correctionData.filter {
                Days.daysBetween(it.timestamp, DateTime.now()).days <= 30
            }
            if (recentCorrections.size < 3) return null

            val totalCorrections = recentCorrections.size
            val tooAggressive = recentCorrections.count { it.outcome == "TOO_AGGRESSIVE" }
            val tooConservative = recentCorrections.count { it.outcome == "TOO_CONSERVATIVE" }

            return when {
                // Correcties te agressief (>30% leidt tot te grote daling)
                tooAggressive > totalCorrections * 0.3 -> {
                    ParameterAdvice(
                        parameter = "hypoRiskFactor",
                        currentValue = hypoRiskFactor,
                        suggestedChange = +20.0, // Verhoog hypo protection
                        confidence = (tooAggressive.toDouble() / totalCorrections).coerceIn(0.0, 1.0),
                        reason = "${tooAggressive}/${totalCorrections} corrections too aggressive",
                        samples = totalCorrections,
                        avgPeak = 0.0,
                        successRate = (totalCorrections - tooAggressive).toDouble() / totalCorrections
                    )
                }
                else -> null
            }

        } catch (e: Exception) {
            println("DEBUG: analyzeCorrectionPerformance failed: ${e.message}")
            return null
        }
    }

    private fun buildParameterAdvisory(
        mealAdvice: ParameterAdvice?,
        correctionAdvice: ParameterAdvice?
    ): String {
        val advices = mutableListOf<String>()

        mealAdvice?.let { advice ->
            if (advice.confidence > 0.6) {
                advices.add("${advice.parameter} ${if (advice.suggestedChange > 0) "+" else ""}${advice.suggestedChange.toInt()}%")
            }
        }

        correctionAdvice?.let { advice ->
            if (advice.confidence > 0.6) {
                advices.add("${advice.parameter} ${if (advice.suggestedChange > 0) "+" else ""}${advice.suggestedChange.toInt()}%")
            }
        }

        return if (advices.isNotEmpty()) {
            "ADVISORY: ${advices.joinToString(", ")}"
        } else {
            ""
        }
    }

    private fun getPerformanceStatsSummary(): String {
        try {
            val mealData = storage.loadMealPerformanceResults()
            val recentMeals = mealData.filter {
                Days.daysBetween(it.timestamp, DateTime.now()).days <= 30
            }
            if (recentMeals.isEmpty()) return "No recent meal data"

            val successRate = (recentMeals.count { it.outcome == "SUCCESS" }.toDouble() / recentMeals.size * 100).toInt()
            val avgPeak = recentMeals.map { it.actualPeak }.average()
            return "$successRate% success, avg peak: ${"%.1f".format(avgPeak)} mmol/L"

        } catch (e: Exception) {
            return "Stats unavailable"
        }
    }

    private fun getParameterAdvisorySummary(): String {
        try {
            val mealAdvice = analyzeMealPerformance()
            val correctionAdvice = analyzeCorrectionPerformance()

            if (mealAdvice == null && correctionAdvice == null) {
                return "No parameter advice available (insufficient data)"
            }

            val summary = StringBuilder()
            summary.append("Parameter Advisor Summary:\n")

            mealAdvice?.let { advice ->
                if (advice.confidence > 0.6) {
                    summary.append("- ${advice.parameter}: ${if (advice.suggestedChange > 0) "+" else ""}${advice.suggestedChange.toInt()}%\n")
                    summary.append("  Reason: ${advice.reason}\n")
                    summary.append("  Confidence: ${(advice.confidence * 100).toInt()}% (${advice.samples} samples)\n")
                }
            }

            correctionAdvice?.let { advice ->
                if (advice.confidence > 0.6) {
                    summary.append("- ${advice.parameter}: ${if (advice.suggestedChange > 0) "+" else ""}${advice.suggestedChange.toInt()}%\n")
                    summary.append("  Reason: ${advice.reason}\n")
                    summary.append("  Confidence: ${(advice.confidence * 100).toInt()}% (${advice.samples} samples)\n")
                }
            }

            // Voeg performance statistieken toe
            val mealData = storage.loadMealPerformanceResults()
            val recentMeals = mealData.filter {
                Days.daysBetween(it.timestamp, DateTime.now()).days <= 30
            }
            if (recentMeals.isNotEmpty()) {
                val successRate = (recentMeals.count { it.outcome == "SUCCESS" }.toDouble() / recentMeals.size * 100).toInt()
                val avgPeak = recentMeals.map { it.actualPeak }.average()
                summary.append("Performance: $successRate% success, avg peak: ${"%.1f".format(avgPeak)} mmol/L\n")
            }

            return summary.toString()

        } catch (e: Exception) {
            return "Advisor temporarily unavailable"
        }
    }

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
            val trends = analyzeTrends(historicalData)

            val mealAdvice = analyzeMealPerformance()
            val correctionAdvice = analyzeCorrectionPerformance()
            val parameterAdvisory = buildParameterAdvisory(mealAdvice, correctionAdvice)

            processPendingLearningUpdates()
            processPendingCorrectionUpdates()
            val phase = determineMealPhase(trends.recentTrend, trends.shortTermTrend, trends.acceleration)
            // housekeeping
            cleanUpMeals()

            // COB
            val cobNow = getCarbsOnBoard()

            val basicAdvice = getInsulinAdvice(
                currentData, historicalData,
                currentISF, targetBG, carbRatio, currentIOB
            )

            // Debug: verhoog loopCounter elke cycle
            learningProfile = learningProfile.copy(
                loopCounter = learningProfile.loopCounter + 1
            )

// Probeer openstaande learning updates af te handelen
            processPendingLearningUpdates()
            processPendingCorrectionUpdates()

// Sla profiel altijd op na wijzigingen
            storage.saveLearningProfile(learningProfile)

            //     log("FCL Debug: loop=${learningProfile.loopCounter}, samples=${learningProfile.totalLearningSamples}, pending=${pendingLearningUpdates.size}")

            val shortTermTrend = calculateShortTermTrend(historicalData)
            val isDeclining = checkConsistentDecline(historicalData)
            println("DEBUG: Short-term trend: ${"%.1f".format(shortTermTrend)} mmol/L/h, Consistent decline: $isDeclining")
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

            val weakRiseThreshold = 0.5   // mmol/L/u
            val nearPeakBGMargin = 1.5    // mmol boven target
            val minCobToDeliver = 0.1     // minimaal COB om meal actief te vinden

            // === Safety ===
            if (shouldWithholdInsulin(currentData, trends, targetBG)) {
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

            // === Meal detection ===
            else {
                val (detectedCarbs, mealState) = detectMealFromBG(
                    historicalData, currentData.bg, mealDetectionSensitivity,
                    carbRatio, currentISF, targetBG
                )
                if (mealState != MealDetectionState.NONE) {
                    finalMealDetected = true
                    finalDetectedCarbs = detectedCarbs

                    // activeMeal updaten of toevoegen
                    val recentMeal = activeMeals.findLast {
                        Minutes.minutesBetween(it.timestamp, DateTime.now()).minutes < 60
                    }
                    if (recentMeal != null) {
                        recentMeal.totalCarbs += detectedCarbs
                    } else {
                        activeMeals.add(
                            ActiveCarbs(
                                timestamp = DateTime.now(),
                                totalCarbs = detectedCarbs,
                                tau = tauAbsorptionMinutes.toDouble()
                            )
                        )
                    }

                    val (immediateBolus, reserved, bolusReason) =
                        handleProgressiveMealBolus(
                            detectedCarbs,
                            carbRatio,
                            currentData.bg,
                            targetBG,
                            currentIOB,
                            currentISF,
                            maxBolus,
                            trends
                        )
                    finalReservedBolus = reserved

                    var adjustedDose = getSafeDoseWithLearning(
                        immediateBolus, null, learningProfile.learningConfidence,
                        currentIOB, trends, phase
                    )

                    finalDeliver = (trends.recentTrend > 0.0 && cobNow > minCobToDeliver)

                    // === NIEUW: Peak detection en reductie ===
                    if (isAtPeakOrDeclining(historicalData, trends)) {
                        finalReason += " | Past peak -> reduced bolus"
                        adjustedDose *= 0.3  // Zeer conservatief na peak
                        finalDeliver = trends.recentTrend > 1.0  // Alleen bij sterke stijging
                    }

                    finalReason = "Meal detected: ${"%.1f".format(detectedCarbs)}g | " +
                        bolusReason + " | BG=${"%.1f".format(currentData.bg)}, " +
                        "target=${"%.1f".format(targetBG)}, IOB=${"%.2f".format(currentIOB)}, " +
                        "trend=${"%.2f".format(trends.recentTrend)}"

                    if (parameterAdvisory.isNotEmpty()) {
                        finalReason += " | $parameterAdvisory"
                    }

                    // peak damping
                    if (trends.recentTrend <= 0.0) {
                        finalReason += " | Peak detected -> no bolus"
                        adjustedDose = 0.0
                        finalDeliver = false
                    } else if (trends.recentTrend <= weakRiseThreshold &&
                        currentData.bg > targetBG + nearPeakBGMargin
                    ) {
                        finalReason += " | Peak damping (x${"%.2f".format(peakDampingFactor)})"
                        adjustedDose *= peakDampingFactor
                    }

                    // hypo risk
                    if (isHypoRiskWithin(120, currentData.bg, currentIOB, currentISF)) {
                        finalReason += " | Hypo risk (x${"%.2f".format(hypoRiskFactor)})"
                        adjustedDose *= hypoRiskFactor
                    }

                    // === StartBoost (meal) ===
                    val deltaMeal = (predictedPeak - currentData.bg).coerceAtLeast(0.0)
                    val startBoostFactorMeal = 1.0 + (deltaMeal / 10.0).coerceIn(0.0, 0.3)
                    if (startBoostFactorMeal > 1.0) {
                        adjustedDose *= startBoostFactorMeal
                        finalReason += " | StartMealBoost(x${"%.2f".format(startBoostFactorMeal)})"
                    }

                    if (trends.recentTrend > 3.0 && currentData.bg < 9.0) {
                        val extraBoost = 1.2
                        adjustedDose *= extraBoost
                        finalReason += " | StrongEarlyBoost(x${"%.2f".format(extraBoost)})"
                    }

                    val cappedDose = min(roundDose(adjustedDose), maxBolus)
                    if (cappedDose < roundDose(adjustedDose)) {
                        finalReason += " | CAPPED at ${"%.2f".format(maxBolus)}U"
                    }

                    try {
                        if (finalDeliver && cappedDose > 0.0) {
                            val expectedPeak = basicAdvice.predictedValue ?: (currentData.bg + 2.5)
                            storeMealForLearning(
                                detectedCarbs = finalDetectedCarbs,
                                givenDose = cappedDose,
                                startBG = currentData.bg,
                                expectedPeak = expectedPeak,
                                mealType = getMealTypeFromHour()
                            )
                        }
                    } catch (ex: Exception) {
                        println("DEBUG: storeMealForLearning failed: ${ex.message}")
                    }

                    finalDose = cappedDose
                    finalPhase = "meal"
                    finalConfidence = basicAdvice.confidence * 0.9
                }

                // === Correction ===
                else if (currentData.bg > targetBG + 0.5) {
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

                    if (parameterAdvisory.isNotEmpty()) {
                        finalReason += " | $parameterAdvisory"
                    }
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
                            println("DEBUG: CorrectionUpdate saved: ins=$finalDose U, predictedDrop=${"%.2f".format(predictedDrop)}, bgStart=${currentData.bg}")
                        }
                    } catch (ex: Exception) {
                        println("DEBUG: savePendingCorrectionUpdate failed: ${ex.message}")
                    }


                }

                // === Geen actie ===
                else {
                    finalDose = 0.0
                    finalReason = "No action: BG=${"%.1f".format(currentData.bg)} ~ target=${"%.1f".format(targetBG)}"
                    finalPhase = "stable"
                    finalDeliver = false
                    finalConfidence = learningProfile.learningConfidence
                }
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
                    loopCounter = learningProfile.loopCounter,
                    parameterAdvisory = buildParameterAdvisory(mealAdvice, correctionAdvice),
                    mealPerformanceStats = getPerformanceStatsSummary()
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
                    hourlySensitivities = learningProfile.sensitivityPatterns,
                    loopCounter = learningProfile.loopCounter
                ),
                reservedDose = 0.0,
                carbsOnBoard = 0.0
            )
        }
    }


}

enum class MealDetectionState { NONE, EARLY_RISE, RISING, PEAK, DECLINING, DETECTED }