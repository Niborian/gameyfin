package org.gameyfin.experiments.fixture.overlap;

import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test-provider helper only. Never a production plugin or benchmark default. */
public final class FixtureRendezvous {
    private final Path root;
    private final Duration timeout;
    private final AtomicBoolean used = new AtomicBoolean();

    public FixtureRendezvous(Path root, Duration timeout) throws Exception {
        if (!root.getFileName().toString().matches("gameyfin-overlap-fixture-[a-f0-9]{16}"))
            throw new IllegalArgumentException("Exact generated fixture leaf required");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(20)) > 0)
            throw new IllegalArgumentException("Bounded fixture deadline required");
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                || !normalized.toRealPath().equals(normalized))
            throw new IllegalArgumentException("Existing non-symlink fixture root required");
        this.root = normalized;
        this.timeout = timeout;
    }

    /** The provider calls this only for fixture-33000. All other IDs are unchanged. */
    public boolean awaitRelease(String originalId) throws Exception {
        if (!"fixture-33000".equals(originalId) || !used.compareAndSet(false, true)) return false;
        long start = System.nanoTime();
        // CREATE_NEW rejects reused receipts and symlink targets; never overwrite.
        receipt("entered");
        try {
            while (System.nanoTime() - start < timeout.toNanos()) {
                Path release = root.resolve("release");
                if (Files.exists(release, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(release, LinkOption.NOFOLLOW_LINKS)
                            || Files.size(release) != 8 || !Files.readString(release).equals("release\n"))
                        throw new IllegalStateException("Invalid fixture release marker");
                    receipt("released");
                    return true;
                }
                Thread.sleep(10);
            }
            throw new IllegalStateException("Fixture waiting-provider deadline exceeded");
        } finally {
            receipt("exited");
        }
    }

    private void receipt(String name) throws Exception {
        Path pending = root.resolve(name + ".pending");
        Files.writeString(pending, "waiting-provider-v1\n" + System.nanoTime() + "\n", StandardOpenOption.CREATE_NEW);
        if (Files.getFileStore(root).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(pending, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        try { Files.createLink(root.resolve(name), pending); }
        finally { Files.delete(pending); }
    }
}
