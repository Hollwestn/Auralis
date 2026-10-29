package io.auralis.core.library;
import io.auralis.core.audio.FfmpegService;
import io.auralis.core.backup.LibraryArchive;
import io.auralis.core.model.Track;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Explicit opt-in writes always keep a full original beside the file. */
public final class MetadataWriter {
 private MetadataWriter(){}
 public static Path writeOriginal(LibraryService library,String id)throws IOException{
  Track track=library.track(id).orElseThrow();Path source=track.path().toRealPath();if(!Files.isRegularFile(source)||!Files.isWritable(source))throw new IOException("Оригинал недоступен для записи");
  String name=source.getFileName().toString(),extension=name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
  if(!Set.of(".mp3",".flac",".m4a",".ogg",".opus",".aif",".aiff",".wav").contains(extension))throw new IOException("Запись тегов этого формата пока не поддерживается");
  if(Files.getFileStore(source).getUsableSpace()<Files.size(source)*2+1024*1024)throw new IOException("Нет места для копии оригинала");
  Path backup=source.resolveSibling(name+".auralis-original-"+UUID.randomUUID()+".bak"),temp=source.resolveSibling(".auralis-tags-"+UUID.randomUUID()+extension);Files.copy(source,backup,StandardCopyOption.COPY_ATTRIBUTES);
  try{
   List<String> command=new ArrayList<>(List.of(library.audio().executable(),"-nostdin","-v","error","-i",source.toString()));
   Path cover=track.artworkPath().isBlank()?null:Path.of(track.artworkPath());boolean embed=cover!=null&&Files.isRegularFile(cover)&&Set.of(".mp3",".flac",".m4a").contains(extension);
   if(embed)command.addAll(List.of("-i",cover.toString(),"-map","0:a","-map","1:v:0","-map_metadata","0","-map_chapters","0","-c","copy","-c:v","png","-disposition:v:0","attached_pic"));
   else command.addAll(List.of("-map","0","-c","copy"));
   command.addAll(List.of("-metadata","title="+track.title(),"-metadata","artist="+track.artist(),"-metadata","album="+track.album(),"-metadata","genre="+track.genre()));var extras=library.extras(id);command.addAll(List.of("-metadata","track="+(extras.trackNumber()==0?"":extras.trackNumber()),"-metadata","disc="+(extras.discNumber()==0?"":extras.discNumber()),"-y",temp.toString()));
   FfmpegService.run(command,Duration.ofMinutes(30));library.audio().probe(temp);
   try{Files.move(temp,source,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,source,StandardCopyOption.REPLACE_EXISTING);}
   try{library.refreshFingerprint(id);}catch(Exception e){Files.copy(backup,source,StandardCopyOption.REPLACE_EXISTING);throw new IOException("Не удалось обновить индекс; оригинал восстановлен",e);}
   return backup;
  }finally{Files.deleteIfExists(temp);}
 }
}
