package com.bringyour.network

/**
 * Durable backing for [VpnRevokeFence]. `write` is true only when the bit is
 * known to be on disk; false means it may or may not be (an ambiguous write).
 */
internal interface VpnRevokeFenceStorage {
    fun read(): Boolean
    fun write(raised: Boolean): Boolean
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
 */
internal class VpnRevokeFence(private val storage: VpnRevokeFenceStorage) {
    private val lock = Any()
    private var cached: Boolean = storage.read()

    // what the disk is known to hold; null after a write that failed, because
    // a failed write may still have landed
    private var persisted: Boolean? = cached

    val raised: Boolean get() = synchronized(lock) { cached }

    /** True when the raised bit is durable. The fence holds in memory either way. */
    fun raise(): Boolean = synchronized(lock) {
        cached = true
        sync()
    }

    /**
     * True when the cleared bit is durable and the fence is down. False leaves
     * the fence raised: the caller must say so and may retry.
     */
    fun clear(): Boolean = synchronized(lock) {
        if (!cached && persisted == false) return true
        if (storage.write(false)) {
            cached = false
            persisted = false
            true
        } else {
            persisted = null
            false
        }
    }

    /** Whether the disk is known to equal the in-memory bit; retries the write when it is not. */
    fun sync(): Boolean = synchronized(lock) {
        if (persisted != cached) {
            persisted = if (storage.write(cached)) cached else null
        }
        persisted == cached
    }
}
