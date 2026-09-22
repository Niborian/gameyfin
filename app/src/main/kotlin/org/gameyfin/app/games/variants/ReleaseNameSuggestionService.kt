package org.gameyfin.app.games.variants

import org.springframework.stereotype.Component

/** Read-only evidence from an observed release name; never applies a grouping or default. */
@Component
class ReleaseNameSuggestionService {
    companion object {
        const val MULTIPLAYER_COMPATIBILITY_FIX = "multiplayer compatibility fix"

        private val defaultAliases = listOf("online-fix", "online_fix", "online.fix", "online fix")
            .associateWith { MULTIPLAYER_COMPATIBILITY_FIX }
        private val versionPattern = Regex(
            "(?<![A-Za-z0-9])v?(\\d{1,3}(?:\\.\\d{1,3}){1,3})(?![A-Za-z0-9]|\\.\\d)",
            RegexOption.IGNORE_CASE
        )
    }

    data class Suggestion(
        val observedName: String,
        val variantLabel: String?,
        val versionHint: String?,
        val confidence: Double,
        val evidence: List<String>,
        val requiresReview: Boolean
    )

    fun suggest(observedName: String, aliases: Map<String, String> = emptyMap()): Suggestion? {
        val dictionary = defaultAliases + aliases.mapKeys { it.key.trim().lowercase() }
        val matched = dictionary.entries
            .filter { (marker, label) -> marker.isNotBlank() && label.isNotBlank() && containsMarker(observedName, marker) }
        if (matched.isEmpty()) return null

        val versionHint = versionPattern.find(observedName)?.groupValues?.get(1)
        val labels = matched.map { it.value.trim() }.distinctBy { it.lowercase() }
        val evidence = matched.map { "Observed release marker '${it.key}'" } +
            listOfNotNull(versionHint?.let { "Observed dotted version hint '$it'" })

        if (labels.size != 1) {
            return Suggestion(observedName, null, versionHint, 0.0,
                evidence + "Conflicting alias labels require administrator review", true)
        }

        return Suggestion(
            observedName = observedName,
            variantLabel = labels.single(),
            versionHint = versionHint,
            confidence = if (versionHint == null) 0.75 else 0.85,
            evidence = evidence,
            requiresReview = true
        )
    }

    private fun containsMarker(name: String, marker: String): Boolean = Regex(
        "(?<![A-Za-z0-9])${Regex.escape(marker.trim())}(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(name)
}
