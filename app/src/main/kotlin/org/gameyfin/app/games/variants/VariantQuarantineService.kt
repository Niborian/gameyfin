package org.gameyfin.app.games.variants

import org.gameyfin.app.core.download.files.DownloadPathLeases
import org.gameyfin.app.core.security.getCurrentAuth
import org.gameyfin.app.games.dto.*
import org.gameyfin.app.games.entities.*
import org.gameyfin.app.games.repositories.*
import org.gameyfin.app.libraries.entities.LibraryStorageMode
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.Properties
import java.io.ByteArrayOutputStream

/** Reversible rename of one archived application mirror; contains no deletion operation. */
@Service
class VariantQuarantineService(
    private val games: GameRepository,
    private val variants: GameVariantRepository,
    private val records: VariantQuarantineRepository,
    private val leases: DownloadPathLeases,
    @Value($$"${spring.content.fs.filesystem-root:./data/}") storageRoot: String
) {
    private val storage = Path.of(storageRoot).toAbsolutePath().normalize()
    private val mirrors = storage.resolve("library-hardlinks")
    private val quarantine = storage.resolve("variant-quarantine")

    @Transactional
    fun quarantine(gameId: Long, variantId: Long, request: QuarantineVariantRequestDto): VariantQuarantineDto {
        require(request.confirmation == "QUARANTINE $variantId") { "Explicit variant confirmation required" }
        require(request.recoveryDays in 1..3650 && request.reason.isNotBlank() && request.reason.length <= 255) { "Valid reason and recovery period required" }
        val variant = findVariant(gameId, variantId)
        require(variant.retirementState == VariantRetirementState.ARCHIVED && variant.quarantinePath == null) { "Variant must be archived and not quarantined" }
        require(!variant.isDefault && !variant.defaultLocked && !variant.isLatestForVariant) { "Default, pinned, and latest variants are protected" }
        require(variant.supersededAt != null && variant.game.variants.any {
            it.id == variant.supersededByVariantId && it.retirementState == VariantRetirementState.ACTIVE &&
                it.name == variant.name && VariantVersionComparator.compare(it.version, variant.version) > 0
        }) { "Current active replacement evidence required" }
        require(variant.linkStatus == VariantLinkStatus.HARDLINKED && variant.game.library.storageMode == LibraryStorageMode.HARDLINK_MIRROR) { "Only application-managed hardlink mirrors may be quarantined" }
        val original = Path.of(variant.path).toAbsolutePath().normalize()
        val libraryRoot = mirrors.resolve("library-${requireNotNull(variant.game.library.id)}")
        require(original.startsWith(libraryRoot) && original.nameCount >= libraryRoot.nameCount + 2) { "Path is not a variant mirror" }
        require(variant.contents.flatMap { it.effectivePaths() }.all { Path.of(it).toAbsolutePath().normalize().startsWith(original) }) { "Content outside mirror prevents quarantine" }
        return leases.whenUnused(original) {
            validateExistingPath(original)
            val identities = inventory(original, 10_000)
            require(identities.isNotEmpty()) { "Empty or unverifiable mirror cannot be quarantined" }
            rejectCatalogDependencies(variantId, identities, original)
            validateExistingPath(storage)
            Files.createDirectories(quarantine)
            validateExistingPath(quarantine)
            val journalRoot = quarantine.resolve(UUID.randomUUID().toString())
            Files.createDirectory(journalRoot)
            val target = journalRoot.resolve("payload")
            require(Files.getFileStore(original) == Files.getFileStore(journalRoot)) { "Quarantine requires atomic same-filesystem rename" }
            val now = Instant.now()
            val record = VariantQuarantineRecord(variant = variant, originalPath = original.toString(), quarantinePath = target.toString(),
                quarantinedAt = now, recoverableUntil = now.plus(request.recoveryDays.toLong(), ChronoUnit.DAYS), actor = actor(), reason = request.reason.trim())
            writeJournal(journalRoot, variantId, record)
            Files.move(original, target, StandardCopyOption.ATOMIC_MOVE)
            compensateOnRollback(target, original)
            try {
                variant.quarantinePath = target.toString()
                games.save(variant.game)
                records.saveAndFlush(record).toDto()
            } catch (error: Exception) {
                variant.quarantinePath = null
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.exists(original, LinkOption.NOFOLLOW_LINKS)) Files.move(target, original, StandardCopyOption.ATOMIC_MOVE)
                throw error
            }
        }
    }

    @Transactional
    fun restore(gameId: Long, variantId: Long, recordId: Long, confirmation: String): VariantQuarantineDto {
        require(confirmation == "RESTORE $variantId") { "Explicit restore confirmation required" }
        val variant = findVariant(gameId, variantId)
        val record = records.findByIdOrNull(recordId) ?: throw IllegalArgumentException("Quarantine record not found")
        require(record.variant.id == variantId && record.restoredAt == null && variant.quarantinePath == record.quarantinePath) { "Record is not this variant's active quarantine" }
        val original = Path.of(record.originalPath).toAbsolutePath().normalize()
        val target = Path.of(record.quarantinePath).toAbsolutePath().normalize()
        require(original == Path.of(variant.path).toAbsolutePath().normalize() && original.startsWith(mirrors) &&
            target.startsWith(quarantine) && target.nameCount == quarantine.nameCount + 2 && target.fileName.toString() == "payload") { "Invalid restoration paths" }
        return leases.whenUnused(original) {
            validateExistingPath(target)
            inventory(target, 10_000)
            validateExistingPath(original.parent)
            require(!Files.exists(original, LinkOption.NOFOLLOW_LINKS)) { "Restoration refuses to overwrite existing content" }
            Files.move(target, original, StandardCopyOption.ATOMIC_MOVE)
            compensateOnRollback(original, target)
            try {
                variant.quarantinePath = null
                record.restoredAt = Instant.now()
                record.restoredBy = actor()
                games.save(variant.game)
                records.saveAndFlush(record).toDto()
            } catch (error: Exception) {
                variant.quarantinePath = target.toString()
                record.restoredAt = null
                record.restoredBy = null
                if (Files.exists(original, LinkOption.NOFOLLOW_LINKS) && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.move(original, target, StandardCopyOption.ATOMIC_MOVE)
                throw error
            }
        }
    }

    @Transactional(readOnly = true)
    fun history(gameId: Long, variantId: Long): List<VariantQuarantineDto> {
        findVariant(gameId, variantId)
        return records.findAllByVariantIdOrderByQuarantinedAtAsc(variantId).map { it.toDto() }
    }

    private data class FileIdentity(val path: Path, val key: Any?)

    private fun rejectCatalogDependencies(variantId: Long, identities: List<FileIdentity>, original: Path) {
        val keyedIdentities = identities.mapNotNull { it.key }.toHashSet()
        val unkeyedIdentities = identities.filter { it.key == null }
        var pageNumber = 0
        var inspected = 0
        var comparisons = 0
        do {
            val page = variants.findAll(PageRequest.of(pageNumber++, 100))
            page.content.filter { it.id != variantId }.forEach { other ->
                val paths = (listOf(other.quarantinePath ?: other.path) +
                    if (other.quarantinePath == null) other.contents.flatMap { it.effectivePaths() } else emptyList()).distinct()
                paths.forEach { value ->
                    val path = Path.of(value).toAbsolutePath().normalize()
                    require(!path.startsWith(original) && !original.startsWith(path)) { "Catalog path depends on this mirror" }
                    validateExistingPath(path)
                    walkRegularFiles(path, { require(++inspected <= 100_000) { "Catalog inventory exceeds safe inspection budget" } }) { file, attrs ->
                        val key = attrs.fileKey()
                        val candidates = if (key == null) identities else unkeyedIdentities
                        val shared = key != null && key in keyedIdentities || candidates.any { candidate ->
                            require(++comparisons <= 1_000_000) { "File identity comparison exceeds safe inspection budget" }
                            Files.isSameFile(candidate.path, file)
                        }
                        require(!shared) { "Another catalog variant references the same physical file" }
                    }
                }
            }
        } while (page.hasNext())
    }

    private fun inventory(root: Path, limit: Int): List<FileIdentity> {
        val identities = mutableListOf<FileIdentity>()
        var count = 0
        walkRegularFiles(root, { require(++count <= limit) { "Mirror inventory exceeds safe inspection budget" } }) { file, attrs ->
            identities.add(FileIdentity(file, attrs.fileKey()))
        }
        return identities
    }

    private fun walkRegularFiles(root: Path, onNode: () -> Unit, visit: (Path, BasicFileAttributes) -> Unit) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                onNode()
                require(!attrs.isSymbolicLink && dir.toRealPath() == dir.toAbsolutePath().normalize()) { "Symlink or redirected directory is not permitted" }
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                onNode()
                require(attrs.isRegularFile && !attrs.isSymbolicLink) { "Only regular files may be quarantined" }
                visit(file, attrs)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun validateExistingPath(path: Path) {
        var current: Path? = path
        while (current != null) {
            require(!Files.isSymbolicLink(current)) { "Symlink paths are not permitted" }
            current = current.parent
        }
        require(path.toRealPath() == path.toAbsolutePath().normalize()) { "Redirected paths are not permitted" }
    }

    private fun writeJournal(root: Path, variantId: Long, record: VariantQuarantineRecord) {
        val journal = Properties().apply {
            setProperty("variantId", variantId.toString()); setProperty("originalPath", record.originalPath)
            setProperty("quarantinePath", record.quarantinePath); setProperty("quarantinedAt", record.quarantinedAt.toString())
            setProperty("recoverableUntil", record.recoverableUntil.toString()); setProperty("actor", record.actor); setProperty("reason", record.reason)
        }
        val bytes = ByteArrayOutputStream().also { journal.store(it, "Recoverable variant quarantine; never delete payload automatically") }.toByteArray()
        FileChannel.open(root.resolve("recovery.properties"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun compensateOnRollback(from: Path, to: Path) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) leases.whenUnused(to) {
                    if (Files.exists(from, LinkOption.NOFOLLOW_LINKS) && !Files.exists(to, LinkOption.NOFOLLOW_LINKS)) Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
                }
            }
        })
    }

    private fun findVariant(gameId: Long, variantId: Long): GameVariant =
        games.findByIdOrNull(gameId)?.variants?.firstOrNull { it.id == variantId }
            ?: throw IllegalArgumentException("Variant does not belong to game")
    private fun actor() = getCurrentAuth()?.name?.takeIf { it.isNotBlank() } ?: "system"
    private fun VariantQuarantineRecord.toDto() = VariantQuarantineDto(requireNotNull(id), requireNotNull(variant.id), originalPath,
        quarantinePath, quarantinedAt, recoverableUntil, actor, reason, restoredAt, restoredBy)
}
