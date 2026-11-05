package com.bringyour.network

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr7RemainingWiringTest {
    private fun source(path: String) = File("app/app/src/main/java/com/bringyour/network/$path").readText()

    @Test fun backupCheckRunsOnIoAndDeletionWaits() {
        val settings = source("ui/settings/SettingsScreen.kt")
        assertTrue(settings.contains("produceState<Boolean?>(initialValue = null, deletedNetworkId)"))
        assertTrue(settings.contains("withContext(Dispatchers.IO)"))
        assertTrue(settings.contains("runCatching { BittensorKeySignOut.needsBackup(context, deletedNetworkId) }.getOrDefault(true)"))
        assertTrue(settings.contains("deleteConfirmed && keyNeedsBackup != null && !isDeletingAccount"))
    }

    @Test fun failedClaimStatusIsNotAnEmptySuccess() {
        val sdk = source("ui/wallet/SdkProtocolSource.kt")
        val callback = sdk.substringAfter("device.snClaimStatus { result, err ->").substringBefore("override suspend fun claimNow")
        assertTrue(callback.contains("if (err != null) snClaimStatusUnavailable()"))
        assertTrue(callback.contains("else result?.let { snClaimStatusOf(it) }"))
        assertTrue(callback.contains("getOrElse { snClaimStatusUnavailable() }"))
    }

    @Test fun signingOutClearsKeyRequestsAndLeavingEarningsClearsDeliveredRequests() {
        assertTrue(source("SignedOutNetworkState.kt").contains("BittensorKeyScreenRequests.clear()"))
        val screen = source("ui/wallet/EarningsScreen.kt")
        assertTrue(screen.contains("if (!BittensorKeyScreenRequests.navigation.value)"))
        assertTrue(screen.contains("BittensorKeyScreenRequests.clear()"))
    }

    @Test fun stableReplacementChecksTheOldActiveFlowBeforeReassigning() {
        val service = source("MainService.kt")
        val replacement = service.substringAfter("if (replacedPacketFlow?.isActive() == true)")
            .substringBefore("appliedTunnelConfiguration = configuration")
        assertTrue(replacement.contains("flowStartedAt - packetFlowStartedAt"))
        assertTrue(replacement.contains("packetFlowRetryFailureCount("))
        assertTrue(replacement.contains("packetFlow = replacementPacketFlow"))
        assertTrue(service.contains("if (packetFlow === endedPacketFlow)"))
    }

    @Test fun alwaysOnLineIsWiredBesideTheDisconnectFirstLine() {
        val actions = source("ui/connect/ConnectActions.kt")
        assertTrue(actions.contains("alwaysOnRecoveryShown(outOfBalanceNotice.disconnectFirst, systemAlwaysOn)"))
        assertTrue(actions.contains("isSystemAlwaysOnVpnActive() == true"))
        assertTrue(actions.contains("R.string.insufficient_balance_always_on_vpn"))
    }
}
