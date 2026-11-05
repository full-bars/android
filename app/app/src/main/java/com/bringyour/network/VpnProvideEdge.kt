package com.bringyour.network

/**
 * The provide-intent edge in one pure decision, so the ring entries can be
 * tested without a device. The reconcile writes one entry per change; the
 * wording must say which change happened, because the next capture uses it to
 * tell a rotation from a permanent refusal from an operator turning it off.
 */
internal data class VpnProvideEdge(
    val intentWithoutProvider: Boolean,
    val event: String?,
)

internal fun provideEdge(
    previous: Boolean,
    provideMode: Long,
    provideModeNone: Long,
    provideEnabled: Boolean,
): VpnProvideEdge {
    val next = provideMode != provideModeNone && !provideEnabled
    val event = when {
        // intent says provide, nothing is providing
        next && !previous -> "provide intent without provider"
        // the intent itself went away: nothing was restored, the operator
        // turned providing off
        previous && !next && provideMode == provideModeNone -> "provide intent withdrawn"
        // the intent still says provide and something is providing again
        previous && !next -> "provider restored"
        else -> null
    }
    return VpnProvideEdge(next, event)
}
