package app.aaps.plugins.aps.openAPSFCL.vnext

import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileRepository
import java.util.concurrent.atomic.AtomicReference

/**
 * FclProfileBridge (26/07/2026) — zelfde in-memory-brug-patroon als
 * FclActiveConfigBridge, nu voor ProfileFunction/ProfileRepository.
 *
 * AANLEIDING: de Accepteren-knop bij een openstaand MANUAL-voorstel van de
 * nacht-basaal-auto-adjuster (ProfileAutoAdjustCard, Advisorscreen.kt) moet
 * FclNightBasalAutoAdjuster.applyPending(context, profileFunction,
 * profileRepository) kunnen aanroepen — maar een @Composable heeft geen
 * Dagger-constructor-injectie zoals DetermineBasalFCL die wel heeft. Deze
 * brug maakt de al-geïnjecteerde instanties uit DetermineBasalFCL
 * beschikbaar voor de UI-laag, zonder een tweede Dagger-graaf op te tuigen.
 *
 * Gezet in DetermineBasalFCL's init-block (eenmalig; ProfileFunction/
 * ProfileRepository zijn Dagger-singletons die niet per cyclus wisselen).
 */
object FclProfileBridge {

    private val profileFunctionRef = AtomicReference<ProfileFunction?>(null)
    private val profileRepositoryRef = AtomicReference<ProfileRepository?>(null)

    fun set(profileFunction: ProfileFunction, profileRepository: ProfileRepository) {
        profileFunctionRef.set(profileFunction)
        profileRepositoryRef.set(profileRepository)
    }

    fun getProfileFunction(): ProfileFunction? = profileFunctionRef.get()
    fun getProfileRepository(): ProfileRepository? = profileRepositoryRef.get()
}
