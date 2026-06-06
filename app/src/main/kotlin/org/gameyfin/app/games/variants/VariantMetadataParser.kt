package org.gameyfin.app.games.variants

import org.gameyfin.app.games.entities.VariantContentType
import org.springframework.stereotype.Component
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream

@Component
class VariantMetadataParser {
    companion object {
        private val FOLDER_PATTERN = Regex("""^(.+?)[\s_-]*(\d+(?:\.\d+)*(?:[-+_][A-Za-z0-9._-]+)?)$""")
        private const val METADATA_FILE = "metadata.txt"
    }

    fun parse(variantPath: Path): ParsedVariantMetadata {
        val folderDerived = parseFolderName(variantPath.fileName.toString())
        val properties = readMetadata(variantPath.resolve(METADATA_FILE))

        val name = properties["variant"]?.ifBlank { null } ?: folderDerived.name
        val version = properties["version"]?.ifBlank { null } ?: folderDerived.version
        val tags = splitCsv(properties["tags"])
        val steamAppId = properties["steamAppId"]?.ifBlank { null }
        val launchArgs = properties["launchArgs"]?.ifBlank { null }
        val patchInfo = properties["patchInfo"]?.ifBlank { null }
        val contents = parseContents(variantPath, properties)

        return ParsedVariantMetadata(
            name = name,
            version = version,
            path = variantPath,
            tags = tags,
            steamAppId = steamAppId,
            launchArgs = launchArgs,
            patchInfo = patchInfo,
            contents = contents
        )
    }

    fun parseFolderName(folderName: String): FolderVariantName {
        val match = FOLDER_PATTERN.matchEntire(folderName)
            ?: return FolderVariantName(name = folderName.trim().ifBlank { "Normal" }, version = "0")

        return FolderVariantName(
            name = match.groupValues[1].trim().ifBlank { "Normal" },
            version = match.groupValues[2].trim().ifBlank { "0" }
        )
    }

    private fun readMetadata(path: Path): Map<String, String> {
        if (!path.exists()) return emptyMap()

        val properties = java.util.Properties()
        path.inputStream().use { properties.load(it) }

        return properties.entries.associate { (key, value) ->
            key.toString().trim() to value.toString().trim()
        }
    }

    private fun parseContents(variantPath: Path, properties: Map<String, String>): List<ParsedVariantContent> {
        val contentKeys = properties.keys
            .filter { it.startsWith("content.") }
            .mapNotNull { key -> key.removePrefix("content.").substringBefore('.').takeIf { it.isNotBlank() } }
            .toSet()

        return contentKeys.map { key ->
            val prefix = "content.$key."
            val rawType = properties["${prefix}type"]?.uppercase()
            val type = rawType?.let { runCatching { VariantContentType.valueOf(it) }.getOrNull() }
                ?: VariantContentType.EXTRA
            val name = properties["${prefix}name"]?.ifBlank { null } ?: key
            val relativePath = properties["${prefix}path"]?.ifBlank { null } ?: key
            val required = properties["${prefix}required"]?.toBooleanStrictOrNull() ?: false
            val defaultSelected = properties["${prefix}defaultSelected"]?.toBooleanStrictOrNull() ?: required
            val tags = splitCsv(properties["${prefix}tags"])

            ParsedVariantContent(
                key = key,
                type = type,
                name = name,
                path = variantPath.resolve(relativePath).normalize(),
                required = required,
                defaultSelected = defaultSelected,
                tags = tags
            )
        }.sortedBy { it.key }
    }

    private fun splitCsv(value: String?): Set<String> {
        return value
            ?.split(',')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
    }

    data class FolderVariantName(
        val name: String,
        val version: String
    )
}
