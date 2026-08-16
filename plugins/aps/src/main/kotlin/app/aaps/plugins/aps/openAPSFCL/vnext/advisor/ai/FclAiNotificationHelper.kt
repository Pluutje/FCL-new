package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

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
 * ── FCL AI Advisor Notificatie ────────────────────────────────────────────
 * Toont TWEE parallelle meldingen als de AI-adviseur openstaande voorstellen heeft:
 *
 * 1. Native AAPS-notificatie (05/07/2026) — via de DI-geïnjecteerde
 *    NotificationManager (bereikbaar via FclNotificationManagerBridge, want
 *    dit object zelf heeft geen constructor-injectie). Verschijnt in AAPS'
 *    eigen notificatie-icoon/lijst op het hoofdscherm, net als de bestaande
 *    pomp/BG/profiel-meldingen. Heeft een actieknop die — als de gebruiker
 *    'm aantikt terwijl de app open is — een vlag zet die FclAnalyzerScreen
 *    bij het openen leest om direct naar het AI Advisor-tabblad te springen
 *    (zie consumeNavigateRequest()).
 *
 *    Hergebruikt NotificationId.AUTOMATION_MESSAGE — de enige generieke,
 *    voor-meerdere-instanties-geschikte ID die niet aan een specifiek
 *    hardware/systeem-onderwerp gebonden is. Er bestaat geen eigen
 *    FCLvNext-NotificationId: dat is een vaste enum in AAPS-core
 *    (app.aaps.core.interfaces.notifications.NotificationId), buiten het
 *    bereik van deze plugin om aan te passen.
 *
 *    Onbekende factor: of het aantikken van de actieknop ook AAPS' eigen
 *    schermnavigatie (onNavigate/NavigationRequest) kan aansturen om vanuit
 *    het hoofdscherm direct in FCL vNext te springen, is niet geverifieerd —
 *    dat zit in de ViewModel-laag die buiten de aangeleverde bestanden valt.
 *    Wat wél gegarandeerd werkt: eenmaal in FCL vNext geopend, opent het
 *    scherm direct op het AI Advisor-tabblad i.p.v. het dashboard.
 *
 * 2. Android-systeemnotificatie (bestaand, 03/07/2026) — blijft behouden als
 *    vangnet voor het geval de app volledig gesloten is; de native AAPS-
 *    notificatie hierboven is namelijk een in-app lijst (StateFlow), die de
 *    gebruiker alleen ziet als de app al open is of wordt geopend.
 *
 * Beide verdwijnen zodra de gebruiker het AI Advisor-scherm daadwerkelijk
 * opent (dismissAdvice()).
 */
object FclAiNotificationHelper {

    // ── Android-systeemnotificatie (bestaand) ──────────────────────────────
    private const val CHANNEL_ID   = "fcl_ai_advisor"
    private const val CHANNEL_NAME = "FCLvNext AI Advisor"
    private const val NOTIF_ID     = 0x46434C41   // "FCLA" als int

    // ── Native AAPS-notificatie (nieuw) ─────────────────────────────────────
    private val nativeHandle = AtomicReference<NotificationHandle?>(null)
    private val navigateRequested = AtomicBoolean(false)

    /** Aanroepen na een succesvolle AI-run met openstaande voorstellen. */
    fun showPendingAdvice(context: Context, pendingCount: Int) {
        if (pendingCount <= 0) {
            dismissAdvice(context)
            return
        }

        showNativeAapsNotification(pendingCount)
        showAndroidSystemNotification(context, pendingCount)
    }

    /** Aanroepen zodra de gebruiker het AI Advisor-scherm opent. */
    fun dismissAdvice(context: Context) {
        FclNotificationManagerBridge.get()?.let { nm ->
            nativeHandle.getAndSet(null)?.let { handle -> nm.dismiss(handle) }
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager
        nm.cancel(NOTIF_ID)
    }

    /**
     * Aanroepen door FclAnalyzerScreen bij het openen. Retourneert true (en
     * reset meteen naar false) als de gebruiker via de actieknop van de
     * native notificatie hier expliciet naartoe wilde springen.
     */
    fun consumeNavigateRequest(): Boolean = navigateRequested.getAndSet(false)

    private fun showNativeAapsNotification(pendingCount: Int) {
        val nm = FclNotificationManagerBridge.get() ?: return

        // Vorige instantie eerst opruimen zodat er nooit twee tegelijk staan.
        nativeHandle.getAndSet(null)?.let { handle -> nm.dismiss(handle) }

        val text = if (pendingCount == 1)
            "AI Advisor: 1 voorstel klaar om te bekijken"
        else
            "AI Advisor: $pendingCount voorstellen klaar om te bekijken"

        // 05/07/2026: eigen NotificationId.FCL_AI_ADVISOR_READY i.p.v.
        // het gedeelde AUTOMATION_MESSAGE — dat gaf zowel het verkeerde icoon
        // (Automation-plugin i.p.v. FCL) als een risico op het "kapen" van de
        // klik-navigatie van echte Automation-meldingen. Met een eigen ID:
        // - eigen icoon via NotificationCategory.FCL (NotificationBottomSheet.kt)
        // - eigen navigatie via handleNotificationAction() (ComposeMainActivity.kt),
        //   die nu een tik op de melding zelf al rechtstreeks naar de FCL-plugin
        //   stuurt. De actieknop hieronder blijft als extra, expliciete route
        //   (zet de vlag die Fclanalyzerscreen bij het openen leest om meteen
        //   op het AI Advisor-tabblad te starten i.p.v. het dashboard).
        val handle = nm.post(
            id = NotificationId.FCL_AI_ADVISOR_READY,
            text = text,
            actions = listOf(
                NotificationAction(
                    // TODO (de gebruiker): zie eerdere notitie — vervang door een eigen
                    // stringresource ("Bekijken") zodra strings.xml is aangevuld.
                    buttonTextRes = android.R.string.ok,
                    action = { navigateRequested.set(true) }
                )
            )
        )
        nativeHandle.set(handle)
    }

    private fun showAndroidSystemNotification(context: Context, pendingCount: Int) {
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

        val title = if (pendingCount == 1)
            "🤖 AI Advisor — 1 voorstel klaar"
        else
            "🤖 AI Advisor — $pendingCount voorstellen klaar"

        val body = "Tik om de voorstellen te bekijken in de FCLvNext Analyzer."

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setNumber(pendingCount)           // badge-teller op het app-icoon
            .setAutoCancel(true)               // verdwijnt bij tikken
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)            // geen herhaald geluid bij updates
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // Paars accent (FCLvNext huisstijl)
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
            AndroidNotificationManager.IMPORTANCE_DEFAULT   // geen geluid, wel statusbalk-icoon
        ).apply {
            description = "Informeert je als de FCLvNext AI-adviseur nieuwe parametervoorstellen heeft."
            enableVibration(false)
            setSound(null, null)                     // stil — niet urgent
        }
        nm.createNotificationChannel(channel)
    }
}
