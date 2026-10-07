package org.gameyfin.app.core.download.files

import org.gameyfin.pluginapi.download.DownloadSelection
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.channels.Channels
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/** Copies authorized selections into an owned cache; never moves or hardlinks sources.
 * No Spring/provider wiring: callers must explicitly configure budgets and lifecycle.
 */
class SelectionSnapshotBuilder(private val cacheRoot: Path, private val maxBytes: Long,
                               private val maxEntries: Int, private val timeout: Duration) {
    data class Snapshot(val directory: Path, val hashes: Map<String, String>, val bytes: Long)

    /** Anchor every ancestor via no-follow directory descriptors; refuse unsafe platforms. */
    private fun <T> withSecureFile(path: Path, operation: (SecureDirectoryStream<Path>, Path) -> T): T {
        val absolute = path.toAbsolutePath().normalize()
        fun descend(directory: SecureDirectoryStream<Path>, index: Int): T {
            if (index == absolute.nameCount - 1) return operation(directory, absolute.getName(index))
            directory.newDirectoryStream(absolute.getName(index), LinkOption.NOFOLLOW_LINKS).use {
                return descend(it, index + 1)
            }
        }
        Files.newDirectoryStream(absolute.root).use { stream ->
            require(stream is SecureDirectoryStream<Path>) { "Secure source-directory access is unavailable on this platform" }
            return descend(stream, 0)
        }
    }

    fun build(selection: DownloadSelection): Snapshot {
        require(maxBytes > 0 && maxEntries > 0 && !timeout.isNegative && !timeout.isZero)
        require(!Files.isSymbolicLink(cacheRoot) && Files.isDirectory(cacheRoot, LinkOption.NOFOLLOW_LINKS))
        val root = cacheRoot.toRealPath()
        val started = System.nanoTime()
        fun checkTime() {
            check(!Thread.currentThread().isInterrupted) { "Snapshot cancelled" }
            check(System.nanoTime() - started < timeout.toNanos()) { "Snapshot timed out" }
        }
        val plan = linkedMapOf<Path, Path>()
        val sourceIdentities = mutableMapOf<Path, Any>()
        var visited = 0
        fun safeName(name: String): String {
            require(name.isNotBlank() && name !in setOf(".", "..") && name.none { it in "/\\:*?\"<>|" || it.code < 32 }) {
                "Selection display name is not a safe relative name"
            }
            return name
        }
        selection.contents.forEach { content ->
            val logicalRoot = Path.of(safeName(content.name))
            content.paths.forEach { source ->
                checkTime()
                require(!Files.isSymbolicLink(source)) { "Snapshot sources cannot be symbolic links" }
                val canonical = source.toRealPath()
                require(source.isAbsolute && source.normalize() == canonical) {
                    "Snapshot sources must retain their authorized canonical path"
                }
                require(!canonical.startsWith(root) && !root.startsWith(canonical)) { "Snapshot cache overlaps source" }
                val prefix = if (content.paths.size > 1) logicalRoot.resolve(safeName(canonical.fileName.toString())) else logicalRoot
                fun visit(directory: SecureDirectoryStream<Path>, name: Path, path: Path) {
                    checkTime()
                    safeName(name.toString())
                    require(++visited <= maxEntries) { "Snapshot entry limit exceeded" }
                    val attrs = directory.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes()
                    require(!attrs.isSymbolicLink) { "Snapshot entries cannot be symbolic links" }
                    if (attrs.isDirectory) {
                        directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { children ->
                            require(attrs.fileKey() != null && attrs.fileKey() == children.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes().fileKey()) {
                                "Selected directory identity changed"
                            }
                            children.forEach { entry -> visit(children, entry.fileName, path.resolve(entry.fileName)) }
                        }
                    } else {
                        require(attrs.isRegularFile && attrs.fileKey() != null) { "Snapshot entries must have regular file identities" }
                        val relative = if (canonical == path) prefix else prefix.resolve(canonical.relativize(path))
                        require(plan.putIfAbsent(relative, path) == null) { "Selected snapshot names collide" }
                        sourceIdentities[path] = attrs.fileKey()
                    }
                }
                withSecureFile(canonical) { directory, name -> visit(directory, name, canonical) }
            }
        }
        require(plan.isNotEmpty()) { "Selection contains no files" }
        var anticipated = 0L
        plan.values.forEach {
            val size = Files.size(it)
            require(size <= maxBytes - anticipated) { "Snapshot byte limit exceeded" }
            anticipated += size
        }
        val token = UUID.randomUUID().toString()
        val staging = Files.createDirectory(root.resolve(".building-$token"))
        val destination = root.resolve("snapshot-$token")
        try {
            val hashes = linkedMapOf<String, String>()
            var total = 0L
            plan.forEach { (relative, source) ->
                checkTime()
                val target = staging.resolve(relative).normalize()
                require(target.startsWith(staging))
                Files.createDirectories(target.parent)
                val digest = MessageDigest.getInstance("SHA-256")
                var copiedHash = ""
                withSecureFile(source) { directory, file ->
                    val attributes = directory.getFileAttributeView(file, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                    val before = attributes.readAttributes()
                    require(before.isRegularFile && !before.isSymbolicLink)
                    require(before.fileKey() == sourceIdentities[source]) { "Selected source identity changed" }
                    directory.newByteChannel(file, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                    val input = Channels.newInputStream(channel)
                    var copiedBytes = 0L
                    Files.newOutputStream(target, StandardOpenOption.CREATE_NEW).use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            checkTime()
                            val size = input.read(buffer)
                            if (size < 0) break
                            require(size.toLong() <= maxBytes - total) { "Snapshot byte limit exceeded" }
                            output.write(buffer, 0, size); digest.update(buffer, 0, size); total += size; copiedBytes += size
                        }
                    }
                    copiedHash = digest.digest().joinToString("") { "%02x".format(it) }
                    val verification = MessageDigest.getInstance("SHA-256")
                    channel.position(0)
                    val buffer = ByteArray(65536)
                    var verifiedBytes = 0L
                    while (true) { checkTime(); val size = input.read(buffer); if (size < 0) break; verifiedBytes += size
                        require(verifiedBytes <= copiedBytes) { "Source grew during snapshot" }; verification.update(buffer, 0, size) }
                    require(copiedBytes == before.size() && verifiedBytes == copiedBytes && copiedHash == verification.digest().joinToString("") { "%02x".format(it) }) { "Source bytes changed during snapshot" }
                    val after = attributes.readAttributes()
                    require(before.fileKey() == after.fileKey() && before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime()) { "Source changed during snapshot" }
                    require(source.toRealPath() == source.normalize() && Files.readAttributes(source, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey() == after.fileKey()) { "Source path changed during snapshot" }
                    }
                }
                hashes[relative.toString().replace('\\', '/')] = copiedHash
            }
            checkTime()
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
            return Snapshot(destination, java.util.Collections.unmodifiableMap(LinkedHashMap(hashes)), total)
        } catch (failure: Exception) {
            // Only the exact newly-created staging leaf is owned by this operation.
            Files.walkFileTree(staging, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
                override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult { if (error != null) throw error; Files.delete(dir); return FileVisitResult.CONTINUE }
            })
            throw failure
        }
    }
}
