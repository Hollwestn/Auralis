package io.auralis.core;
import io.auralis.core.backup.LibraryArchive;
import io.auralis.core.library.LibraryService;
import io.auralis.core.audio.FfmpegService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
class LibraryArchiveTest {
 @TempDir Path root;
 private Path music()throws Exception{var audio=new FfmpegService();Assumptions.assumeTrue(audio.available());Path path=root.resolve("signal.wav");FfmpegService.run(List.of(audio.executable(),"-v","error","-f","lavfi","-i","sine=frequency=440:duration=1","-y",path.toString()),Duration.ofSeconds(20));return path;}
 @Test void portableRoundTripPreservesRelationsAndOmitsCredentials()throws Exception{
  Path source=music(),archive=root.resolve("full.auralis");String id;
  try(var library=new LibraryService(root.resolve("a"))){library.importPaths(List.of(source));id=library.tracks().getFirst().id();library.setFavorite(id,true);var playlist=library.createPlaylist("Личное");library.addToPlaylist(playlist.id(),id);library.recordListen(id,.9,false);library.setSetting("serverToken","PRIVATE_TOKEN");library.setSetting("theme","DEEP_VIOLET");library.setTrackNumbers(id,7,2);LibraryArchive.write(library,archive,true,null);}
  Files.delete(source);
  try(var library=new LibraryService(root.resolve("b"))){var preview=LibraryArchive.preview(archive,library);assertEquals(1,preview.tracks());assertTrue(preview.audioBytes()>0);assertFalse(preview.manifest().path("settings").has("serverToken"));LibraryArchive.restore(library,archive,false);assertEquals(id,library.tracks().getFirst().id());assertTrue(Files.isRegularFile(library.tracks().getFirst().path()));assertEquals(id,library.favorites().getFirst().id());assertEquals(id,library.playlists().getFirst().tracks().getFirst().id());assertEquals(1,library.history(100).size());assertEquals(7,library.extras(id).trackNumber());LibraryArchive.restore(library,archive,false);assertEquals(1,library.tracks().size());assertEquals(1,library.playlists().size());assertEquals(1,library.history(100).size());}
 }
 @Test void metadataOnlyRestoreKeepsMissingTrackUntilOriginalIsReimported()throws Exception{
  Path source=music(),archive=root.resolve("metadata.auralis");String id;
  try(var library=new LibraryService(root.resolve("a"))){library.importPaths(List.of(source));id=library.tracks().getFirst().id();library.setFavorite(id,true);LibraryArchive.write(library,archive,false,null);}
  Path moved=root.resolve("moved.wav");Files.move(source,moved);
  try(var library=new LibraryService(root.resolve("b"))){LibraryArchive.restore(library,archive,false);assertFalse(Files.exists(library.track(id).orElseThrow().path()));library.importPaths(List.of(moved));assertEquals(1,library.tracks().size());assertEquals(moved.toRealPath(),library.track(id).orElseThrow().path());assertTrue(library.isFavorite(id));}
 }
 @Test void invalidHistoryRollsBackWholeDatabaseAndZipTraversalIsRejected()throws Exception{
  Path source=music(),good=root.resolve("good.auralis"),bad=root.resolve("bad.auralis");
  try(var library=new LibraryService(root.resolve("a"))){library.importPaths(List.of(source));var id=library.tracks().getFirst().id();library.recordListen(id,.5,false);LibraryArchive.write(library,good,true,null);var manifest=LibraryArchive.preview(good,library).manifest();((com.fasterxml.jackson.databind.node.ObjectNode)manifest.path("history").get(0)).put("playedAt","invalid-date");assertThrows(Exception.class,()->library.restoreBackup(manifest,Map.of(),Map.of(),true));assertEquals(1,library.tracks().size());assertEquals(1,library.history(10).size());}
  try(var out=new ZipOutputStream(Files.newOutputStream(bad))){out.putNextEntry(new ZipEntry("../escape"));out.write(1);out.closeEntry();}assertThrows(java.io.IOException.class,()->LibraryArchive.preview(bad,null));assertFalse(Files.exists(root.resolve("escape")));
 }
}
