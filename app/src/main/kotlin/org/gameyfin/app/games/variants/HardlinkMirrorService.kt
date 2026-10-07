package org.gameyfin.app.games.variants

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gameyfin.app.games.entities.VariantLinkStatus
import org.gameyfin.app.libraries.entities.Library
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

@Service
class HardlinkMirrorService(
    @Value($$"${spring.content.fs.filesystem-root:./data/}") storageRoot: String
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val mirrorRoot: Path = Path.of(storageRoot).resolve("library-hardlinks").normalize()

    data class LinkResult(
        val path: Path,
        val status: VariantLinkStatus,
        val fallbackReason: String?
    )

    /** Pure path check used by metadata-only retirement decisions; it does not access the filesystem. */
    fun isManagedMirrorPath(path: Path): Boolean = path.toAbsolutePath().normalize().startsWith(mirrorRoot.toAbsolutePath())

    fun mirror(source: Path, library: Library, gamePath: Path, targetName: String): LinkResult {
        val target = mirrorTarget(library, gamePath, targetName)
        require(source.exists()) { "Hardlink source path does not exist: $source" }
        var targetTouched = false
        return try {
            val actualSource = source.toRealPath()
            val actualMirrorRoot = resolveThroughExistingParent(mirrorRoot)
            require(!actualSource.startsWith(actualMirrorRoot) && !actualMirrorRoot.startsWith(actualSource)) {
                "Hardlink mirror storage must be separate from source paths"
            }
            require(resolveThroughExistingParent(target).startsWith(actualMirrorRoot)) {
                "Hardlink target must remain within managed mirror storage"
            }
            mirrorRoot.createDirectories()
            require(Files.getFileStore(source) == Files.getFileStore(mirrorRoot)) {
                "Hardlink mirror requires source and mirror storage on the same filesystem"
            }
            targetTouched = true
            deleteTargetIfPresent(target)
            linkTree(source, target)
            LinkResult(target, VariantLinkStatus.HARDLINKED, null)
        } catch (e: Exception) {
            log.warn { "Hardlinking '$source' to '$target' failed: ${e.message}" }
            if (targetTouched) {
                runCatching { deleteTargetIfPresent(target) }
                    .onFailure { log.warn { "Could not clean incomplete mirror '$target': ${it.message}" } }
            }
            // Keep the library usable without copying or writing torrent-managed data.
            LinkResult(source, VariantLinkStatus.DIRECT,
                "Hardlink unavailable; using original source directly: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Resolve existing symbolic-link ancestors before creating any mirror directories. */
    private fun resolveThroughExistingParent(path: Path): Path {
        var existing = path.toAbsolutePath().normalize()
        val suffix = mutableListOf<Path>()
        while (!Files.exists(existing)) {
            suffix.add(existing.fileName)
            existing = existing.parent
        }
        return suffix.asReversed().fold(existing.toRealPath()) { result, segment -> result.resolve(segment) }
    }

    private fun mirrorTarget(library: Library, gamePath: Path, targetName: String): Path {
        val libraryId = library.id?.toString() ?: "new"
        return mirrorRoot
            .resolve("library-$libraryId")
            .resolve(safeName(gamePath.name))
            .resolve(safeName(targetName))
            .normalize()
    }

    private fun linkTree(source: Path, target: Path) {
        if (!source.isDirectory()) {
            target.parent.createDirectories()
            Files.createLink(target, source)
            return
        }

        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                target.resolve(source.relativize(dir)).createDirectories()
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val targetFile = target.resolve(source.relativize(file))
                targetFile.parent.createDirectories()
                Files.createLink(targetFile, file)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun deleteTargetIfPresent(target: Path) {
        val normalizedTarget = target.normalize()
        check(normalizedTarget.startsWith(mirrorRoot)) { "Refusing to delete path outside hardlink mirror root" }
        if (!normalizedTarget.exists()) return

        Files.walkFileTree(normalizedTarget, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun safeName(value: String): String {
        return value.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "unknown" }
    }
}
