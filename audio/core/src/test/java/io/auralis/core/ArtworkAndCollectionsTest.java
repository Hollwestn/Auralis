package io.auralis.core;

import io.auralis.core.audio.FfmpegService;
import io.auralis.core.library.LibraryService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ArtworkAndCollectionsTest {
    @TempDir Path root;
    final FfmpegService audio = new FfmpegService();
    @BeforeEach void requireFfmpeg(){Assumptions.assumeTrue(audio.available());}
    Path sound(String name, int frequency) throws Exception {
        Path path=root.resolve(name+".wav");
        FfmpegService.run(List.of(audio.executable(),"-v","error","-f","lavfi","-i","sine=frequency="+frequency+":duration=45","-y",path.toString()),Duration.ofSeconds(30));return path;
    }
    Path picture(String name) throws Exception {Path path=root.resolve(name);var image=new BufferedImage(20,30,BufferedImage.TYPE_INT_RGB);image.setRGB(2,2,0xffcc44);ImageIO.write(image,"png",path.toFile());return path;}
    @Test void repairsExistingCatalogAndOwnsItsCoverCache() throws Exception {
        Path music=sound("Track",440);
        String id;
        try(var library=new LibraryService(root.resolve("data"))){
            library.importPaths(List.of(music));id=library.tracks().getFirst().id();assertTrue(library.track(id).orElseThrow().artworkPath().isBlank());
            library.setFavorite(id,true);var playlist=library.createPlaylist("Keep");library.addToPlaylist(playlist.id(),id);
            Path external=picture("COVER.PNG");assertEquals(1,library.repairArtwork());
            Path cached=Path.of(library.track(id).orElseThrow().artworkPath());assertNotEquals(external,cached);assertNotNull(ImageIO.read(cached.toFile()));
            Files.delete(cached);assertEquals(1,library.repairArtwork());Files.delete(external);assertEquals(0,library.repairArtwork());assertTrue(Files.exists(cached));
            assertTrue(library.isFavorite(id));assertEquals(id,library.playlist(playlist.id()).orElseThrow().tracks().getFirst().id());
        }
        try(var library=new LibraryService(root.resolve("data"))){assertTrue(Files.isRegularFile(Path.of(library.track(id).orElseThrow().artworkPath())));}
    }
    @Test void extractsEmbeddedMp3Artwork() throws Exception {
        Path wav=sound("Embedded",660),art=picture("Art.png"),mp3=root.resolve("Embedded.mp3");
        FfmpegService.run(List.of(audio.executable(),"-v","error","-i",wav.toString(),"-i",art.toString(),"-map","0:a","-map","1:v","-c:a","libmp3lame","-c:v","png","-id3v2_version","3","-disposition:v","attached_pic","-y",mp3.toString()),Duration.ofSeconds(30));
        try(var library=new LibraryService(root.resolve("data"))){assertEquals(1,library.importPaths(List.of(mp3)).imported());assertNotNull(ImageIO.read(Path.of(library.tracks().getFirst().artworkPath()).toFile()));}
    }
    @Test void smartCollectionsUseMeaningfulListeningAndKeepLibraryIntact() throws Exception {
        Path a=sound("First",440),b=sound("Second",880);
        try(var library=new LibraryService(root.resolve("data"))){
            library.importPaths(List.of(a,b));var tracks=library.tracks();String first=tracks.get(0).id(),second=tracks.get(1).id();
            library.recordListen(first,1,true);assertTrue(library.smartCollection(LibraryService.SmartCollection.MOST_PLAYED).isEmpty());
            library.recordListen(first,20,false);library.recordListen(second,40,false);
            assertEquals(second,library.smartCollection(LibraryService.SmartCollection.MOST_PLAYED).getFirst().id());
            assertEquals(2,library.smartCollection(LibraryService.SmartCollection.RECENT).size());assertTrue(library.smartCollection(LibraryService.SmartCollection.REDISCOVER).isEmpty());assertEquals(2,library.tracks().size());
        }
    }
}
