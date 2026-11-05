package com.bringyour.network

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The real file store, on a real directory: atomic replace, read-back, fail-closed reads. */
class VpnRevokeFenceFileTest {
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("fence").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private val target get() = File(dir, VPN_REVOKE_FENCE_FILE)
    private val temp get() = File(dir, "$VPN_REVOKE_FENCE_FILE.tmp")

    @Test
    fun aMissingFileIsAClearedFence() {
        assertFalse(VpnRevokeFenceFile(dir).read())
    }

    @Test
    fun raisedAndClearedRoundTripAndLeaveNoTempFile() {
        val store = VpnRevokeFenceFile(dir)
        assertTrue(store.write(true))
        assertTrue(store.read())
        assertTrue(store.write(false))
        assertFalse(store.read())
        assertFalse("the temp file is gone", temp.exists())
    }

    @Test
    fun aTornOrForeignFileReadsAsRaised() {
        val store = VpnRevokeFenceFile(dir)
        for (junk in listOf("", "vpn-revoke-fence-v1 clea", "garbage\n", "vpn-revoke-fence-v1 cleared\nextra")) {
            target.writeText(junk)
            assertTrue("<$junk> must fail closed", store.read())
        }
    }

    @Test
    fun anUnreadableTargetReadsAsRaised() {
        target.mkdir() // a directory where the file should be: readText throws
        assertTrue(VpnRevokeFenceFile(dir).read())
    }

    @Test
    fun aFailedRenameLeavesTheOldContentWholeAndNoTemp() {
        VpnRevokeFenceFile(dir).write(true)
        val store = VpnRevokeFenceFile(dir, moveIntoPlace = { _, _ -> throw java.io.IOException("rename failed") })
        assertFalse(store.write(false))
        assertTrue("old raised content survives", VpnRevokeFenceFile(dir).read())
        assertFalse(temp.exists())
    }

    @Test
    fun aLeftoverTempFromACrashIsNeverPromoted() {
        VpnRevokeFenceFile(dir).write(true)
        temp.writeText("vpn-revoke-fence-v1 cleared\n") // crash after writing temp, before rename
        assertTrue("reads ignore the temp", VpnRevokeFenceFile(dir).read())
    }

    /** The rename landed, then the call failed: the write reports false but the disk holds the new bit. */
    @Test
    fun aRenameThatLandsThenFailsIsReportedAsFailedAndStateRepairsIt() {
        VpnRevokeFenceFile(dir).write(true)
        var failAfterMove = true
        val store = VpnRevokeFenceFile(dir, moveIntoPlace = { t, g ->
            java.nio.file.Files.move(t.toPath(), g.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            if (failAfterMove) throw java.io.IOException("failed after rename")
        })
        val fence = VpnRevokeFence(store)
        assertFalse("not reported", fence.clear())
        assertTrue("fail closed in memory", fence.raised)
        assertFalse("but the disk holds cleared (ambiguous)", VpnRevokeFenceFile(dir).read())
        failAfterMove = false
        assertTrue(fence.sync())
        assertEquals("memory and disk agree again", fence.raised, VpnRevokeFenceFile(dir).read())
    }

    @Test
    fun aWrongReadBackIsAFailedWrite() {
        val store = VpnRevokeFenceFile(dir, moveIntoPlace = { t, g -> g.writeText("vpn-revoke-fence-v1 raised\n"); t.delete() })
        assertFalse("wrote cleared, disk says raised", store.write(false))
    }
}
