package org.gameyfin.tools

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.Properties

/** Offline source lock/copy/hash; all JDBC operations use copies, never the source database. */
object OfflineH2Rehearsal {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 7 && args[0] == "--offline-confirmed")
        val evidence = rehearse(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), args[5], args[6],
            requireNotNull(System.getenv("GAMEYFIN_REHEARSAL_DB_USER")), requireNotNull(System.getenv("GAMEYFIN_REHEARSAL_DB_PASSWORD")))
        println("Offline restore verified at ${evidence.getProperty("rehearsedAt")}; duration ${evidence.getProperty("restoreDurationMillis")} ms")
        for (table in listOf("LIBRARY", "GAME", "GAME_VARIANT", "VARIANT_CONTENT")) println("$table=${evidence.getProperty("count.$table")}")
    }
    @JvmStatic fun rehearse(dbInput: Path, dataInput: Path, backupInput: Path, restoreInput: Path, dbName: String,
                           image: String, user: String, password: String): Properties {
        require(dbName.matches(Regex("[A-Za-z0-9_-]+")))
        require(image.matches(Regex("(?:.*@)?sha256:[a-f0-9]{64}"))) { "Record immutable rollback image" }
        fun source(path: Path): Path { require(!Files.isSymbolicLink(path) && Files.isDirectory(path)); return path.toRealPath() }
        fun destination(path: Path): Path {
            val absolute = path.toAbsolutePath().normalize()
            require(!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS))
            return absolute.parent.toRealPath().resolve(absolute.fileName)
        }
        val db = source(dbInput); val data = source(dataInput)
        val backup = destination(backupInput); val restore = destination(restoreInput)
        val roots = listOf(db, data, backup, restore)
        for (i in roots.indices) for (j in i+1 until roots.size)
            require(!roots[i].startsWith(roots[j]) && !roots[j].startsWith(roots[i])) { "Isolated non-overlapping roots required" }
        val mv = db.resolve("$dbName.mv.db")
        require(Files.isRegularFile(mv) && !Files.isSymbolicLink(mv))
        check(!Files.exists(db.resolve("$dbName.lock.db"))) { "Stop service before copying" }
        val evidence = Properties()
        FileChannel.open(mv, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            val lock = checkNotNull(channel.tryLock()) { "Source database is locked" }
            lock.use {
                val dbHashes = hashes(db, mv, channel); val dataHashes = hashes(data)
                Files.createDirectory(backup)
                copyTree(db, backup.resolve("db"), mv, channel); copyTree(data, backup.resolve("data"))
                verify(dbHashes, backup.resolve("db")); verify(dataHashes, backup.resolve("data"))
                check(dbHashes == hashes(db, mv, channel)); verify(dataHashes, data)
                dbHashes.forEach { (path, hash) -> evidence.setProperty("sha256.db.$path", hash) }
                dataHashes.forEach { (path, hash) -> evidence.setProperty("sha256.data.$path", hash) }
            }
        }
        val dbHashes = hashes(backup.resolve("db")); val dataHashes = hashes(backup.resolve("data"))
        val expected = counts(backup.resolve("db").resolve(dbName), user, password)
        verify(dbHashes, backup.resolve("db")); verify(dataHashes, backup.resolve("data"))
        val started = System.nanoTime()
        Files.createDirectory(restore)
        copyTree(backup.resolve("db"), restore.resolve("db")); copyTree(backup.resolve("data"), restore.resolve("data"))
        verify(dbHashes, restore.resolve("db")); verify(dataHashes, restore.resolve("data"))
        val actual = counts(restore.resolve("db").resolve(dbName), user, password)
        check(expected == actual) { "Restored library counts do not match backup" }
        verify(dbHashes, restore.resolve("db")); verify(dataHashes, restore.resolve("data"))
        evidence.setProperty("rehearsedAt", Instant.now().toString())
        evidence.setProperty("restoreDurationMillis", Duration.ofNanos(System.nanoTime()-started).toMillis().toString())
        evidence.setProperty("rollbackImageRef", image)
        evidence.setProperty("evidenceScope", "offline supplied source; not evidence of a production backup unless explicitly performed on production backup copies")
        actual.forEach { (table,count) -> evidence.setProperty("count.$table", count.toString()) }
        Files.newOutputStream(backup.resolve("rehearsal.properties"), StandardOpenOption.CREATE_NEW).use {
            evidence.store(it, "Gameyfin offline backup/restore evidence; contains no credentials")
        }
        return evidence
    }
    private fun hashes(root: Path, locked: Path? = null, channel: FileChannel? = null): Map<String,String> {
        val output = sortedMapOf<String,String>()
        Files.walk(root).use { paths -> paths.forEach { path ->
            require(!Files.isSymbolicLink(path)) { "Symbolic links unsupported" }
            val relative = root.relativize(path).toString().replace('\\','/')
            if (Files.isDirectory(path)) output["$relative/"] = "directory"
            else if (Files.isRegularFile(path)) {
                output[relative] = if (path == locked) {
                    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteBuffer.allocate(65536)
                    requireNotNull(channel).position(0)
                    while (channel.read(buffer) != -1) { buffer.flip(); digest.update(buffer); buffer.clear() }
                    HexFormat.of().formatHex(digest.digest())
                } else ScanFixture.sha(path)
            } else error("Unsupported file type")
        } }
        return output
    }
    private fun copyTree(source: Path, target: Path, locked: Path? = null, channel: FileChannel? = null) {
        Files.walk(source).use { paths -> paths.forEach { path ->
            require(!Files.isSymbolicLink(path))
            val to = target.resolve(source.relativize(path))
            if (Files.isDirectory(path)) Files.createDirectory(to)
            else if (path == locked) FileChannel.open(to, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                requireNotNull(channel).position(0); val buffer = ByteBuffer.allocate(65536)
                while (channel.read(buffer) != -1) { buffer.flip(); while (buffer.hasRemaining()) out.write(buffer); buffer.clear() }
            } else Files.copy(path, to, StandardCopyOption.COPY_ATTRIBUTES)
        } }
    }
    private fun verify(expected: Map<String,String>, root: Path) = check(expected == hashes(root)) { "Backup checksum/directory mismatch" }
    private fun counts(base: Path, user: String, password: String): Map<String,Long> {
        val url = "jdbc:h2:file:${base.toAbsolutePath().toString().replace('\\','/')};IFEXISTS=TRUE;ACCESS_MODE_DATA=r"
        return DriverManager.getConnection(url,user,password).use { connection ->
            connection.isReadOnly = true
            connection.createStatement().use { statement ->
                listOf("LIBRARY","GAME","GAME_VARIANT","VARIANT_CONTENT").associateWith { table ->
                    statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows -> check(rows.next()); rows.getLong(1) }
                }.toSortedMap()
            }
        }
    }
}
