package com.bringyour.network.ui.stats

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demand chart's retry as wired into the app, read from the sources: the
 * SDK's controller needs the native library, which these JVM tests do not
 * have, and the screen needs a device. The controller polls about once a
 * minute on its own (`defaultProviderStatusPollInterval`), so unless the
 * screen's refresh and the chart's Try again force a poll, a failed one leaves
 * the chart and its "Why?" blank until the next interval, with nothing on
 * screen to bring them back.
 *
 * The behavior is ProviderStatusPresentationTest's.
 */
class ProviderStatusRefreshWiringTest {

    private fun stats(name: String): String = File("src/main/java/com/bringyour/network/ui/stats/$name").readText()

    private fun wallet(name: String): String = File("src/main/java/com/bringyour/network/ui/wallet/$name").readText()

    // the text between two markers, failing when either is missing
    private fun between(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("missing: $start", 0 <= from)
        val to = source.indexOf(end, from + start.length)
        assertTrue("missing after $start: $end", 0 <= to)
        return source.substring(from, to)
    }

    @Test
    fun `the view model polls now when asked`() {
        val viewModel = stats("ProviderStatusViewModel.kt")
        // the controller's own Refresh, not a second controller and not a wait
        val refresh = between(viewModel, "fun refresh() {", "}")
        assertTrue(refresh.contains("statusVc?.refresh()"))
    }

    @Test
    fun `the screen's refresh polls the provider status`() {
        val screen = wallet("EarningsScreen.kt")
        // the box the page's drag to refresh runs
        val onRefresh = between(screen, "PullToRefreshBox(", "modifier = Modifier")
        assertTrue(onRefresh.contains("providerStatusViewModel.refresh()"))
        assertTrue(onRefresh.contains("refresh()"))
        // the same view model drives the section, so the poll it forces is the
        // one the chart reads
        assertTrue(screen.contains("val providerStatusViewModel: ProviderStatusViewModel = hiltViewModel()"))
        assertTrue(screen.contains("providerStatusViewModel = providerStatusViewModel,"))
    }

    @Test
    fun `the chart's Try again polls the provider status`() {
        val section = stats("ProviderStatsSection.kt")
        // the chart is handed the view model's own poll
        assertTrue(
            section.contains(
                "ProviderDemandChart(\n" +
                    "                status = providerStatusViewModel.status,\n" +
                    "                onRetry = { providerStatusViewModel.refresh() },\n" +
                    "            )"
            )
        )
    }

    @Test
    fun `a failed first poll is retried after a few seconds`() {
        val viewModel = stats("ProviderStatusViewModel.kt")
        val retry = between(viewModel, "private fun scheduleFirstLoadRetry() {", "override fun onCleared")
        assertTrue(retry.contains("providerStatusRetryAfterFailure(status, firstLoadRetries)"))
        assertTrue(retry.contains("delay(PROVIDER_STATUS_RETRY_DELAY_MILLIS)"))
        assertTrue(retry.contains("statusVc?.refresh()"))
    }

    @Test
    fun `Try again shows only while asking again could help`() {
        val chart = stats("ProviderDemandChart.kt")
        val unavailable = between(chart, "private fun DemandUnavailable(", "private fun DrawScope.drawDemandBars")
        // the note always, the action only for a retryable state
        assertTrue(unavailable.contains("R.string.provider_status_unavailable"))
        assertTrue(unavailable.contains("if (retryable) {"))
        val action = between(unavailable, "if (retryable) {", "}")
        assertTrue(action.contains("R.string.try_again"))
        assertTrue(action.contains(".clickable(onClick = onRetry)"))
    }
}
