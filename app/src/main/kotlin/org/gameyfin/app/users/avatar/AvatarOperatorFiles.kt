package org.gameyfin.app.users.avatar

import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipal

/** Main-attested private control mount, NEVER a request-selected path or restored
 * application-data directory. POSIX secure-directory support is mandatory;
 * unsupported platforms fail closed rather than guessing Windows ACL policy.
 * Expected owner comes from trusted deployment inventory, not from the files.
 */
class AvatarOperatorFiles(private val root: Path, private val owner: UserPrincipal) {
    init { require(root.isAbsolute && root == root.normalize()) }

    fun configuration(): ByteArray = read("avatar-config.json", 262144)
    fun epoch(): ByteArray = read("avatar-epoch.json", 4096)

    private fun read(name: String, limit: Int): ByteArray {
        try {
            val initial = Files.readAttributes(root, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            privateAttributes(initial, true)
            Files.newDirectoryStream(root).use { opened ->
                val directory = opened as? SecureDirectoryStream<Path> ?: throw AvatarAuthority.Denied()
                val actual = directory.getFileAttributeView(PosixFileAttributeView::class.java).readAttributes()
                privateAttributes(actual, true)
                if (initial.fileKey() == null || initial.fileKey() != actual.fileKey()) throw AvatarAuthority.Denied()
                val relative = Path.of(name)
                val view = directory.getFileAttributeView(relative, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                val before = view.readAttributes()
                privateAttributes(before, false)
                if (before.size() !in 1..limit.toLong()) throw AvatarAuthority.Denied()
                val bytes = directory.newByteChannel(relative, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use {
                    Channels.newInputStream(it).use { input -> input.readNBytes(limit + 1) }
                }
                val after = view.readAttributes()
                privateAttributes(after, false)
                if (bytes.size > limit || bytes.size.toLong() != before.size() || before.fileKey() == null ||
                    before.fileKey() != after.fileKey() || before.size() != after.size() ||
                    before.lastModifiedTime() != after.lastModifiedTime()) throw AvatarAuthority.Denied()
                return bytes
            }
        } catch (_: Exception) { throw AvatarAuthority.Denied() } // No path/config/error-value disclosure.
    }

    private fun privateAttributes(attributes: PosixFileAttributes, directory: Boolean) {
        val allowed = mutableSetOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        if (directory) allowed += PosixFilePermission.OWNER_EXECUTE
        if (attributes.owner() != owner || attributes.permissions().any { it !in allowed } ||
            PosixFilePermission.OWNER_READ !in attributes.permissions() ||
            if (directory) !attributes.isDirectory || PosixFilePermission.OWNER_EXECUTE !in attributes.permissions()
            else !attributes.isRegularFile) throw AvatarAuthority.Denied()
    }
}
