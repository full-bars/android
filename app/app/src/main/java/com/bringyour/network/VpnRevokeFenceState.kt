package com.bringyour.network

/** Durable backing for [VpnRevokeFence]. `write` reports whether the bit reached disk. */
internal interface VpnRevokeFenceStorage {
    fun read(): Boolean
    fun write(raised: Boolean): Boolean
}

/**
 * The raised/cleared state of the revoke fence, serialized: the cached read,
 * the durable write and the check are one critical section, so a raise on the
 * binder thread and a user clear on main cannot interleave into a state the
 * disk disagrees with. The in-memory bit changes first and is what every check
 * reads (a failed write never lets a restart through in this process); the
 * result of the durable write is returned and a failed one is retried by the
 * next operation.
 */
internal class VpnRevokeFence(private val storage: VpnRevokeFenceStorage) {
    private val lock = Any()
    private var cached: Boolean = storage.read()
    private var persisted: Boolean = cached

    val raised: Boolean get() = synchronized(lock) { cached }

    /** True when the raised bit is durable. The fence holds in memory either way. */
    fun raise(): Boolean = synchronized(lock) {
        cached = true
        sync()
    }

    /** True when the cleared bit is durable. A failed write leaves disk raised (fail-safe on restart). */
    fun clear(): Boolean = synchronized(lock) {
        cached = false
        sync()
    }

    /** Whether the durable bit equals the in-memory bit; retries the write when it does not. */
    fun sync(): Boolean = synchronized(lock) {
        if (persisted != cached) {
            persisted = if (storage.write(cached)) cached else persisted
        }
        persisted == cached
    }
}
