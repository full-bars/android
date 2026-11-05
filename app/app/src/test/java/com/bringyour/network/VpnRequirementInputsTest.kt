package com.bringyour.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The requirement follows the provide MODE, not the provider pointer. A
 * rotation or a memory-budget wait detaches the remote-user NAT while the
 * operator's intent is unchanged; reading the pointer there stopped a VPN
 * nobody asked to turn off (device ring: "app no longer requires VPN" every
 * ~2.5s while mode stayed Public).
 */
class VpnRequirementInputsTest {
    private val none = 0L
    private val public = 3L

    private fun inputs(
        mode: Long = none,
        enabled: Boolean = false,
        connectRequested: Boolean = false,
        connectEnabled: Boolean = false,
        routeLocal: Boolean = true,
    ) = VpnRequirementInputs(
        provideMode = mode,
        provideModeNone = none,
        provideEnabled = enabled,
        connectRequested = connectRequested,
        connectEnabled = connectEnabled,
        routeLocal = routeLocal,
    )

    @Test
    fun aRotationDoesNotMakeTheVpnUnwanted() {
        // mode Public, provider detached: the measured rotation window
        assertTrue(inputs(mode = public, enabled = false).required())
        // mode Public, provider attached
        assertTrue(inputs(mode = public, enabled = true).required())
    }

    @Test
    fun modeNoneWithNoOtherReasonIsNotRequired() {
        assertFalse(inputs().required())
    }

    @Test
    fun connectAndRemoteRoutingStillRequireTheVpn() {
        assertTrue(inputs(connectRequested = true).required())
        assertTrue(inputs(routeLocal = false).required())
    }

    @Test
    fun aQueuedDisconnectCountsAsOff() {
        // raw connect flag true, but a disconnect is queued and has not run:
        // the pending request wins, so a reconcile inside that window does not
        // restart the tunnel
        assertFalse(inputs(connectRequested = false, connectEnabled = true).required())
    }

    @Test
    fun theProviderPointerAloneNeitherRequiresNorForbids() {
        // pointer true with mode None: intent says off
        assertFalse(inputs(mode = none, enabled = true).required())
        // pointer false with mode Public: intent says on
        assertTrue(inputs(mode = public, enabled = false).required())
    }
}
