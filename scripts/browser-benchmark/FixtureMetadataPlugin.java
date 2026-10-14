package org.gameyfin.experiments.fixture;

import java.util.List;
import java.util.Set;
import org.gameyfin.pluginapi.core.wrapper.GameyfinPlugin;
import org.gameyfin.pluginapi.gamemetadata.*;
import org.pf4j.Extension;
import org.pf4j.PluginWrapper;

/** Staging-only deterministic provider. Never ship with ordinary Gameyfin. */
public final class FixtureMetadataPlugin extends GameyfinPlugin {
    public FixtureMetadataPlugin(PluginWrapper wrapper) { super(wrapper); }

    @Extension
    public static final class Provider implements GameMetadataProvider {
        public Set<Platform> getSupportedPlatforms() {
            return Set.of(Platform.PC_MICROSOFT_WINDOWS, Platform.LINUX);
        }
        public List<GameMetadata> fetchByTitle(String title, Set<? extends Platform> platforms, int maximum) {
            return List.of(); // No speculative creation or external searching.
        }
        public GameMetadata fetchById(String id) {
            if (id==null || !id.matches("fixture-[0-9]{5}")) return null;
            int game=Integer.parseInt(id.substring(8));
            if (!((game>=31000 && game<=31103) || (game>=32000 && game<=32026) || game==33000)) return null;
            return new GameMetadata(id,"Enriched fixture game "+game,
                Set.of(Platform.PC_MICROSOFT_WINDOWS,Platform.LINUX),
                "Deterministic enrichment for synthetic game "+game,
                null,null,null,null,null,Set.of("Fixture Studio"),Set.of("Fixture Publisher"),
                Set.of(Genre.ACTION),null,Set.of("synthetic-positive-provider"),null,null,null,null);
        }
    }
}
