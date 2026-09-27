package org.gameyfin.app.games.variants

import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

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

    /**
     * Adds evidence from a local NFO sidecar when one is present. The source path is only read;
     * a suggestion remains review-only and never changes the scanned folder.
     */
    fun suggest(path: Path, aliases: Map<String, String> = emptyMap()): Suggestion? {
        val observedName = path.fileName?.toString().orEmpty()
        val fromName = suggest(observedName, aliases)
        val nfo = readNfo(path) ?: return fromName
        val fromNfo = suggest(nfo.contents, aliases) ?: return fromName

        val evidence = fromName?.evidence.orEmpty() +
            "Observed release marker in local NFO '${nfo.path.fileName}'" +
            fromNfo.evidence.filterNot { it.startsWith("Observed release marker") }
        val labels = listOfNotNull(fromName?.variantLabel, fromNfo.variantLabel).distinctBy { it.lowercase() }
        val conflictingLabels = labels.size > 1 ||
            (fromName != null && fromName.variantLabel == null) ||
            fromNfo.variantLabel == null

        return Suggestion(
            observedName = observedName,
            variantLabel = if (conflictingLabels) null else labels.singleOrNull(),
            versionHint = fromName?.versionHint ?: fromNfo.versionHint,
            confidence = if (conflictingLabels) 0.0 else minOf(fromName?.confidence ?: 0.75, fromNfo.confidence),
            evidence = if (conflictingLabels) evidence + "Conflicting local release evidence requires administrator review" else evidence,
            requiresReview = true
        )
    }

    private fun readNfo(path: Path): Nfo? {
        if (!Files.isDirectory(path)) return null
        return runCatching {
            val nfoPath = Files.list(path).use { entries ->
                entries.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".nfo", ignoreCase = true) }
                    .findFirst()
                    .orElse(null)
            } ?: return null
            val contents = Files.newBufferedReader(nfoPath, StandardCharsets.UTF_8).use { reader ->
                reader.readText().take(32 * 1024)
            }
            Nfo(nfoPath, contents)
        }.getOrNull()
    }

    private data class Nfo(val path: Path, val contents: String)

    private fun containsMarker(name: String, marker: String): Boolean = Regex(
        "(?<![A-Za-z0-9])${Regex.escape(marker.trim())}(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(name)
}
