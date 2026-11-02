package com.bringyour.network.ui.stats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bringyour.network.DeviceManager
import com.bringyour.network.VisibleDeviceControllerOwner
import com.bringyour.sdk.DeviceLocal
import com.bringyour.sdk.ProviderStatusViewController
import com.bringyour.sdk.Sub
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Publishes this device's provider status from the sdk's
 * `ProviderStatusViewController`: how often the network offered the device to
 * clients per minute over the last hour, the server's reason, and the numbers
 * it ranks the device by.
 *
 * The controller polls about once a minute. It is open only while providing
 * is enabled and the app is in the foreground, and it polls only while the
 * provider card's screen is visible (see `VisibleDeviceControllerOwner`).
 */
@HiltViewModel
class ProviderStatusViewModel @Inject constructor(
    private val deviceManager: DeviceManager,
) : ViewModel(), DefaultLifecycleObserver {

    private val subs = mutableListOf<Sub>()
    private var statusVc: ProviderStatusViewController? = null
    private var removeDeviceChangeListener: (() -> Unit)? = null
    private val processLifecycle = ProcessLifecycleOwner.get().lifecycle
    private val controllerOwner =
        VisibleDeviceControllerOwner<DeviceLocal, ProviderStatusViewController>(
            open = { openProviderStatus(it) },
            close = { device, vc -> closeProviderStatus(device, vc) },
            start = { it.start() },
            stop = { it.stop() },
        )

    var status by mutableStateOf(ProviderStatusUi.Empty)
        private set

    // the fast retries of a poll that failed before anything loaded
    private var firstLoadRetries = 0
    private var retryJob: Job? = null

    init {
        processLifecycle.addObserver(this)
        controllerOwner.setForeground(
            processLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        )
        // a replaced device closes the former device's controller, which
        // clears the status
        removeDeviceChangeListener = deviceManager.addDeviceChangeListener { device ->
            viewModelScope.launch {
                controllerOwner.setDevice(device)
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        controllerOwner.setForeground(true)
    }

    override fun onStop(owner: LifecycleOwner) {
        controllerOwner.setForeground(false)
    }

    /**
     * The same gate as the provider plots: never open while providing is
     * disabled.
     */
    fun setProvidingEnabled(enabled: Boolean) {
        controllerOwner.setEnabled(enabled)
    }

    /**
     * Whether the provider card's screen is visible: polls while it is, and
     * keeps the last snapshot while it is not.
     */
    fun setVisible(visible: Boolean) {
        controllerOwner.setVisible(visible)
    }

    /**
     * Polls now, instead of at the controller's next interval. This is what
     * pull to refresh and the chart's Try again do, because the controller
     * polls about once a minute: a failed poll, or a poll the server answered
     * from a cold cache, otherwise leaves the demand chart and the "Why?"
     * blank until the next interval.
     */
    fun refresh() {
        statusVc?.refresh()
    }

    /** Opens the device's controller and follows its polls. */
    private fun openProviderStatus(device: DeviceLocal): ProviderStatusViewController {
        val vc = device.openProviderStatusViewController()
        statusVc = vc
        // fires after every poll, success or failure, on an sdk thread
        subs.add(vc.addProviderStatusListener {
            viewModelScope.launch {
                update()
            }
        })
        update()
        return vc
    }

    /** Stops following [vc], stops and closes it, and clears its status. */
    private fun closeProviderStatus(device: DeviceLocal, vc: ProviderStatusViewController) {
        subs.forEach { it.close() }
        subs.clear()
        retryJob?.cancel()
        retryJob = null
        firstLoadRetries = 0
        vc.stop()
        device.closeProviderStatusViewController(vc)
        if (statusVc === vc) {
            statusVc = null
            status = ProviderStatusUi.Empty
        }
    }

    /** Reads the open controller's state into [status]. */
    private fun update() {
        val vc = statusVc ?: return
        status = ProviderStatusUi.fromSdk(vc)
        scheduleFirstLoadRetry()
    }

    /**
     * The controller retries a failed poll at its next interval, about a
     * minute away, so one transient failure at screen open would leave the
     * demand chart and its "Why?" blank that long with nothing on screen to
     * heal them. Ask again after a few seconds, a bounded number of times; a
     * poll after a load keeps its snapshot, so it waits for the controller.
     */
    private fun scheduleFirstLoadRetry() {
        if (status.lastFetchError.isEmpty()) {
            firstLoadRetries = 0
        }
        if (!providerStatusRetryAfterFailure(status, firstLoadRetries)) {
            return
        }
        if (retryJob?.isActive == true) {
            return
        }
        firstLoadRetries += 1
        retryJob = viewModelScope.launch {
            delay(PROVIDER_STATUS_RETRY_DELAY_MILLIS)
            statusVc?.refresh()
        }
    }

    override fun onCleared() {
        removeDeviceChangeListener?.invoke()
        removeDeviceChangeListener = null
        processLifecycle.removeObserver(this)
        controllerOwner.close()
        super.onCleared()
    }
}
