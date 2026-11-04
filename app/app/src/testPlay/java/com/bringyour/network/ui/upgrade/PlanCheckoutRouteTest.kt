package com.bringyour.network.ui.upgrade

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether this flavor's alert can offer payment recovery advice while the tunnel
 * holds traffic. Per-app routing can leave Play Store and Play services outside it.
 */
class PlanCheckoutRouteTest {

    @Test
    fun checkoutRidesTheTunnel() {
        assertTrue(PLAN_CHECKOUT_RIDES_TUNNEL)
    }
}
