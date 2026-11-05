package com.bringyour.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class VpnLifecycleLogTest {
    @Test fun keepsNewestEventsAndOneLinePerEvent() {
        val dir = Files.createTempDirectory("vpn-log-test").toFile()
        try {
            val file = dir.resolve("vpn-lifecycle.log")
            val ring = VpnLifecycleRing(file, 3)
            repeat(5) { ring.append(Instant.ofEpochSecond(it.toLong()), "event$it", "detail\n$it") }
            assertEquals(3, file.readLines().size)
            assertEquals(listOf("event4", "event3", "event2"), ring.newestFirst().map { it.split('\t')[1] })
            assertTrue(ring.newestFirst().first().endsWith("detail 4"))
            assertEquals(ring.newestFirst(), VpnLifecycleRing(file, 3).newestFirst())
        } finally {
            dir.deleteRecursively()
        }
    }
}
