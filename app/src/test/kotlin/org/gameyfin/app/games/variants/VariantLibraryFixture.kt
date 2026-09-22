package org.gameyfin.app.games.variants

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

/** A deterministic, local-only representation of a torrent-managed variant library. */
class VariantLibraryFixture private constructor(val root: Path) {
    val gamePath = root.resolve("Example Game")
    val ignoredAttachedSourcePath = root.resolve("attached-source-to-ignore")
    val hardlinkSource = root.resolve("hardlinks/source.bin")
    val hardlinkMirror = root.resolve("hardlinks/mirror.bin")

    data class Snapshot(val files: Map<String, String>, val directories: List<String>)

    fun snapshot(): Snapshot {
        Files.walk(root).use { paths ->
            val allPaths = paths.toList().sorted()
            return Snapshot(
                files = allPaths.filter { it.isRegularFile() }.associate { path ->
                    path.relativeTo(root).toString() to sha256(path)
                },
                directories = allPaths.filter { Files.isDirectory(it) }
                    .map { it.relativeTo(root).toString() }
            )
        }
    }

    companion object {
        fun create(root: Path): VariantLibraryFixture {
            val fixture = VariantLibraryFixture(root)
            fixture.gamePath.createDirectories()
            fixture.writeVariant("Normal 1.0", """
                variant=Normal
                version=1.0
                content.base.type=BASE
                content.base.path=game.bin
                content.base.required=true
            """)
            fixture.writeVariant("Normal 1.1", """
                variant=Normal
                version=1.1
                content.base.type=BASE
                content.base.path=game.bin
                content.base.required=true
                content.dlc.type=DLC
                content.dlc.path=dlc
                content.patch.type=PATCH
                content.patch.path=patch
                content.mod.type=MOD
                content.mod.path=mods
                content.extra.type=EXTRA
                content.extra.path=extras
                content.server.type=DEDICATED_SERVER
                content.server.path=server
                content.shared.type=EXTRA
                content.shared.path=../shared-content
            """)
            fixture.writeVariant("Multiplayer Fix 1.1", """
                variant=Multiplayer Fix
                version=1.1
                tags=multiplayer
                content.base.type=BASE
                content.base.path=game.bin
                content.archives.type=EXTRA
                content.archives.path=archives
            """)

            fixture.gamePath.resolve("shared-content").createDirectories().resolve("shared.dat").writeText("shared")
            fixture.gamePath.resolve("Multiplayer Fix 1.1/archives").createDirectories()
            fixture.gamePath.resolve("Multiplayer Fix 1.1/archives/game.part01.rar").writeText("part one")
            fixture.gamePath.resolve("Multiplayer Fix 1.1/archives/game.part02.rar").writeText("part two")
            fixture.ignoredAttachedSourcePath.createDirectories().resolve("source.bin").writeText("ignored source")
            fixture.hardlinkSource.parent.createDirectories()
            fixture.hardlinkSource.writeText("hardlinked data")
            Files.createLink(fixture.hardlinkMirror, fixture.hardlinkSource)
            return fixture
        }

        private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
    }

    private fun writeVariant(name: String, metadata: String) {
        val variant = gamePath.resolve(name).createDirectories()
        variant.resolve("metadata.txt").writeText(metadata.trimIndent())
        variant.resolve("game.bin").writeText(name)
        listOf("dlc", "patch", "mods", "extras", "server").forEach { directory ->
            variant.resolve(directory).createDirectories().resolve("$directory.dat").writeText(directory)
        }
    }
}
