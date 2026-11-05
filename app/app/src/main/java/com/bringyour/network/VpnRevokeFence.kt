package com.bringyour.network

import android.content.Context
import androidx.core.content.edit

internal const val VPN_REVOKE_FENCE_PREFERENCES = "vpn_revoke_fence"
internal const val VPN_REVOKE_FENCE_RAISED = "raised"

/**
 * Durable "the user turned the VPN off" bit (see [VpnReconnectEntry]).
 *
 * Device-protected storage, like [DirectBootState], so a Direct Boot system
 * start sees it before unlock; committed synchronously so it is on disk
 * before any restore that could be queued behind the revoke. Raised from any
 * thread; cleared only by an explicit user connect.
 */
internal class VpnRevokeFence(context: Context) {
    private val preferences = context
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(VPN_REVOKE_FENCE_PREFERENCES, Context.MODE_PRIVATE)

    @Volatile
    private var cached: Boolean = preferences.getBoolean(VPN_REVOKE_FENCE_RAISED, false)

    val raised: Boolean get() = cached

    fun raise() {
        if (cached) return
        cached = true
        preferences.edit(commit = true) { putBoolean(VPN_REVOKE_FENCE_RAISED, true) }
    }

    fun clear() {
        if (!cached) return
        cached = false
        preferences.edit(commit = true) { remove(VPN_REVOKE_FENCE_RAISED) }
    }
}
