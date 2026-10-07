package org.gameyfin.pluginapi.download

import java.nio.file.Path
import java.util.Collections

/** An additive capability for providers that must preserve an exact multi-content selection.
 *
 * Core authorization and content-root validation run before dispatch. Paths are canonical
 * read-only inputs, not permission to rename, modify or delete source files. A provider
 * retaining data after this call must own an isolated snapshot/lifecycle; the response
 * stream lease does not authorize persistent seeding from a mutable source tree.
 */
interface SelectionAwareDownloadProvider : DownloadProvider {
    fun download(selection: DownloadSelection): Download
}

/** Logical selected content, with all grouped paths and no unchecked optional content. */
class DownloadSelectionContent(val name: String, paths: List<Path>) {
    val paths: List<Path> = Collections.unmodifiableList(paths.toList())
    init { require(this.paths.isNotEmpty()) { "Selected content must have a source path" } }
}

/** Defensive immutable selection snapshot, independent of application persistence entities. */
class DownloadSelection(contents: List<DownloadSelectionContent>) {
    val contents: List<DownloadSelectionContent> = Collections.unmodifiableList(contents.toList())
    init { require(this.contents.isNotEmpty()) { "Download selection must not be empty" } }
}
