package org.gameyfin.app.games.variants

import io.mockk.*
import org.gameyfin.app.core.download.files.DownloadPathLeases
import org.gameyfin.app.games.dto.QuarantineVariantRequestDto
import org.gameyfin.app.games.entities.*
import org.gameyfin.app.games.repositories.*
import org.gameyfin.app.libraries.entities.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.springframework.data.domain.PageImpl
import org.springframework.data.repository.findByIdOrNull
import java.nio.file.*
import java.time.Instant
import kotlin.test.*

class VariantQuarantineServiceTest {
    @TempDir lateinit var root: Path
    private lateinit var source: Path
    private lateinit var mirror: Path
    private lateinit var old: GameVariant
    private lateinit var game: Game
    private lateinit var service: VariantQuarantineService
    private lateinit var games: GameRepository
    private lateinit var records: VariantQuarantineRepository
    private lateinit var variants: GameVariantRepository
    private lateinit var leases: DownloadPathLeases

    @BeforeEach
    fun setup() {
        source = root.resolve("torrent.bin")
        Files.writeString(source, "source content")
        mirror = root.resolve("library-hardlinks/library-1/Test/Normal-1.0")
        Files.createDirectories(mirror)
        Files.createLink(mirror.resolve("base.bin"), source)
        games = mockk<GameRepository>()
        variants = mockk()
        records = mockk()
        leases = DownloadPathLeases()
        val library = Library(id = 1L, name = "Games", storageMode = LibraryStorageMode.HARDLINK_MIRROR)
        game = Game(id = 1L, library = library, metadata = GameMetadata(path = root.resolve("torrent.bin").toString()))
        old = GameVariant(id = 10L, game = game, path = mirror.toString(), name = "Normal", version = "1.0",
            linkStatus = VariantLinkStatus.HARDLINKED, retirementState = VariantRetirementState.ARCHIVED,
            supersededAt = Instant.now(), supersededByVariantId = 11L)
        old.contents.add(VariantContent(id = 1L, variant = old, name = "Base", path = mirror.resolve("base.bin").toString(), required = true))
        val newPath = root.resolve("new.bin")
        Files.writeString(newPath, "new version")
        val newer = GameVariant(id = 11L, game = game, name = "Normal", version = "2.0", path = newPath.toString(), isLatestForVariant = true)
        game.variants.addAll(listOf(old, newer))
        every { games.findByIdOrNull(1L) } returns game
        every { games.save(game) } returns game
        every { variants.findAll(any<org.springframework.data.domain.Pageable>()) } returns PageImpl(game.variants)
        every { records.saveAndFlush(any()) } answers { firstArg<VariantQuarantineRecord>().also { it.id = 1L } }
        service = VariantQuarantineService(games, variants, records, leases, root.toString())
        mockkStatic("org.gameyfin.app.core.security.SecurityUtilsKt")
        every { org.gameyfin.app.core.security.getCurrentAuth() } returns null
    }

    @AfterEach fun cleanup() = unmockkAll()
    private fun quarantine() = service.quarantine(1L, 10L, QuarantineVariantRequestDto("QUARANTINE 10", "Superseded mirror"))

    @Test
    fun `quarantine and restore preserve source inode content and catalog paths`() {
        val result = quarantine()
        assertFalse(Files.exists(mirror))
        assertEquals("source content", Files.readString(source))
        assertTrue(Files.isSameFile(source, Path.of(result.quarantinePath).resolve("base.bin")))
        assertEquals(mirror.toString(), old.path)
        assertEquals(VariantRetirementState.ARCHIVED, old.retirementState)
        assertTrue(Files.exists(Path.of(result.quarantinePath).parent.resolve("recovery.properties")))
        val persisted = slot<VariantQuarantineRecord>()
        verify { records.saveAndFlush(capture(persisted)) }
        every { records.findByIdOrNull(1L) } returns persisted.captured
        val restartedService = VariantQuarantineService(games, variants, records, DownloadPathLeases(), root.toString())
        val restored = restartedService.restore(1L, 10L, 1L, "RESTORE 10")
        assertNotNull(restored.restoredAt)
        assertNull(old.quarantinePath)
        assertTrue(Files.isSameFile(source, mirror.resolve("base.bin")))
        assertEquals("source content", Files.readString(source))
    }

