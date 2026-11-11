package com.bringyour.network.ui.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnClaimRelaySelectionTest {
    @Test
    fun walletAlwaysSelectsItsOwnLedger() {
        val status = SnClaimStatusState(showsTile = true, claimableRao = 42)
        val ledger = SnLedgerTile(null, true, false, false)
        assertNull(snLedgerTileWithRelay(true, null, status))
        assertEquals(ledger, snLedgerTileWithRelay(true, ledger, status))
        assertEquals(42L, snLedgerTileWithRelay(false, null, status)?.relayClaimableRao)
        assertNull(snLedgerTileWithRelay(false, ledger, status.copy(showsTile = false)))
        assertNull(snLedgerTileWithRelay(false, null, null))
    }

    @Test
    fun unavailableStatusDoesNotShowAZeroBalance() {
        val status = snClaimStatusUnavailable()
        assertTrue(status.showsTile)
        assertTrue(status.unavailable)
        val tile = requireNotNull(snLedgerTileWithRelay(false, null, status))
        assertTrue(tile.unavailable)
        assertNull(tile.relayClaimableRao)
        assertFalse(tile.claimEnabled)
    }
}
