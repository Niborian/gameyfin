package org.gameyfin.app.games.variants

import org.gameyfin.app.games.dto.VariantLocalVersionPreviewDto
import org.gameyfin.app.games.dto.VariantLocalVersionSuggestionDto
import org.springframework.stereotype.Component
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.channels.Channels
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributeView

/** Local advisory metadata only: no network, writes, process execution or persistence.
 * Directory-descriptor traversal refuses symbolic links, including configured-root ancestors.
 * Providers without SecureDirectoryStream fail closed. The one-second budget is cooperative;
 * public Java cannot impose a hard deadline on blocked filesystem operations or guarantee an
 * immutable snapshot of concurrently edited files. Conflicting evidence is retained, never ranked.
 */
@Component
class VariantLocalVersionDetector {
    private val mapper = JsonMapper.builder(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(4096).maxNumberLength(32).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()

    fun preview(libraryRoot: Path, variantPath: Path, steamAppId: String? = null, gogProductId: String? = null, epicCatalogItemId: String? = null, allowUnknownFilenameHint: Boolean = false): VariantLocalVersionPreviewDto {
        val root = libraryRoot.toAbsolutePath().normalize()
        val selected = variantPath.toAbsolutePath().normalize()
        require(selected.startsWith(root)) { "Selected variant must be inside its configured library" }
        val steam = steamAppId?.takeIf { it.matches(Regex("[1-9][0-9]{0,15}")) }
        val gog = gogProductId?.takeIf { it.matches(Regex("[1-9][0-9]{0,31}")) }
        val epic = epicCatalogItemId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
        val installerHints = steamAppId.isNullOrBlank() && epicCatalogItemId.isNullOrBlank() && (gog != null || allowUnknownFilenameHint)
        val budget = Budget()
        val suggestions = mutableListOf<VariantLocalVersionSuggestionDto>()
        val visited = mutableSetOf<Path>()
        var unreadable = false
        try {
            RootReader(root).use { reader ->
                fun inspect(relative: Path, scoped: Boolean) {
                    if (!budget.available() || !visited.add(relative)) return
                    val name = relative.fileName.toString()
                    try {
                        when {
                            steam != null && name == "appmanifest_$steam.acf" -> {
                                val fields = Acf(reader.read(relative, budget)).fields()
                                if (fields["appid"] != steam) return
                                if (!scoped) {
                                    val installName = fields["installdir"] ?: return
                                    if (installName.length !in 1..255 || installName.any { it == '/' || it == '\\' || it.isISOControl() } || installName in listOf(".", "..")) return
                                    val parent = root.resolve(relative).parent
                                    if (parent.fileName.toString() != "steamapps") return
                                    val installed = parent.resolve("common").resolve(installName).normalize()
                                    if (!installed.startsWith(selected)) return
                                }
                                clean(fields["buildid"])?.let { build -> suggestions.add(VariantLocalVersionSuggestionDto("STEAM_APP_MANIFEST", null, build, "Exact configured Steam app ID; build ID is not a release-version label.", evidence(relative))) }
                            }
                            gog != null && name == "goggame-$gog.info" && scoped -> {
                                val json = json(reader.read(relative, budget))
                                if (field(json, "gameId") != gog) return
                                val build = field(json, "buildId")
                                // goggame.info's ordinary `version` is a schema number, not the installed game version.
                                if (build != null) suggestions.add(VariantLocalVersionSuggestionDto("GOG_GAME_INFO", null, build, "Exact configured GOG product ID; reports buildId, not the metadata schema version.", evidence(relative)))
                            }
                            epic != null && scoped && relative.any { it.toString() == ".egstore" } && name.endsWithAny(".item", ".json", ".manifest") -> {
                                val text = reader.read(relative, budget)
                                if (!text.trimStart().startsWith("{")) return // Binary Epic manifests are deliberately unsupported.
                                val json = json(text)
                                if (field(json, "CatalogItemId") != epic) return
                                field(json, "InstallLocation")?.let { location ->
                                    val installed = Path.of(location)
                                    if (!installed.isAbsolute || !installed.normalize().startsWith(selected)) return
                                }
                                val version = field(json, "AppVersionString")
                                val build = field(json, "BuildVersion")
                                if (version != null || build != null) suggestions.add(VariantLocalVersionSuggestionDto("EPIC_MANIFEST", version, build, "Exact configured Epic catalog item ID from local JSON manifest; no online release comparison.", evidence(relative)))
                            }
                            installerHints && scoped && name.startsWith("setup_", ignoreCase = true) -> {
                                INSTALLER.matchEntire(name)?.groupValues?.get(1)?.let { version ->
                                    clean(version)?.let { suggestions.add(VariantLocalVersionSuggestionDto("GOG_INSTALLER_FILENAME", it, null, "Filename-only installer hint, not verified installed version or product identity. Review before applying.", evidence(relative))) }
                                }
                            }
                        }
                    } catch (_: LimitReached) { budget.truncated = true }
                    catch (_: Exception) { unreadable = true } // Never return paths, parser excerpts, or file contents in errors.
                }
                reader.scanSelected(root.relativize(selected), budget) { relative -> inspect(relative, true) }
                // No ancestor walks: only the exact configured Steam manifest at bounded ancestor locations.
                if (steam != null) {
                    var ancestor: Path? = selected
                    var count = 0
                    while (ancestor != null && ancestor.startsWith(root) && count++ < 16 && budget.available()) {
                        for (candidate in listOf(ancestor.resolve("appmanifest_$steam.acf"), ancestor.resolve("steamapps/appmanifest_$steam.acf"))) {
                            if (candidate.startsWith(root)) inspect(root.relativize(candidate), candidate.startsWith(selected))
                        }
                        if (ancestor == root) break
                        ancestor = ancestor.parent
                    }
                }
            }
        } catch (_: UnsupportedOperationException) {
            return VariantLocalVersionPreviewDto(emptyList(), "Safe descriptor-relative filesystem reads are unavailable on this filesystem; enter metadata manually.", false)
        } catch (_: Exception) { unreadable = true }
        val result = suggestions.distinct().sortedWith(compareBy({ it.source }, { it.kind }, { it.version.orEmpty() }, { it.build.orEmpty() })).take(32)
        val conflicting = result.map { it.version to it.build }.distinct().size > 1
        val message = when {
            conflicting -> "Multiple different local hints were found. Nothing was selected or saved; review each source."
            result.isNotEmpty() -> "Local hints only. Review and explicitly apply any desired fields; nothing was saved."
            else -> "No matching supported local version evidence found. Product IDs may need configuration; manual entry remains available."
        } + if (unreadable) " Some candidate metadata was absent, unreadable or unsupported." else ""
        return VariantLocalVersionPreviewDto(result, message, budget.truncated || suggestions.size > 32)
    }

    private fun json(text: String): JsonNode = mapper.readTree(text).also { require(it.isObject) }
    private fun field(node: JsonNode, name: String): String? = node[name]?.let { if (it.isString || it.isIntegralNumber) clean(it.asText()) else null }
    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.length in 1..128 && it.none { ch -> ch.isISOControl() } }
    private fun evidence(path: Path): String = path.joinToString("/") { it.toString() }.take(1024)
    private fun String.endsWithAny(vararg suffixes: String) = suffixes.any { endsWith(it, ignoreCase = true) }
    private class LimitReached : RuntimeException()
    private class Budget {
        private val end = System.nanoTime() + 1_000_000_000L
        var entries = 0; var files = 0; var bytes = 0; var truncated = false
        fun available(): Boolean = (!Thread.currentThread().isInterrupted && System.nanoTime() < end && entries < 256 && files < 16 && bytes < 512 * 1024).also { if (!it) truncated = true }
        fun check() { if (!available()) throw LimitReached() }
    }
    private class RootReader(root: Path) : AutoCloseable {
        private val directory: SecureDirectoryStream<Path> = openRoot(root)
        private fun <T> at(relative: Path, action: (SecureDirectoryStream<Path>) -> T): T {
            val segments = relative.filter { it.toString().isNotEmpty() }
            require(segments.none { it.toString() in listOf(".", "..") })
            fun visit(current: SecureDirectoryStream<Path>, index: Int): T = if (index == segments.size) action(current)
                else current.newDirectoryStream(segments[index], NOFOLLOW_LINKS).use { visit(it, index + 1) }
            return visit(directory, 0)
        }
        fun walk(relative: Path, depth: Int, budget: Budget, inspect: (Path) -> Unit) {
            if (!budget.available() || depth > 3) { budget.truncated = true; return }
            at(relative) { parent ->
                for (entry in parent) {
                    if (!budget.available()) break
                    budget.entries++
                    val name = entry.fileName
                    val attrs = parent.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
                    val child = relative.resolve(name)
                    if (attrs.isRegularFile) inspect(child)
                    else if (attrs.isDirectory && !attrs.isSymbolicLink) walk(child, depth + 1, budget, inspect)
                }
            }
        }
        fun scanSelected(relative: Path, budget: Budget, inspect: (Path) -> Unit) {
            budget.check()
            val attributes = if (relative.toString().isEmpty()) directory.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes()
                else at(relative.parent ?: Path.of("")) { parent -> parent.getFileAttributeView(relative.fileName, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes() }
            require(!attributes.isSymbolicLink) { "Selected variant cannot be a symbolic link" }
            if (attributes.isDirectory) walk(relative, 0, budget, inspect)
            else if (attributes.isRegularFile) { budget.entries++; inspect(relative) }
        }
        fun read(relative: Path, budget: Budget): String {
            budget.check()
            return at(relative.parent ?: Path.of("")) { parent ->
                val name = relative.fileName
                val before = parent.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
                require(before.isRegularFile && !before.isSymbolicLink && before.size() <= 128 * 1024)
                budget.files++
                val limit = minOf(128 * 1024, 512 * 1024 - budget.bytes)
                val bytes = parent.newByteChannel(name, setOf<java.nio.file.OpenOption>(READ, NOFOLLOW_LINKS)).use { channel -> Channels.newInputStream(channel).readNBytes(limit + 1) }
                require(bytes.size <= limit); budget.bytes += bytes.size
                val after = parent.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
                require(before.fileKey() == after.fileKey() && before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime())
                budget.check()
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            }
        }
        override fun close() = directory.close()
        companion object {
            private fun openRoot(root: Path): SecureDirectoryStream<Path> {
                var stream: DirectoryStream<Path> = Files.newDirectoryStream(requireNotNull(root.root))
                try {
                    for (part in root) {
                        val secure = stream as? SecureDirectoryStream<Path> ?: throw UnsupportedOperationException()
                        val child = secure.newDirectoryStream(part, NOFOLLOW_LINKS)
                        stream.close(); stream = child
                    }
                    return stream as? SecureDirectoryStream<Path> ?: throw UnsupportedOperationException()
                } catch (error: Exception) { stream.close(); throw error }
            }
        }
    }
    /** Strict bounded Valve KeyValues subset: quoted keys/values and nested objects only. */
    private class Acf(private val text: String) {
        private var index = 0; private var tokens = 0
        private fun token(): String? {
            while (index < text.length && text[index].isWhitespace()) index++
            if (index == text.length) return null
            require(++tokens <= 4096)
            val ch = text[index++]
            if (ch == '{' || ch == '}') return ch.toString()
            require(ch == '"')
            val result = StringBuilder()
            while (index < text.length) {
                val next = text[index++]
                if (next == '"') return result.toString()
                require(!next.isISOControl() && result.length < 4096)
                result.append(if (next == '\\') text[index++].also { require(it == '\\' || it == '"') } else next)
            }
            error("Incomplete local manifest")
        }
        private fun objectFields(depth: Int): Map<String, Any> {
            require(depth <= 8 && token() == "{")
            val values = linkedMapOf<String, Any>()
            while (true) {
                val key = requireNotNull(token()); if (key == "}") return values
                require(key != "{" && !values.containsKey(key))
                val saved = index; val value = requireNotNull(token())
                values[key] = if (value == "{") { index = saved; objectFields(depth + 1) } else { require(value != "}"); value }
            }
        }
        fun fields(): Map<String, String> {
            require(token() == "AppState")
            val values = objectFields(0); require(token() == null)
            return values.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
        }
    }
    companion object {
        private val INSTALLER = Regex("(?i)^setup_.+?_((?:v)?[0-9]+(?:\\.[0-9A-Za-z]+){1,5}(?:[-+][A-Za-z0-9.-]+)?)(?:_.*)?\\.(?:exe|sh|dmg|pkg)$")
    }
}
