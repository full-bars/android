package com.bringyour.network.location

import com.bringyour.network.DeviceManager
import com.bringyour.sdk.DeviceLocal
import com.bringyour.sdk.Sub
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feeds MockLocationController with tunnel lifecycle and exit provider location updates
 * from the SDK DeviceLocal instance across the entire application lifecycle.
 */
@Singleton
class MockLocationFeeder @Inject constructor(
    private val deviceManager: DeviceManager,
    private val controller: MockLocationController,
) {
    @Volatile
    private var removeDeviceChangeListener: (() -> Unit)? = null
    @Volatile
    private var connectSub: Sub? = null
    @Volatile
    private var tunnelSub: Sub? = null
    @Volatile
    private var providerSub: Sub? = null
    @Volatile
    private var currentDevice: DeviceLocal? = null

    @Synchronized
    fun start() {
        if (removeDeviceChangeListener != null) return
        removeDeviceChangeListener = deviceManager.addDeviceChangeListener { device ->
            attach(device)
        }
        attach(deviceManager.device)
    }

    @Synchronized
    fun stop() {
        removeDeviceChangeListener?.invoke()
        removeDeviceChangeListener = null
        attach(null)
    }

    @Synchronized
    private fun attach(device: DeviceLocal?) {
        connectSub?.close()
        connectSub = null
        tunnelSub?.close()
        tunnelSub = null
        providerSub?.close()
        providerSub = null
        currentDevice = device

        if (device != null) {
            val updateClientTunnelState = {
                // Location mocking requires an active client connection to an exit provider.
                // In Provide Mode (e.g. Provide mode = Always), tunnelStarted is true for server
                // packet routing while connectEnabled is false. We must only mock while both
                // connectEnabled (client mode) and tunnelStarted (VPN active) are true.
                val isClientConnected = device.connectEnabled && device.tunnelStarted
                controller.onTunnelChanged(isClientConnected)
                if (isClientConnected) {
                    pushTarget(device)
                } else {
                    controller.onTargetChanged(null)
                }
            }

            connectSub = device.addConnectChangeListener {
                synchronized(this@MockLocationFeeder) {
                    if (currentDevice !== device) return@addConnectChangeListener
                    updateClientTunnelState()
                }
            }
            tunnelSub = device.addTunnelChangeListener {
                synchronized(this@MockLocationFeeder) {
                    if (currentDevice !== device) return@addTunnelChangeListener
                    updateClientTunnelState()
                }
            }
            providerSub = device.addConnectedProviderLocationChangeListener {
                synchronized(this@MockLocationFeeder) {
                    if (currentDevice !== device) return@addConnectedProviderLocationChangeListener
                    if (device.connectEnabled && device.tunnelStarted) {
                        pushTarget(device)
                    }
                }
            }

            updateClientTunnelState()
        } else {
            controller.onTunnelChanged(false)
            controller.onTargetChanged(null)
        }
    }

    private fun pushTarget(device: DeviceLocal) {
        val locations = device.connectedProviderLocations
        var target: MockLocationTarget? = null
        if (locations != null) {
            for (i in 0 until locations.len()) {
                val location = locations.get(i)
                val lat: Double
                val lon: Double
                when {
                    location.hasCityCoordinates -> {
                        lat = location.cityLat
                        lon = location.cityLon
                    }
                    location.hasRegionCoordinates -> {
                        lat = location.regionLat
                        lon = location.regionLon
                    }
                    else -> continue
                }
                val label = listOf(location.city, location.region, location.country)
                    .filter { it.isNotEmpty() }
                    .take(2)
                    .joinToString(", ")
                target = MockLocationTarget(
                    clientId = location.clientId?.idStr ?: "",
                    label = label,
                    lat = lat,
                    lon = lon,
                )
                break
            }
        }
        controller.onTargetChanged(target)
    }
}
