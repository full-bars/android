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
        assertEquals(VpnRevokeFenceRecord(false, 0), VpnRevokeFenceFile(dir).read())
    }

    @Test
    fun raisedAndClearedRoundTripAndLeaveNoTempFile() {
        val store = VpnRevokeFenceFile(dir)
        assertTrue(store.write(VpnRevokeFenceRecord(true, 7)))
        assertEquals(VpnRevokeFenceRecord(true, 7), store.read())
        assertTrue(store.write(VpnRevokeFenceRecord(false, 8)))
        assertEquals(VpnRevokeFenceRecord(false, 8), store.read())
        assertFalse("the temp file is gone", temp.exists())
    }

    @Test
    fun aTornOrForeignFileReadsAsRaised() {
        val store = VpnRevokeFenceFile(dir)
        val junkFiles = listOf(
            "", "vpn-revoke-fence-v2 clea", "garbage\n", "vpn-revoke-fence-v2 cleared 3", // no newline: torn
            "vpn-revoke-fence-v2 cleared\n", "vpn-revoke-fence-v2 cleared x\n", "vpn-revoke-fence-v2 cleared -1\n",
            "vpn-revoke-fence-v1 cleared\n", "vpn-revoke-fence-v2 cleared 3\nextra",
        )
        for (junk in junkFiles) {
            target.writeText(junk)
            val read = store.read()
            assertTrue("<$junk> must fail closed", read.raised)
            assertEquals(VPN_REVOKE_FENCE_UNREADABLE_EPOCH, read.epoch)
        }
    }

    @Test
    fun anUnreadableTargetReadsAsRaised() {
        target.mkdir() // a directory where the file should be: readText throws
        assertTrue(VpnRevokeFenceFile(dir).read().raised)
    }

    @Test
    fun aFailedRenameLeavesTheOldContentWholeAndNoTemp() {
        VpnRevokeFenceFile(dir).write(VpnRevokeFenceRecord(true, 1))
        val store = VpnRevokeFenceFile(dir, moveIntoPlace = { _, _ -> throw java.io.IOException("rename failed") })
        assertFalse(store.write(VpnRevokeFenceRecord(false, 2)))
        assertTrue("old raised content survives", VpnRevokeFenceFile(dir).read().raised)
        assertFalse(temp.exists())
    }

    @Test
    fun aLeftoverTempFromACrashIsNeverPromoted() {
        VpnRevokeFenceFile(dir).write(VpnRevokeFenceRecord(true, 1))
        temp.writeText("vpn-revoke-fence-v2 cleared 2\n") // crash after writing temp, before rename
        assertTrue("reads ignore the temp", VpnRevokeFenceFile(dir).read().raised)
    }

    /** The rename landed, then the call failed: the write reports false but the disk holds the new bit. */
    @Test
    fun aRenameThatLandsThenFailsIsReportedAsFailedAndStateRepairsIt() {
        VpnRevokeFenceFile(dir).write(VpnRevokeFenceRecord(true, 1))
        var failAfterMove = true
        val store = VpnRevokeFenceFile(dir, moveIntoPlace = { t, g ->
            java.nio.file.Files.move(t.toPath(), g.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            if (failAfterMove) throw java.io.IOException("failed after rename")
        })
        val fence = VpnRevokeFence(store)
        assertFalse("not reported", fence.clear())
        assertTrue("fail closed in memory", fence.raised)
        assertFalse("but the disk holds cleared (ambiguous)", VpnRevokeFenceFile(dir).read().raised)
        failAfterMove = false
        assertTrue(fence.sync())
        assertEquals("memory and disk agree again", fence.raised, VpnRevokeFenceFile(dir).read().raised)
        assertEquals(fence.epoch, VpnRevokeFenceFile(dir).read().epoch)
    }

    @Test
    fun aWrongReadBackIsAFailedWrite() {
        val store = VpnRevokeFenceFile(dir, moveIntoPlace = { t, g -> g.writeText("vpn-revoke-fence-v2 raised 5\n"); t.delete() })
        assertFalse("wrote cleared, disk says raised", store.write(VpnRevokeFenceRecord(false, 5)))
    }
}
