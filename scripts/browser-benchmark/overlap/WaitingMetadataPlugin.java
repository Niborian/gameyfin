package org.gameyfin.experiments.fixture.overlap;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.gameyfin.pluginapi.core.wrapper.GameyfinPlugin;
import org.gameyfin.pluginapi.gamemetadata.*;
import org.gameyfin.experiments.fixture.FixtureMetadataPlugin;
import org.pf4j.Extension;
import org.pf4j.PluginWrapper;

/** Separate opt-in fixture only; original throughput provider source stays unchanged. */
public final class WaitingMetadataPlugin extends GameyfinPlugin {
    public WaitingMetadataPlugin(PluginWrapper wrapper) { super(wrapper); }

    @Extension
    public static final class Provider implements GameMetadataProvider {
        private final FixtureMetadataPlugin.Provider delegate = new FixtureMetadataPlugin.Provider();
        private final FixtureRendezvous gate;
        public Provider() throws Exception {
            String root = System.getProperty("gameyfin.fixture.waitingRoot");
            if (root == null) throw new IllegalStateException("Explicit waiting-fixture root required");
            gate = new FixtureRendezvous(Path.of(root), Duration.ofSeconds(20));
        }
        public Set<Platform> getSupportedPlatforms() { return delegate.getSupportedPlatforms(); }
        public List<GameMetadata> fetchByTitle(String title, Set<? extends Platform> platforms, int maximum) {
            return delegate.fetchByTitle(title, platforms, maximum);
        }
        public GameMetadata fetchById(String id) {
            try { gate.awaitRelease(id); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Waiting fixture interrupted", interrupted);
            } catch (Exception failure) { throw new IllegalStateException("Waiting fixture failed", failure); }
            return delegate.fetchById(id);
        }
    }
}
