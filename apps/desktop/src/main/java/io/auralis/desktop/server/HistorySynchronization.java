package io.auralis.desktop.server;

import com.fasterxml.jackson.databind.JsonNode;
import io.auralis.core.library.LibraryService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Stable event keys make a lost HTTP acknowledgement safe to retry. */
public final class HistorySynchronization {
    public record Transfer(int transferred,int unavailable){}
    private HistorySynchronization(){}
    public static Transfer push(ServerClient client,LibraryService library)throws Exception{
        String device;
        synchronized(library){device=library.setting("syncDeviceId","");if(device.isBlank()){device=UUID.randomUUID().toString();library.setSetting("syncDeviceId",device);}}
        int sent=0,unavailable=0;
        for(LibraryService.HistoryEntry event:library.history(100_000).reversed()){
            String remote=library.setting(key(client,"serverTrack",event.track().id()),"");
            if(remote.isBlank()||Boolean.parseBoolean(library.setting(key(client,"historySent",Long.toString(event.id())),"false")))continue;
            String eventId=UUID.nameUUIDFromBytes((client.identity()+"|"+device+"|"+event.id()).getBytes(StandardCharsets.UTF_8)).toString();
            try{
                JsonNode saved=client.request("history","POST",Map.of("eventId",eventId,"trackId",remote,"playedAt",event.playedAt().toString(),"playedSeconds",event.listenedSeconds(),"completed",event.listenedSeconds()>=event.track().durationSeconds()*.9,"skipped",event.skipped()));
                // A crash before historySent is harmless: the stable eventId retries safely.
                // Recording historySent first could let pull duplicate this original local row.
                library.setSetting(key(client,"historyReceived",saved.path("id").asText()),"true");library.setSetting(key(client,"historySent",Long.toString(event.id())),"true");sent++;
            }catch(ServerClient.ApiFailure e){if(e.status()==404){unavailable++;continue;}throw e;}
        }
        return new Transfer(sent,unavailable);
    }
    public static Transfer pull(ServerClient client,LibraryService library)throws Exception{
        int received=0,unavailable=0,offset=0;
        while(true){
            JsonNode page=client.request("history?limit=1000&offset="+offset,"GET",null);
            if(!page.isArray())throw new java.io.IOException("Некорректная история сервера");
            for(JsonNode event:page){
                String remoteEvent=event.path("id").asText(),local=library.setting(key(client,"localTrack",event.path("trackId").asText()),"");
                if(Boolean.parseBoolean(library.setting(key(client,"historyReceived",remoteEvent),"false")))continue;
                if(library.track(local).isEmpty()){unavailable++;continue;}
                if(library.importRemoteListen(client.identity(),remoteEvent,local,event.path("playedSeconds").asDouble(),event.path("skipped").asBoolean(),Instant.parse(event.path("playedAt").asText())))received++;
            }
            if(page.size()<1000)break;offset+=page.size();
        }
        return new Transfer(received,unavailable);
    }
    public static String key(ServerClient client,String kind,String id){return kind+"."+client.identity()+"."+id;}
}
