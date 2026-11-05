package com.bringyour.network

/**
 * What the fence holds: whether it is raised, and its epoch. The epoch counts
 * revokes: it moves each time the fence is raised and never otherwise, so a
 * number taken while the fence was down means "admitted with no revoke since".
 * Stored with the bit so it survives a restart.
 */
internal data class VpnRevokeFenceRecord(val raised: Boolean, val epoch: Long)

/**
 * Durable backing for [VpnRevokeFence]. `write` is true only when the record is
 * known to be on disk; false means it may or may not be (an ambiguous write).
 */
internal interface VpnRevokeFenceStorage {
    fun read(): VpnRevokeFenceRecord
    fun write(record: VpnRevokeFenceRecord): Boolean
}

/**
 * The raised/cleared state of the revoke fence, serialized: the cached read,
 * the durable write and the check are one critical section, so a raise on the
 * binder thread and a user clear on main cannot interleave into a state the
 * disk disagrees with.
 *
 * Fails closed on a failed write. A raise holds in memory first (a restart is
 * not let through in this process) and is retried until it lands. A clear is
 * written first and takes effect in memory only once it landed: a clear that
 * did not land is reported as false and the fence stays raised, never "cleared
 * here, raised on disk". After a failed write the state of the disk is unknown
 * (the rename may have landed before the read-back failed), so it is retried
 * rather than believed.
 *
 * Work that waits (a queued connect, a start intent in flight, a scheduled
 * retry) is admitted with [admission] and asks [admits] when it runs. A plain
 * "is it raised now" check is not enough for that: a revoke followed by the
 * user's connect leaves the fence down again, and would let the older work
 * through. The epoch it was admitted under no longer matches, so it does not.
 */
internal class VpnRevokeFence(private val storage: VpnRevokeFenceStorage) {
    private val lock = Any()
    private var cached: VpnRevokeFenceRecord = storage.read()

    // what the disk is known to hold; null after a write that failed, because
    // a failed write may still have landed
    private var persisted: VpnRevokeFenceRecord? = cached

    val raised: Boolean get() = synchronized(lock) { cached.raised }

    val epoch: Long get() = synchronized(lock) { cached.epoch }

    /**
     * The epoch to admit deferred work under, or null while the fence is raised.
     * Never null-then-equal: a raise moves the epoch, so the number handed out
     * after a clear is not one any earlier admission holds.
     */
    fun admission(): Long? = synchronized(lock) { if (cached.raised) null else cached.epoch }

    /** Whether work admitted under `admission` may still run: no revoke since. */
    fun admits(admission: Long?): Boolean = synchronized(lock) {
        admission != null && !cached.raised && cached.epoch == admission
    }

    /** True when the raised bit is durable. The fence holds in memory either way. */
    fun raise(): Boolean = synchronized(lock) {
        if (!cached.raised) cached = VpnRevokeFenceRecord(raised = true, epoch = cached.epoch + 1)
        sync()
    }

    /**
     * True when the cleared bit is durable and the fence is down. False leaves
     * the fence raised: the caller must say so and may retry. A clear of a
     * fence that is not raised changes nothing, so work admitted meanwhile
     * stays admitted.
     */
    fun clear(): Boolean = synchronized(lock) {
        if (!cached.raised) return sync()
        val lowered = VpnRevokeFenceRecord(raised = false, epoch = cached.epoch)
        if (storage.write(lowered)) {
            cached = lowered
            persisted = lowered
            true
        } else {
            persisted = null
            false
        }
    }

    /** Whether the disk is known to equal the in-memory record; retries the write when it is not. */
    fun sync(): Boolean = synchronized(lock) {
        if (persisted != cached) {
            persisted = if (storage.write(cached)) cached else null
        }
        persisted == cached
    }
}
