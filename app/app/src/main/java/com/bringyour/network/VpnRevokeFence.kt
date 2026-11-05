package com.bringyour.network

import android.content.Context
import android.util.Log

/**
 * Durable "the user turned the VPN off" bit (see [VpnReconnectEntry] and
 * [VpnRevokeFence]).
 *
 * Device-protected storage, like [DirectBootState], so a Direct Boot system
 * start sees it before unlock. Stored by [VpnRevokeFenceFile], which replaces
 * the file all-or-nothing and reports whether the new bit was read back, so it
 * is on disk before any restore that could be queued behind the revoke. Cleared
 * only by an explicit user connect.
 */
internal fun VpnRevokeFence(context: Context): VpnRevokeFence {
    val file = VpnRevokeFenceFile(context.createDeviceProtectedStorageContext().filesDir)
    return VpnRevokeFence(object : VpnRevokeFenceStorage {
        override fun read() = file.read()

        override fun write(record: VpnRevokeFenceRecord): Boolean {
            val landed = file.write(record)
            if (!landed) Log.e("VpnRevokeFence", "durable write of $record failed")
            return landed
        }
    })
}
