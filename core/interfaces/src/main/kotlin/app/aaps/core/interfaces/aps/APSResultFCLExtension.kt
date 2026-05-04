package app.aaps.core.interfaces.aps

/**
 * Backwards-compatibility extensie voor FCL vNext.
 * oapsProfileFCL was een apart veld in de oude APSResult.
 * Nu een extension property die naar oapsProfile delegeert.
 */
var APSResult.oapsProfileFCL: OapsProfileFCL?
    get() = oapsProfile
    set(value) { oapsProfile = value }
