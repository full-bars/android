package com.bringyour.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A revoke leaves provide enabled, so the reconcile could start the service
 * again at once. The fence holds every automatic reconnect path until the user
 * connects. Policy is tested directly; MainApplication and MainService need a
 * device, so which call sites consult the fence is read from the source.
 */
class VpnRevokeFenceTest {

    private fun facts(
        stop: Boolean = false,
        appActive: Boolean = true,
        systemStart: Boolean = false,
        fenced: Boolean = false,
    ) = VpnServiceStartFacts(
        stopRequested = stop,
        startRequested = true,
        appMarkedActive = appActive,
        deviceRequiresVpn = true,
        redelivered = true,
        systemStart = systemStart,
        revokeFenced = fenced,
    )

    @Test
    fun onlyAnExplicitUserConnectPassesTheRaisedFence() {
        for (entry in VpnReconnectEntry.values()) {
            assertTrue("$entry is open while not fenced", vpnReconnectAllowed(entry, raised = false))
            assertEquals(
                "$entry while fenced",
                entry == VpnReconnectEntry.USER_CONNECT,
                vpnReconnectAllowed(entry, raised = true),
            )
        }
    }

    @Test
    fun theFenceCoversEveryAutomaticEntryPoint() {
        val automatic = listOf(
            VpnReconnectEntry.RECONCILE,
            VpnReconnectEntry.START_SERVICE,
            VpnReconnectEntry.FOREGROUND_RETRY,
            VpnReconnectEntry.RESTORE,
            VpnReconnectEntry.SYSTEM_START,
            VpnReconnectEntry.ALWAYS_ON_GUARD,
            VpnReconnectEntry.ALWAYS_ON_CONNECT,
            VpnReconnectEntry.BALANCE_RECOVERY,
        )
        assertEquals(VpnReconnectEntry.values().size - 1, automatic.size)
        automatic.forEach { assertFalse("$it", vpnReconnectAllowed(it, raised = true)) }
    }

    @Test
    fun aFencedServiceStartStopsWhateverElseSaysRun() {
        assertEquals(VpnServiceStartDecision.RUN, decideVpnServiceStart(facts()))
        assertEquals(VpnServiceStartDecision.STOP, decideVpnServiceStart(facts(fenced = true)))
        // the system-owned (Always-on) start, which otherwise always runs
        assertEquals(VpnServiceStartDecision.RUN, decideVpnServiceStart(facts(systemStart = true)))
        assertEquals(
            VpnServiceStartDecision.STOP,
            decideVpnServiceStart(facts(systemStart = true, fenced = true)),
        )
    }

    @Test
    fun aFencedDirectBootSystemStartDoesNotHoldTheGuard() {
        fun decide(unlocked: Boolean, fenced: Boolean) = decideDirectBootServiceStart(
            credentialStorageUnlocked = unlocked,
            stopRequested = false,
            startRequested = true,
            systemStart = true,
            commandCompatible = true,
            revokeFenced = fenced,
        )
        assertEquals(DirectBootServiceDecision.HOLD_ALWAYS_ON_GUARD, decide(unlocked = false, fenced = false))
        assertEquals(DirectBootServiceDecision.STOP, decide(unlocked = false, fenced = true))
        assertEquals(DirectBootServiceDecision.STOP, decide(unlocked = true, fenced = true))
    }

    @Test
    fun theDelayedForegroundRetryIsHeldByTheFence() {
        fun eligible(fenced: Boolean) = vpnServiceStartRetryEligible(
            retryRequested = true,
            vpnRequired = true,
            serviceActive = false,
            startPending = false,
            foregroundActivityAvailable = true,
            revokeFenced = fenced,
        )
        assertTrue(eligible(fenced = false))
        assertFalse(eligible(fenced = true))
    }

    @Test
    fun aUserDisconnectOrStaleStartStillStops() {
        assertEquals(VpnServiceStartDecision.STOP, decideVpnServiceStart(facts(stop = true)))
        assertEquals(
            VpnServiceStartDecision.STOP,
            decideVpnServiceStart(facts(stop = true, systemStart = true)),
        )
        assertFalse(vpnServiceRequired(provideEnabled = false, connectEnabled = false, routeLocal = true))
    }

    // wiring, read from the source

    private fun source(name: String): String {
        val file = listOf("", "app/", "app/app/")
            .map { File(it + "src/main/java/com/bringyour/network/$name") }
            .firstOrNull { it.isFile }
        assertNotNull(name, file)
        return file!!.readText()
    }

    private fun function(source: String, signature: String): String {
        val from = source.indexOf(signature)
        assertTrue("missing $signature", 0 <= from)
        return source.substring(from, source.indexOf("\n    }\n", from))
    }

    private fun consults(body: String, entry: VpnReconnectEntry) =
        body.contains("VpnReconnectEntry.$entry")

