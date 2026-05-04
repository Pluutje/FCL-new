package app.aaps.plugins.aps.openAPSFCL.vnext

import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory brug tussen de geïntegreerde FCL Analyzer en FCLvNext.
 *
 * Vervangt de oude JSON-bestand flow:
 *   OUD: Analyzer schrijft FCLvNext_config_override.json → FCLvNextConfigOverride leest bestand
 *   NIEUW: Analyzer roept FclOverrideBridge.post() aan → FCLvNextConfigOverride.load() leest uit geheugen
 *
 * Gedrag is identiek aan consume_after_use: true — de override wordt precies één keer
 * geconsumeerd door FCLvNext en daarna automatisch gewist.
 *
 * Thread-safety: AtomicReference garandeert dat gelijktijdige toegang vanuit
 * de AAPS APS-thread (FCLvNext) en de Analyzer coroutine veilig is.
 */
object FclOverrideBridge {

    private val pending = AtomicReference<FCLvNextConfigOverride.Override?>(null)

    /**
     * Aanroepen vanuit de Analyzer zodra de gebruiker een parameter-aanpassing bevestigt.
     * De override wordt bewaard totdat FCLvNext hem ophaalt via consume().
     */
    fun post(override: FCLvNextConfigOverride.Override) {
        pending.set(override)
    }

    /**
     * Aanroepen vanuit FCLvNextConfigOverride.load().
     * Geeft de pending override terug EN wist hem atomisch — eenmalig gebruik.
     * Geeft null terug als er geen pending override is.
     */
    fun consume(): FCLvNextConfigOverride.Override? =
        pending.getAndSet(null)

    /**
     * Geeft true als er een pending override klaarstaat die nog niet geconsumeerd is.
     * Handig voor de Analyzer UI om te tonen dat een aanpassing wacht op de volgende cyclus.
     */
    fun hasPending(): Boolean = pending.get() != null

    /**
     * Wist een eventueel pending override zonder hem te consumeren.
     * Aanroepen als de gebruiker de aanpassing annuleert in de UI.
     */
    fun cancel() {
        pending.set(null)
    }
}