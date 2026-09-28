package org.gameyfin.app.games.variants

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SteamNewsContentUpdateClassifierTest {
    private val classifier = SteamNewsContentUpdateClassifier()

    @Test
    fun `classifies an official patch note as a content update`() {
        val result = classifier.classify("Version 1.2 patch notes", setOf("patchnotes"), "Bug fixes and improvements")

        assertEquals(SteamNewsClassification.CONTENT_UPDATE, result.classification)
        assertEquals("Matched Steam patchnotes tag", result.reason)
    }

    @Test
    fun `excludes a livestream even when its body mentions an update`() {
        val result = classifier.classify("Developer livestream", emptySet(), "We will discuss the update")

        assertEquals(SteamNewsClassification.NOT_CONTENT_UPDATE, result.classification)
    }

    @Test
    fun `leaves an unmarked community announcement for review`() {
        val result = classifier.classify("Community round-up", setOf("workshop"), "Highlights from the week")

        assertEquals(SteamNewsClassification.NOT_CONTENT_UPDATE, result.classification)
    }
}
