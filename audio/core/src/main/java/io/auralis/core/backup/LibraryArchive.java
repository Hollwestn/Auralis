package io.auralis.core.backup;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.auralis.core.library.LibraryService;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

/** Portable ZIP format shared with Android/iOS. No credentials or executable content. */
public final class LibraryArchive {
    public static final ObjectMapper JSON = new ObjectMapper();
    public static final long MAX_BYTES = 64L * 1024 * 1024 * 1024;
    private static final int MANIFEST_LIMIT = 16 * 1024 * 1024;
    public record Preview(Path archive, ObjectNode manifest, int tracks, int playlists, int favorites, int history, long audioBytes, int missingAudio, int conflicts) { }
    private LibraryArchive() {}
    public static boolean portableSetting(String key) {
        return key.matches("(theme|reducedMotion|advanced|language|volume|genres|artists|preferences\\.(genres|artists)|visual\\.[A-Za-z]+|background\\.enabled|animations\\.enabled|eq\\.((saved\\.)?[0-9]+|enabled)|audio\\.[A-Za-z]+|albumTransition\\.[a-f0-9]+)");
    }
    public static String hash(Path source) throws IOException {
        try { MessageDigest digest=MessageDigest.getInstance("SHA-256"); try(InputStream in=Files.newInputStream(source)){byte[] buffer=new byte[131072];int n;while((n=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Операция отменена");digest.update(buffer,0,n);}}return HexFormat.of().formatHex(digest.digest()); }
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static void write(LibraryService library, Path destination, boolean audio, Set<String> selection) throws IOException {
        write(library,destination,audio,selection,Set.of());
    }
    public static void write(LibraryService library,Path destination,boolean audio,Set<String> selection,Set<String> playlistSelection)throws IOException {
        ObjectNode manifest=library.backupSnapshot(); manifest.put("format","auralis.backup");manifest.put("version",1);manifest.put("createdAt",Instant.now().toString());
        if(selection!=null){ArrayNode chosen=JSON.createArrayNode();manifest.withArray("tracks").forEach(t->{if(selection.contains(t.path("id").asText()))chosen.add(t);});manifest.set("tracks",chosen);manifest.set("favorites",JSON.createArrayNode());ArrayNode lists=JSON.createArrayNode();for(JsonNode p:manifest.path("playlists"))if(playlistSelection.contains(p.path("id").asText())){ObjectNode value=(ObjectNode)p.deepCopy();ArrayNode ids=value.putArray("trackIds");p.path("trackIds").forEach(id->{if(selection.contains(id.asText()))ids.add(id.asText());});lists.add(value);}manifest.set("playlists",lists);manifest.set("history",JSON.createArrayNode());manifest.set("settings",JSON.createObjectNode());manifest.set("playback",JSON.createObjectNode());}
        Map<String,Path> assets=new LinkedHashMap<>();
        if(selection!=null)manifest.putObject("feedback");
        for(JsonNode node:manifest.withArray("tracks")){
            ObjectNode track=(ObjectNode)node;Path source=Path.of(track.path("originalPath").asText());
            if(audio&&Files.isRegularFile(source)){String digest=hash(source);track.put("contentHash",digest);String extension=extension(source,"audio");String name="audio/"+digest+"."+extension;track.set("audio",asset(name,digest,Files.size(source)));assets.put(name,source);}
            String art=track.path("originalArtwork").asText();track.remove("originalArtwork");
            if(!art.isBlank()&&Files.isRegularFile(Path.of(art))){Path cover=Path.of(art);String digest=hash(cover),name="artwork/"+digest+"."+extension(cover,"png");track.set("cover",asset(name,digest,Files.size(cover)));assets.put(name,cover);}
            if(selection!=null)track.remove("originalPath");
        }
        byte[] json=JSON.writeValueAsBytes(manifest);if(json.length>MANIFEST_LIMIT)throw new IOException("Слишком большая медиатека для одного архива");
        Path target=destination.toAbsolutePath().normalize();Files.createDirectories(target.getParent());Path temp=Files.createTempFile(target.getParent(),".auralis-backup-",".part");
        try{
            try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))){zip.setLevel(0);zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(json);zip.closeEntry();for(var entry:assets.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));try(InputStream input=Files.newInputStream(entry.getValue())){input.transferTo(zip);}zip.closeEntry();}}
            preview(temp,library);move(temp,target);
        }finally{Files.deleteIfExists(temp);}
    }
    private static ObjectNode asset(String name,String hash,long bytes){return JSON.createObjectNode().put("entry",name).put("sha256",hash).put("size",bytes);}
    private static String extension(Path source,String fallback){String name=source.getFileName().toString();int dot=name.lastIndexOf('.');String ext=dot<0?fallback:name.substring(dot+1).toLowerCase(Locale.ROOT);return ext.matches("[a-z0-9]{1,8}")?ext:fallback;}
    public static Preview preview(Path archive,LibraryService library)throws IOException{
        try(ZipFile zip=new ZipFile(archive.toFile())){
            Set<String> names=new HashSet<>();long total=0;var entries=zip.entries();while(entries.hasMoreElements()){ZipEntry entry=entries.nextElement();if(entry.isDirectory()||!names.add(entry.getName())||!(entry.getName().equals("manifest.json")||entry.getName().matches("(audio|artwork)/[a-f0-9]{64}\\.[a-z0-9]{1,8}")))throw new IOException("Недопустимый или повторяющийся файл в архиве");if(entry.getSize()<0||entry.getSize()>MAX_BYTES-total)throw new IOException("Архив превышает допустимый размер");total+=entry.getSize();if(names.size()>200001)throw new IOException("Слишком много файлов в архиве");}
            ZipEntry entry=zip.getEntry("manifest.json");if(entry==null||entry.getSize()>MANIFEST_LIMIT)throw new IOException("Нет корректного manifest.json");
            ObjectNode manifest;try(InputStream in=zip.getInputStream(entry)){JsonNode parsed=JSON.readTree(in.readNBytes(MANIFEST_LIMIT+1));validate(parsed);manifest=(ObjectNode)parsed;}
            long bytes=0;int missing=0,conflicts=0;Set<String> referenced=new HashSet<>(Set.of("manifest.json"));
            for(JsonNode track:manifest.path("tracks")){
                for(String key:List.of("audio","cover")){JsonNode asset=track.path(key);if(asset.isMissingNode()||asset.isNull())continue;String name=asset.path("entry").asText();ZipEntry content=zip.getEntry(name);if(!name.startsWith(key.equals("audio")?"audio/":"artwork/")||!name.contains(asset.path("sha256").asText())||!asset.path("sha256").asText().matches("[a-f0-9]{64}")||content==null||content.getSize()!=asset.path("size").asLong(-1))throw new IOException("Не совпадает описание файла архива");referenced.add(name);if(key.equals("audio"))bytes+=content.getSize();}
                if(!track.hasNonNull("audio")&&!Files.isRegularFile(safeOriginal(track.path("originalPath").asText())))missing++;
                if(library!=null&&library.trackByHash(track.path("contentHash").asText()).isPresent())conflicts++;
            }
            if(!referenced.equals(names))throw new IOException("Архив содержит неописанные файлы");
            return new Preview(archive.toAbsolutePath(),manifest,manifest.path("tracks").size(),manifest.path("playlists").size(),manifest.path("favorites").size(),manifest.path("history").size(),bytes,missing,conflicts);
        }catch(IllegalArgumentException e){throw new IOException("Некорректный архив Auralis",e);}
    }
    public static void validate(JsonNode root)throws IOException{
        if(!root.isObject()||!root.path("format").asText().equals("auralis.backup")||root.path("version").asInt()!=1)throw new IOException("Неизвестный формат или версия резервной копии");
        for(String field:List.of("tracks","favorites","playlists","history"))if(!root.path(field).isArray()||root.path(field).size()>100000)throw new IOException("Некорректный раздел "+field);
        Set<String> ids=new HashSet<>();for(JsonNode track:root.path("tracks")){String id=track.path("id").asText();if(id.isBlank()||id.length()>200||!ids.add(id)||!track.path("contentHash").asText().matches("[a-f0-9]{64}")||!Double.isFinite(track.path("durationSeconds").asDouble())||track.path("durationSeconds").asDouble()<0)throw new IOException("Повреждена запись трека");for(String field:List.of("title","artist","album","genre"))if(!track.path(field).isTextual()||track.path(field).asText().length()>4096)throw new IOException("Некорректные метаданные");}
        for(JsonNode id:root.path("favorites"))if(!ids.contains(id.asText()))throw new IOException("Неизвестный трек в избранном");
        Set<String> playlists=new HashSet<>();for(JsonNode p:root.path("playlists")){if(!p.path("id").isTextual()||!playlists.add(p.path("id").asText())||p.path("name").asText().isBlank()||p.path("name").asText().length()>200||!p.path("trackIds").isArray()||p.path("trackIds").size()>100000)throw new IOException("Некорректный плейлист");for(JsonNode id:p.path("trackIds"))if(!ids.contains(id.asText()))throw new IOException("Неизвестный трек плейлиста");}
        for(JsonNode h:root.path("history"))if(!ids.contains(h.path("trackId").asText())||!h.path("playedAt").isTextual()||!Double.isFinite(h.path("playedSeconds").asDouble())||h.path("playedSeconds").asDouble()<0)throw new IOException("Некорректная история");
    }
    private static Path safeOriginal(String value){try{return value.isBlank()?Path.of("__auralis_missing__"):Path.of(value);}catch(InvalidPathException ignored){return Path.of("__auralis_missing__");}}
    public static void restore(LibraryService library,Path archive,boolean replace)throws IOException{
        Preview preview=preview(archive,library);Map<String,Path> audio=new HashMap<>(),covers=new HashMap<>();Path directory=library.dataDirectory().resolve("restored");Files.createDirectories(directory);
        if(Files.getFileStore(directory).getUsableSpace()<preview.audioBytes()+32L*1024*1024)throw new IOException("Недостаточно места для восстановления");
        try(ZipFile zip=new ZipFile(archive.toFile())){for(JsonNode track:preview.manifest().path("tracks")){for(String key:List.of("audio","cover")){JsonNode item=track.path(key);if(!item.isObject())continue;Path destination=directory.resolve(Path.of(item.path("entry").asText()).getFileName().toString()).normalize();if(!destination.getParent().equals(directory))throw new IOException("Недопустимый путь");String expected=item.path("sha256").asText();if(!Files.isRegularFile(destination)||!hash(destination).equals(expected)){Path temp=Files.createTempFile(directory,"restore-",".part");try{try(InputStream in=zip.getInputStream(zip.getEntry(item.path("entry").asText()));OutputStream out=Files.newOutputStream(temp)){byte[] buffer=new byte[131072];long bytes=0;int n;while((n=in.read(buffer))!=-1){bytes+=n;if(bytes>item.path("size").asLong())throw new IOException("Размер файла не совпадает");out.write(buffer,0,n);}if(bytes!=item.path("size").asLong())throw new IOException("Файл обрезан");}if(!hash(temp).equals(expected))throw new IOException("Контрольная сумма не совпадает");move(temp,destination);}finally{Files.deleteIfExists(temp);}}
                    (key.equals("audio")?audio:covers).put(track.path("id").asText(),destination);
                }}}
        // Keep a metadata-only rollback copy before applying a user-confirmed replacement.
        if(replace)write(library,library.dataDirectory().resolve("before-restore.auralis"),false,null);
        library.restoreBackup(preview.manifest(),audio,covers,replace);
    }
    private static void move(Path source,Path target)throws IOException{try{Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(source,target,StandardCopyOption.REPLACE_EXISTING);}}
}
