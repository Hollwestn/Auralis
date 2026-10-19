package io.auralis.server;

import static io.auralis.server.ApiModels.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LibraryService {
    private final JdbcTemplate db;
    private final CatalogService catalog;
    private final ObjectMapper json;
    LibraryService(JdbcTemplate db,CatalogService catalog,ObjectMapper json){this.db=db;this.catalog=catalog;this.json=json;}
    List<Track> favorites(String user){return db.query("SELECT t.* FROM tracks t JOIN favorites f ON f.track_id=t.id WHERE f.user_id=? ORDER BY LOWER(t.artist),LOWER(t.title)",catalog.mapper(),user);}
    void favorite(String user,String id,boolean enabled){catalog.require(user,id);if(enabled){try{db.update("INSERT INTO favorites(user_id,track_id) VALUES(?,?)",user,id);}catch(DuplicateKeyException ignored){}}else db.update("DELETE FROM favorites WHERE user_id=? AND track_id=?",user,id);}
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    List<Playlist> playlists(String user){return db.query("SELECT * FROM playlists WHERE user_id=? ORDER BY updated_at DESC",(rs,n)->new Playlist(rs.getString("id"),rs.getString("name"),db.queryForList("SELECT track_id FROM playlist_items WHERE playlist_id=? ORDER BY position",String.class,rs.getString("id")),rs.getTimestamp("updated_at").toInstant(),rs.getLong("version")),user);}
    @Transactional
    Playlist createPlaylist(String user,PlaylistInput input){String id=UUID.randomUUID().toString();db.update("INSERT INTO playlists(id,user_id,name,updated_at) VALUES(?,?,?,?)",id,user,input.name().strip(),Timestamp.from(Instant.now()));return replacePlaylist(user,id,input);}
    @Transactional
    Playlist replacePlaylist(String user,String id,PlaylistInput input){
        var versions=db.queryForList("SELECT version FROM playlists WHERE id=? AND user_id=? FOR UPDATE",Long.class,id,user);
        if(versions.isEmpty())throw ApiException.missing();
        long version=versions.getFirst();
        if(input.version()!=null&&input.version()!=version)throw new ApiException(HttpStatus.CONFLICT,"Плейлист изменён на другом устройстве. Получите свежую версию перед сохранением");
        List<String> tracks=input.trackIds()==null?List.of():input.trackIds();
        Set<String> owned=new HashSet<>(db.queryForList("SELECT id FROM tracks WHERE user_id=?",String.class,user));
        if(tracks.stream().anyMatch(t->!owned.contains(t)))throw ApiException.missing();
        Instant now=Instant.now();db.update("UPDATE playlists SET name=?,updated_at=?,version=version+1 WHERE id=? AND user_id=?",input.name().strip(),Timestamp.from(now),id,user);
        db.update("DELETE FROM playlist_items WHERE playlist_id=?",id);
        for(int i=0;i<tracks.size();i++)db.update("INSERT INTO playlist_items(playlist_id,track_id,position) VALUES(?,?,?)",id,tracks.get(i),i);
        return new Playlist(id,input.name().strip(),tracks,now,version+1);
    }
    void deletePlaylist(String user,String id){requirePlaylist(user,id);db.update("DELETE FROM playlists WHERE id=? AND user_id=?",id,user);}
    private void requirePlaylist(String user,String id){if(db.queryForObject("SELECT COUNT(*) FROM playlists WHERE id=? AND user_id=?",Integer.class,id,user)==0)throw ApiException.missing();}
    @Transactional
    History history(String user,HistoryInput input){
        Track track=catalog.require(user,input.trackId());
        if(!Double.isFinite(input.playedSeconds()))throw ApiException.bad("Некорректная длительность");
        if(track.durationSeconds()>0&&input.playedSeconds()>track.durationSeconds()+5)throw ApiException.bad("Прослушивание превышает длительность трека");
        Instant playedAt=input.playedAt()==null?Instant.now():input.playedAt();
        if(playedAt.isBefore(Instant.EPOCH)||playedAt.isAfter(Instant.now().plusSeconds(300)))throw ApiException.bad("Дата прослушивания должна быть после 1970 года и не более чем на 5 минут в будущем");
        playedAt=playedAt.truncatedTo(ChronoUnit.MICROS);
        if(input.eventId()!=null){
            // Serialize same-account ingestion so racing retries cannot duplicate an event.
            db.queryForObject("SELECT id FROM accounts WHERE id=? FOR UPDATE",String.class,user);
            var existing=db.query("SELECT * FROM listening_history WHERE user_id=? AND client_event_id=?",historyMapper(),user,input.eventId());
            if(!existing.isEmpty()){
                History saved=existing.getFirst();
                if(!saved.trackId().equals(input.trackId())||Double.compare(saved.playedSeconds(),input.playedSeconds())!=0||saved.completed()!=input.completed()||saved.skipped()!=input.skipped()||(input.playedAt()!=null&&!saved.playedAt().equals(playedAt)))
                    throw new ApiException(HttpStatus.CONFLICT,"eventId уже использован для другого события истории");
                return saved;
            }
        }
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO listening_history(id,user_id,track_id,played_seconds,completed,skipped,played_at,client_event_id) VALUES(?,?,?,?,?,?,?,?)",id,user,input.trackId(),input.playedSeconds(),input.completed(),input.skipped(),Timestamp.from(playedAt),input.eventId());
        return new History(id,input.trackId(),input.playedSeconds(),input.completed(),input.skipped(),playedAt,input.eventId());
    }
    List<History> history(String user,int limit,int offset){if(limit<1||limit>1000||offset<0)throw ApiException.bad("Некорректная пагинация");return db.query("SELECT * FROM listening_history WHERE user_id=? ORDER BY played_at DESC,id LIMIT ? OFFSET ?",historyMapper(),user,limit,offset);}
    private static org.springframework.jdbc.core.RowMapper<History> historyMapper(){return (rs,n)->new History(rs.getString("id"),rs.getString("track_id"),rs.getDouble("played_seconds"),rs.getBoolean("completed"),rs.getBoolean("skipped"),rs.getTimestamp("played_at").toInstant(),rs.getString("client_event_id"));}
    void clearHistory(String user){db.update("DELETE FROM listening_history WHERE user_id=?",user);}
    Preference preferences(String user){return db.query("SELECT genres_json,artists_json FROM preferences WHERE user_id=?",(rs,n)->new Preference(strings(rs.getString(1)),strings(rs.getString(2))),user).getFirst();}
    void preferences(String user,Preference p){db.update("UPDATE preferences SET genres_json=?,artists_json=? WHERE user_id=?",encode(p.genres()),encode(p.artists()),user);}
    @Transactional
    void feedback(String user,FeedbackInput input){
        Track track=catalog.require(user,input.trackId());String action=input.action().toUpperCase(Locale.ROOT);
        if(action.equals("BLOCK_ARTIST")){db.queryForObject("SELECT id FROM accounts WHERE id=? FOR UPDATE",String.class,user);if(db.queryForObject("SELECT COUNT(*) FROM blocked_artists WHERE user_id=? AND artist=?",Integer.class,user,track.artist())==0)db.update("INSERT INTO blocked_artists(user_id,artist) VALUES(?,?)",user,track.artist());return;}
        if(action.equals("UNBLOCK_ARTIST")){db.update("DELETE FROM blocked_artists WHERE user_id=? AND artist=?",user,track.artist());return;}
        if(!Set.of("LIKE","DISLIKE","MORE","LESS").contains(action))throw ApiException.bad("Неизвестная реакция");
        db.update("DELETE FROM feedback WHERE user_id=? AND track_id=?",user,track.id());
        db.update("INSERT INTO feedback(user_id,track_id,action,updated_at) VALUES(?,?,?,?)",user,track.id(),action,Timestamp.from(Instant.now()));
    }
    Sync sync(String user){return db.query("SELECT * FROM sync_state WHERE user_id=?",(rs,n)->new Sync(rs.getLong("version"),object(rs.getString("settings_json")),object(rs.getString("playback_json")),rs.getTimestamp("updated_at").toInstant()),user).getFirst();}
    @Transactional
    Sync sync(String user,SyncInput input){
        String settings=encode(input.settings()),playback=encode(input.playback());
        if(settings.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536||playback.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)throw ApiException.bad("Состояние превышает 64 КиБ UTF-8");
        Object track=input.playback().get("trackId");if(track!=null){if(!(track instanceof String id))throw ApiException.bad("Некорректный trackId");catalog.require(user,id);}
        Object position=input.playback().get("positionSeconds");if(position!=null&&(!(position instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<0))throw ApiException.bad("Некорректная позиция");
        Object queue=input.playback().get("queue");if(queue!=null){if(!(queue instanceof List<?> list)||list.size()>1000)throw ApiException.bad("Некорректная очередь");Set<String> owned=new HashSet<>(db.queryForList("SELECT id FROM tracks WHERE user_id=?",String.class,user));for(Object id:list)if(!owned.contains(id))throw ApiException.missing();}
        Object index=input.playback().get("queueIndex");
        if(index!=null){
            if(!(index instanceof Number number)||!Double.isFinite(number.doubleValue())||number.doubleValue()!=number.intValue()||!(queue instanceof List<?> items))throw ApiException.bad("queueIndex требует целочисленного индекса и очереди");
            int selected=number.intValue();
            if(items.isEmpty()){if(selected!=-1||track!=null)throw ApiException.bad("Пустая очередь требует queueIndex=-1 и trackId=null");}
            else if(selected<0||selected>=items.size()||!items.get(selected).equals(track))throw ApiException.bad("queueIndex должен указывать на текущий trackId");
        }
        for(String flag:List.of("playing","shuffle"))if(input.playback().containsKey(flag)&&!(input.playback().get(flag) instanceof Boolean))throw ApiException.bad(flag+" должен быть boolean");
        Object repeat=input.playback().get("repeat");if(repeat!=null&&(!(repeat instanceof String value)||!Set.of("OFF","ALL","ONE").contains(value.toUpperCase(Locale.ROOT))))throw ApiException.bad("repeat: OFF, ALL или ONE");
        int changed=db.update("UPDATE sync_state SET version=version+1,settings_json=?,playback_json=?,updated_at=? WHERE user_id=? AND version=?",settings,playback,Timestamp.from(Instant.now()),user,input.version());
        if(changed==0)throw new ApiException(HttpStatus.CONFLICT,"Состояние изменилось на другом устройстве. Загрузите /sync и повторите изменения");
        return sync(user);
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw ApiException.bad("Некорректный JSON");}}
    private List<String> strings(String value){try{return json.readValue(value,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException("Invalid persisted preferences",e);}}
    private Map<String,Object> object(String value){try{return json.readValue(value,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException("Invalid persisted state",e);}}
}
