package org.gameyfin.app.games.variants

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseNameSuggestionServiceTest {
    private val service = ReleaseNameSuggestionService()

    @Test
    fun `online fix suggests a neutral label without applying it to source`() {
        val observed = "Example.Game.v1.4.2.Online-Fix"

        val suggestion = assertNotNull(service.suggest(observed))

        assertEquals(observed, suggestion.observedName)
        assertEquals("multiplayer compatibility fix", suggestion.variantLabel)
        assertEquals("1.4.2", suggestion.versionHint)
        assertEquals(0.85, suggestion.confidence)
        assertTrue(suggestion.requiresReview)
        assertTrue(suggestion.evidence.any { it.contains("Online", ignoreCase = true) })
    }

    @Test
    fun `caller alias can override the built in marker without changing source`() {
        val suggestion = assertNotNull(service.suggest(
            "Example_online-fix",
            mapOf("Online-Fix" to "LAN compatibility build")
        ))

        assertEquals("LAN compatibility build", suggestion.variantLabel)
        assertEquals("Example_online-fix", suggestion.observedName)
        assertEquals(0.75, suggestion.confidence)
    }

    @Test
    fun `ambiguous aliases require review instead of selecting a label`() {
        val suggestion = assertNotNull(service.suggest(
            "Example.online-fix.extra-patch",
            mapOf("extra-patch" to "patch")
        ))

        assertNull(suggestion.variantLabel)
        assertEquals(0.0, suggestion.confidence)
        assertTrue(suggestion.requiresReview)
        assertTrue(suggestion.evidence.any { it.contains("Conflicting") })
    }

    @Test
    fun `marker boundaries avoid guessing from a game title`() {
        assertNull(service.suggest("The Online-Fixation Game"))
        assertNull(service.suggest("Ordinary Game 2024.09.20"))
        assertEquals("multiplayer compatibility fix", service.suggest("Example_Online_Fix")?.variantLabel)
    }

    @Test
    fun `suggestion never selects a default version`() {
        val suggestion = assertNotNull(service.suggest("Example.online-fix.v2.0"))
        assertFalse(suggestion.evidence.any { it.contains("default", ignoreCase = true) })
        assertTrue(suggestion.requiresReview)
    }

    @Test
    fun `local NFO contributes review-only marker evidence`(@TempDir tempDir: Path) {
        val release = tempDir.resolve("Example Game v1.4.2").createDirectory()
        release.resolve("release.nfo").writeText("Example Game Online-Fix v1.4.2")

        val suggestion = assertNotNull(service.suggest(release))

        assertEquals("multiplayer compatibility fix", suggestion.variantLabel)
        assertEquals("1.4.2", suggestion.versionHint)
        assertTrue(suggestion.requiresReview)
        assertTrue(suggestion.evidence.any { it.contains("local NFO") })
    }

    @Test
    fun `ambiguous local NFO evidence never selects a label`(@TempDir tempDir: Path) {
        val release = tempDir.resolve("Example Game").createDirectory()
        release.resolve("release.nfo").writeText("Example online-fix extra-patch")

        val suggestion = assertNotNull(service.suggest(release, mapOf("extra-patch" to "patch")))

        assertNull(suggestion.variantLabel)
        assertEquals(0.0, suggestion.confidence)
        assertTrue(suggestion.requiresReview)
    }
}
