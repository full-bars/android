package com.bringyour.network

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal const val VPN_REVOKE_FENCE_FILE = "vpn_revoke_fence"
private const val RAISED_LINE = "vpn-revoke-fence-v1 raised\n"
private const val CLEARED_LINE = "vpn-revoke-fence-v1 cleared\n"

/**
 * The fence bit as a one-line file, replaced all-or-nothing: the new content is
 * written to a temp file in the same directory, fsynced, renamed over the
 * target (atomic on the same filesystem) and read back. A reader therefore sees
 * the whole old content or the whole new content, never a torn mix.
 *
 * Reading fails closed: a file that exists but is unreadable, empty or not
 * exactly one of the two known lines is a raised fence. Only a missing file
 * (nothing was ever written) is a cleared one.
 *
 * `moveIntoPlace` is the rename, a parameter so a test can fail it, or fail it
 * after it landed (the ambiguous case [VpnRevokeFence] has to survive).
 */
internal class VpnRevokeFenceFile(
    private val dir: File,
    private val name: String = VPN_REVOKE_FENCE_FILE,
    private val moveIntoPlace: (temp: File, target: File) -> Unit = ::atomicMove,
) : VpnRevokeFenceStorage {
    private val target get() = File(dir, name)
    private val temp get() = File(dir, "$name.tmp")

    override fun read(): Boolean {
        val file = target
        if (!file.exists()) return false
        return try {
            when (file.readText(Charsets.UTF_8)) {
                CLEARED_LINE -> false
                else -> true // RAISED_LINE, or anything torn or foreign: fail closed
            }
        } catch (_: Exception) {
            true
        }
    }

    /** True only when the new bit was renamed into place and read back equal. */
    override fun write(raised: Boolean): Boolean {
        val content = if (raised) RAISED_LINE else CLEARED_LINE
        return try {
            temp.delete() // a leftover from a crash mid-write is never promoted
            FileOutputStream(temp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            moveIntoPlace(temp, target)
            target.readText(Charsets.UTF_8) == content
        } catch (_: Exception) {
            false
        } finally {
            temp.delete()
        }
    }
}

private fun atomicMove(temp: File, target: File) {
    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
}
