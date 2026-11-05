package com.bringyour.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ring entries that separate a rotation from a permanent refusal from the
 * operator turning providing off. The decision is pure, so the wording and the
 * flag are tested without a device; the wiring test covers that the reconcile
 * calls it and that every stop names its caller.
 */
class VpnProvideEdgeTest {
    private val none = 0L
    private val public = 3L

    private fun edge(previous: Boolean, mode: Long = public, enabled: Boolean = false) =
        provideEdge(previous, mode, none, enabled)

    @Test
    fun enteringARotationIsOneEntry() {
        val e = edge(previous = false, mode = public, enabled = false)
        assertEquals("provide intent without provider", e.event)
        assertTrue(e.intentWithoutProvider)
    }

    @Test
    fun leavingARotationSaysRestored() {
        val e = edge(previous = true, mode = public, enabled = true)
        assertEquals("provider restored", e.event)
        assertFalse(e.intentWithoutProvider)
    }

    @Test
    fun withdrawingTheIntentSaysWithdrawnNotRestored() {
        // the operator turned providing off while nothing was providing: nothing
        // was restored, and the capture must not claim it was
        val e = edge(previous = true, mode = none, enabled = false)
        assertEquals("provide intent withdrawn", e.event)
        assertFalse(e.intentWithoutProvider)
    }

    @Test
    fun noChangeWritesNothing() {
        assertEquals(null, edge(previous = false, mode = public, enabled = true).event)
        assertEquals(null, edge(previous = true, mode = public, enabled = false).event)
        assertEquals(null, edge(previous = false, mode = none, enabled = false).event)
    }

    @Test
    fun theFlagFollowsTheIntentEveryTime() {
        assertTrue(edge(previous = false, mode = public, enabled = false).intentWithoutProvider)
        assertFalse(edge(previous = true, mode = none, enabled = false).intentWithoutProvider)
        assertFalse(edge(previous = true, mode = public, enabled = true).intentWithoutProvider)
    }

    @Test
    fun everyFenceStopNamesItself() {
        val file = listOf("", "app/", "app/app/")
            .map { File(it + "src/main/java/com/bringyour/network/MainService.kt") }
            .firstOrNull { it.isFile }
        assertNotNull("MainService.kt", file)
        val service = file!!.readText()
        val fenceStops = Regex("""stop\("revoke fence"\)""").findAll(service).count()
        assertTrue("the fence stops must name themselves: found $fenceStops", fenceStops >= 2)
        assertFalse("no fence stop may stay unnamed", Regex("""\n\s+stop\(\)\n""").containsMatchIn(service))
    }

    @Test
    fun theLogoutPathForgetsTheEdge() {
        val app = listOf("", "app/", "app/app/")
            .map { File(it + "src/main/java/com/bringyour/network/MainApplication.kt") }
            .firstOrNull { it.isFile }!!.readText()
        val stop = app.substring(app.indexOf("fun stop() {")).take(600)
        assertTrue("logout must forget the remembered edge", stop.contains("provideIntentWithoutProvider = false"))
    }
}
