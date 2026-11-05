package com.bringyour.network

import java.io.File
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** The file is chronological on disk; callers read it in reverse order. */
internal class VpnLifecycleRing(private val file: File, private val capacity: Int = 200) {
    init { require(capacity > 0) }

    fun append(timestamp: Instant, event: String, detail: String) {
        val cleanEvent = event.replace(Regex("[\\r\\n\\t]"), " ").take(128)
        val cleanDetail = detail.replace(Regex("[\\r\\n\\t]"), " ").take(1024)
        val lines = if (file.exists()) file.readLines().takeLast(capacity - 1) else emptyList()
        val updated = lines + "$timestamp\t$cleanEvent\t$cleanDetail"
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(updated.joinToString("\n", postfix = "\n"))
        check(temp.renameTo(file)) { "could not replace lifecycle log" }
    }

    fun newestFirst(): List<String> = if (file.exists()) file.readLines().takeLast(capacity).asReversed() else emptyList()
}

/** All disk access is serialized on a bounded background worker. */
internal object VpnLifecycleLog {
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(256),
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    private val fileLock = Any()

    fun record(filesDir: () -> File, event: String, detail: String = "") {
        val timestamp = Instant.now()
        worker.execute {
            try {
                synchronized(fileLock) {
                    VpnLifecycleRing(File(filesDir(), "vpn-lifecycle.log")).append(timestamp, event, detail)
                }
            } catch (_: Exception) {
                // Diagnostics must never change VPN behavior, including before unlock.
            }
        }
    }

    fun read(filesDir: File): List<String> = synchronized(fileLock) {
        VpnLifecycleRing(File(filesDir, "vpn-lifecycle.log")).newestFirst()
    }
}
