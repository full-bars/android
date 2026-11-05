package com.bringyour.network

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wiring of the requirement decision, not just the predicate: every
 * site that decides whether the VPN is required must read the shared intent
 * snapshot (provide mode plus the pending connect request), and the stop must
 * carry a reason the ring log can tell apart. Reverting any call site to the
 * provider pointer or the raw connect flag fails here.
 */
class VpnRequirementWiringTest {
    private fun source(name: String): String {
        val file = listOf("", "app/", "app/app/")
            .map { File(it + "src/main/java/com/bringyour/network/$name") }
            .firstOrNull { it.isFile }
        assertNotNull(name, file)
        return file!!.readText()
    }

    /** Body of a function, from its signature to the matching closing brace. */
    private fun function(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        var depth = 0
        var i = source.indexOf('{', start)
        val from = i
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(from, i + 1)
                }
            }
            i++
        }
        return source.substring(from)
    }

    @Test
    fun everyDecisionSiteUsesTheSharedIntentSnapshot() {
        val app = source("MainApplication.kt")
        val reconcile = function(app, "    private fun reconcileVpnService(")
        assertTrue("reconcile must use the snapshot", reconcile.contains("vpnRequirementInputs(device)"))
        assertFalse("reconcile must not read the provider pointer", reconcile.contains(".provideEnabled"))
        assertFalse("reconcile must not read the raw connect flag", reconcile.contains(".connectEnabled"))

        val restored = function(app, "    fun restoredVpnServiceRequired(")
        assertTrue("restored retry must use the snapshot", restored.contains("vpnRequirementInputs(current).required()"))
        assertFalse("restored retry must not read the provider pointer", restored.contains(".provideEnabled"))

        val service = source("MainService.kt")
        val keepalive = function(service, "    private fun keepRequiredVpnUp(")
        assertTrue("the keepalive must use the snapshot", keepalive.contains("app.vpnRequirementInputs("))
        assertFalse("the keepalive must not read the provider pointer", keepalive.contains(".provideEnabled"))
        assertFalse("the keepalive must not read the raw connect flag", keepalive.contains(".connectEnabled"))
    }

    @Test
    fun aProvideModeChangeAsksForAReconcile() {
        // turning providing off while the provider is already detached fires no
        // provideChanged event, so the mode change itself has to trigger one
        val app = source("MainApplication.kt")
        assertTrue(app.contains("addProvideModeChangeListener"))
        assertTrue(app.contains("deviceProvideModeSub"))
        assertTrue(app.contains("deviceProvideModeSub?.close()"))
    }

    @Test
    fun theStopCarriesAReasonThatNamesItsCaller() {
        val app = source("MainApplication.kt")
        assertTrue(app.contains("private fun stopVpnService(reason: String"))
        assertTrue("the reason must reach the ring", app.contains("service?.get()?.stop(reason)"))
        assertTrue(app.contains("stopVpnService(\"logout\")"))
        assertTrue(app.contains("stopVpnService(\"revoke fence\""))
        assertTrue(app.contains("stopVpnService(\"force stop\")"))
        assertTrue(app.contains("stopVpnService(\"not required: \$inputs\")"))
    }

    @Test
    fun theMismatchEdgeReachesTheRing() {
        // an intent to provide with no provider up is the one state that used to
        // stop the VPN; it now writes one ring entry per edge so the next capture
        // separates a rotation from a permanent refusal
        val app = source("MainApplication.kt")
        assertTrue(app.contains("\"provide intent without provider\""))
        assertTrue(app.contains("provideIntentWithoutProvider"))
    }
}
