package org.gameyfin.app.users.avatar

import org.gameyfin.app.media.FileStorageService
import org.gameyfin.app.media.ImageType
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.sql.Connection
import java.util.HexFormat
import java.util.UUID

/** Existing native avatar bytes only; no URL fetch, decode/re-encode or deletion.
 * Called under the native user/state transaction by the trusted adapter, never
 * with a request-chosen path. Unsupported large/missing/nonregular legacy files
 * remain untouched; an ordinary native upload can replace them separately.
 */
class AvatarLegacyReader(private val storage: FileStorageService) {
    fun hash(connection: Connection, imageId: Long): String {
        check(!connection.autoCommit)
        val contentId = connection.prepareStatement("SELECT type,content_id FROM image WHERE id=? FOR UPDATE").use { sql ->
            sql.setLong(1, imageId)
            sql.executeQuery().use { rows ->
                if (!rows.next() || rows.getInt(1) != ImageType.AVATAR.ordinal || rows.wasNull())
                    throw AvatarLedger.UnsupportedLegacy()
                rows.getString(2) ?: throw AvatarLedger.UnsupportedLegacy()
            }
        }
        // Native storage IDs are canonical UUID filenames, not URLs or arbitrary paths.
        if (runCatching { UUID.fromString(contentId).toString() }.getOrNull() != contentId)
            throw AvatarLedger.UnsupportedLegacy()
        val path = storage.getFilePath(contentId) ?: throw AvatarLedger.UnsupportedLegacy()
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!before.isRegularFile || before.size() !in 1..CanonicalAvatarPng.MAX_BYTES.toLong())
            throw AvatarLedger.UnsupportedLegacy()
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), CanonicalAvatarPng.MAX_BYTES + 1L - count).toInt())
                if (read < 0) break
                count += read
                if (count > CanonicalAvatarPng.MAX_BYTES) throw AvatarLedger.UnsupportedLegacy()
                digest.update(buffer, 0, read)
            }
        }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!after.isRegularFile || count != before.size() || before.size() != after.size() ||
            before.fileKey() != after.fileKey() || before.lastModifiedTime() != after.lastModifiedTime())
            throw AvatarLedger.UnsupportedLegacy()
        return HexFormat.of().formatHex(digest.digest())
    }
}
