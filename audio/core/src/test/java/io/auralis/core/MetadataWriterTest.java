package io.auralis.core;
import io.auralis.core.audio.FfmpegService;
import io.auralis.core.backup.LibraryArchive;
import io.auralis.core.library.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;
class MetadataWriterTest {
 @TempDir Path root;
 @Test void explicitOriginalWritePreservesAudioAndKeepsFullBackupWithNewCover()throws Exception{
  var ffmpeg=new FfmpegService();Assumptions.assumeTrue(ffmpeg.available());Path file=root.resolve("song.mp3");
  FfmpegService.run(List.of(ffmpeg.executable(),"-v","error","-f","lavfi","-i","sine=frequency=440:duration=1","-y",file.toString()),Duration.ofSeconds(20));
  String hash=LibraryArchive.hash(file),before=pcmHash(ffmpeg,file);
  try(var library=new LibraryService(root.resolve("data"))){library.importPaths(List.of(file));var track=library.tracks().getFirst();library.updateMetadata(track.id(),"New title","Artist","Album","Ambient");library.setTrackNumbers(track.id(),4,2);Path cover=root.resolve("cover.png");ImageIO.write(new BufferedImage(80,80,BufferedImage.TYPE_INT_RGB),"png",cover.toFile());library.setTrackArtwork(track.id(),cover);
   assertEquals(hash,LibraryArchive.hash(file));Path backup=MetadataWriter.writeOriginal(library,track.id());assertEquals(hash,LibraryArchive.hash(backup));assertEquals(before,pcmHash(ffmpeg,file));assertEquals("New title",ffmpeg.probe(file).title());assertTrue(ffmpeg.probe(file).embeddedArtwork());assertEquals(LibraryArchive.hash(file),library.contentHash(track.id()));assertEquals(4,library.extras(track.id()).trackNumber());
  }
 }
 private String pcmHash(FfmpegService audio,Path source)throws Exception{return FfmpegService.run(List.of(audio.executable(),"-v","error","-i",source.toString(),"-map","0:a:0","-f","hash","-hash","sha256","-"),Duration.ofSeconds(20)).trim();}
}
