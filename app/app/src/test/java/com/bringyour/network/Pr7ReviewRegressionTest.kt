package com.bringyour.network

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr7ReviewRegressionTest {
    private fun source(path: String): String {
        val file = listOf(File("src/$path"), File("app/src/$path"), File("app/app/src/$path"))
            .firstOrNull { it.isFile }
        requireNotNull(file) { "Missing source: $path" }
        return file.readText()
    }

    @Test
    fun targetChangesKeepHistoryUntilAStableRecovery() {
        val service = source("main/java/com/bringyour/network/MainService.kt")
        assertTrue(service.contains("tunnelRetryConfiguration != desired\n        ) {\n            cancelTunnelRetry(resetFailureCount = false)"))
        assertTrue(service.contains("if (!sameTarget) {\n            // Keep failure history when a retry changes target or a loop flaps.\n            cancelTunnelRetry(resetFailureCount = false)"))
        assertTrue(service.contains("if (desired != configuration) {\n                        cancelTunnelRetry(resetFailureCount = false)"))
        assertTrue(service.contains("if (boundDevice !== retryDevice || configuration == null) {\n                        cancelTunnelRetry()"))
        val settings = VpnTunnelTimingSettings()
        assertEquals(5_000L, tunnelRetryDelayMillis(settings, 3))
        assertEquals(3, packetFlowRetryFailureCount(3, 29_999L, false))
        assertEquals(0, packetFlowRetryFailureCount(3, 30_000L, false))
    }

    @Test
    fun flowLifetimeEndsBeforeTheMainThreadCallbackRuns() {
        val service = source("main/java/com/bringyour/network/MainService.kt")
        val callback = service.substringAfter("{ endedPacketFlow ->").substringBefore("} catch (e: Exception)")
        val endTime = callback.indexOf("val flowEndedAt = SystemClock.elapsedRealtime()")
        val post = callback.indexOf("mainHandler.post {")
        assertTrue(endTime >= 0 && post > endTime)
        assertTrue(callback.contains("flowEndedAt - flowStartedAt"))
        assertFalse(callback.contains("SystemClock.elapsedRealtime() - flowStartedAt"))
        assertEquals(4, packetFlowRetryFailureCount(4, 29_999L, false))
        assertEquals(0, packetFlowRetryFailureCount(4, 30_000L, false))
    }

    @Test
    fun paymentGuidanceIsConditionalInEveryLocale() {
        val markers = mapOf(
            "values" to "If payment fails", "values-ar" to "إذا فشلت", "values-cs" to "Pokud platba selže",
            "values-de" to "Falls die Zahlung fehlschlägt", "values-el" to "Αν η πληρωμή αποτύχει",
            "values-es" to "Si falla el pago", "values-es-rMX" to "Si falla el pago",
            "values-fr" to "Si le paiement échoue", "values-hi" to "अगर भुगतान विफल हो",
            "values-ja" to "支払いに失敗した場合", "values-ko" to "결제에 실패하면",
            "values-nl" to "Als de betaling mislukt", "values-pl" to "Jeśli płatność się nie powiedzie",
            "values-pt" to "Se o pagamento falhar", "values-pt-rBR" to "Se o pagamento falhar",
            "values-ru" to "Если оплата не прошла", "values-sw" to "Ikiwa malipo yatashindwa",
            "values-th" to "หากชำระเงินไม่สำเร็จ", "values-zh" to "如果付款失败",
        )
        for ((locale, marker) in markers) {
            val xml = source("main/res/$locale/strings.xml")
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(xml.byteInputStream())
            val strings = document.getElementsByTagName("string")
            val text = (0 until strings.length).map { strings.item(it) }
                .single { it.attributes.getNamedItem("name").nodeValue == "insufficient_balance_disconnect_first_kill_switch" }
                .textContent
            assertTrue(locale, text.contains(marker))
        }
        val actions = source("main/java/com/bringyour/network/ui/connect/ConnectActions.kt")
        assertTrue(actions.contains("stringResource(id = R.string.insufficient_balance_disconnect_first_kill_switch)"))
    }
}
