package com.bringyour.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keepalive for post-promotion internal stops: a stale start ("obsolete
 * flow"), the close monitor and an init failure keep a required VPN's service,
 * foreground notification and TUN retry alive instead of calling stopSelf.
 * The decision is pure; the wiring is read from the source, since MainService
 * needs a device. The retry itself on a device is not covered here.
 */
class VpnInternalStopKeepaliveTest {
    private fun decide(
        promoted: Boolean = true,
        owns: Boolean = true,
        device: Boolean = true,
        fenced: Boolean = false,
        provide: Boolean = false,
        connect: Boolean = false,
        routeLocal: Boolean = true,
    ) = decideInternalStop(promoted, owns, device, fenced, provide, connect, routeLocal)

    @Test
    fun requiredByAnyOfProvideConnectOrKillSwitchKeepsAndRetries() {
        assertEquals(VpnInternalStopDecision.KEEP_AND_RETRY, decide(provide = true))
        assertEquals(VpnInternalStopDecision.KEEP_AND_RETRY, decide(connect = true))
        assertEquals(VpnInternalStopDecision.KEEP_AND_RETRY, decide(routeLocal = false))
    }

    @Test
    fun notRequiredStillStops() {
        assertEquals(VpnInternalStopDecision.STOP, decide())
    }

    @Test
    fun missingDeviceStopsEvenWhenOtherwiseRequired() {
        assertEquals(VpnInternalStopDecision.STOP, decide(device = false, provide = true))
    }

    @Test
    fun replacementAndUnpromotedAndFencedStillStop() {
        assertEquals(VpnInternalStopDecision.STOP, decide(owns = false, provide = true))
        assertEquals(VpnInternalStopDecision.STOP, decide(promoted = false, provide = true))
        assertEquals(VpnInternalStopDecision.STOP, decide(fenced = true, provide = true))
    }

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

    @Test
    fun theKeepHoldsTheServiceAndSchedulesTheConfigurationRetry() {
        val keep = function(source("MainService.kt"), "    private fun keepRequiredVpnUp(")
        assertTrue(keep.contains("decideInternalStop("))
        assertTrue(keep.contains("TunnelRetryKind.CONFIGURATION"))
        assertTrue("the held stop must not stop", !keep.contains("stopSelf") && !keep.contains("stop()"))
        assertTrue("the held stop must not drop the notification", !keep.contains("stopForegroundNotification"))
    }

    @Test
    fun obsoleteFlowOnlyHeldForAStaleIntent() {
        val start = function(source("MainService.kt"), "    override fun onStartCommand(")
        val held = start.indexOf("keepRequiredVpnUp(\"obsolete-flow\")")
        assertTrue("obsolete flow does not consult the keepalive", 0 <= held)
        val line = start.substring(start.lastIndexOf("val obsoleteFlow", held), held)
        assertTrue(line.contains("!stop") && line.contains("!revokeFenced") && line.contains("commandCompatible"))
        assertTrue("the held stop must precede stop()", held < start.indexOf("stop()", held))
    }

    @Test
    fun theCloseMonitorAndInitFailureConsultTheKeepalive() {
        val service = source("MainService.kt")
        val monitorFrom = service.indexOf("private fun startCloseMonitor(")
        assertTrue("missing startCloseMonitor", 0 <= monitorFrom)
        val monitor = service.substring(monitorFrom)
        assertTrue(monitor.contains("if (!keepRequiredVpnUp(\"close-monitor\")) stop()"))
        val start = function(service, "    override fun onStartCommand(")
        val init = start.substring(start.indexOf("application initialization failed"))
        assertTrue(init.indexOf("keepRequiredVpnUp(\"application-init-failure\")") in 0 until init.indexOf("stop()"))
    }

    @Test
    fun missingDeviceAndReplacementStopsAreUnchanged() {
        val service = source("MainService.kt")
        val reconcile = function(service, "    private fun reconcilePfd(")
        assertTrue(reconcile.contains("device unavailable outside Always-on; stopping"))
        assertTrue(!reconcile.contains("keepRequiredVpnUp"))
        val update = function(service, "    private fun updatePfd(")
        assertTrue(!update.contains("keepRequiredVpnUp"))
        val onRevoke = function(service, "    override fun onRevoke(")
        assertTrue(!onRevoke.contains("keepRequiredVpnUp"))
        val onDestroy = function(service, "    override fun onDestroy(")
        assertTrue(!onDestroy.contains("keepRequiredVpnUp"))
    }
}
