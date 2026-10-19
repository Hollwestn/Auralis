package io.auralis.desktop.server;

import com.fasterxml.jackson.databind.JsonNode;
import io.auralis.core.library.LibraryService;
import io.auralis.core.model.Track;
import java.util.*;

/** Three-way boolean merge; an untouched PC cache cannot erase a newer phone favorite. */
public final class FavoriteSynchronization {
    public record Transfer(int changed,int unavailable){}
    private FavoriteSynchronization(){}
    public static Transfer push(ServerClient client,LibraryService library)throws Exception{
        Set<String> remote=ids(client.request("favorites","GET",null)),live=liveTracks(client);int changed=0,unavailable=0;
        for(Track track:library.tracks()){
            String id=library.setting(key(client,"serverTrack",track.id()),"");if(id.isBlank())continue;
            if(!live.contains(id)){unavailable++;continue;}
            boolean local=library.isFavorite(track.id()),server=remote.contains(id);String baseline=library.setting(key(client,"favoriteBaseline",track.id()),"");
            boolean desired=baseline.isBlank()?local||server:local==Boolean.parseBoolean(baseline)?server:local;
            if(desired!=server){try{client.request("favorites/"+id,desired?"PUT":"DELETE",null);changed++;}catch(ServerClient.ApiFailure e){if(e.status()==404){unavailable++;continue;}throw e;}}
            library.setFavorite(track.id(),desired);library.setSetting(key(client,"favoriteBaseline",track.id()),Boolean.toString(desired));
        }
        return new Transfer(changed,unavailable);
    }
    public static int pull(ServerClient client,LibraryService library,JsonNode favorites)throws Exception{
        Set<String> remote=ids(favorites),live=liveTracks(client);int changed=0;
        for(Track track:library.tracks()){
            String id=library.setting(key(client,"serverTrack",track.id()),"");if(id.isBlank())continue;
            if(!live.contains(id))continue;
            boolean local=library.isFavorite(track.id()),server=remote.contains(id);String baseline=library.setting(key(client,"favoriteBaseline",track.id()),"");
            boolean pending=baseline.isBlank()?local:local!=Boolean.parseBoolean(baseline);
            boolean desired=pending?local:server;
            if(desired!=local){library.setFavorite(track.id(),desired);changed++;}
            library.setSetting(key(client,"favoriteBaseline",track.id()),Boolean.toString(server));
        }
        return changed;
    }
    public static void acknowledge(ServerClient client,LibraryService library,String localId,boolean value){library.setFavorite(localId,value);library.setSetting(key(client,"favoriteBaseline",localId),Boolean.toString(value));}
    private static String key(ServerClient client,String kind,String id){return HistorySynchronization.key(client,kind,id);}
    private static Set<String> ids(JsonNode tracks){Set<String> ids=new HashSet<>();tracks.forEach(t->ids.add(t.path("id").asText()));return ids;}
    private static Set<String> liveTracks(ServerClient client)throws Exception{Set<String> live=new HashSet<>();for(int offset=0;;offset+=1000){JsonNode page=client.request("tracks?limit=1000&offset="+offset,"GET",null);if(!page.isArray())throw new java.io.IOException("Некорректный каталог сервера");live.addAll(ids(page));if(page.size()<1000)return live;}}
}
