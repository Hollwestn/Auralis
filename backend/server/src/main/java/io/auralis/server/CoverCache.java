package io.auralis.server;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.*;
import org.jaudiotagger.audio.AudioFileIO;

/** Immutable audio has at most one validated bounded PNG/JPEG cover; misses are cached too. */
final class CoverCache {
    static final int MAX_BYTES=5*1024*1024,MAX_DIMENSION=2048;
    record Cover(Path path,String mimeType){}
    private final Path storage;
    private final Map<String,Object> locks=new ConcurrentHashMap<>();
    CoverCache(Path storage){this.storage=storage;}
    Cover find(String user,String id,String relative){
        Path folder=storage.resolve(user),music=storage.resolve(relative).normalize();
        if(!music.startsWith(folder)||!safeFile(music,folder))return null;
        synchronized(locks.computeIfAbsent(user,k->new Object())){
            Path png=folder.resolve(id+".cover.png"),jpeg=folder.resolve(id+".cover.jpg"),miss=folder.resolve(id+".cover-none");
            if(safeFile(png,folder))return new Cover(png,"image/png");if(safeFile(jpeg,folder))return new Cover(jpeg,"image/jpeg");if(safeFile(miss,folder))return null;
            Path temp=null;
            try{
                var tag=AudioFileIO.read(music.toFile()).getTag();var artwork=tag==null?null:tag.getFirstArtwork();
                // WAV/AIFF can expose RIFF INFO as their active tag while artwork lives in ID3.
                if(artwork==null&&tag instanceof org.jaudiotagger.tag.id3.Id3SupportingTag id3&&id3.getID3Tag()!=null)artwork=id3.getID3Tag().getFirstArtwork();
                byte[] bytes=artwork==null||artwork.isLinked()?null:artwork.getBinaryData();String format=validatedFormat(bytes);
                if(format==null){Files.write(miss,new byte[0],StandardOpenOption.CREATE_NEW);return null;}
                temp=Files.createTempFile(folder,".cover-",".part");Files.write(temp,bytes);Path destination=format.equals("png")?png:jpeg;Files.move(temp,destination,StandardCopyOption.REPLACE_EXISTING);return new Cover(destination,format.equals("png")?"image/png":"image/jpeg");
            }catch(Exception e){return null;}finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ignored){}}
        }
    }
    private static String validatedFormat(byte[] bytes)throws IOException{
        if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)return null;
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())return null;var reader=readers.next();
            try{reader.setInput(input,true,true);String format=reader.getFormatName().toLowerCase(Locale.ROOT);if(!Set.of("png","jpeg","jpg").contains(format))return null;int width=reader.getWidth(0),height=reader.getHeight(0);if(width<1||height<1||width>MAX_DIMENSION||height>MAX_DIMENSION)return null;reader.read(0);return format.equals("png")?"png":"jpeg";}finally{reader.dispose();}
        }
    }
    private static boolean safeFile(Path path,Path folder){try{return Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&path.toRealPath().startsWith(folder.toRealPath());}catch(IOException e){return false;}}
    void remove(String user,String id){for(String suffix:List.of(".cover.png",".cover.jpg",".cover-none"))try{Path file=storage.resolve(user).resolve(id+suffix);if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))Files.deleteIfExists(file);}catch(IOException ignored){}}
}
