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
import java.nio.file.StandardCopyOption
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

    fun mirror(source: Path, library: Library, gamePath: Path, targetName: String): LinkResult {
        val target = mirrorTarget(library, gamePath, targetName)
        deleteTargetIfPresent(target)

        return try {
            linkTree(source, target)
            LinkResult(target, VariantLinkStatus.HARDLINKED, null)
        } catch (e: Exception) {
            log.warn { "Hardlinking '$source' to '$target' failed: ${e.message}; copying instead" }
            deleteTargetIfPresent(target)
            copyTree(source, target)
            LinkResult(
                path = target,
                status = VariantLinkStatus.COPIED_FALLBACK,
                fallbackReason = e.message ?: e.javaClass.simpleName
            )
        }
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
        if (!source.exists()) throw IOException("Source path does not exist")
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

    private fun copyTree(source: Path, target: Path) {
        if (!source.exists()) throw IOException("Source path does not exist")
        if (!source.isDirectory()) {
            target.parent.createDirectories()
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
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
                Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING)
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
