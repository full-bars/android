package com.bringyour.network

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal const val VPN_REVOKE_FENCE_FILE = "vpn_revoke_fence"
private const val PREFIX = "vpn-revoke-fence-v2"

/**
 * The epoch of a fence file that exists but cannot be read: far above any
 * counter the fence has reached, so work admitted under an earlier epoch is
 * never mistaken for work admitted after this.
 */
internal const val VPN_REVOKE_FENCE_UNREADABLE_EPOCH = 1L shl 62

private fun encode(record: VpnRevokeFenceRecord) =
    "$PREFIX ${if (record.raised) "raised" else "cleared"} ${record.epoch}\n"

private fun decode(text: String): VpnRevokeFenceRecord? {
    val parts = text.removeSuffix("\n").split(' ')
    if (!text.endsWith("\n") || parts.size != 3 || parts[0] != PREFIX) return null
    val epoch = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
    return when (parts[1]) {
        "raised" -> VpnRevokeFenceRecord(true, epoch)
        "cleared" -> VpnRevokeFenceRecord(false, epoch)
        else -> null
    }
}

/**
 * The fence record (bit and epoch) as a one-line file, replaced all-or-nothing: the new content is
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

    override fun read(): VpnRevokeFenceRecord {
        val file = target
        if (!file.exists()) return VpnRevokeFenceRecord(raised = false, epoch = 0)
        // anything torn, foreign or unreadable: fail closed
        val unreadable = VpnRevokeFenceRecord(raised = true, epoch = VPN_REVOKE_FENCE_UNREADABLE_EPOCH)
        return try {
            decode(file.readText(Charsets.UTF_8)) ?: unreadable
        } catch (_: Exception) {
            unreadable
        }
    }

    /** True only when the new record was renamed into place and read back equal. */
    override fun write(record: VpnRevokeFenceRecord): Boolean {
        val content = encode(record)
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
