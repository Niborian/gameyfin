package org.gameyfin.app.games.variants

import org.gameyfin.app.games.entities.VariantContentType
import org.springframework.stereotype.Service
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isHidden
import kotlin.io.path.listDirectoryEntries

@Service
class GameVariantDiscoveryService(
    private val parser: VariantMetadataParser
) {
    private val variantFolderPattern = Regex(""".*\d+(?:\.\d+)*(?:[-+_][A-Za-z0-9._-]+)?$""")

    fun discover(path: Path): DiscoveredGameVariants {
        if (!path.isDirectory()) {
            return DiscoveredGameVariants(path, listOf(defaultVariant(path)))
        }

        val variantDirectories = path.listDirectoryEntries()
            .filter { it.isDirectory() && !it.isHidden() }
            .filter { isVariantDirectory(it) }
            .sortedBy { it.fileName.toString() }

        val variants = if (variantDirectories.isEmpty()) {
            listOf(defaultVariant(path))
        } else {
            variantDirectories.map { variantPath ->
                val parsed = parser.parse(variantPath)
                parsed.ensureBaseContent()
            }
        }

        return DiscoveredGameVariants(path, variants)
    }

    private fun isVariantDirectory(path: Path): Boolean {
        return path.resolve("metadata.txt").exists() || variantFolderPattern.matches(path.fileName.toString())
    }

    private fun defaultVariant(path: Path): ParsedVariantMetadata {
        return ParsedVariantMetadata(
            name = "Normal",
            version = "0",
            path = path,
            tags = emptySet(),
            steamAppId = null,
            launchArgs = null,
            patchInfo = null,
            contents = listOf(baseContent(path))
        )
    }

    private fun ParsedVariantMetadata.ensureBaseContent(): ParsedVariantMetadata {
        if (contents.any { it.type == VariantContentType.BASE }) return this
        return copy(contents = listOf(baseContent(path)) + contents)
    }

    private fun baseContent(path: Path): ParsedVariantContent {
        return ParsedVariantContent(
            key = "base",
            type = VariantContentType.BASE,
            name = "Base game",
            path = path,
            required = true,
            defaultSelected = true,
            tags = emptySet()
        )
    }
}
