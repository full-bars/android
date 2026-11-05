package com.bringyour.network

/** One snapshot for reconcile, restart and internal-stop decisions. */
internal data class VpnRequirementInputs(
    val provideMode: Long,
    val provideModeNone: Long,
    val provideEnabled: Boolean,
    val connectRequested: Boolean,
    val connectEnabled: Boolean,
    val routeLocal: Boolean,
) {
    fun required(): Boolean = vpnServiceRequired(
        provideEnabled = provideMode != provideModeNone,
        connectEnabled = connectRequested,
        routeLocal = routeLocal,
    )
}