    @Test
    fun `quarantine refuses direct source selected variant active lease and shared hardlink`() {
        old.linkStatus = VariantLinkStatus.DIRECT
        assertFailsWith<IllegalArgumentException> { quarantine() }
        old.linkStatus = VariantLinkStatus.HARDLINKED
        old.isDefault = true
        assertFailsWith<IllegalArgumentException> { quarantine() }
        old.isDefault = false
        leases.acquire(listOf(mirror.resolve("base.bin"))).use {
            assertFailsWith<IllegalArgumentException> { quarantine() }
        }
        val sharedPath = root.resolve("shared.bin")
        Files.createLink(sharedPath, source)
        game.variants.add(GameVariant(id = 12L, game = game, path = sharedPath.toString()))
        every { variants.findAll(any<org.springframework.data.domain.Pageable>()) } returns PageImpl(game.variants)
        assertFailsWith<IllegalArgumentException> { quarantine() }
        assertTrue(Files.exists(mirror))
        assertEquals("source content", Files.readString(source))
        verify(exactly = 0) { records.saveAndFlush(any()) }
    }

    @Test
    fun `symlink child and explicit confirmation failure preserve mirror`() {
        assertFailsWith<IllegalArgumentException> {
            service.quarantine(1L, 10L, QuarantineVariantRequestDto("wrong", "test"))
        }
        try { Files.createSymbolicLink(mirror.resolve("link.bin"), source) } catch (_: FileSystemException) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Host does not permit creating symlinks; Linux CI must exercise this guard")
        }
        assertFailsWith<IllegalArgumentException> { quarantine() }
        assertTrue(Files.exists(mirror.resolve("base.bin")))
    }

    @Test
    fun `database failure compensates rename and leaves independent recovery journal`() {
        every { records.saveAndFlush(any()) } throws IllegalStateException("database unavailable")
        assertFailsWith<IllegalStateException> { quarantine() }
        assertNull(old.quarantinePath)
        assertTrue(Files.isSameFile(source, mirror.resolve("base.bin")))
        Files.list(root.resolve("variant-quarantine")).use { journals ->
            assertTrue(journals.anyMatch { Files.exists(it.resolve("recovery.properties")) })
        }
    }

    @Test
    fun `restoration refuses overwrite and transaction rollback restores original mirror`() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization()
        try {
            quarantine()
            val callbacks = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
            callbacks.forEach { it.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK) }
            assertTrue(Files.isSameFile(source, mirror.resolve("base.bin")))
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization() }
    }

    @Test
    fun `restoration refuses to replace newly created original path`() {
        val result = quarantine()
        val record = slot<VariantQuarantineRecord>()
        verify { records.saveAndFlush(capture(record)) }
        every { records.findByIdOrNull(1L) } returns record.captured
        Files.createDirectories(mirror)
        Files.writeString(mirror.resolve("new.bin"), "new content")
        assertFailsWith<IllegalArgumentException> { service.restore(1L, 10L, 1L, "RESTORE 10") }
        assertEquals("new content", Files.readString(mirror.resolve("new.bin")))
        assertTrue(Files.isSameFile(source, Path.of(result.quarantinePath).resolve("base.bin")))
    }

    @Test
    fun `path escape pretending to be a hardlink mirror never moves source`() {
        old.path = source.toString()
        assertFailsWith<IllegalArgumentException> { quarantine() }
        assertEquals("source content", Files.readString(source))
        verify(exactly = 0) { records.saveAndFlush(any()) }
    }
}
