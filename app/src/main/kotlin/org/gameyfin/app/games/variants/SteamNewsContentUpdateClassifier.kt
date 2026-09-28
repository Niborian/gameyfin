package org.gameyfin.app.games.variants

import org.springframework.stereotype.Component

enum class SteamNewsClassification {
    CONTENT_UPDATE,
    NOT_CONTENT_UPDATE,
    REVIEW_NEEDED
}

data class SteamNewsClassificationResult(
    val classification: SteamNewsClassification,
    val reason: String
)

/**
 * Conservative, explainable classification for events returned by Steam's official ISteamNews API.
 * It intentionally does not infer a binary build number and has no acquisition side effect.
 */
@Component
class SteamNewsContentUpdateClassifier {
    fun classify(title: String, tags: Set<String>, contents: String): SteamNewsClassificationResult {
        val normalizedTitle = title.lowercase()
        val normalizedTags = tags.map { it.lowercase() }.toSet()
        val evidence = "$normalizedTitle\n${contents.lowercase()}"

        if (normalizedTags.any { it in excludedTags } || excludedTerms.any { it in normalizedTitle }) {
            return SteamNewsClassificationResult(
                SteamNewsClassification.NOT_CONTENT_UPDATE,
                "Excluded because the event is labelled as a promotion, livestream, or community item"
            )
        }
        if ("patchnotes" in normalizedTags) {
            return SteamNewsClassificationResult(
                SteamNewsClassification.CONTENT_UPDATE,
                "Matched Steam patchnotes tag"
            )
        }
        val matchingTerm = updateTerms.firstOrNull { term -> Regex("\\b${Regex.escape(term)}\\b").containsMatchIn(evidence) }
        if (matchingTerm != null) {
            return SteamNewsClassificationResult(
                SteamNewsClassification.CONTENT_UPDATE,
                "Matched '$matchingTerm' in the official event title or content"
            )
        }
        return SteamNewsClassificationResult(
            SteamNewsClassification.REVIEW_NEEDED,
            "Official event has no reliable content-update marker"
        )
    }

    private companion object {
        val excludedTags = setOf("workshop", "broadcast", "sale")
        val excludedTerms = setOf("livestream", "live-stream", "sale", "discount", "tournament", "play-along", "creator spotlight")
        val updateTerms = setOf("patch", "hotfix", "update", "release notes", "changelog", "version")
    }
}
