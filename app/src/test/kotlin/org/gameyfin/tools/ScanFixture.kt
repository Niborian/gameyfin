package org.gameyfin.tools

import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.HexFormat

/** Synthetic files/SQL only; never opens a database. Shared by the scanner fixture and image rehearsal. */
object ScanFixture {
    val json = JsonMapper.builder().build()
    fun sha(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun sha(path: Path): String = Files.newInputStream(path).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        HexFormat.of().formatHex(digest.digest())
    }
    fun inventory(root: Path): Map<String, String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.sorted().toList()
            .associate { root.relativize(it).toString().replace('\\', '/') to sha(it) }
    }
    fun generate(output: Path, containerRoot: String) {
        require(!Files.exists(output)) { "Output must be a new isolated directory" }
        require(containerRoot.startsWith('/') && '\'' !in containerRoot && ".." !in containerRoot.split('/'))
        Files.createDirectory(output)
        val sources = Files.createDirectory(output.resolve("sources"))
        val statements = mutableListOf<String>()
        val games = mutableListOf<Map<String, Any>>()
        listOf(104, 27, 1, 0).forEachIndexed { zero, count ->
            val library = zero + 1; val id = 27000 + library
            val libraryPath = "${containerRoot.trimEnd('/')}/lib$library"
            Files.createDirectory(sources.resolve("lib$library"))
            statements += "INSERT INTO LIBRARY(ID,CREATED_AT,NAME,UPDATED_AT,STORAGE_MODE) VALUES($id,CURRENT_TIMESTAMP,'Synthetic library $library',CURRENT_TIMESTAMP,'DIRECT');"
            statements += "INSERT INTO DIRECTORY_MAPPING(ID,EXTERNAL_PATH,INTERNAL_PATH) VALUES($id,'$libraryPath','$libraryPath');"
            statements += "INSERT INTO LIBRARY_DIRECTORIES(LIBRARY_ID,DIRECTORIES_ID) VALUES($id,$id);"
            repeat(count) { index ->
                val game = 30000 + library * 1000 + index
                val relative = "lib$library/game-${index.toString().padStart(3, '0')}"
                val directory = Files.createDirectory(sources.resolve(relative))
                val path = "$libraryPath/game-${index.toString().padStart(3, '0')}"
                for (version in listOf("1.0", "1.1")) {
                    val variant = Files.createDirectory(directory.resolve("Normal $version"))
                    Files.write(variant.resolve("game.bin"), "$game-$version\n".repeat(4096).toByteArray().copyOfRange(0, 32768), CREATE_NEW)
                    Files.writeString(variant.resolve("metadata.txt"), "variant=Normal\nversion=$version\ncontent.base.type=BASE\ncontent.base.path=game.bin\ncontent.base.required=true\n", CREATE_NEW)
                }
                for (name in listOf("base-a.bin", "base-b.bin", "patch.bin"))
                    Files.write(directory.resolve(name), "$game-$name\n".repeat(2048).toByteArray().copyOfRange(0, 16384), CREATE_NEW)
                val variant = game + 100000; val base = game * 2 + 200000
                statements += "INSERT INTO GAME(ID,CREATED_AT,TITLE,UPDATED_AT,PATH,FILE_SIZE,LIBRARY_ID,DOWNLOAD_COUNT,MATCH_CONFIRMED) VALUES($game,CURRENT_TIMESTAMP,'Synthetic game $game',CURRENT_TIMESTAMP,'$path',49152,$id,0,TRUE);"
                statements += "INSERT INTO GAME_VARIANT(ID,FILE_SIZE,IS_DEFAULT,IS_LATEST_FOR_VARIANT,NAME,PATH,VERSION,GAME_ID,SCAN_MANAGED,DEFAULT_LOCKED,LINK_STATUS) VALUES($variant,49152,TRUE,TRUE,'Normal','$path/Normal 1.0','1.0',$game,FALSE,TRUE,'DIRECT');"
                statements += "INSERT INTO VARIANT_CONTENT(ID,DEFAULT_SELECTED,FILE_SIZE,NAME,PATH,REQUIRED,TYPE,VARIANT_ID) VALUES($base,TRUE,32768,'Grouped base','$path/base-a.bin',TRUE,'BASE',$variant),(${base+1},FALSE,16384,'Optional patch','$path/patch.bin',FALSE,'PATCH',$variant);"
                statements += "INSERT INTO VARIANT_CONTENT_PATHS(VARIANT_CONTENT_ID,PATH_INDEX,PATH) VALUES($base,0,'$path/base-a.bin'),($base,1,'$path/base-b.bin'),(${base+1},0,'$path/patch.bin');"
                games += mapOf("gameId" to game, "variantId" to variant, "requiredContentId" to base, "optionalContentId" to base+1,
                    "path" to path, "requiredMembers" to listOf("$relative/base-a.bin", "$relative/base-b.bin"),
                    "optionalMembers" to listOf("$relative/patch.bin"),
                    "requiredArchiveMembers" to mapOf("Grouped base/base-a.bin" to "$relative/base-a.bin", "Grouped base/base-b.bin" to "$relative/base-b.bin"),
                    "optionalArchiveMembers" to mapOf("Optional patch.bin" to "$relative/patch.bin"))
            }
        }
        val hashes = inventory(sources)
        check(games.size == 132 && hashes.size == 924)
        Files.writeString(output.resolve("seed.sql"), statements.joinToString("\n", postfix = "\n"), CREATE_NEW)
        Files.writeString(output.resolve("manifest.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf(
            "scope" to "synthetic offline fixture", "libraryCounts" to listOf(104,27,1,0), "gameCount" to 132,
            "variantCount" to 132, "contentCount" to 264, "sourceFileCount" to 924, "sha256" to hashes, "games" to games))+"\n", CREATE_NEW)
    }
    @JvmStatic fun main(args: Array<String>) {
        require((args.size == 2 || args.size == 4) && args[0] == "--output")
        require(args.size == 2 || args[2] == "--container-fixture-root")
        generate(Path.of(args[1]), if (args.size == 4) args[3] else "/fixture")
    }
}
