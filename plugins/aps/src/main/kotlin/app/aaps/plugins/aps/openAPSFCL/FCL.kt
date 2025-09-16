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
            // Beperk tot laatste 1000 entries
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

    private var externalReductionPercentage: Double = 100.0

    fun setExternalReductionPercentage(percentage: Int) {
        externalReductionPercentage = percentage.toDouble()
    }
    fun getLearningStatus(): String {
        return """
            FCL Learning Status:
            - Confidence: ${round(learningProfile.learningConfidence * 100, 1)}%
            - Samples: ${learningProfile.totalLearningSamples}
            - Carb Ratio Adjustment: ${round(learningProfile.personalCarbRatio, 2)}
            - ISF Adjustment: ${round(learningProfile.personalISF, 2)}
            - Last Updated: ${learningProfile.lastUpdated.toString("yyyy-MM-dd HH:mm")}
            
            Meal Time Factors:
            ${learningProfile.mealTimingFactors.entries.joinToString("\n") { "  - ${it.key}: ${round(it.value, 2)}" }}
            
            Hourly Sensitivities:
            ${learningProfile.sensitivityPatterns.entries.sortedBy { it.key }.joinToString("\n") { "  - ${it.key}:00: ${round(it.value, 2)}" }}
        """.trimIndent()
    }

    private val mealDetectionThreshold = 0.3
    private val minIOBForEffect = 0.3
    private val insulinSensitivityFactor = 3.0
    private val dailyReductionFactor = 0.7

    // State tracking
    private var lastMealTime: DateTime? = null
    private var mealInProgress = false
    private var peakDetected = false

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

    // MealDetector class
    class MealDetector {
        private var mealState = MealDetectionState.NONE
        private var lastDetectionTime = 0L
        private var detectedCarbs = 0.0
        private var lastRiseDetected = 0L

        fun detectMealFromBG(historicalData: List<BGDataPoint>, currentBG: Double): Pair<Double, MealDetectionState> {
            if (historicalData.size < 6) return Pair(0.0, MealDetectionState.NONE)
            val carbReductionFactor = 0.4

            val bg15minAgo = historicalData.getOrNull(historicalData.size - 3)?.bg ?: return Pair(0.0, MealDetectionState.NONE)
            val bg30minAgo = historicalData.getOrNull(historicalData.size - 6)?.bg ?: return Pair(0.0, MealDetectionState.NONE)

            val delta15min = currentBG - bg15minAgo
            val delta30min = currentBG - bg30minAgo
            val slope15min = delta15min * 12
            val slope30min = delta30min * 2

            if (slope30min > 1.2 && slope15min > 1.8 && delta15min > 0.4) {
                mealState = MealDetectionState.EARLY_RISE
                detectedCarbs = 20.0 * carbReductionFactor
                lastRiseDetected = System.currentTimeMillis()
                return Pair(detectedCarbs, mealState)
            }

            if (slope30min > 2.0 && delta30min > 1.2) {
                mealState = MealDetectionState.RISING
                detectedCarbs = 35.0 * carbReductionFactor
                lastRiseDetected = System.currentTimeMillis()
                return Pair(detectedCarbs, mealState)
            }

            if (slope30min > 1.8 && delta30min > 1.0) {
                mealState = MealDetectionState.RISING
                detectedCarbs = 25.0 * carbReductionFactor
                lastRiseDetected = System.currentTimeMillis()
                return Pair(detectedCarbs, mealState)
            }

            if (slope30min > 1.2 && delta30min > 0.7) {
                mealState = MealDetectionState.RISING
                detectedCarbs = 15.0 * carbReductionFactor
                lastRiseDetected = System.currentTimeMillis()
                return Pair(detectedCarbs, mealState)
            }

            if (mealState == MealDetectionState.RISING && slope15min < 1.0) {
                mealState = MealDetectionState.DETECTED
                lastDetectionTime = System.currentTimeMillis()
                return Pair(detectedCarbs, mealState)
            }

            return Pair(0.0, MealDetectionState.NONE)
        }

        fun shouldDeliverBolus(currentTime: Long): Boolean {
            return (mealState == MealDetectionState.DETECTED &&
                currentTime - lastDetectionTime < 3600000) ||
                (mealState == MealDetectionState.RISING &&
                    currentTime - lastRiseDetected < 1800000) ||
                (mealState == MealDetectionState.EARLY_RISE &&
                    currentTime - lastRiseDetected < 900000)
        }

        fun resetDetection() {
            mealState = MealDetectionState.NONE
            detectedCarbs = 0.0
        }
    }

    // Learning functions
    private fun updateLearningFromMealResponse(
        detectedCarbs: Double,
        givenDose: Double,
        predictedPeak: Double,
        actualPeak: Double,
        bgStart: Double,
        bgEnd: Double
    ) {
        val adjustedGivenDose = givenDose * (100.0 / externalReductionPercentage)
        val actualRise = actualPeak - bgStart
        val expectedRise = detectedCarbs / learningProfile.personalCarbRatio
        val effectiveness = actualRise / expectedRise

        val newCarbRatio = learningProfile.personalCarbRatio * (0.9 + (0.2 * effectiveness)).coerceIn(0.7, 1.3)
        val newISF = learningProfile.personalISF * (0.9 + (0.1 * effectiveness)).coerceIn(0.8, 1.2)

        val currentHour = DateTime.now().hourOfDay
        val mealType = when (currentHour) {
            in 6..10 -> "breakfast"
            in 11..14 -> "lunch"
            in 17..21 -> "dinner"
            else -> "other"
        }

        val newMealFactors = learningProfile.mealTimingFactors.toMutableMap()
        newMealFactors[mealType] = (newMealFactors[mealType] ?: 1.0) * 0.95 + (effectiveness * 0.05)

        val newSensitivity = learningProfile.sensitivityPatterns.toMutableMap()
        newSensitivity[currentHour] = (newSensitivity[currentHour] ?: 1.0) * 0.97 + (effectiveness * 0.03)

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

        // Save meal response data
        val mealData = MealResponseData(
            timestamp = DateTime.now(),
            carbs = detectedCarbs,
            insulinGiven = givenDose,
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


    // Nieuwe functie voor progressieve maaltijdbolus
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

    // Nieuwe functie voor reserved bolus beslissing
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

    // Hoofdfunctie voor real-time voorspelling
    fun predictRealTime(
        currentData: BGDataPoint,
        historicalData: List<BGDataPoint>,
        currentISF: Double,
        minutesAhead: Int = 60
    ): PredictionResult {
        val trends = analyzeTrends(historicalData)
        val mealDetected = detectMealRealTime(historicalData)
        val phase = determineMealPhase(trends.recentTrend, trends.shortTermTrend, trends.acceleration)

        updateMealStatusAutomatically(currentData, historicalData, trends, phase)

        val prediction = when {
            mealInProgress -> predictMealResponse(currentData.bg, trends, phase, minutesAhead)
            currentData.iob > minIOBForEffect -> predictIOBEffect(
                currentData.bg,
                currentData.iob,
                currentISF,
                minutesAhead
            )
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
        if (historicalData.size < 10) {
            return InsulinAdvice(0.0, "Insufficient data", 0.0)
        }

        val trends = analyzeTrends(historicalData)
        val realTimePrediction = predictRealTime(currentData, historicalData, currentISF, 60)

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

    private fun adjustDetectedCarbsForBG(detectedCarbs: Double, currentBG: Double, targetBG: Double): Double {
        val bgRatio = currentBG / targetBG
        return when {
            bgRatio < 0.9 -> detectedCarbs * 0.3  // Zeer lage BG
            bgRatio < 1.0 -> detectedCarbs * 0.6  // Iets lage BG
            bgRatio < 1.2 -> detectedCarbs * 0.8  // Licht verhoogde BG
            else -> detectedCarbs
        }
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
        val basicAdvice = getInsulinAdvice(currentData, historicalData, currentISF, targetBG)
        val reducedBasicDose = basicAdvice.dose * dailyReductionFactor

        checkAndAdjustExtendedBolus(currentData, historicalData, targetBG)
        cancelReservedBolusIfNotNeeded(currentData, historicalData)

        val mealDetector = MealDetector()
        val (rawDetectedCarbs, mealState) = mealDetector.detectMealFromBG(historicalData, currentData.bg)
        val actualDetectedCarbs = adjustDetectedCarbsForBG(rawDetectedCarbs, currentData.bg, targetBG)

        var mealAdjustedDose = reducedBasicDose
        var mealReason = "${basicAdvice.reason} (30% reduced)"
        var shouldDeliver = false
        var mealActuallyDetected = false

        // Apply learning adjustments
        val effectiveCarbRatio = if (learningProfile.learningConfidence > 0.6) {
            learningProfile.getEffectiveCarbRatio(carbRatio)
        } else {
            carbRatio
        }

        val effectiveISF = if (learningProfile.learningConfidence > 0.6) {
            learningProfile.getEffectiveISF(currentISF)
        } else {
            currentISF
        }

        if (mealState == MealDetectionState.EARLY_RISE && actualDetectedCarbs > 0 && !extendedBolusActive && reservedBolusAmount == 0.0) {
            val (immediateBolus, reservedBolus, progressReason) =
                handleProgressiveMealBolus(actualDetectedCarbs, effectiveCarbRatio, currentData.bg, targetBG, currentIOB)

            reservedBolusAmount = reservedBolus
            reservedBolusCarbs = actualDetectedCarbs
            reservedBolusTime = currentData.timestamp

            mealAdjustedDose += immediateBolus
            mealReason += " | Early meal: ${round(actualDetectedCarbs, 1)}g - $progressReason"
            shouldDeliver = isSafeForBolus(immediateBolus, currentIOB)
            mealActuallyDetected = true

        } else if (mealState == MealDetectionState.DETECTED && actualDetectedCarbs > 0 && !extendedBolusActive && reservedBolusAmount == 0.0) {
            val (immediateBolus, reservedBolus, progressReason) =
                handleProgressiveMealBolus(actualDetectedCarbs, effectiveCarbRatio, currentData.bg, targetBG, currentIOB)

            reservedBolusAmount = reservedBolus
            reservedBolusCarbs = actualDetectedCarbs
            reservedBolusTime = currentData.timestamp

            mealAdjustedDose += immediateBolus
            mealReason += " | Meal: ${round(actualDetectedCarbs, 1)}g - $progressReason"
            shouldDeliver = isSafeForBolus(immediateBolus, currentIOB)

            mealActuallyDetected = true

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
            reason = mealReason + " | Learning confidence: ${round(learningProfile.learningConfidence * 100, 1)}%",
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

    // Nieuwe functie voor piek-gebaseerde bolus reductie
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

        if (peakDetected && trends.recentTrend < -1.0 && minutesSinceMeal > 120) {
            return true
        }

        val startBG = historicalData.firstOrNull { it.timestamp == lastMealTime }?.bg ?: return false

        if (currentData.bg <= startBG && minutesSinceMeal > 90) {
            return true
        }

        if (minutesSinceMeal > 150 && abs(trends.recentTrend) < 0.3) {
            return true
        }

        return false
    }

    // Helper functies
    private fun analyzeTrends(data: List<BGDataPoint>): TrendAnalysis {
        return TrendAnalysis(
            recentTrend = calculateRecentTrend(data, 4),
            shortTermTrend = calculateShortTermTrend(data, 2),
            acceleration = calculateAcceleration(data, 3)
        )
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

    private fun roundDose(dose: Double): Double {
        return round(dose * 20) / 20
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

    // Trend berekeningsfuncties
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

    private fun round(value: Double, digits: Int): Double {
        val scale = Math.pow(10.0, digits.toDouble())
        return Math.round(value * scale) / scale
    }

    // Reset functie voor testing of speciale gevallen
    fun resetState() {
        lastMealTime = null
        mealInProgress = false
        peakDetected = false
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
    }

    fun getLearningConfidence(): Double {
        return learningProfile.learningConfidence
    }

    fun getLearningSamples(): Int {
        return learningProfile.totalLearningSamples
    }
}

// Enum met toegevoegde EARLY_RISE state
enum class MealDetectionState { NONE, EARLY_RISE, RISING, PEAK, DECLINING, DETECTED }