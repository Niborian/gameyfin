package org.gameyfin.experiments.fixture.overlap;

import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;

public final class FixtureRendezvousTest {
    static Path leaf(Path parent, String suffix) throws Exception {
        return Files.createDirectory(parent.resolve("gameyfin-overlap-fixture-" + suffix));
    }
    public static void main(String[] args) throws Exception {
        Path parent = Files.createTempDirectory("gameyfin-overlap-local-");
        try {
            Path root = leaf(parent, "0000000000000001");
            var gate = new FixtureRendezvous(root, Duration.ofSeconds(2));
            if (gate.awaitRelease("fixture-31000")) throw new AssertionError("Wrong ID held");
            try (var executor = Executors.newSingleThreadExecutor()) {
                Future<Boolean> task = executor.submit(() -> gate.awaitRelease("fixture-33000"));
                long end = System.nanoTime() + Duration.ofSeconds(1).toNanos();
                while (!Files.exists(root.resolve("entered")) && System.nanoTime() < end) Thread.sleep(5);
                if (!Files.exists(root.resolve("entered")) || task.isDone()) throw new AssertionError("Not held");
                // A caller can perform browser work here; this test does not claim browser integration.
                // Publish only complete bytes, exactly like the real controller.
                // CREATE_NEW on the public name exposes an empty/partial marker
                // to the provider while writeString is still writing.
                Path pending = root.resolve("release.pending");
                Files.writeString(pending, "rel", StandardOpenOption.CREATE_NEW);
                // Deterministic partial-write regression: incomplete private bytes
                // must remain invisible to the running strict marker validator.
                Thread.sleep(30);
                if (Files.exists(root.resolve("release")) || task.isDone())
                    throw new AssertionError("Partial release exposed");
                Files.writeString(pending, "ease\n", StandardOpenOption.APPEND);
                try { Files.createLink(root.resolve("release"), pending); }
                finally { Files.delete(pending); }
                if (!task.get(2, TimeUnit.SECONDS) || !Files.exists(root.resolve("released"))
                        || !Files.exists(root.resolve("exited"))) throw new AssertionError("Release failed");
            }
            if (gate.awaitRelease("fixture-33000")) throw new AssertionError("Gate reused");
            Path timeoutRoot = leaf(parent, "0000000000000002");
            try {
                new FixtureRendezvous(timeoutRoot, Duration.ofMillis(30)).awaitRelease("fixture-33000");
                throw new AssertionError("Missing timeout");
            } catch (IllegalStateException expected) {
                if (!Files.exists(timeoutRoot.resolve("exited"))) throw new AssertionError("Missing final receipt");
            }
            try { new FixtureRendezvous(root, Duration.ofSeconds(21)); throw new AssertionError("Unbounded wait"); }
            catch (IllegalArgumentException expected) { }
            Path invalidRoot = leaf(parent, "0000000000000003");
            Files.writeString(invalidRoot.resolve("release"), "bad");
            try { new FixtureRendezvous(invalidRoot, Duration.ofSeconds(1)).awaitRelease("fixture-33000"); throw new AssertionError("Invalid release accepted"); }
            catch (IllegalStateException expected) { }
            System.out.println("PASS: exact ID, finite hold/release, timeout, invalid marker, one-use lifecycle");
        } finally {
            // Exact locally generated temporary parent only; no external fixture cleanup.
            try (var paths = Files.walk(parent)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
