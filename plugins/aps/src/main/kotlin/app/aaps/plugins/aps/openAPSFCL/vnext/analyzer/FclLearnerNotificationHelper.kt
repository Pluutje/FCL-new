package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import android.app.NotificationChannel
import android.app.NotificationManager as AndroidNotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationHandle
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.plugins.aps.openAPSFCL.vnext.FclNotificationManagerBridge
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * FclLearnerNotificationHelper (10/07/2026) — exacte spiegeling van
 * FclAiNotificationHelper.kt, nu voor de Learner's MANUAL-voorstel.
 *
 * Hergebruikt bewust dezelfde NotificationId.FCL_AI_ADVISOR_READY (geen
 * nieuwe AAPS-core-registratie nodig — dezelfde afweging als destijds bij de
 * AI-melding: het gaat om "FCL vNext heeft iets voor je klaarstaan", de
 * tekst maakt het onderscheid, en één tik op de plugin opent toch altijd
 * hetzelfde scherm). De navigatie-vlag is wél een EIGEN, aparte vlag
 * (navigateToLearnerRequested) naast de bestaande AI-vlag, zodat
 * Fclanalyzerscreen.kt bij het openen kan onderscheiden naar welk tabblad
 * precies gesprongen moet worden.
 *
 * Zelfde dubbele aanpak (native AAPS-notificatie + Android-systeemnotificatie
 * als vangnet) en dezelfde dismiss-bij-daadwerkelijk-bekijken-regel als bij
 * de AI — zie de kdoc in FclAiNotificationHelper.kt voor de volledige
 * toelichting, hier niet herhaald.
 */
object FclLearnerNotificationHelper {

    private const val CHANNEL_ID   = "fcl_learner"
    private const val CHANNEL_NAME = "FCLvNext Learner"
    private const val NOTIF_ID     = 0x46434C4C   // "FCLL" als int — anders dan de AI's "FCLA"

    private val nativeHandle = AtomicReference<NotificationHandle?>(null)
    private val navigateRequested = AtomicBoolean(false)

    /** Aanroepen na een episode-evaluatie in MANUAL-modus met een nog niet
     *  beoordeeld voorstel. */
    fun showPendingProposal(context: Context) {
        showNativeAapsNotification(context)
        showAndroidSystemNotification(context)
    }

    /** Aanroepen bij accepteren/afwijzen (zie FclLearnerApplier). */
    fun dismissAdvice(context: Context) {
        FclNotificationManagerBridge.get()?.let { nm ->
            nativeHandle.getAndSet(null)?.let { handle -> nm.dismiss(handle) }
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager
        nm.cancel(NOTIF_ID)
    }

    /** Aanroepen door FclAnalyzerScreen bij het openen. Retourneert true (en
     *  reset meteen naar false) als de gebruiker via de actieknop van de
     *  native notificatie hier expliciet naartoe wilde springen. */
    fun consumeNavigateRequest(): Boolean = navigateRequested.getAndSet(false)

    private fun showNativeAapsNotification(context: Context) {
        val nm = FclNotificationManagerBridge.get() ?: return
        nativeHandle.getAndSet(null)?.let { handle -> nm.dismiss(handle) }

        val handle = nm.post(
            id = NotificationId.FCL_AI_ADVISOR_READY,
            text = "Learner: nieuw voorstel klaar om te bekijken",
            actions = listOf(
                NotificationAction(
                    buttonTextRes = android.R.string.ok,
                    action = { navigateRequested.set(true) }
                )
            )
        )
        nativeHandle.set(handle)
    }

    private fun showAndroidSystemNotification(context: Context) {
        ensureChannel(context)

        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }
            ?: Intent()

        val pi = PendingIntent.getActivity(
            context,
            NOTIF_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🧠 Learner — nieuw voorstel klaar")
            .setContentText("Tik om het voorstel te bekijken in de FCLvNext Analyzer.")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setColor(0xFF5B3A8E.toInt())
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager
        nm.notify(NOTIF_ID, notif)
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            AndroidNotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Informeert je als de FCLvNext Learner een nieuw voorstel heeft (MANUAL-modus)."
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
    }
}
