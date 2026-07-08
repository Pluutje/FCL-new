package app.aaps.wear.activitytype

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.wear.comm.IntentWearToMobile
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import io.reactivex.rxjava3.disposables.Disposable

/**
 * ActivityTypeListener (06/07/2026, Ecko) — gebruikt Android's
 * ActivityRecognitionClient (Google Play Services) om automatisch te
 * herkennen of de drager fietst, loopt, rent, stilzit, etc., en stuurt dit
 * naar de telefoon zodat FCLvNext de calorie-schatting kan corrigeren voor
 * activiteiten die weinig stappen maar wel substantiële inspanning geven
 * (met name fietsen — zie EstimatedCaloriesCalculator.kt op de telefoon).
 *
 * ANDERE VORM dan HeartRateListener/StepCountListener: die luisteren naar een
 * kale Sensor (SensorEventListener, synchroon, direct hardware-toegang).
 * ActivityRecognitionClient is een asynchrone Google-service die zelf een
 * ML-classificatie doet over een bewegingspatroon van enkele minuten, en
 * levert resultaten via een BroadcastReceiver/PendingIntent — vandaar de
 * afwijkende opzet hieronder, ook al is de buitenkant (Disposable, init/
 * dispose, aapsLogger, LTag.WEAR) bewust zoveel mogelijk gelijk gehouden aan
 * de bestaande twee listeners.
 *
 * VEREIST (nog niet gecontroleerd/toegevoegd, zie overdracht):
 *   - Gradle-dependency play-services-location in de wear-module (los van de
 *     al aanwezige play-services-wearable voor de Data Layer).
 *   - Permissie android.permission.ACTIVITY_RECOGNITION in het manifest van
 *     de WEAR-module zelf (een ander manifest dan het telefoon-app-manifest
 *     dat we eerder al hebben aangepast voor de calorieën-permissie).
 */
class ActivityTypeListener(
    private val ctx: Context,
    private val aapsLogger: AAPSLogger,
) : Disposable {

    private var disposed = false
    // 07/07/2026 (Ecko) — nullable + defensief geïnitialiseerd. ActivityRecognition.getClient()
    // zelf zou normaal niet moeten gooien, maar dit staat vóór elke try/catch in de klasse —
    // een class-property-initializer die faalt, laat de HELE constructie mislukken, en dus
    // (via DataLayerListenerServiceWear.onCreate()) de hele wear-service crashen. Nooit
    // meer laten gebeuren, zie ook de bredere catch in het init-block hieronder.
    private val client = try {
        ActivityRecognition.getClient(ctx)
    } catch (e: Exception) {
        aapsLogger.error(LTag.WEAR, "ActivityRecognition.getClient() faalde: ${e.message}")
        null
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!ActivityRecognitionResult.hasResult(intent)) return
            val result = ActivityRecognitionResult.extractResult(intent) ?: return
            val most = result.mostProbableActivity
            send(most.type, most.confidence)
        }
    }

    private val pendingIntent: PendingIntent by lazy {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_MUTABLE else 0
        PendingIntent.getBroadcast(ctx, REQUEST_CODE, Intent(ACTION_ACTIVITY_UPDATE), flags)
    }

    init {
        aapsLogger.info(LTag.WEAR, "Create ${javaClass.simpleName}")
        // 07/07/2026 (Ecko) — verbreed van catch (e: SecurityException) naar een
        // brede catch: op sommige toestellen (verouderde/beperkte Play Services op
        // het horloge) kan ActivityRecognition.getClient()/requestActivityUpdates()
        // ook een ANDER type fout gooien dan SecurityException. Ongevangen zou dat
        // de hele constructor laten falen, en daarmee — omdat deze listener vanuit
        // DataLayerListenerServiceWear.onCreate() wordt aangemaakt — die service in
        // zijn geheel laten crashen bij het opstarten. Dat is precies wat "No Watch
        // Connected" op de telefoon zou verklaren: de service komt onCreate() dan
        // nooit voorbij, en meldt zich dus nooit bij de Data Layer/Capability-API.
        // Deze feature mag de rest van de wear-app nooit kunnen platleggen.
        try {
            ContextCompat.registerReceiver(
                ctx, receiver, IntentFilter(ACTION_ACTIVITY_UPDATE), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            client?.requestActivityUpdates(DETECTION_INTERVAL_MILLIS, pendingIntent)
                ?.addOnFailureListener { e ->
                    aapsLogger.error(LTag.WEAR, "Could not start activity updates: ${e.message}")
                }
        } catch (e: SecurityException) {
            // ACTIVITY_RECOGNITION niet toegestaan — stil falen, net als de andere
            // listeners doen wanneer hun sensor/permissie ontbreekt.
            aapsLogger.warn(LTag.WEAR, "Missing ACTIVITY_RECOGNITION permission: ${e.message}")
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "ActivityTypeListener kon niet starten: ${e.message}")
        }
    }

    @VisibleForTesting
    var sendActivityType: (EventData.ActionActivityType) -> Unit =
        { a -> ctx.startService(IntentWearToMobile(ctx, a)) }

    private fun send(type: Int, confidence: Int) {
        val name = activityTypeName(type)
        val device = (Build.MANUFACTURER ?: "unknown") + " " + (Build.MODEL ?: "unknown")
        aapsLogger.info(LTag.WEAR, "Detected activity $name ($confidence%)")
        sendActivityType(
            EventData.ActionActivityType(
                timestamp = System.currentTimeMillis(),
                activityType = name,
                confidencePct = confidence,
                device = device
            )
        )
    }

    private fun activityTypeName(type: Int): String = when (type) {
        DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
        DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
        DetectedActivity.ON_FOOT    -> "ON_FOOT"
        DetectedActivity.RUNNING    -> "RUNNING"
        DetectedActivity.STILL      -> "STILL"
        DetectedActivity.TILTING    -> "TILTING"
        DetectedActivity.WALKING    -> "WALKING"
        else                        -> "UNKNOWN"
    }

    override fun isDisposed() = disposed

    override fun dispose() {
        aapsLogger.info(LTag.WEAR, "Dispose ${javaClass.simpleName}")
        try {
            client?.removeActivityUpdates(pendingIntent)
        } catch (e: SecurityException) {
            aapsLogger.error(LTag.WEAR, "Error removing activity updates: ${e.message}")
        }
        try {
            ctx.unregisterReceiver(receiver)
        } catch (e: Exception) {
            // al ontkoppeld of nooit succesvol geregistreerd — onschadelijk
        }
        disposed = true
    }

    companion object {

        private const val ACTION_ACTIVITY_UPDATE = "app.aaps.wear.ACTIVITY_RECOGNITION_UPDATE"
        private const val REQUEST_CODE = 4271

        /** Elke minuut een update — zelfde cadans als HeartRateListener's sampling. */
        private const val DETECTION_INTERVAL_MILLIS = 60_000L
    }
}
