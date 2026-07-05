package app.aaps.plugins.aps.openAPSFCL.vnext

import app.aaps.core.interfaces.notifications.NotificationManager
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory brug die de DI-geïnjecteerde AAPS [NotificationManager] beschikbaar
 * stelt voor plekken die zelf geen constructor-injectie hebben (zoals de
 * FclAiAdvisorScheduler/FclAiNotificationHelper singleton-objects, die vanuit
 * FCLvNext.getAdvice() met een kale Context worden aangeroepen — zie
 * FclAiAdvisorScheduler.kt voor waarom daar bewust geen DI-scaffolding zit).
 *
 * Zelfde patroon als FclActiveConfigBridge/FclOverrideBridge: OpenAPSFCLPlugin
 * zet de referentie één keer bij het aanmaken van de plugin (init-block),
 * de rest van de code leest via get().
 *
 * (05/07/2026, Ecko — voor de native AAPS-notificatie i.p.v. alleen de
 * Android-systeemnotificatie, zodat de AI Advisor ook als icoontje/melding
 * in AAPS' eigen notificatiesysteem verschijnt, net als pomp/BG/profiel-meldingen.)
 */
object FclNotificationManagerBridge {

    private val current = AtomicReference<NotificationManager?>(null)

    /** Aanroepen vanuit OpenAPSFCLPlugin (init-block), één keer bij plugin-constructie. */
    fun set(manager: NotificationManager) {
        current.set(manager)
    }

    /** Aanroepen vanuit FclAiNotificationHelper. Kan null zijn vóór de eerste plugin-init. */
    fun get(): NotificationManager? = current.get()
}
