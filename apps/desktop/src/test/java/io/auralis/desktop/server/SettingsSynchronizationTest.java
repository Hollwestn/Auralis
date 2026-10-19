package io.auralis.desktop.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.auralis.core.library.LibraryService;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SettingsSynchronizationTest {
    @TempDir Path data;
    @Test void removedSceneFromAnOlderDeviceMigratesWithoutLosingOtherPreferences() throws Exception {
        try(var library=new LibraryService(data)) {
            library.setSetting("visual.mode","LIQUID_CORE");library.setSetting("volume","0.7");
            var outgoing=SettingsSynchronization.toServer(library,Map.of("customPreference","keep"));
            assertEquals("BLACK_HOLE",outgoing.get("visualMode"));assertEquals("keep",outgoing.get("customPreference"));
            SettingsSynchronization.applyRemote(library,new ObjectMapper().valueToTree(Map.of("visualMode","AMBIENT_FLOW","visualIntensity",1.2)));
            assertEquals("BLACK_HOLE",library.setting("visual.mode",""));assertEquals("0.7",library.setting("volume",""));assertEquals("1.2",library.setting("visual.intensity",""));
        }
    }
    @Test void newSceneRoundTripsAndUnknownRemoteSceneDoesNotReplaceIt() throws Exception {
        try(var library=new LibraryService(data)) {
            var mapper=new ObjectMapper();
            SettingsSynchronization.applyRemote(library,mapper.valueToTree(Map.of("visualMode","OCEAN_CAUSTICS")));
            assertEquals("OCEAN_CAUSTICS",SettingsSynchronization.toServer(library,Map.of()).get("visualMode"));
            SettingsSynchronization.applyRemote(library,mapper.valueToTree(Map.of("visualMode","UNKNOWN_FUTURE_SCENE")));
            assertEquals("OCEAN_CAUSTICS",library.setting("visual.mode",""));
            library.setSetting("visual.mode","invalid");
            assertEquals("BLACK_HOLE",SettingsSynchronization.toServer(library,Map.of()).get("visualMode"));
        }
    }
    @Test void visualSpeedsDefaultSafelyAndRoundTripIndependently() throws Exception {
        try(var library=new LibraryService(data)) {
            var defaults=SettingsSynchronization.toServer(library,Map.of());
            assertEquals(1.,defaults.get("visualIdleSpeed"));assertEquals(.75,defaults.get("visualPlayingSpeed"));
            SettingsSynchronization.applyRemote(library,new ObjectMapper().valueToTree(Map.of("visualIdleSpeed",1.5,"visualPlayingSpeed",.5)));
            var restored=SettingsSynchronization.toServer(library,Map.of());
            assertEquals(1.5,restored.get("visualIdleSpeed"));assertEquals(.5,restored.get("visualPlayingSpeed"));
            SettingsSynchronization.applyRemote(library,new ObjectMapper().valueToTree(Map.of("visualIdleSpeed",-1,"visualPlayingSpeed",5)));
            assertEquals(restored,SettingsSynchronization.toServer(library,Map.of()));
            library.setSetting("visual.idleSpeed","NaN");library.setSetting("visual.playingSpeed","Infinity");
            var recovered=SettingsSynchronization.toServer(library,Map.of());
            assertEquals(1.,recovered.get("visualIdleSpeed"));assertEquals(.75,recovered.get("visualPlayingSpeed"));
        }
    }

}
