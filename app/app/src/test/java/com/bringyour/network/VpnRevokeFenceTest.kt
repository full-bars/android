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
 *
 * Not covered here, on purpose: behavioural race tests need the Android
 * runtime. A device run must prove (1) a revoke during a pending TUN retry
 * leaves no interface behind, (2) a revoke during a queued Always-on connect
 * or balance-recovery connect leaves the device disconnected, and (3) what
 * kill-switch continuity a held internal stop actually gives (see the gap
 * noted in MainService.keepRequiredVpnUp).
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
        assertTrue(alwaysOn.contains("vpnReconnectPermitted(VpnReconnectEntry.ALWAYS_ON_CONNECT) && vpnFenceAdmits(admission)"))
    }

    // ---- epoch: every site that holds work across a possible revoke

    /** Source-text checks: admission taken when the work is queued, asked again when it runs. */
    @Test
    fun everyDeferredSiteTakesAnAdmissionAndAsksItAgain() {
        val app = source("MainApplication.kt")
        val service = source("MainService.kt")
        // queued connects: balance recovery, Always-on
        for (name in listOf("    fun observeBalanceRecovery(", "    private fun ensureAlwaysOnConnected(")) {
            val body = function(app, name)
            val take = body.indexOf("val admission = vpnFenceAdmission()")
            assertTrue("$name takes an admission", take >= 0)
            assertTrue("$name asks it at execution", body.indexOf("vpnFenceAdmits(admission)") > take)
            assertTrue("$name queues after taking it", take < body.indexOf("runOnDevice("))
        }
        // the start intent carries it and the service asks it
        val start = function(app, "    fun startVpnService(")
        assertTrue(start.contains("val admission = vpnFenceAdmission()"))
        assertTrue(start.contains("admission == null"))
        assertTrue(start.contains("putExtra(VPN_START_FENCE_EPOCH_EXTRA, admission)"))
        val onStart = function(service, "    override fun onStartCommand(")
        assertTrue(onStart.contains("VPN_START_FENCE_EPOCH_EXTRA"))
        assertTrue(onStart.contains("vpnStartEpochAdmitted(carriedEpoch) { app.vpnFenceAdmits(it) }"))
        // the scheduled foreground retry
        val retry = function(app, "    private fun scheduleVpnStartRetry(")
        assertTrue(retry.contains("val admission = vpnFenceAdmission()"))
        assertTrue(retry.contains("!vpnFenceAdmits(admission)"))
        // the TUN retry and the build that ends in establish()
        val tunnelSchedule = service.substring(service.indexOf("private fun scheduleTunnelRetry("))
        val runnable = tunnelSchedule.substring(
            tunnelSchedule.indexOf("val admission ="),
            tunnelSchedule.indexOf("tunnelRetryRunnable = runnable"),
        )
        assertTrue(runnable.contains("!app.vpnFenceAdmits(admission)"))
        val update = function(service, "    private fun updatePfd(")
        assertTrue(update.indexOf("val admission = app.vpnFenceAdmission()") in 0 until update.indexOf("builder.setSession"))
        assertTrue(update.contains("!app.vpnFenceAdmits(admission)"))
    }

    @Test
    fun aRevokeInvalidatesWhatWasAlreadyPendingBeforeAnyEarlyReturn() {
        val app = source("MainApplication.kt")
        val revoked = function(app, "    fun vpnServiceRevoked(")
        val invalidate = revoked.indexOf("invalidatePendingVpnStarts()")
        assertTrue(invalidate > revoked.lastIndexOf("revokeFence.raise()"))
        assertTrue(invalidate < revoked.indexOf("device ?: return"))
        val body = function(app, "    private fun invalidatePendingVpnStarts(")
        for (piece in listOf(
            "cancelVpnStartRetry()", "cancelVpnStartAdoptionWatchdog()", "vpnRequestStart = false",
            "vpnStartPending = false", "alwaysOnConnectRequestedDevice = null",
        )) assertTrue(piece, body.contains(piece))
    }

    /** Deferred posts that carry no state are admitted when they run, by taking the admission then. */
    @Test
    fun postedStartsTakeTheirAdmissionWhenTheyRunNotWhenPosted() {
        val start = function(source("MainApplication.kt"), "    fun startVpnService(")
        // the repost to main returns before the admission is taken
        assertTrue(start.indexOf("mainHandler.post { startVpnService() }") < start.indexOf("val admission"))
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
    fun aPendingTunnelRetryAsksTheFenceWhenItRuns() {
        val service = source("MainService.kt")
        val schedule = service.substring(service.indexOf("private fun scheduleTunnelRetry("))
        val runnable = schedule.substring(schedule.indexOf("runnable = Runnable {"), schedule.indexOf("tunnelRetryRunnable = runnable"))
        val check = runnable.indexOf("app.vpnRevokeFenced()")
        assertTrue(0 <= check)
        // before either retry kind acts
        assertTrue(check < runnable.indexOf("when (kind)"))
        assertTrue(check < runnable.indexOf("updatePfd(configuration)"))
        // and updatePfd checks once more right before it builds the interface
        val update = function(service, "    private fun updatePfd(")
        assertTrue(update.indexOf("vpnRevokeFenced()") in 0 until update.indexOf("val pfd = try"))
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

    /** Source-text check: a clear that did not land is returned and surfaced, not swallowed. */
    @Test
    fun aClearThatDidNotLandIsSurfacedNotSwallowed() {
        val clear = function(source("MainApplication.kt"), "    fun clearVpnRevokeFence(")
        assertTrue(clear.contains("val cleared = revokeFence.clear()"))
        assertTrue(clear.contains("vpnRevokeFenceClearFailed.value = !cleared"))
        assertTrue(clear.contains("Log.e("))
        assertTrue(clear.contains("return cleared"))
    }

    /** Source-text check: the accepted check-then-act windows are named at both sites, with no lock added. */
    @Test
    fun theAcceptedEstablishWindowsAreDocumentedAtBothSitesWithoutALock() {
        val service = source("MainService.kt")
        val update = function(service, "    private fun updatePfd(")
        val guard = function(service, "    private fun establishAlwaysOnGuard(")
        for (body in listOf(update, guard)) {
            assertTrue(body.contains("ACCEPTED RISK, deliberately not closed"))
            assertTrue(body.contains("ANR"))
            assertTrue(body.contains("A device run must show"))
        }
        // the premise of both comments: nothing is held across establish()
        for (body in listOf(update, guard)) {
            val before = body.substring(0, body.indexOf("builder.establish()"))
            assertFalse(before.contains("synchronized"))
            assertFalse(before.contains(".withLock"))
        }
    }

    @Test
    fun replacementAndDestroyStillTearDown() {
        val service = source("MainService.kt")
        val start = function(service, "    override fun onStartCommand(")
        assertTrue("a replaced owner is stopped", Regex("""currentService\?\.stop\(""").containsMatchIn(start))
        val destroy = function(service, "    override fun onDestroy(")
        assertTrue(Regex("""\bstop\(""").containsMatchIn(destroy))
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
        assertTrue(Regex("""\bstop\(""").containsMatchIn(monitor))
        assertFalse(monitor.contains("scheduleTunnelRetry"))
    }
}
