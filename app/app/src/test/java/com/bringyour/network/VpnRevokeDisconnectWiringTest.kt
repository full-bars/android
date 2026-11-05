package com.bringyour.network

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A disconnect in the system VPN settings revokes the VPN (MainService.onRevoke).
 * The service stopped, but the connection stayed enabled, so the tunnel stop
 * reconciled the service and started it again (support inbox 8018). The revoke
 * disconnects the connection on the connect command queue, and the reconcile
 * counts that queued disconnect. Read from the source, since MainService and
 * MainApplication need a device; what a queued command does and what
 * connectRequested reads is DeviceConnectCommandTest's.
 */
class VpnRevokeDisconnectWiringTest {

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
    fun foregroundPromotionFailureStopsImmediately() {
        val onCreate = function(source("MainService.kt"), "    override fun onCreate(")
        val failure = onCreate.substring(onCreate.indexOf("catch (e: Exception)"))
        val report = failure.indexOf("vpnServiceDidNotStart(this)")
        val stop = failure.indexOf("stopSelf()")
        assertTrue("promotion failure must be reported", report >= 0)
        assertTrue("promotion failure must stop immediately", stop > report)
        assertTrue("promotion must be marked unsuccessful", failure.indexOf("false", stop) > stop)
    }

    @Test
    fun theSystemsRevokeDisconnectsBeforeTheServiceStops() {
        val onRevoke = function(source("MainService.kt"), "    override fun onRevoke(")
        val revoked = onRevoke.indexOf("vpnServiceRevoked()")
        assertTrue("onRevoke does not disconnect the connection", 0 <= revoked)
        assertTrue("onRevoke stops before it disconnects", revoked < onRevoke.indexOf("stop()"))
    }

    @Test
    fun theRevokeDisconnectsOnTheQueueEvenWithAlwaysOn() {
        val revoked = function(source("MainApplication.kt"), "    fun vpnServiceRevoked(")
        assertTrue(revoked.contains("connectCommands.runOnDevice(connectDevice, connect = false)"))
        assertFalse("the revoke is ignored with Always-on", revoked.contains("systemAlwaysOnVpn"))
        assertFalse("the revoke changes provide", revoked.contains("provide", ignoreCase = true))
    }

    @Test
    fun theReconcileCountsAQueuedDisconnect() {
        val reconcile = function(source("MainApplication.kt"), "    private fun reconcileVpnService(")
        assertTrue(reconcile.contains("connectCommands.connectRequested("))
        assertFalse("the reconcile reads only the device", reconcile.contains("device.connectEnabled"))
    }
}