    @Test
    fun everyAutomaticEntryPointAsksTheFence() {
        val app = source("MainApplication.kt")
        assertTrue(consults(function(app, "    private fun reconcileVpnService("), VpnReconnectEntry.RECONCILE))
        assertTrue(consults(function(app, "    fun startVpnService("), VpnReconnectEntry.START_SERVICE))
        assertTrue(consults(function(app, "    private fun scheduleVpnStartRetry("), VpnReconnectEntry.FOREGROUND_RETRY))
        assertTrue(consults(function(app, "    fun restoreVpnServiceFromSystemEvent("), VpnReconnectEntry.RESTORE))
        assertTrue(consults(function(app, "    private fun ensureAlwaysOnConnected("), VpnReconnectEntry.ALWAYS_ON_CONNECT))
        // the stop path must not re-arm the foreground retry
        assertTrue(consults(function(app, "    fun vpnServiceDidStop("), VpnReconnectEntry.FOREGROUND_RETRY))
        val service = source("MainService.kt")
        assertTrue(consults(function(service, "    override fun onStartCommand("), VpnReconnectEntry.SYSTEM_START))
        assertTrue(consults(function(service, "    private fun establishAlwaysOnGuard("), VpnReconnectEntry.ALWAYS_ON_GUARD))
        assertTrue(function(service, "    private fun reconcilePfd(").contains("vpnRevokeFenced()"))
    }

    @Test
    fun queuedAutomaticConnectsAskTheFenceWhenTheyRunToo() {
        val app = source("MainApplication.kt")
        val recovery = function(app, "    fun observeBalanceRecovery(")
        // once before queueing, once more as the admit check at execution
        assertEquals(2, Regex("VpnReconnectEntry\\.BALANCE_RECOVERY").findAll(recovery).count())
        assertTrue(recovery.contains("admit ="))
        val alwaysOn = function(app, "    private fun ensureAlwaysOnConnected(")
        assertTrue(alwaysOn.contains("admit = { vpnReconnectPermitted(VpnReconnectEntry.ALWAYS_ON_CONNECT) }"))
    }

    @Test
    fun theFenceIsRaisedBeforeEveryEarlyReturnOfTheRevoke() {
        val revoked = function(source("MainApplication.kt"), "    fun vpnServiceRevoked(")
        val raise = revoked.indexOf("revokeFence.raise()")
        assertTrue(0 <= raise)
        assertTrue("raised before the first early return", raise < revoked.indexOf("?: return"))
        // and on the calling thread, before the post to main
        assertTrue(raise < revoked.indexOf("mainHandler.post"))
    }

    @Test
    fun aRevokeDropsTheBalanceRecoveryBeforeAnyEarlyReturn() {
        val revoked = function(source("MainApplication.kt"), "    fun vpnServiceRevoked(")
        val clear = revoked.indexOf("clearBalanceRecovery()")
        assertTrue(0 <= clear)
        // the not-connected and no-device returns must not skip it
        assertTrue(clear < revoked.indexOf("device ?: return"))
        assertTrue(clear < revoked.indexOf("connectRequested(connectDevice)) return"))
        assertTrue(revoked.indexOf("revokeFence.raise()") < clear)
    }

    @Test
    fun onlyExplicitUserConnectsClearTheFence() {
        val clearing = listOf(
            "MainApplication.kt",
            "QuickConnect.kt",
            "ui/connect/ConnectViewModel.kt",
            "ui/connect/ConnectScreen.kt",
        )
        assertTrue(source("QuickConnect.kt").contains("clearVpnRevokeFence()"))
        assertTrue(source("ui/connect/ConnectViewModel.kt").contains("clearVpnRevokeFence()"))
        assertTrue(source("ui/connect/ConnectScreen.kt").contains("userRequestedVpnConnect()"))
        // nothing else clears it: balance recovery and Always-on do not
        val app = source("MainApplication.kt")
        assertFalse(function(app, "    fun observeBalanceRecovery(").contains("clearVpnRevokeFence"))
        assertFalse(function(app, "    private fun ensureAlwaysOnConnected(").contains("revokeFence.clear"))
        val clears = Regex("""revokeFence\.clear\(""").findAll(app).count()
        assertEquals("only clearVpnRevokeFence clears", 1, clears)
        assertTrue(clearing.isNotEmpty())
    }

    @Test
    fun replacementAndDestroyStillTearDown() {
        val service = source("MainService.kt")
        val start = function(service, "    override fun onStartCommand(")
        assertTrue("a replaced owner is stopped", start.contains("currentService?.stop()"))
        assertTrue(function(service, "    override fun onDestroy(").contains("stop()"))
    }

    @Test
    fun ownershipStopsStayTerminal() {
        // Obsolete flow, close monitor and missing device are ownership or
        // intent stops (a replaced instance, the app's own release, no device
        // to run). Keeping the service for them would defeat replacement and
        // the user's disconnect, and each already has its recovery path.
        val service = source("MainService.kt")
        val reconcile = function(service, "    private fun reconcilePfd(")
        assertTrue(reconcile.contains("device unavailable outside Always-on; stopping"))
        val monitor = service.substring(service.indexOf("private fun startCloseMonitor("))
        assertTrue(monitor.contains("stop()"))
        assertFalse(monitor.contains("scheduleTunnelRetry"))
    }
}
