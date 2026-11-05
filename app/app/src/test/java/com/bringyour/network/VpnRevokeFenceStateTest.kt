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
        var diskEpoch: Long = 0,
        var failWrites: Boolean = false,
        // a write that reports failure after the bit reached the disk
        var landsEvenWhenFailing: Boolean = false,
    ) : VpnRevokeFenceStorage {
        val writes = mutableListOf<Boolean>()
        override fun read() = VpnRevokeFenceRecord(disk, diskEpoch)
        override fun write(record: VpnRevokeFenceRecord): Boolean {
            writes.add(record.raised)
            if (failWrites) {
                if (landsEvenWhenFailing) { disk = record.raised; diskEpoch = record.epoch }
                return false
            }
            disk = record.raised
            diskEpoch = record.epoch
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
    fun aFailedClearIsNotReportedAndTheFenceStaysRaised() {
        val storage = FakeStorage(disk = true, failWrites = true)
        val fence = VpnRevokeFence(storage)
        assertFalse("a clear that did not land must not be reported", fence.clear())
        assertTrue("fail closed: still raised in memory", fence.raised)
        assertTrue("disk untouched, still raised", storage.disk)
        // the next user connect retries and now lands
        storage.failWrites = false
        assertTrue(fence.clear())
        assertFalse(fence.raised || storage.disk)
    }

    @Test
    fun aFailedClearOfAnUnpersistedRaiseStillDoesNotClear() {
        val storage = FakeStorage(failWrites = true)
        val fence = VpnRevokeFence(storage)
        assertFalse(fence.raise()) // held in memory, not durable
        assertFalse(fence.clear())
        assertTrue("memory stays raised", fence.raised)
    }

    /** The write reports failure but the new bit did reach the disk. */
    @Test
    fun anAmbiguousFailedRaiseThatLandedIsNotBelievedAndIsRewritten() {
        val storage = FakeStorage(landsEvenWhenFailing = true, failWrites = true)
        val fence = VpnRevokeFence(storage)
        assertFalse(fence.raise())
        assertTrue("it did land", storage.disk)
        storage.failWrites = false
        storage.writes.clear()
        assertTrue(fence.sync())
        assertEquals("unknown disk state is rewritten, not trusted", listOf(true), storage.writes)
    }

    /** A failed clear that landed on disk while memory stayed raised: they must converge on raised. */
    @Test
    fun anAmbiguousFailedClearThatLandedConvergesBackToRaised() {
        val storage = FakeStorage(disk = true, landsEvenWhenFailing = true, failWrites = true)
        val fence = VpnRevokeFence(storage)
        assertFalse(fence.clear())
        assertFalse("disk got the clear", storage.disk)
        assertTrue("memory held the fence", fence.raised)
        storage.failWrites = false
        assertTrue(fence.sync())
        assertTrue("disk repaired to match memory", storage.disk)
        assertTrue(fence.raised)
    }

    @Test
    fun aLaterOperationNeverBelievesAStaleDurableState() {
        val storage = FakeStorage(failWrites = true)
        val fence = VpnRevokeFence(storage)
        fence.raise() // fails: disk says cleared, memory raised
        storage.failWrites = false
        // a redundant raise must not skip the write just because memory already says raised
        assertTrue(fence.raise())
        assertTrue(storage.disk)
    }

    @Test
    fun aClearOnAClearedFenceWritesNothing() {
        val storage = FakeStorage()
        val fence = VpnRevokeFence(storage)
        assertTrue(fence.clear())
        assertEquals(emptyList<Boolean>(), storage.writes)
    }

    @Test
    fun memoryAndDiskAgreeAfterEveryOperationThatReportsSuccess() {
        val storage = FakeStorage()
        val fence = VpnRevokeFence(storage)
        for (step in 0 until 40) {
            val ok = if (step % 3 == 0) fence.clear() else fence.raise()
            if (ok) assertEquals("step $step", fence.raised, storage.disk)
        }
        assertEquals(fence.raised, storage.disk)
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

    // ---- epoch: work admitted before a revoke cannot ride a later clear

    @Test
    fun anAdmissionIsRefusedWhileRaisedAndTakenOtherwise() {
        val fence = VpnRevokeFence(FakeStorage())
        val admitted = fence.admission()
        assertTrue(admitted != null && fence.admits(admitted))
        fence.raise()
        assertEquals(null, fence.admission())
        assertFalse("raised: nothing is admitted", fence.admits(admitted))
        assertFalse(fence.admits(null))
    }

    /** The case a current-value check misses: revoke, then the user connects, then the old work runs. */
    @Test
    fun aClearedFenceDoesNotReadmitAnOlderPendingStart() {
        val fence = VpnRevokeFence(FakeStorage())
        val pending = fence.admission() // a start admitted before the revoke
        fence.raise()
        assertTrue(fence.clear()) // the user connects: fence is down again
        assertFalse("the fence is down but the epoch moved", fence.raised)
        assertFalse("the older start is refused", fence.admits(pending))
        val fresh = fence.admission()
        assertTrue("work admitted after the clear is admitted", fence.admits(fresh))
        assertTrue(fresh != pending)
    }

    @Test
    fun everyRevokeInvalidatesEveryEarlierAdmission() {
        val fence = VpnRevokeFence(FakeStorage())
        val tokens = mutableListOf<Long>()
        repeat(5) {
            tokens.add(fence.admission()!!)
            fence.raise(); fence.clear()
        }
        val live = fence.admission()
        assertEquals("no epoch is ever reused", tokens.size, tokens.toSet().size)
        assertTrue(tokens.none { fence.admits(it) })
        assertTrue(fence.admits(live))
    }

    @Test
    fun aClearOfALoweredFenceKeepsPendingWorkAdmitted() {
        val fence = VpnRevokeFence(FakeStorage())
        val pending = fence.admission()
        assertTrue(fence.clear()) // a user connect with no revoke before it
        assertTrue(fence.admits(pending))
    }

    @Test
    fun aRedundantRaiseDoesNotMoveTheEpochAgain() {
        val fence = VpnRevokeFence(FakeStorage())
        fence.raise()
        val raisedAt = fence.epoch
        fence.raise()
        assertEquals(raisedAt, fence.epoch)
    }

    @Test
    fun aClearThatDidNotLandDoesNotReadmitOlderWork() {
        val storage = FakeStorage()
        val fence = VpnRevokeFence(storage)
        val pending = fence.admission()
        fence.raise()
        storage.failWrites = true
        assertFalse(fence.clear())
        assertFalse(fence.admits(pending))
        assertEquals("no admission while still raised", null, fence.admission())
    }

    @Test
    fun theEpochSurvivesARestart() {
        val storage = FakeStorage()
        val first = VpnRevokeFence(storage)
        val pending = first.admission()
        first.raise(); first.clear()
        val restarted = VpnRevokeFence(storage) // a new process reads the disk
        assertEquals(first.epoch, restarted.epoch)
        assertFalse("an intent from before the revoke dies with its epoch", restarted.admits(pending))
    }

    @Test
    fun aStartIntentWithNoEpochIsJudgedByTheCurrentValueOnly() {
        val fence = VpnRevokeFence(FakeStorage())
        assertTrue(vpnStartEpochAdmitted(null) { fence.admits(it) })
        val carried = fence.admission()!!
        assertTrue(vpnStartEpochAdmitted(carried) { fence.admits(it) })
        fence.raise(); fence.clear()
        assertFalse(vpnStartEpochAdmitted(carried) { fence.admits(it) })
        assertTrue("no epoch carried: current value only", vpnStartEpochAdmitted(null) { fence.admits(it) })
    }
}
