package org.gameyfin.tools;

import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.io.*;

/** Offline only: never connects to the source database or launches an application. */
public final class OfflineH2Rehearsal {
    public static void main(String[] args) throws Exception {
        if (args.length != 7 || !args[0].equals("--offline-confirmed")) {
            throw new IllegalArgumentException("Usage: --offline-confirmed DB_DIR DATA_DIR BACKUP_DIR RESTORE_DIR DB_NAME IMAGE_REF");
        }
        String user = Objects.requireNonNull(System.getenv("GAMEYFIN_REHEARSAL_DB_USER"), "Set GAMEYFIN_REHEARSAL_DB_USER");
        String password = Objects.requireNonNull(System.getenv("GAMEYFIN_REHEARSAL_DB_PASSWORD"), "Set GAMEYFIN_REHEARSAL_DB_PASSWORD");
        Properties evidence = rehearse(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), args[5], args[6], user, password);
        System.out.println("Offline restore verified at " + evidence.getProperty("rehearsedAt") + "; duration " + evidence.getProperty("restoreDurationMillis") + " ms");
        for (String table : List.of("LIBRARY", "GAME", "GAME_VARIANT", "VARIANT_CONTENT")) {
            System.out.println(table + "=" + evidence.getProperty("count." + table));
        }
    }

    public static Properties rehearse(Path db, Path data, Path backup, Path restore, String dbName,
                                      String image, String user, String password) throws Exception {
        if (!dbName.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid database basename");
        if (!image.matches("(?:.*@)?sha256:[a-f0-9]{64}")) throw new IllegalArgumentException("Record the current immutable image digest or local image ID for rollback");
        db = sourceRoot(db); data = sourceRoot(data);
        backup = destination(backup); restore = destination(restore);
        List<Path> roots = List.of(db, data, backup, restore);
        for (int i = 0; i < roots.size(); i++) for (int j = i + 1; j < roots.size(); j++) {
            if (roots.get(i).startsWith(roots.get(j)) || roots.get(j).startsWith(roots.get(i))) {
                throw new IllegalArgumentException("Database, data, backup and restore roots must be isolated and non-overlapping");
            }
        }
        Path mv = db.resolve(dbName + ".mv.db");
        if (!Files.isRegularFile(mv) || Files.isSymbolicLink(mv)) throw new IllegalArgumentException("Missing regular H2 MVStore database");
        if (Files.exists(db.resolve(dbName + ".lock.db"))) throw new IllegalStateException("Database lock file exists; confirm the service is stopped");
        Properties evidence = new Properties();
        try (var channel = FileChannel.open(mv, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var lock = channel.tryLock();
            if (lock == null) throw new IllegalStateException("Source database is locked; stop the service before copying");
            try (lock) {
                Map<String, String> dbHashes = hashes(db, mv, channel), dataHashes = hashes(data);
                Files.createDirectory(backup);
                copyTree(db, backup.resolve("db"), mv, channel); copyTree(data, backup.resolve("data"));
                verify(dbHashes, backup.resolve("db")); verify(dataHashes, backup.resolve("data"));
                if (!dbHashes.equals(hashes(db, mv, channel))) throw new IllegalStateException("Source database inventory changed during backup");
                verify(dataHashes, data);
                dbHashes.forEach((path, hash) -> evidence.setProperty("sha256.db." + path, hash));
                dataHashes.forEach((path, hash) -> evidence.setProperty("sha256.data." + path, hash));
            }
        }
        // All JDBC operations happen on copies, after the offline source lock is released.
        Map<String, Long> expected = counts(backup.resolve("db").resolve(dbName), user, password);
        Map<String, String> backupDb = hashes(backup.resolve("db")), backupData = hashes(backup.resolve("data"));
        long started = System.nanoTime();
        Files.createDirectory(restore);
        copyTree(backup.resolve("db"), restore.resolve("db")); copyTree(backup.resolve("data"), restore.resolve("data"));
        verify(backupDb, restore.resolve("db")); verify(backupData, restore.resolve("data"));
        Map<String, Long> actual = counts(restore.resolve("db").resolve(dbName), user, password);
        if (!expected.equals(actual)) throw new IllegalStateException("Restored library counts do not match backup");
        verify(backupDb, restore.resolve("db")); verify(backupData, restore.resolve("data"));
        evidence.setProperty("rehearsedAt", Instant.now().toString());
        evidence.setProperty("restoreDurationMillis", Long.toString(Duration.ofNanos(System.nanoTime() - started).toMillis()));
        evidence.setProperty("rollbackImageRef", image);
        evidence.setProperty("evidenceScope", "offline supplied source; not evidence of a production backup unless explicitly performed on production backup copies");
        actual.forEach((table, count) -> evidence.setProperty("count." + table, count.toString()));
        try (var out = Files.newOutputStream(backup.resolve("rehearsal.properties"), StandardOpenOption.CREATE_NEW)) {
            evidence.store(out, "Gameyfin offline backup/restore evidence; contains no credentials");
        }
        return evidence;
    }

    private static Path sourceRoot(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path)) throw new IllegalArgumentException("Source must be a regular directory");
        return path.toRealPath();
    }
    private static Path destination(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Destination must not already exist");
        return absolute.getParent().toRealPath().resolve(absolute.getFileName());
    }
    private static Map<String, String> hashes(Path root) throws Exception {
        return hashes(root, null, null);
    }
    private static Map<String, String> hashes(Path root, Path locked, FileChannel channel) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (var stream = Files.walk(root)) {
            for (var paths = stream.iterator(); paths.hasNext();) {
                Path path = paths.next();
                if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("Symbolic links are not supported in backup roots");
                if (Files.isDirectory(path)) result.put(root.relativize(path).toString().replace('\\', '/') + "/", "directory");
                else if (Files.isRegularFile(path)) {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    if (path.equals(locked)) {
                        channel.position(0);
                        ByteBuffer buffer = ByteBuffer.allocate(65536);
                        while (channel.read(buffer) != -1) { buffer.flip(); digest.update(buffer); buffer.clear(); }
                    } else {
                        try (var in = Files.newInputStream(path)) {
                            byte[] buffer = new byte[65536]; int length;
                            while ((length = in.read(buffer)) != -1) digest.update(buffer, 0, length);
                        }
                    }
                    result.put(root.relativize(path).toString().replace('\\', '/'), HexFormat.of().formatHex(digest.digest()));
                } else throw new IllegalArgumentException("Unsupported file type in backup root");
            }
        }
        return result;
    }
    private static void copyTree(Path source, Path target) throws IOException {
        copyTree(source, target, null, null);
    }
    private static void copyTree(Path source, Path target, Path locked, FileChannel channel) throws IOException {
        try (var stream = Files.walk(source)) {
            for (var paths = stream.iterator(); paths.hasNext();) {
                Path path = paths.next();
                if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("Refusing symbolic link copy");
                Path to = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectory(to);
                else if (path.equals(locked)) {
                    // Windows enforces locks against other handles in this process too.
                    // Read through the already locked channel instead of opening the source again.
                    try (var out = FileChannel.open(to, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        channel.position(0); ByteBuffer buffer = ByteBuffer.allocate(65536);
                        while (channel.read(buffer) != -1) { buffer.flip(); while (buffer.hasRemaining()) out.write(buffer); buffer.clear(); }
                    }
                } else Files.copy(path, to, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }
    private static void verify(Map<String, String> expected, Path root) throws Exception {
        if (!expected.equals(hashes(root))) throw new IllegalStateException("Backup checksum or directory inventory mismatch");
    }
    private static Map<String, Long> counts(Path base, String user, String password) throws Exception {
        String url = "jdbc:h2:file:" + base.toAbsolutePath().toString().replace('\\', '/') + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r";
        Map<String, Long> result = new TreeMap<>();
        try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
            connection.setReadOnly(true);
            for (String table : List.of("LIBRARY", "GAME", "GAME_VARIANT", "VARIANT_CONTENT")) {
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) { rows.next(); result.put(table, rows.getLong(1)); }
            }
        }
        return result;
    }
}
