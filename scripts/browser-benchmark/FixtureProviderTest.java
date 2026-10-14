package org.gameyfin.experiments.fixture;
import java.util.Set;
import org.gameyfin.pluginapi.gamemetadata.Platform;

public final class FixtureProviderTest {
    public static void main(String[] args) {
        var provider=new FixtureMetadataPlugin.Provider(); int count=0;
        for(int id=30000;id<34000;id++) {
            var metadata=provider.fetchById("fixture-"+id);
            if(metadata==null) continue;
            count++;
            if(!metadata.getTitle().equals("Enriched fixture game "+id)) throw new AssertionError("title");
            if(!metadata.getPlatforms().contains(Platform.PC_MICROSOFT_WINDOWS)) throw new AssertionError("platform");
            if(metadata.getCoverUrls()!=null || metadata.getHeaderUrls()!=null || metadata.getScreenshotUrls()!=null || metadata.getVideoUrls()!=null) throw new AssertionError("media");
            if(!metadata.equals(provider.fetchById("fixture-"+id))) throw new AssertionError("determinism");
        }
        if(count!=132) throw new AssertionError("count");
        for(String id:new String[]{"fixture-99999","fixture-31000/../","31000","https://example.invalid","fixture-031000","fixture-31104","fixture-32027","fixture-33001"})
            if(provider.fetchById(id)!=null) throw new AssertionError("invalid ID");
        if(provider.fetchById(null)!=null || !provider.fetchByTitle("fixture",Set.of(),1).isEmpty()) throw new AssertionError("fallback");
        System.out.println("Fixture provider assertions passed: 132 exact IDs, deterministic metadata, no media URLs.");
    }
}
