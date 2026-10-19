package io.auralis.server;

import static io.auralis.server.ApiModels.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CatalogService {
    static final long MAX_UPLOAD=300L*1024*1024;
    private static final Map<String,String> MIME=Map.of("mp3","audio/mpeg","flac","audio/flac","wav","audio/wav","ogg","audio/ogg","m4a","audio/mp4","aiff","audio/aiff","aif","audio/aiff");
    private final JdbcTemplate db;
    private final Path storage;
    private final CoverCache covers;
    private final String importRoot,admin;
    private final long quota;
    private final ConcurrentHashMap<String,Object> locks=new ConcurrentHashMap<>();
    CatalogService(JdbcTemplate db,@Value("${auralis.storage}") String root,@Value("${auralis.import-root:}") String importRoot,
                   @Value("${auralis.import-admin-user-id:}") String admin,@Value("${auralis.max-user-bytes:21474836480}") long quota) throws IOException {
        this.db=db;Files.createDirectories(Path.of(root));this.storage=Path.of(root).toRealPath();this.covers=new CoverCache(storage);this.importRoot=importRoot;this.admin=admin;this.quota=quota;
    }
    List<Track> list(String user,String query,int offset,int limit){
        if(offset<0||limit<1||limit>1000) throw ApiException.bad("limit: 1–1000; offset: от 0");
        String search=(query==null?"":query.trim().toLowerCase(Locale.ROOT));
        if(search.length()>300) throw ApiException.bad("Слишком длинный запрос");
        // SQL parameters and escaped LIKE wildcards keep search literal.
        search="%"+search.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        return db.query("SELECT * FROM tracks WHERE user_id=? AND (LOWER(title) LIKE ? ESCAPE '!' OR LOWER(artist) LIKE ? ESCAPE '!' OR LOWER(album) LIKE ? ESCAPE '!') ORDER BY LOWER(artist),LOWER(title),id LIMIT ? OFFSET ?",mapper(),user,search,search,search,limit,offset);
    }
    List<Track> all(String user){return db.query("SELECT * FROM tracks WHERE user_id=? ORDER BY id",mapper(),user);}
    Track require(String user,String id){return db.query("SELECT * FROM tracks WHERE user_id=? AND id=?",mapper(),user,id).stream().findFirst().orElseThrow(ApiException::missing);}
    CoverCache.Cover cover(String user,String id){
        var paths=db.queryForList("SELECT storage_path FROM tracks WHERE user_id=? AND id=?",String.class,user,id);if(paths.isEmpty())throw ApiException.missing();var cover=covers.find(user,id,paths.getFirst());if(cover==null)throw ApiException.missing();return cover;
    }
    Media media(String user,String id) throws IOException {
        var rows=db.query("SELECT * FROM tracks WHERE user_id=? AND id=?",(rs,n)->new Media(mapper().mapRow(rs,n),rs.getString("storage_path"),rs.getString("content_hash")),user,id);
        if(rows.isEmpty()) throw ApiException.missing();
        Media media=rows.getFirst();Path path=storage.resolve(media.path()).normalize();
        if(!path.startsWith(storage.resolve(user))||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!path.toRealPath().startsWith(storage)) throw ApiException.missing();
        return new Media(media.track(),path.toString(),media.hash());
    }
    Track upload(String user,String filename,InputStream input,Map<String,String> overrides) throws IOException {
        String ext=extension(filename);if(!MIME.containsKey(ext))throw ApiException.bad("Поддерживаются MP3, FLAC, WAV, OGG, M4A и AIFF");
        synchronized(locks.computeIfAbsent(user,k->new Object())) {
            Path folder=storage.resolve(user);Files.createDirectories(folder);
            if(!folder.toRealPath().startsWith(storage))throw ApiException.bad("Некорректное хранилище");
            Path temp=Files.createTempFile(folder,"upload-","."+ext);
            boolean keep=false;
            try {
                long bytes=copyBounded(input,temp);
                if(bytes==0)throw ApiException.bad("Пустой файл");
                String hash=digest(temp);
                var duplicates=db.query("SELECT * FROM tracks WHERE user_id=? AND content_hash=?",mapper(),user,hash);
                if(!duplicates.isEmpty())return duplicates.getFirst();
                Long used=db.queryForObject("SELECT COALESCE(SUM(size_bytes),0) FROM tracks WHERE user_id=?",Long.class,user);
                if(used+bytes>quota)throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"Достигнута квота музыкального хранилища");
                String title,artist,album,genre;double duration;
                try {
                    var audio=AudioFileIO.read(temp.toFile());Tag tag=audio.getTag();
                    title=field(tag,FieldKey.TITLE,stripExtension(filename));artist=field(tag,FieldKey.ARTIST,"Неизвестный исполнитель");
                    album=field(tag,FieldKey.ALBUM,"");genre=field(tag,FieldKey.GENRE,"");duration=audio.getAudioHeader().getTrackLength();
                } catch(Exception e) {throw ApiException.bad("Файл не распознан как поддерживаемое аудио");}
                title=override(overrides,"title",title,300);artist=override(overrides,"artist",artist,300);
                album=override(overrides,"album",album,300);genre=override(overrides,"genre",genre,100);
                String id=UUID.randomUUID().toString(),relative=user+"/"+id+"."+ext;
                Path destination=storage.resolve(relative);Files.move(temp,destination);
                try {
                    db.update("INSERT INTO tracks(id,user_id,title,artist,album,genre,duration_seconds,size_bytes,mime_type,storage_path,content_hash,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                        id,user,title,artist,album,genre,duration,bytes,MIME.get(ext),relative,hash,Timestamp.from(Instant.now()));
                } catch(RuntimeException e) {Files.deleteIfExists(destination);throw e;}
                keep=true;return require(user,id);
            } finally {if(!keep)Files.deleteIfExists(temp);}
        }
    }
    Map<String,Object> importCatalog(User user,String requested) throws IOException {
        if(importRoot.isBlank()||admin.isBlank()||!admin.equals(user.id()))throw new ApiException(HttpStatus.FORBIDDEN,"Импорт папок отключён для аккаунта");
        Path root=Path.of(importRoot).toRealPath(),path=root.resolve(requested).normalize().toRealPath();
        if(!path.startsWith(root))throw new ApiException(HttpStatus.FORBIDDEN,"Путь находится вне разрешённой папки");
        List<Track> imported=new ArrayList<>();List<String> rejected=new ArrayList<>();
        try(var paths=Files.walk(path)) {
            for(Path file:paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).limit(10001).toList()) {
                if(imported.size()+rejected.size()>=10000)throw ApiException.bad("Импортируйте не более 10000 файлов за один запрос");
                if(!MIME.containsKey(extension(file.getFileName().toString())))continue;
                if(!file.toRealPath().startsWith(root)){rejected.add(file.getFileName().toString());continue;}
                try(var in=Files.newInputStream(file)){imported.add(upload(user.id(),file.getFileName().toString(),in,Map.of()));}
                catch(ApiException e){rejected.add(file.getFileName()+": "+e.getMessage());}
            }
        }
        return Map.of("tracks",imported,"rejected",rejected);
    }
    @Transactional
    void deleteTrack(String user,String id) throws IOException {
        Media media=media(user,id);db.update("DELETE FROM tracks WHERE user_id=? AND id=?",user,id);Files.deleteIfExists(Path.of(media.path()));covers.remove(user,id);
    }
    void deleteAccount(String user) {
        synchronized(locks.computeIfAbsent(user,k->new Object())) {
            // Delete only files listed for the authenticated account; never recursively remove a supplied path.
            var ids=db.queryForList("SELECT id FROM tracks WHERE user_id=?",String.class,user);var files=db.queryForList("SELECT storage_path FROM tracks WHERE user_id=?",String.class,user);
            db.update("DELETE FROM accounts WHERE id=?",user);
            ids.forEach(id->covers.remove(user,id));
            for(String file:files) {Path p=storage.resolve(file).normalize();if(p.startsWith(storage.resolve(user)))try{Files.deleteIfExists(p);}catch(IOException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Orphaned media requires cleanup for account {}",user);}}
            try{Files.deleteIfExists(storage.resolve(user));}catch(IOException ignored){}
        }
    }
    RowMapper<Track> mapper(){return (rs,n)->{String id=rs.getString("id");boolean cover=covers.find(rs.getString("user_id"),id,rs.getString("storage_path"))!=null;return new Track(id,rs.getString("title"),rs.getString("artist"),rs.getString("album"),rs.getString("genre"),rs.getDouble("duration_seconds"),rs.getLong("size_bytes"),rs.getString("mime_type"),"SERVER",cover?"/api/v1/tracks/"+id+"/cover":null);};}
    private static String extension(String name){int dot=name==null?-1:name.lastIndexOf('.');return dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);}
    private static String stripExtension(String name){String base=Path.of(name).getFileName().toString();int i=base.lastIndexOf('.');return i<0?base:base.substring(0,i);}
    private static String field(Tag tag,FieldKey key,String fallback){if(tag==null)return fallback;String v=tag.getFirst(key);return v==null||v.isBlank()?fallback:v;}
    private static String override(Map<String,String> m,String key,String fallback,int max){String value=m.getOrDefault(key,fallback).strip().replace('\u0000',' ');if(value.length()>max)value=value.substring(0,max);return value.isBlank()&&key.equals("title")?"Без названия":value;}
    private static long copyBounded(InputStream input,Path target) throws IOException {long count=0;byte[] buffer=new byte[65536];try(var out=Files.newOutputStream(target)){int n;while((n=input.read(buffer))!=-1){count+=n;if(count>MAX_UPLOAD)throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"Максимальный размер файла — 300 МБ");out.write(buffer,0,n);}}return count;}
    private static String digest(Path file) throws IOException {try{MessageDigest hash=MessageDigest.getInstance("SHA-256");try(var in=new DigestInputStream(Files.newInputStream(file),hash)){in.transferTo(OutputStream.nullOutputStream());}return HexFormat.of().formatHex(hash.digest());}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    record Media(Track track,String path,String hash){}
}
