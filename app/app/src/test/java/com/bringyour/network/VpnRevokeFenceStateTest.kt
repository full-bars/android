package com.bringyour.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fence state machine over a fake store: serialization and failed writes. */
class VpnRevokeFenceStateTest {

    private class FakeStorage(
        var disk: Boolean = false,
        var failWrites: Boolean = false,
    ) : VpnRevokeFenceStorage {
        val writes = mutableListOf<Boolean>()
        override fun read() = disk
        override fun write(raised: Boolean): Boolean {
            writes.add(raised)
            if (failWrites) return false
            disk = raised
            return true
        }
    }

    @Test
    fun raiseAndClearAreDurable() {
        val storage = FakeStorage()
        val fence = VpnRevokeFence(storage)
        assertTrue(fence.raise())
        assertTrue(fence.raised && storage.disk)
        assertTrue(fence.clear())
        assertFalse(fence.raised || storage.disk)
    }

    @Test
    fun aRaisedBitOnDiskIsReadAtConstruction() {
        assertTrue(VpnRevokeFence(FakeStorage(disk = true)).raised)
    }

    @Test
    fun aFailedRaiseDoesNotReportSuccessButStillHoldsTheFence() {
        val storage = FakeStorage(failWrites = true)
        val fence = VpnRevokeFence(storage)
        assertFalse("not durable, must not claim it", fence.raise())
        assertTrue("held in memory regardless", fence.raised)
        assertFalse(storage.disk)
        // the next operation retries the write
        storage.failWrites = false
        assertTrue(fence.sync())
        assertTrue(storage.disk)
    }

    @Test
    fun aFailedClearReportsFailureAndLeavesDiskRaised() {
        val storage = FakeStorage(disk = true, failWrites = true)
        val fence = VpnRevokeFence(storage)
        assertFalse(fence.clear())
        assertFalse("the user's connect wins in this process", fence.raised)
        assertTrue("disk stays raised: a restart fails safe", storage.disk)
        storage.failWrites = false
        assertTrue(fence.sync())
        assertFalse(storage.disk)
    }

    @Test
    fun aRedundantRaiseWritesNothingButAFailedOneIsRetried() {
        val storage = FakeStorage()
        val fence = VpnRevokeFence(storage)
        fence.raise(); fence.raise(); fence.raise()
        assertEquals(listOf(true), storage.writes)
    }

    @Test
    fun concurrentRaiseAndClearNeverLeaveDiskDisagreeingWithMemory() {
        repeat(200) {
            val storage = FakeStorage()
            val fence = VpnRevokeFence(storage)
            val pool = Executors.newFixedThreadPool(2)
            val go = CountDownLatch(1)
            val done = CountDownLatch(2)
            pool.execute { go.await(); fence.raise(); done.countDown() }
            pool.execute { go.await(); fence.clear(); done.countDown() }
            go.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            pool.shutdown()
            assertEquals("disk matches memory", fence.raised, storage.disk)
        }
    }
}
