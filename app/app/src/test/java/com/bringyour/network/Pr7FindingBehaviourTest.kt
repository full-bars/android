package com.bringyour.network

import com.bringyour.network.ui.connect.alwaysOnRecoveryShown
import com.bringyour.network.ui.wallet.BittensorKeyScreenRequests
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr7FindingBehaviourTest {
    @Test fun alwaysOnLineNeedsBothTheDisconnectFirstLineAndTheSystemSetting() {
        assertTrue(alwaysOnRecoveryShown(disconnectFirst = true, systemAlwaysOn = true))
        assertFalse(alwaysOnRecoveryShown(disconnectFirst = true, systemAlwaysOn = false))
        assertFalse(alwaysOnRecoveryShown(disconnectFirst = false, systemAlwaysOn = true))
        assertFalse(alwaysOnRecoveryShown(disconnectFirst = false, systemAlwaysOn = false))
    }

    @Test fun clearDropsAWaitingKeyScreenRequestFromSignOutOrALeftEarnings() {
        BittensorKeyScreenRequests.request()
        assertTrue(BittensorKeyScreenRequests.screen.value)
        assertTrue(BittensorKeyScreenRequests.navigation.value)
        BittensorKeyScreenRequests.clear()
        assertFalse(BittensorKeyScreenRequests.screen.value)
        assertFalse(BittensorKeyScreenRequests.navigation.value)
        assertFalse(BittensorKeyScreenRequests.take())
    }

    @Test fun aDeliveredRequestIsStillTakenOnceBeforeAnyClear() {
        BittensorKeyScreenRequests.request()
        BittensorKeyScreenRequests.consumeNavigation()
        assertTrue(BittensorKeyScreenRequests.take())
        assertFalse(BittensorKeyScreenRequests.take())
        BittensorKeyScreenRequests.clear()
    }

    // The replacement path asks the same question as the ended-flow path: a flow that
    // held for the stable window forgives its failures, a shorter one keeps them.
    @Test fun aReplacedFlowThatStayedUpClearsTheBackoffHistory() {
        assertEquals(0, packetFlowRetryFailureCount(4, 30_000L, retryPending = false))
        assertEquals(4, packetFlowRetryFailureCount(4, 29_999L, retryPending = false))
    }

    private fun res(): File = listOf("app/app/src/main/res", "app/src/main/res", "src/main/res")
        .map { File(it) }.first { it.isDirectory }

    private fun value(file: File, name: String): String? =
        Regex("""<string name="$name">(.*?)</string>""").find(file.readText())?.groupValues?.get(1)

    @Test fun clipboardAndSignOutWordingIsInEveryLanguageAndNoLongerPromisesTheClear() {
        val base = File(res(), "values/strings.xml")
        val copy = value(base, "bittensor_key_copy_warning_2")!!
        val signOut = value(base, "bittensor_key_sign_out_message_2")!!
        assertTrue(copy, copy.contains("can\\'t guarantee"))
        assertFalse(copy, copy.contains("is cleared after"))
        assertTrue(signOut, signOut.contains("backing up the recovery phrase"))
        val locales = res().listFiles { f -> f.name.startsWith("values-") && File(f, "strings.xml").isFile }!!
        assertTrue(locales.size >= 18)
        for (dir in locales) {
            val f = File(dir, "strings.xml")
            for ((name, english) in listOf(
                "bittensor_key_copy_warning_2" to copy,
                "bittensor_key_sign_out_message_2" to signOut,
                "insufficient_balance_always_on_vpn" to value(base, "insufficient_balance_always_on_vpn")!!,
            )) {
                val v = value(f, name)
                assertTrue("${dir.name} $name", v != null && v.isNotBlank())
                assertNotEquals("${dir.name} $name", english, v)
            }
        }
    }
}
