package io.auralis.desktop.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackSynchronizationTest {
    @Test void preservesDuplicateQueueSelectionPositionAndModesAcrossDevices()throws Exception{
        Map<String,Object> original=Map.of("trackId","pc-a","queue",List.of("pc-a","pc-b","pc-a"),"queueIndex",2,"positionSeconds",42.75,"playing",false,"shuffle",true,"repeat","all");
        Map<String,String> mapping=Map.of("pc-a","server-a","pc-b","server-b");
        Map<String,Object> remote=PlaybackSynchronization.toServer(original,mapping::get,id->id);
        assertEquals(List.of("server-a","server-b","server-a"),remote.get("queue"));assertEquals(2,remote.get("queueIndex"));assertEquals("ALL",remote.get("repeat"));
        AtomicInteger resolutions=new AtomicInteger();
        var restored=PlaybackSynchronization.toLocal(new ObjectMapper().valueToTree(remote),id->{resolutions.incrementAndGet();return "phone-"+id;});
        assertEquals(2,resolutions.get(),"One file resolution per distinct track, while repeated queue entries are retained");
        assertEquals("phone-server-a",restored.path("localTrackId").asText());assertEquals(3,restored.path("localQueueIds").size());assertEquals(2,restored.path("queueIndex").asInt());assertEquals(42.75,restored.path("positionSeconds").asDouble());assertFalse(restored.path("playing").asBoolean());assertTrue(restored.path("shuffle").asBoolean());
    }
    @Test void localOnlyQueueEntryIsExplainedAndNeverSilentlyDropped(){
        var snapshot=Map.<String,Object>of("trackId","pc-a","queue",List.of("pc-a","private-b"),"queueIndex",0);
        IOException error=assertThrows(IOException.class,()->PlaybackSynchronization.toServer(snapshot,id->id.equals("pc-a")?"server-a":null,id->id.equals("private-b")?"Личная запись":id));
        assertTrue(error.getMessage().contains("Личная запись"));assertTrue(error.getMessage().contains("не отправлена"));assertEquals(List.of("pc-a","private-b"),snapshot.get("queue"));
    }
    @Test void rejectsInvalidIndexBeforeResolvingFiles(){
        var invalid=Map.<String,Object>of("trackId","a","queue",List.of("a","b"),"queueIndex",1);AtomicInteger resolutions=new AtomicInteger();
        assertThrows(IOException.class,()->PlaybackSynchronization.toLocal(new ObjectMapper().valueToTree(invalid),id->{resolutions.incrementAndGet();return id;}));assertEquals(0,resolutions.get());
    }
    @Test void supportsLegacyCurrentTrackSnapshotsAndEmptyPlayer()throws Exception{
        var remote=PlaybackSynchronization.toServer(Map.of("trackId","local-a","positionSeconds",12),id->"remote-a",id->id);
        assertEquals(List.of("remote-a"),remote.get("queue"));assertEquals(0,remote.get("queueIndex"));
        var empty=PlaybackSynchronization.toServer(Map.of("trackId","","queue",List.of(),"queueIndex",-1),id->null,id->id);
        assertNull(empty.get("trackId"));assertEquals(List.of(),empty.get("queue"));assertEquals(-1,empty.get("queueIndex"));
    }
    @Test void refusesForeignUnavailableQueueWithoutProducingAResumeSnapshot(){
        var remote=Map.<String,Object>of("trackId","available","queue",List.of("available","foreign"),"queueIndex",0);
        assertThrows(IOException.class,()->PlaybackSynchronization.toLocal(new ObjectMapper().valueToTree(remote),id->{if(id.equals("foreign"))throw new IOException("404");return "local-a";}));
    }
}
