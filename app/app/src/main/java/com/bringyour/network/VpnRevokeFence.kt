package com.bringyour.network

import android.content.Context
import android.util.Log

internal const val VPN_REVOKE_FENCE_PREFERENCES = "vpn_revoke_fence"
internal const val VPN_REVOKE_FENCE_RAISED = "raised"

/**
 * Durable "the user turned the VPN off" bit (see [VpnReconnectEntry] and
 * [VpnRevokeFence]).
 *
 * Device-protected storage, like [DirectBootState], so a Direct Boot system
 * start sees it before unlock; committed synchronously (SharedPreferences
 * commit() writes a backup and renames, and reports the result) so it is on
 * disk before any restore that could be queued behind the revoke. Cleared only
 * by an explicit user connect.
 */
internal fun VpnRevokeFence(context: Context): VpnRevokeFence {
    val preferences = context
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(VPN_REVOKE_FENCE_PREFERENCES, Context.MODE_PRIVATE)
    return VpnRevokeFence(object : VpnRevokeFenceStorage {
        override fun read() = preferences.getBoolean(VPN_REVOKE_FENCE_RAISED, false)

        override fun write(raised: Boolean): Boolean {
            val committed = preferences.edit().apply {
                if (raised) putBoolean(VPN_REVOKE_FENCE_RAISED, true) else remove(VPN_REVOKE_FENCE_RAISED)
            }.commit()
            if (!committed) Log.w("VpnRevokeFence", "durable write of raised=$raised failed")
            return committed
        }
    })
}
