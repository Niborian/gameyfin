package org.gameyfin.app.games.variants

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tiny owned filesystem fixtures only; no application context, network, container or source library. */
class VariantLocalVersionDetectorTest {
    private val detector = VariantLocalVersionDetector()
    private fun supports(path: Path) = Files.newDirectoryStream(path).use { it is SecureDirectoryStream<*> }
    private fun secure(path: Path) = assumeTrue(supports(path), "Filesystem lacks descriptor-relative directory reads; explicit fail-closed case covers it")
    private fun file(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        return Files.writeString(path, text)
    }
    private fun digest(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).toList()

    @Test fun `selected installer file is advisory only and unknown hints require explicit opt in`(@TempDir root: Path) {
        secure(root)
        val installer = file(root.resolve("setup_owned_game_1.2.3.exe"), "synthetic installer bytes, never executed")
        val before = digest(installer)
        assertTrue(detector.preview(root, installer).suggestions.isEmpty())
        val hint = detector.preview(root, installer, allowUnknownFilenameHint = true).suggestions.single()
        assertEquals("GOG_INSTALLER_FILENAME", hint.kind)
        assertEquals("1.2.3", hint.version)
        assertEquals("setup_owned_game_1.2.3.exe", hint.source)
        assertNull(hint.build)
        assertTrue(hint.message.contains("not verified"))
        assertTrue(detector.preview(root, installer, steamAppId = "123", allowUnknownFilenameHint = true).suggestions.isEmpty())
        assertEquals(before, digest(installer))
    }

    @Test fun `GOG info matches exact configured identity and never presents schema version as release`(@TempDir root: Path) {
        secure(root)
        val selected = Files.createDirectory(root.resolve("owned"))
        val metadata = file(selected.resolve("goggame-123.info"), """{"gameId":"123","buildId":"456789","version":1}""")
        val before = digest(metadata)
        val hint = detector.preview(root, selected, gogProductId = "123").suggestions.single()
        assertEquals("GOG_GAME_INFO", hint.kind)
        assertEquals("456789", hint.build)
        assertNull(hint.version)
        assertEquals(before, digest(metadata))
        assertTrue(detector.preview(root, selected, gogProductId = "999").suggestions.isEmpty())
        Files.writeString(metadata, """{"gameId":"999","buildId":"456789","version":1}""")
        assertTrue(detector.preview(root, selected, gogProductId = "123").suggestions.isEmpty())
    }

    @Test fun `Steam ancestor manifest requires exact app id and selected installation directory`(@TempDir root: Path) {
        secure(root)
        val selected = Files.createDirectories(root.resolve("steamapps/common/Owned"))
        val metadata = file(root.resolve("steamapps/appmanifest_123.acf"), """"AppState" { "appid" "123" "installdir" "Owned" "buildid" "987654" }""")
        val hint = detector.preview(root, selected, steamAppId = "123").suggestions.single()
        assertEquals("STEAM_APP_MANIFEST", hint.kind)
        assertEquals("987654", hint.build)
        assertNull(hint.version)
        assertEquals("steamapps/appmanifest_123.acf", hint.source)
        Files.writeString(metadata, """"AppState" { "appid" "123" "installdir" "Sibling" "buildid" "999999" }""")
        assertTrue(detector.preview(root, selected, steamAppId = "123").suggestions.isEmpty())
        Files.writeString(metadata, """"AppState" { "appid" "999" "installdir" "Owned" "buildid" "999999" }""")
        assertTrue(detector.preview(root, selected, steamAppId = "123").suggestions.isEmpty())
    }

    @Test fun `selected symlink and nested manifest symlink never escape the library`(@TempDir temporary: Path) {
        secure(temporary)
        val root = Files.createDirectory(temporary.resolve("library"))
        val external = file(temporary.resolve("external/goggame-123.info"), """{"gameId":"123","buildId":"secret-outside"}""")
        val selected = Files.createDirectory(root.resolve("owned"))
        try {
            Files.createSymbolicLink(selected.resolve("goggame-123.info"), external)
            Files.createSymbolicLink(root.resolve("selected-link"), external.parent)
        } catch (_: UnsupportedOperationException) { assumeTrue(false, "Symbolic link creation unavailable"); return }
        catch (_: java.io.IOException) { assumeTrue(false, "Symbolic link creation not permitted"); return }
        val before = digest(external)
        assertTrue(detector.preview(root, selected, gogProductId = "123").suggestions.isEmpty())
        assertTrue(detector.preview(root, root.resolve("selected-link"), gogProductId = "123").suggestions.isEmpty())
        assertEquals(before, digest(external))
    }

    @Test fun `conflicting hints remain separate rather than choosing the highest version`(@TempDir root: Path) {
        secure(root)
        file(root.resolve("setup_owned_game_1.2.3.exe"), "one")
        file(root.resolve("setup_owned_game_9.8.7.exe"), "two")
        val preview = detector.preview(root, root, allowUnknownFilenameHint = true)
        assertEquals(setOf("1.2.3", "9.8.7"), preview.suggestions.map { it.version }.toSet())
        assertTrue(preview.message.contains("Multiple different"))
        assertFalse(preview.truncated)
    }

    @Test fun `bounded entry traversal and oversized metadata report no invented result`(@TempDir root: Path) {
        secure(root)
        repeat(260) { file(root.resolve("ordinary-$it.bin"), "") }
        assertTrue(detector.preview(root, root).truncated)
        val selected = Files.createDirectory(root.resolve("large-metadata"))
        file(selected.resolve("goggame-123.info"), " ".repeat(128 * 1024 + 1))
        assertTrue(detector.preview(root, selected, gogProductId = "123").suggestions.isEmpty())
    }

    @Test fun `preview does not create remove or rewrite any selected source file`(@TempDir root: Path) {
        secure(root)
        val installer = file(root.resolve("setup_owned_game_2.3.4.exe"), "original synthetic bytes")
        val info = file(root.resolve("goggame-123.info"), """{"gameId":"123","buildId":"321","version":1}""")
        val paths = listOf(installer, info)
        val before = paths.associateWith { digest(it) to Files.getLastModifiedTime(it) }
        val namesBefore = Files.list(root).use { it.map { path -> path.fileName.toString() }.sorted().toList() }
        detector.preview(root, root, gogProductId = "123")
        assertEquals(before, paths.associateWith { digest(it) to Files.getLastModifiedTime(it) })
        assertEquals(namesBefore, Files.list(root).use { it.map { path -> path.fileName.toString() }.sorted().toList() })
    }

    @Test fun `unsupported descriptor filesystem fails closed and outside selected path is always refused`(@TempDir root: Path) {
        val selected = file(root.resolve("setup_owned_game_1.2.3.exe"), "owned")
        if (!supports(root)) {
            val preview = detector.preview(root, selected, allowUnknownFilenameHint = true)
            assertTrue(preview.suggestions.isEmpty())
            assertTrue(preview.message.contains("unavailable"))
            assertFalse(preview.truncated)
        }
        assertFailsWith<IllegalArgumentException> { detector.preview(root, root.parent.resolve("outside-unread"), allowUnknownFilenameHint = true) }
    }
}
