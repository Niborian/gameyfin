package org.gameyfin.app.libraries

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.*
import org.gameyfin.app.config.ConfigProperties
import org.gameyfin.app.config.ConfigService
import org.gameyfin.app.core.filesystem.FilesystemService
import org.gameyfin.app.core.metrics.ScanMetrics
import org.gameyfin.app.core.plugins.PluginService
import org.gameyfin.app.core.plugins.dto.PluginDto
import org.gameyfin.app.games.entities.*
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.games.variants.GameVariantGroupingService
import org.gameyfin.app.libraries.entities.DirectoryMapping
import org.gameyfin.app.libraries.entities.Library
import org.gameyfin.app.libraries.enums.ScanType
import org.gameyfin.app.libraries.scan.LibraryGameProcessor
import org.gameyfin.pluginapi.gamemetadata.GameMetadataProvider
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import org.pf4j.PluginState
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Opt-in fixture. Real filesystem and scanner; metadata providers and persistence are mocked. */
@EnabledIfSystemProperty(named = "gameyfin.scanBenchmark", matches = "true")
class ScanMemoryBenchmarkTest {
    @Test
    fun `reusable image fixture SQL imports into actual migrated H2 schema`(@TempDir root: Path) {
        val output = root.resolve("generated")
        val python = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
        val script = Path.of("../scripts/rehearsal/seed-scan-fixture.py").toAbsolutePath().normalize()
        val process = ProcessBuilder(python, script.toString(), "--output", output.toString(),
            "--container-fixture-root", "/fixture").redirectErrorStream(true).start()
        val messages = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), messages)
        val url = "jdbc:h2:file:" + root.resolve("fixture-db").toString().replace('\\', '/')
        org.flywaydb.core.Flyway.configure().dataSource(url, "fixture", "fixture-only")
            .locations("classpath:db/migration").load().migrate()
        java.sql.DriverManager.getConnection(url, "fixture", "fixture-only").use { connection ->
            connection.createStatement().use { sql ->
                Files.readString(output.resolve("seed.sql")).split(';').filter { it.isNotBlank() }
                    .forEach { sql.execute(it) }
                for ((table, expected) in mapOf("LIBRARY" to 4, "GAME" to 132,
                    "GAME_VARIANT" to 132, "VARIANT_CONTENT" to 264)) {
                    sql.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
                        rows.next(); assertEquals(expected, rows.getInt(1), table)
                    }
                }
            }
        }
        Files.walk(output.resolve("sources")).use { paths ->
            assertEquals(924L, paths.filter { Files.isRegularFile(it) }.count())
        }
    }

    @Test
    fun `measure synthetic full scan and recovery`(@TempDir fixture: Path) {
        val count = System.getProperty("gameyfin.scanBenchmark.games", "1000").toInt()
        require(count in 1..10000)
        val library = Library(id = 2700, name = "Synthetic memory fixture", directories = mutableListOf(
            DirectoryMapping(internalPath = fixture.toString())
        ))
        repeat(count) { index ->
            val gamePath = Files.createDirectory(fixture.resolve("game-$index"))
            val game = Game(id = index.toLong() + 1, library = library, metadata = GameMetadata(path = gamePath.toString()))
            repeat(2) { variantIndex ->
                val variantPath = Files.createDirectory(gamePath.resolve("version-$variantIndex"))
                val variant = GameVariant(game = game, name = "Version $variantIndex", path = variantPath.toString(), isDefault = variantIndex == 0)
                repeat(3) { contentIndex ->
                    val path = Files.write(variantPath.resolve("content-$contentIndex.bin"), ByteArray(32) { contentIndex.toByte() })
                    variant.contents.add(VariantContent(variant = variant, name = "Content $contentIndex", path = path.toString(), required = contentIndex == 0))
                }
                game.variants.add(variant)
            }
            library.games.add(game)
        }
        fun snapshot() = Files.walk(fixture).use { paths ->
            paths.filter { Files.isRegularFile(it) }.map {
                fixture.relativize(it).toString() to HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)))
            }.toList().toMap()
        }
        val original = snapshot()
        val config = mockk<ConfigService>()
        every { config.get(ConfigProperties.Libraries.Scan.MaxConcurrency) } returns 4
        every { config.get(ConfigProperties.Libraries.Scan.GameFileExtensions) } returns arrayOf("zip")
        every { config.get(ConfigProperties.Libraries.Scan.ScanEmptyDirectories) } returns false
        val libraries = mockk<LibraryRepository>()
        every { libraries.findAllById(listOf(2700L)) } returns listOf(library)
        every { libraries.save(library) } returns library
        val core = mockk<LibraryCoreService>()
        every { core.addGamesToLibrary(emptyList(), library, false) } returns library
        val processor = mockk<LibraryGameProcessor>()
        val failNext = AtomicBoolean(true)
        every { processor.processExistingGame(any()) } answers {
            if (failNext.compareAndSet(true, false)) throw SQLException("Synthetic interrupted scan")
            firstArg<Game>()
        }
        val games = mockk<GameRepository>()
        val grouping = mockk<GameVariantGroupingService>()
        every { grouping.autoGroupExactMatches(library) } returns 0
        val ignored = mockk<IgnoredPathRepository>()
        val plugins = mockk<PluginService>()
        every { plugins.getAllByTypeAndState(GameMetadataProvider::class, PluginState.STARTED) } returns listOf(mockk<PluginDto>())
        val registry = SimpleMeterRegistry()
        val scanner = LibraryScanService(libraries, FilesystemService(config), core, processor, games, grouping, ignored, plugins, config, ScanMetrics(registry))
        fun counter(name: String) = registry.find(name).tag("type", "full").counter()!!.count()
        fun awaitCounter(name: String, expected: Double) {
            val deadline = System.nanoTime() + 120_000_000_000L
            while (counter(name) < expected && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(expected, counter(name), "Timed out waiting for $name")
            while (registry.find("gameyfin.scans.active").gauge()!!.value() != 0.0 && System.nanoTime() < deadline) Thread.sleep(10)
            Thread.sleep(50) // allow scan in-progress guard to clear after metric completion
        }
        scanner.triggerScan(ScanType.FULL, listOf(2700L))
        awaitCounter("gameyfin.scans.failed", 1.0)
        val peakHeap = AtomicLong(ManagementFactory.getMemoryMXBean().heapMemoryUsage.used)
        val sampling = AtomicBoolean(true)
        val sampler = Thread.ofPlatform().start {
            while (sampling.get()) {
                peakHeap.accumulateAndGet(ManagementFactory.getMemoryMXBean().heapMemoryUsage.used, ::maxOf)
                Thread.sleep(2)
            }
        }
        val started = System.nanoTime()
        try {
            scanner.triggerScan(ScanType.FULL, listOf(2700L))
            awaitCounter("gameyfin.scans.completed", 1.0)
        } finally {
            sampling.set(false)
            sampler.join()
        }
        val durationSeconds = (System.nanoTime() - started) / 1e9
        assertEquals(count, library.games.size)
        assertEquals(count * 2, library.games.sumOf { it.variants.size })
        assertEquals(count * 6, library.games.sumOf { it.variants.sumOf { variant -> variant.contents.size } })
        assertEquals(original, snapshot())
        assertEquals(1.0, counter("gameyfin.scans.failed"))
        assertEquals(count.toDouble(), registry.find("gameyfin.scans.games.updated").counter()!!.count())
        val rss = peakRssBytes()
        val report = """{"fixtureGames":$count,"variants":${count * 2},"contents":${count * 6},"concurrency":4,"maxHeapBytes":${Runtime.getRuntime().maxMemory()},"sampledPeakHeapBytes":${peakHeap.get()},"processLifetimePeakRssBytes":$rss,"durationSeconds":$durationSeconds,"gamesPerSecond":${count / durationSeconds},"injectedFailures":1,"recoveryFailures":0,"sourceFilesUnchanged":true,"persistence":"mocked","javaVersion":"${System.getProperty("java.version")}"}"""
        val reportPath = Path.of("build/reports/scan-memory-benchmark.json")
        Files.createDirectories(reportPath.parent)
        Files.writeString(reportPath, report)
        println(report)
        clearAllMocks()
    }

    private fun peakRssBytes(): Long? {
        val status = Path.of("/proc/self/status")
        if (Files.exists(status)) return Files.readAllLines(status).first { it.startsWith("VmHWM:") }.split(Regex("\\s+"))[1].toLong() * 1024
        if (System.getProperty("os.name").lowercase().contains("win")) {
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "(Get-Process -Id ${ProcessHandle.current().pid()}).PeakWorkingSet64").start()
            val result = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0) { "Failed to measure benchmark JVM RSS" }
            return result.toLong()
        }
        return null
    }
}
