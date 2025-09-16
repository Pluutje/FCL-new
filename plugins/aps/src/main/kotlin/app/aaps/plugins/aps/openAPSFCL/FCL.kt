package app.aaps.plugins.aps.openAPSFCL

import android.content.Context
import com.google.gson.Gson
import org.joda.time.DateTime
import org.joda.time.Hours
import org.joda.time.Minutes
import kotlin.math.*


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
    }

    // Android implementatie met geïsoleerde storage
    class AndroidFCLStorage(private val context: Context) : FCLStorage {
        private val prefs = context.getSharedPreferences("FCL_Learning_Data", Context.MODE_PRIVATE)
        private val gson = Gson()

        override fun saveLearningProfile(profile: FCLLearningProfile) {
            val json = gson.toJson(profile)
            prefs.edit().putString("learning_profile", json).apply()
        }

        override fun loadLearningProfile(): FCLLearningProfile? {
            val json = prefs.getString("learning_profile", null)
            return try {
                gson.fromJson(json, FCLLearningProfile::class.java)
            } catch (e: Exception) {
                null
            }
        }

        override fun saveMealResponseData(mealData: MealResponseData) {
            val currentData = loadHistoricalMealData().toMutableList()
            currentData.add(mealData)
            val limitedData = currentData.takeLast(1000)
            val json = gson.toJson(limitedData)
            prefs.edit().putString("meal_response_data", json).apply()
        }

        override fun loadHistoricalMealData(): List<MealResponseData> {
            val json = prefs.getString("meal_response_data", null)
            return try {
                gson.fromJson(json, Array<MealResponseData>::class.java)?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        override fun savePendingLearningUpdate(update: LearningUpdate) {
            val currentUpdates = loadPendingLearningUpdates().toMutableList()
            currentUpdates.add(update)
            val json = gson.toJson(currentUpdates)
            prefs.edit().putString("pending_learning_updates", json).apply()
        }

        override fun loadPendingLearningUpdates(): List<LearningUpdate> {
            val json = prefs.getString("pending_learning_updates", null)
            return try {
                gson.fromJson(json, Array<LearningUpdate>::class.java)?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        override fun clearPendingLearningUpdates() {
            prefs.edit().remove("pending_learning_updates").apply()
        }

        override fun savePeakDetectionData(peakData: PeakDetectionData) {
            val currentData = loadPeakDetectionData().toMutableList()
            currentData.add(peakData)
            val limitedData = currentData.takeLast(500)
            val json = gson.toJson(limitedData)
            prefs.edit().putString("peak_detection_data", json).apply()
        }

        override fun loadPeakDetectionData(): List<PeakDetectionData> {
            val json = prefs.getString("peak_detection_data", null)
            return try {
                gson.fromJson(json, Array<PeakDetectionData>::class.java)?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
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

    data class PeakDetectionData(
        val timestamp: DateTime,
        val bg: Double,
        val trend: Double,
        val acceleration: Double,
        val isPeak: Boolean
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
        fun getEffectiveCarbRatio(baseRatio: Double): Double {
            return baseRatio * personalCarbRatio
        }

        fun getEffectiveISF(baseISF: Double): Double {
            return baseISF * personalISF
        }

        fun getMealTimeFactor(hour: Int): Double {
            val mealType = when (hour) {
                in 6..10 -> "breakfast"
                in 11..14 -> "lunch"
                in 17..21 -> "dinner"
                else -> "other"
            }
            return mealTimingFactors[mealType] ?: 1.0
        }

        fun getHourlySensitivity(hour: Int): Double {
            return sensitivityPatterns[hour] ?: 1.0
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
        val learningMetrics: LearningMetrics? = null
    )

    data class LearningMetrics(
        val confidence: Double,
        val samples: Int,
        val carbRatioAdjustment: Double,
        val isfAdjustment: Double,
        val mealTimeFactors: Map<String, Double>,
        val hourlySensitivities: Map<Int, Double>
    )

    data class BGDataPoint(
        val timestamp: DateTime,
        val bg: Double,
        val iob: Double
    )

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
    private var externalReductionPercentage: Double = 100.0
    private var carbReductionFactor: Double = 0.6

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

    // Extended bolus tracking
    private var extendedBolusActive = false
    private var extendedBolusAmount = 0.0
    private var extendedBolusSteps = 0
    private var extendedBolusStepSize = 0.0
    private var extendedBolusStartTime: DateTime? = null
    private var lastExtendedBolusDelivery: DateTime? = null

    // Progressieve bolus tracking
    private var reservedBolusAmount = 0.0
    private var reservedBolusCarbs = 0.0
    private var reservedBolusTime: DateTime? = null

    // Learning system
    private val storage: FCLStorage = AndroidFCLStorage(context)
    private var learningProfile: FCLLearningProfile = storage.loadLearningProfile() ?: FCLLearningProfile()

    init {
        loadPreferences(context)
        processPendingLearningUpdates()
        detectPeaksFromHistoricalData()
    }

    private fun loadPreferences(context: Context) {
        try {
            val prefs = context.getSharedPreferences("androidaps", Context.MODE_PRIVATE)
            val percentage = prefs.getInt("fcl_percentage", 100)
            setExternalReductionPercentage(percentage)

            val carbPercentage = prefs.getInt("carb_percentage", 60)
            carbReductionFactor = carbPercentage / 100.0
        } catch (e: Exception) {
            setExternalReductionPercentage(100)
            carbReductionFactor = 0.6
        }
    }

    fun setExternalReductionPercentage(percentage: Int) {
        externalReductionPercentage = percentage.toDouble()
    }

    fun setCarbReductionPercentage(percentage: Int) {
        carbReductionFactor = percentage / 100.0
    }

    fun getLearningStatus(): String {
        val currentHour = DateTime.now().hourOfDay
        return """
        FCL Learning Status:
        - Confidence: ${round(learningProfile.learningConfidence * 100, 1)}%
        - Samples: ${learningProfile.totalLearningSamples}
        - Carb Ratio Adjustment: ${round(learningProfile.personalCarbRatio, 2)}
        - ISF Adjustment: ${round(learningProfile.personalISF, 2)}
        - Current Meal Factor: ${round(learningProfile.getMealTimeFactor(currentHour), 2)}
        - Current Sensitivity: ${round(learningProfile.getHourlySensitivity(currentHour), 2)}
        - Bolus %: ${externalReductionPercentage.toInt()}%
        - Carb Calc %: ${round(carbReductionFactor * 100, 0)}%
        - Last Updated: ${learningProfile.lastUpdated.toString("yyyy-MM-dd HH:mm")}
        
        Meal Time Factors:
        ${learningProfile.mealTimingFactors.entries.joinToString("\n") { "  - ${it.key}: ${round(it.value, 2)}" }}
        
        Hourly Sensitivities:
        ${learningProfile.sensitivityPatterns.entries.sortedBy { it.key }.joinToString("\n") { "  - ${it.key}:00: ${round(it.value, 2)}" }}
    """.trimIndent()
    }

    // Automatic peak detection from historical data
    private fun detectPeaksFromHistoricalData() {
        val historicalData = storage.loadPeakDetectionData()
        if (historicalData.size < 10) return

        val pendingUpdates = storage.loadPendingLearningUpdates()
        if (pendingUpdates.isEmpty()) return

        // Zoek peaks in de historische data
        val peaks = mutableListOf<PeakDetectionData>()
        for (i in 2 until historicalData.size - 2) {
            val current = historicalData[i]
            val prev1 = historicalData[i - 1]
            val prev2 = historicalData[i - 2]
            val next1 = historicalData[i + 1]
            val next2 = historicalData[i + 2]

            // Check of dit een peak is (bg daalt na stijging)
            val isPeak = current.bg > prev1.bg && current.bg > prev2.bg &&
                current.bg > next1.bg && current.bg > next2.bg &&
                current.trend < 0 && next1.trend < 0

            if (isPeak) {
                peaks.add(current.copy(isPeak = true))
            }
        }

        // Koppel peaks aan pending learning updates
        peaks.forEach { peak ->
            val correspondingUpdate = pendingUpdates.find { update ->
                Minutes.minutesBetween(update.timestamp, peak.timestamp).minutes in 40..peakDetectionWindow
            }

            correspondingUpdate?.let { update ->
                // Update learning met werkelijke peak
                updateLearningFromMealResponse(
                    detectedCarbs = update.detectedCarbs,
                    givenDose = update.givenDose,
                    predictedPeak = update.expectedPeak,
                    actualPeak = peak.bg,
                    bgStart = update.startBG,
                    bgEnd = peak.bg,
                    mealType = update.mealType
                )
            }
        }

        // Verwijder verwerkte updates
        val updatesToRemove = pendingUpdates.filter { update ->
            peaks.any { peak ->
                Minutes.minutesBetween(update.timestamp, peak.timestamp).minutes in 40..peakDetectionWindow
            }
        }

        if (updatesToRemove.isNotEmpty()) {
            val remainingUpdates = pendingUpdates - updatesToRemove.toSet()
            storage.clearPendingLearningUpdates()
            remainingUpdates.forEach { storage.savePendingLearningUpdate(it) }
        }
    }

    // Meal detection functie
    private fun detectMealFromBG(historicalData: List<BGDataPoint>, currentBG: Double): Pair<Double, MealDetectionState> {
        if (historicalData.size < 6) return Pair(0.0, MealDetectionState.NONE)

        val bg15minAgo = historicalData.getOrNull(historicalData.size - 3)?.bg ?: return Pair(0.0, MealDetectionState.NONE)
        val bg30minAgo = historicalData.getOrNull(historicalData.size - 6)?.bg ?: return Pair(0.0, MealDetectionState.NONE)

        val delta15min = currentBG - bg15minAgo
        val delta30min = currentBG - bg30minAgo
        val slope15min = delta15min * 4
        val slope30min = delta30min * 2

        var localDetectedCarbs = 0.0
        var detectedState = MealDetectionState.NONE

        when {
            slope30min > 2.0 && slope15min > 3.0 && delta15min > 0.6 -> {
                detectedState = MealDetectionState.EARLY_RISE
                localDetectedCarbs = 40.0 * carbReductionFactor
            }
            slope30min > 3.0 && delta30min > 1.8 -> {
                detectedState = MealDetectionState.RISING
                localDetectedCarbs = 60.0 * carbReductionFactor
            }
            slope30min > 2.5 && delta30min > 1.5 -> {
                detectedState = MealDetectionState.RISING
                localDetectedCarbs = 45.0 * carbReductionFactor
            }
            slope30min > 1.8 && delta30min > 1.0 -> {
                detectedState = MealDetectionState.RISING
                localDetectedCarbs = 30.0 * carbReductionFactor
            }
            slope30min > 1.2 && delta30min > 0.7 -> {
                detectedState = MealDetectionState.RISING
                localDetectedCarbs = 20.0 * carbReductionFactor
            }
        }

        // Update state tracking
        if (detectedState != MealDetectionState.NONE) {
            mealDetectionState = detectedState
            lastRiseDetectionTime = DateTime.now()
        }

        return Pair(localDetectedCarbs, detectedState)
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
        val peakData = PeakDetectionData(
            timestamp = currentData.timestamp,
            bg = currentData.bg,
            trend = trends.recentTrend,
            acceleration = trends.acceleration,
            isPeak = false // Wordt later bepaald
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
        storage.savePendingLearningUpdate(learningUpdate)
    }

    private fun processPendingLearningUpdates() {
        val pendingUpdates = storage.loadPendingLearningUpdates()
        val now = DateTime.now()

        // Verwijder updates ouder dan 6 uur (te oud voor betrouwbare peak detectie)
        val expiredUpdates = pendingUpdates.filter { update ->
            Minutes.minutesBetween(update.timestamp, now).minutes > 360
        }

        if (expiredUpdates.isNotEmpty()) {
            val remainingUpdates = pendingUpdates - expiredUpdates.toSet()
            storage.clearPendingLearningUpdates()
            remainingUpdates.forEach { storage.savePendingLearningUpdate(it) }
        }
    }

    private fun updateLearningFromMealResponse(
        detectedCarbs: Double,
        givenDose: Double,
        predictedPeak: Double,
        actualPeak: Double,
        bgStart: Double,
        bgEnd: Double,
        mealType: String
    ) {
        val adjustedGivenDose = givenDose * (100.0 / externalReductionPercentage)
        val actualRise = actualPeak - bgStart

        val expectedRise = detectedCarbs / (learningProfile.personalCarbRatio * learningProfile.getMealTimeFactor(DateTime.now().hourOfDay))
        val effectiveness = (actualRise / expectedRise).coerceIn(0.5, 1.5)


        val newCarbRatio = learningProfile.personalCarbRatio * (0.9 + (0.2 * effectiveness)).coerceIn(0.7, 1.3)
        val newISF = learningProfile.personalISF * (0.9 + (0.1 * effectiveness)).coerceIn(0.8, 1.2)

        val newMealFactors = learningProfile.mealTimingFactors.toMutableMap()
        val currentFactor = newMealFactors[mealType] ?: 1.0
        newMealFactors[mealType] = (currentFactor * 0.8 + (effectiveness * 0.2)).coerceIn(0.7, 1.3)

        val currentHour = DateTime.now().hourOfDay
        val newSensitivity = learningProfile.sensitivityPatterns.toMutableMap()
        val currentSensitivity = newSensitivity[currentHour] ?: 1.0
        newSensitivity[currentHour] = (currentSensitivity * 0.9 + (effectiveness * 0.1)).coerceIn(0.8, 1.2)

        val timeSinceUpdate = Hours.hoursBetween(learningProfile.lastUpdated, DateTime.now()).hours
        val newConfidence = (learningProfile.learningConfidence * exp(-timeSinceUpdate / 168.0) + 0.1).coerceIn(0.0, 1.0)

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

        val mealData = MealResponseData(
            timestamp = DateTime.now(),
            carbs = detectedCarbs,
            insulinGiven = adjustedGivenDose,
            predictedPeak = predictedPeak,
            actualPeak = actualPeak,
            timeToPeak = Minutes.minutesBetween(DateTime.now(), DateTime.now()).minutes,
            bgStart = bgStart,
            bgEnd = bgEnd
        )
        storage.saveMealResponseData(mealData)
    }

    private fun getSafeDoseWithLearning(
        calculatedDose: Double,
        learnedDose: Double?,
        confidence: Double
    ): Double {
        return when {
            confidence > 0.8 -> learnedDose ?: calculatedDose
            confidence > 0.6 -> (learnedDose ?: calculatedDose) * 0.8
            else -> calculatedDose * 0.7
        }
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
        currentIOB: Double
    ): Triple<Double, Double, String> {
        val fullMealBolus = (detectedCarbs / carbRatio)
        val distanceToTarget = currentBG - targetBG

        val initialPercentage = when {
            distanceToTarget < 0.5 -> 0.3
            distanceToTarget < 1.0 -> 0.4
            else -> 0.6
        }

        val immediateBolus = fullMealBolus * initialPercentage
        val reservedBolus = fullMealBolus * (1 - initialPercentage)

        return Triple(immediateBolus, reservedBolus,
                      "Progressive: ${round(immediateBolus, 2)}U now, ${round(reservedBolus, 2)}U reserved")
    }

    private fun shouldDeliverReservedBolus(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>
    ): Boolean {
        if (reservedBolusTime == null) return false

        val minutesSinceReserved = Minutes.minutesBetween(reservedBolusTime, currentData.timestamp).minutes
        val trends = analyzeTrends(historicalData)
        val originalBG = historicalData.firstOrNull { it.timestamp == reservedBolusTime }?.bg ?: 0.0

        return minutesSinceReserved >= 5 &&
            trends.recentTrend > 1.0 &&
            currentData.bg > originalBG + 0.3
    }

    private fun cancelReservedBolusIfNotNeeded(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>
    ) {
        if (reservedBolusAmount > 0 && reservedBolusTime != null) {
            val minutesSinceReserved = Minutes.minutesBetween(reservedBolusTime, currentData.timestamp).minutes
            val trends = analyzeTrends(historicalData)

            if (minutesSinceReserved > 15 && trends.recentTrend < 0.5) {
                reservedBolusAmount = 0.0
                reservedBolusCarbs = 0.0
                reservedBolusTime = null
            }
        }
    }

    // Trend analysis functions
    private fun analyzeTrends(data: List<BGDataPoint>): TrendAnalysis {
        val recentTrend = calculateRecentTrend(data, 4)
        val shortTermTrend = calculateShortTermTrend(data, 2)
        val acceleration = calculateAcceleration(data, 3)

        // Store voor peak detectie
        if (data.isNotEmpty()) {
            storePeakDetectionData(data.last(), TrendAnalysis(recentTrend, shortTermTrend, acceleration))
        }

        return TrendAnalysis(recentTrend, shortTermTrend, acceleration)
    }

    private fun calculateRecentTrend(data: List<BGDataPoint>, pointsBack: Int): Double {
        if (data.size <= pointsBack) return 0.0
        val currentIndex = data.lastIndex
        val timeDiff = (data[currentIndex].timestamp.millis - data[currentIndex - pointsBack].timestamp.millis) / 60000.0
        if (timeDiff <= 0) return 0.0
        val bgDiff = data[currentIndex].bg - data[currentIndex - pointsBack].bg
        return (bgDiff / timeDiff) * 60
    }

    private fun calculateShortTermTrend(data: List<BGDataPoint>, points: Int): Double {
        if (data.size <= points) return 0.0
        val currentIndex = data.lastIndex
        val timeDiff = (data[currentIndex].timestamp.millis - data[currentIndex - points].timestamp.millis) / 60000.0
        if (timeDiff <= 0) return 0.0
        val bgDiff = data[currentIndex].bg - data[currentIndex - points].bg
        return (bgDiff / timeDiff) * 60
    }

    private fun calculateAcceleration(data: List<BGDataPoint>, points: Int): Double {
        if (data.size <= points * 2) return 0.0
        val currentIndex = data.lastIndex
        val recentTrend = calculateShortTermTrend(data.take(currentIndex + 1), 1)
        val previousTrend = calculateShortTermTrend(data.take(currentIndex - points + 1), 1)
        val timeDiff = (data[currentIndex].timestamp.millis - data[currentIndex - points].timestamp.millis) / 60000.0
        if (timeDiff <= 0) return 0.0
        return (recentTrend - previousTrend) / timeDiff
    }

    private fun detectMealRealTime(data: List<BGDataPoint>): Boolean {
        if (data.size < 4) return false
        val currentBG = data.last().bg
        val bg20minAgo = data[data.size - 3].bg
        val instantRise = currentBG - bg20minAgo
        return instantRise > mealDetectionThreshold && checkConsistentRise(data, 3)
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
        if (iob == 0.0 || isf == 0.0) return currentBG
        val hours = minutesAhead / 60.0
        val baseDrop = (iob / max(1.0, isf)) * insulinSensitivityFactor
        val effectiveDrop = baseDrop * (1 - exp(-hours / 1.5))
        return max(3.5, currentBG - effectiveDrop)
    }

    private fun predictBasalResponse(currentBG: Double, trends: TrendAnalysis, minutesAhead: Int): Double {
        return currentBG + (trends.recentTrend * minutesAhead / 60 * 0.3)
    }

    // Hoofdfunctie voor real-time voorspelling
    fun predictRealTime(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        currentISF: Double,
        minutesAhead: Int = 60
    ): PredictionResult {
        val currentHour = DateTime.now().hourOfDay
        val hourlySensitivity = learningProfile.getHourlySensitivity(currentHour)
        val adjustedISF = currentISF * hourlySensitivity

        val trends = analyzeTrends(historicalData)
        val mealDetected = detectMealRealTime(historicalData)
        val phase = determineMealPhase(trends.recentTrend, trends.shortTermTrend, trends.acceleration)

        updateMealStatusAutomatically(currentData, historicalData, trends, phase)

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

    // Insulin advice voor closed loop
    fun getInsulinAdvice(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        currentISF: Double,
        targetBG: Double
    ): InsulinAdvice {
        val currentHour = DateTime.now().hourOfDay
        val hourlySensitivity = learningProfile.getHourlySensitivity(currentHour)
        val adjustedISF = currentISF * hourlySensitivity
        if (historicalData.size < 10) {
            return InsulinAdvice(0.0, "Insufficient data", 0.0)
        }

        val trends = analyzeTrends(historicalData)
        val realTimePrediction = predictRealTime(currentData, historicalData, adjustedISF, 60)

        if (shouldWithholdInsulin(currentData, trends)) {
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
        return roundDose(conservativeDose)
    }

    private fun shouldWithholdInsulin(currentData: BGDataPoint, trends: TrendAnalysis): Boolean {
        return when {
            currentData.bg < 5.8 -> true
            currentData.bg < 6.5 && trends.recentTrend < -0.5 -> true
            currentData.iob > 1.0 && trends.recentTrend < 0 -> true
            currentData.iob > 1.8 -> true
            else -> false
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

    private fun adjustDetectedCarbsForBG(detectedCarbs: Double, currentBG: Double, targetBG: Double): Double {
        val bgRatio = currentBG / targetBG
        return when {
            bgRatio < 0.9 -> detectedCarbs * 0.3
            bgRatio < 1.0 -> detectedCarbs * 0.6
            bgRatio < 1.2 -> detectedCarbs * 0.8
            else -> detectedCarbs
        }
    }

    private fun adjustBolusForPeakPhase(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        proposedDose: Double
    ): Double {
        if (proposedDose <= 0) return proposedDose
        val trends = analyzeTrends(historicalData)

        val isApproachingPeak = trends.recentTrend < 1.0 && trends.acceleration < -0.1
        val isAtPeak = trends.recentTrend < 0.3 && trends.acceleration < -0.2
        val isPostPeak = trends.recentTrend < -0.3

        val iobFactor = when {
            currentData.iob > 3.0 -> 0.2
            currentData.iob > 2.0 -> 0.3
            currentData.iob > 1.5 -> 0.5
            currentData.iob > 1.0 -> 0.7
            else -> 1.0
        }

        return when {
            isAtPeak -> proposedDose * 0.3 * iobFactor
            isApproachingPeak -> proposedDose * 0.6 * iobFactor
            isPostPeak -> proposedDose * 0.2 * iobFactor
            else -> proposedDose * iobFactor
        }
    }

    // Automatische update van maaltijdstatus
    private fun updateMealStatusAutomatically(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        trends: TrendAnalysis,
        phase: String
    ) {
        val currentTime = currentData.timestamp

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

    private fun checkAndAdjustExtendedBolus(currentData: BGDataPoint, historicalData: List<BGDataPoint>, targetBG: Double) {
        if (!extendedBolusActive) return
        val trends = analyzeTrends(historicalData)
        val minutesSinceStart = extendedBolusStartTime?.let {
            Minutes.minutesBetween(it, currentData.timestamp).minutes
        } ?: 0

        if (trends.recentTrend < -3.0) {
            cancelExtendedBolus("BG dropping very fast")
            return
        }
        if (currentData.bg < 5.0) {
            cancelExtendedBolus("BG approaching low")
            return
        }
        if (minutesSinceStart > 30) {
            cancelExtendedBolus("Extended bolus time expired")
            return
        }
        if (minutesSinceStart > 15 && trends.recentTrend < 0.2 && currentData.bg < targetBG + 1.0) {
            cancelExtendedBolus("BG well controlled")
            return
        }

        val isNearPeak = trends.recentTrend < 0.5 && trends.acceleration < -0.1 && currentData.bg > targetBG + 2.0
        if (isNearPeak && extendedBolusActive) {
            extendedBolusAmount *= 0.5
            extendedBolusSteps = max(1, extendedBolusSteps / 2)
            extendedBolusStepSize = extendedBolusAmount / extendedBolusSteps
        }
    }

    private fun getNextExtendedBolusStep(currentTime: DateTime): Double {
        if (!extendedBolusActive || extendedBolusAmount <= 0) return 0.0
        val minutesSinceLastDelivery = lastExtendedBolusDelivery?.let {
            Minutes.minutesBetween(it, currentTime).minutes
        } ?: 999

        if (minutesSinceLastDelivery >= 5) {
            val step = min(extendedBolusStepSize, extendedBolusAmount)
            extendedBolusAmount -= step
            extendedBolusSteps--
            if (extendedBolusAmount <= 0.01 || extendedBolusSteps <= 0) {
                extendedBolusActive = false
            }
            return step
        }
        return 0.0
    }

    private fun cancelExtendedBolus(reason: String) {
        extendedBolusActive = false
        extendedBolusAmount = 0.0
        extendedBolusSteps = 0
    }

    // Safety check voor bolus
    private fun isSafeForBolus(proposedBolus: Double, currentIOB: Double): Boolean {
        val maxBolus = when {
            currentIOB > 3.0 -> 1.0
            currentIOB > 2.0 -> 1.5
            currentIOB > 1.0 -> 2.0
            else -> 2.5
        }
        return proposedBolus <= maxBolus && proposedBolus > 0.1
    }

    private fun roundDose(dose: Double): Double {
        return round(dose * 20) / 20
    }

    private fun round(value: Double, digits: Int): Double {
        val scale = Math.pow(10.0, digits.toDouble())
        return Math.round(value * scale) / scale
    }

    // Enhanced insulin advice met automatische maaltijd detectie
    fun getEnhancedInsulinAdvice(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        currentISF: Double,
        targetBG: Double,
        carbRatio: Double,
        currentIOB: Double
    ): EnhancedInsulinAdvice {
        val currentHour = DateTime.now().hourOfDay
        val mealTimeFactor = learningProfile.getMealTimeFactor(currentHour)
        val hourlySensitivity = learningProfile.getHourlySensitivity(currentHour)

        // Tijd-aangepaste ratios
        val timeAdjustedCarbRatio = carbRatio * mealTimeFactor
        val timeAdjustedISF = currentISF * hourlySensitivity

        // Learning-aangepaste ratios (alleen als confidence hoog genoeg is)
        val effectiveCarbRatio = if (learningProfile.learningConfidence > 0.6) {
            learningProfile.getEffectiveCarbRatio(timeAdjustedCarbRatio)
        } else {
            carbRatio  // Origineel, niet tijd-aangepast
        }

        val effectiveISF = if (learningProfile.learningConfidence > 0.6) {
            learningProfile.getEffectiveISF(timeAdjustedISF)
        } else {
            currentISF  // Origineel, niet tijd-aangepast
        }

        // Gebruik effectiveISF voor alle berekeningen
        val basicAdvice = getInsulinAdvice(currentData, historicalData, effectiveISF, targetBG)
        val reducedBasicDose = basicAdvice.dose * dailyReductionFactor

        checkAndAdjustExtendedBolus(currentData, historicalData, targetBG)
        cancelReservedBolusIfNotNeeded(currentData, historicalData)

        val (rawDetectedCarbs, mealState) = detectMealFromBG(historicalData, currentData.bg)
        val actualDetectedCarbs = adjustDetectedCarbsForBG(rawDetectedCarbs, currentData.bg, targetBG)

        var mealAdjustedDose = reducedBasicDose
        var mealReason = "${basicAdvice.reason} (${(dailyReductionFactor * 100).toInt()}% reduced)"
        var shouldDeliver = false
        var mealActuallyDetected = false

        if (mealState == MealDetectionState.EARLY_RISE && actualDetectedCarbs > 0 && !extendedBolusActive && reservedBolusAmount == 0.0) {
            val (immediateBolus, reservedBolus, progressReason) =
                handleProgressiveMealBolus(actualDetectedCarbs, effectiveCarbRatio, currentData.bg, targetBG, currentIOB)

            reservedBolusAmount = reservedBolus
            reservedBolusCarbs = actualDetectedCarbs
            reservedBolusTime = currentData.timestamp

            mealAdjustedDose += immediateBolus
            mealReason += " | Early meal: ${round(actualDetectedCarbs, 1)}g - $progressReason"
            shouldDeliver = isSafeForBolus(immediateBolus, currentIOB) && shouldDeliverBolusBasedOnState()
            mealActuallyDetected = true

            // Store for learning - gebruik effectiveISF voor voorspelling
            val predictedPeak = predictRealTime(currentData, historicalData, effectiveISF, 90).value
            val mealType = getMealTypeFromHour()
            storeMealForLearning(actualDetectedCarbs, immediateBolus, currentData.bg, predictedPeak, mealType)

        } else if (mealState == MealDetectionState.DETECTED && actualDetectedCarbs > 0 && !extendedBolusActive && reservedBolusAmount == 0.0) {
            val (immediateBolus, reservedBolus, progressReason) =
                handleProgressiveMealBolus(actualDetectedCarbs, effectiveCarbRatio, currentData.bg, targetBG, currentIOB)

            reservedBolusAmount = reservedBolus
            reservedBolusCarbs = actualDetectedCarbs
            reservedBolusTime = currentData.timestamp

            mealAdjustedDose += immediateBolus
            mealReason += " | Meal: ${round(actualDetectedCarbs, 1)}g - $progressReason"
            shouldDeliver = isSafeForBolus(immediateBolus, currentIOB) && shouldDeliverBolusBasedOnState()
            mealActuallyDetected = true

            val predictedPeak = predictRealTime(currentData, historicalData, effectiveISF, 90).value
            val mealType = getMealTypeFromHour()
            storeMealForLearning(actualDetectedCarbs, immediateBolus, currentData.bg, predictedPeak, mealType)

        } else if (reservedBolusAmount > 0 && shouldDeliverReservedBolus(currentData, historicalData)) {
            mealAdjustedDose += reservedBolusAmount
            mealReason += " | Reserved bolus: ${round(reservedBolusAmount, 2)}U (confirmed rise)"
            shouldDeliver = true
            reservedBolusAmount = 0.0
            reservedBolusCarbs = 0.0
            reservedBolusTime = null

        } else if (extendedBolusActive) {
            val nextBolusStep = getNextExtendedBolusStep(currentData.timestamp)
            if (nextBolusStep > 0) {
                val reducedStep = when {
                    currentData.iob > 2.0 -> nextBolusStep * 0.3
                    currentData.iob > 1.5 -> nextBolusStep * 0.5
                    currentData.iob > 1.0 -> nextBolusStep * 0.7
                    else -> nextBolusStep
                }
                mealAdjustedDose += reducedStep
                mealReason += " | Ext bolus: ${round(reducedStep, 2)}U (reduced)"
                shouldDeliver = true
                lastExtendedBolusDelivery = currentData.timestamp
            }
        }

        mealAdjustedDose = adjustBolusForPeakPhase(currentData, historicalData, mealAdjustedDose)

        if (mealAdjustedDose < reducedBasicDose) {
            mealReason += " | Reduced due to peak phase detection"
        }

        // Apply final learning-based safety
        mealAdjustedDose = getSafeDoseWithLearning(
            mealAdjustedDose,
            null,
            learningProfile.learningConfidence
        )

        val finalDose = mealAdjustedDose * (externalReductionPercentage / 100.0)

        return EnhancedInsulinAdvice(
            dose = roundDose(finalDose),
            reason = mealReason + " | Learning confidence: ${round(learningProfile.learningConfidence * 100, 1)}%" +
                " | Meal factor: ${round(mealTimeFactor, 2)}" +
                " | Hourly sens: ${round(hourlySensitivity, 2)}",
            confidence = basicAdvice.confidence * 0.9,
            predictedValue = basicAdvice.predictedValue,
            mealDetected = mealActuallyDetected,
            detectedCarbs = actualDetectedCarbs,
            shouldDeliverBolus = shouldDeliver,
            phase = basicAdvice.phase,
            learningMetrics = LearningMetrics(
                confidence = learningProfile.learningConfidence,
                samples = learningProfile.totalLearningSamples,
                carbRatioAdjustment = learningProfile.personalCarbRatio,
                isfAdjustment = learningProfile.personalISF,
                mealTimeFactors = learningProfile.mealTimingFactors,
                hourlySensitivities = learningProfile.sensitivityPatterns
            )
        )
    }

    // Reset functie voor testing of speciale gevallen
    /*
    fun resetState() {
        lastMealTime = null
        mealInProgress = false
        peakDetected = false
        mealDetectionState = MealDetectionState.NONE
        lastMealDetectionTime = null
        lastRiseDetectionTime = null
        extendedBolusActive = false
        extendedBolusAmount = 0.0
        extendedBolusSteps = 0
        reservedBolusAmount = 0.0
        reservedBolusCarbs = 0.0
        reservedBolusTime = null
    }

    fun resetLearning() {
        learningProfile = FCLLearningProfile()
        storage.saveLearningProfile(learningProfile)
        storage.clearPendingLearningUpdates()
    }

    fun getLearningConfidence(): Double {
        return learningProfile.learningConfidence
    }

    fun getLearningSamples(): Int {
        return learningProfile.totalLearningSamples
    }

    */
}

enum class MealDetectionState { NONE, EARLY_RISE, RISING, PEAK, DECLINING, DETECTED }