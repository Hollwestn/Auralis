package io.auralis.core;

import io.auralis.core.audio.FfmpegService;
import io.auralis.core.editor.*;
import io.auralis.core.library.LibraryService;
import io.auralis.core.model.Track;
import io.auralis.core.recommendation.RecommendationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CoreIntegrationTest {
    @TempDir Path directory;
    private final FfmpegService audio = new FfmpegService();
    private Path fixture(String name, int frequency, String artist, String genre) throws IOException {
        Path file = directory.resolve(name + ".wav");
        FfmpegService.run(List.of(audio.executable(), "-nostdin", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=" + frequency + ":duration=4:sample_rate=48000", "-metadata", "title=" + name, "-metadata", "artist=" + artist, "-metadata", "genre=" + genre, "-c:a", "pcm_s16le", "-y", file.toString()), Duration.ofSeconds(30));
        return file;
    }
    @BeforeEach void requireFfmpeg() { Assumptions.assumeTrue(audio.available(), "Install FFmpeg to run audio integration tests"); }

    @Test void importsDeduplicatesRejectsCorruptionAndPersistsAcrossRestart() throws Exception {
        Path first = fixture("Нефрит", 440, "Auralis", "Ambient");
        Path second = fixture("Волна", 880, "Другой", "Electronic");
        Path copy = directory.resolve("identical.wav"); Files.copy(first, copy);
        Path bad = directory.resolve("broken.mp3"); Files.writeString(bad, "this is not audio");
        String id;
        try (LibraryService library = new LibraryService(directory.resolve("data"))) {
            var result = library.importPaths(List.of(first, second, copy, bad));
            assertEquals(2, result.imported()); assertEquals(1, result.duplicates()); assertEquals(1, result.failures().size());
            Track track = library.search("НЕФРИТ").getFirst(); id = track.id();
            assertEquals("Auralis", track.artist()); assertEquals("Ambient", track.genre()); assertEquals(4, track.durationSeconds(), .02);
            assertTrue(library.toggleFavorite(id)); library.recordListen(id, 3.5, false); library.setSetting("theme", "jade");
            assertEquals(1, library.favorites().size());
        }
        try (LibraryService reopened = new LibraryService(directory.resolve("data"))) {
            assertEquals(2, reopened.tracks().size()); assertTrue(reopened.isFavorite(id)); assertEquals(id, reopened.history(10).getFirst().track().id());
            assertEquals(3.5, reopened.history(10).getFirst().listenedSeconds()); assertEquals("jade", reopened.setting("theme", "amber"));
            reopened.removeTrack(id); assertEquals(1, reopened.tracks().size()); assertTrue(Files.isRegularFile(first));
        }
    }
    @Test void removeTrackCascadesCatalogReferencesAndNeverDeletesOriginal() throws Exception {
        Path file=fixture("Keep original",440,"Artist","Ambient");byte[] original=Files.readAllBytes(file);String playlistId;
        try(var library=new LibraryService(directory.resolve("remove-data"))){
            library.importPaths(List.of(file));String id=library.tracks().getFirst().id();var playlist=library.createPlaylist("Survives");playlistId=playlist.id();
            library.addToPlaylist(playlistId,id);library.setFavorite(id,true);library.setReaction(id,LibraryService.Reaction.MORE);library.recordListen(id,3,false);
            library.removeTrack(id);
            assertTrue(library.track(id).isEmpty());assertTrue(library.favorites().isEmpty());assertTrue(library.history(10).isEmpty());assertTrue(library.feedback().isEmpty());assertTrue(library.playlist(playlistId).orElseThrow().tracks().isEmpty());assertArrayEquals(original,Files.readAllBytes(file));
        }
        try(var library=new LibraryService(directory.resolve("remove-data"))){assertTrue(library.tracks().isEmpty());assertTrue(library.playlist(playlistId).isPresent());assertArrayEquals(original,Files.readAllBytes(file));}
    }
    @Test void playlistOrderSurvivesRestartAndM3uRoundTrip() throws Exception {
        Path first = fixture("First", 440, "One", "Rock"), second = fixture("Second", 880, "Two", "Jazz");
        String playlistId;
        List<String> order;
        try (LibraryService library = new LibraryService(directory.resolve("data"))) {
            library.importPaths(List.of(first, second)); var tracks = library.tracks();
            var playlist = library.createPlaylist("Evening"); playlistId = playlist.id();
            library.addToPlaylist(playlistId, tracks.get(0).id()); library.addToPlaylist(playlistId, tracks.get(1).id()); library.addToPlaylist(playlistId, tracks.get(1).id());
            order = List.of(tracks.get(1).id(), tracks.get(0).id()); library.reorderPlaylist(playlistId, order);
            assertThrows(IllegalArgumentException.class, () -> library.replacePlaylistContent(playlistId, "Broken", List.of("missing")));
            assertEquals("Evening", library.playlist(playlistId).orElseThrow().name());
            assertEquals(order, library.playlist(playlistId).orElseThrow().tracks().stream().map(Track::id).toList());
            library.replacePlaylistContent(playlistId, "Replaced", List.of(order.get(0), order.get(0), order.get(1)));
            assertEquals(order, library.playlist(playlistId).orElseThrow().tracks().stream().map(Track::id).toList());
            assertThrows(IllegalArgumentException.class, () -> library.reorderPlaylist(playlistId, List.of(tracks.getFirst().id(), tracks.getFirst().id())));
            library.renamePlaylist(playlistId, "Ночь"); library.exportM3u(playlistId, directory.resolve("list.m3u8"));
        }
        try (LibraryService reopened = new LibraryService(directory.resolve("data"))) {
            var playlist = reopened.playlist(playlistId).orElseThrow(); assertEquals("Ночь", playlist.name()); assertEquals(order, playlist.tracks().stream().map(Track::id).toList());
            var imported = reopened.importM3u(directory.resolve("list.m3u8")); assertEquals(order, imported.tracks().stream().map(Track::id).toList());
            reopened.deletePlaylist(playlistId); assertTrue(reopened.playlist(playlistId).isEmpty()); assertEquals(2, reopened.tracks().size());
        }
    }
    @Test void synchronizedMetadataAndHistoryPreserveLabelsAndChronology() throws Exception {
        Path source = fixture("Remote", 440, "Original", "Rock");
        try (LibraryService library = new LibraryService(directory.resolve("data"))) {
            library.importPaths(List.of(source)); String id = library.tracks().getFirst().id();
            library.updateMetadata(id, "Server title", "Server artist", "Server album", "Jazz");
            assertEquals("Server title", library.track(id).orElseThrow().title()); assertEquals("Server artist", library.track(id).orElseThrow().artist());
            library.recordListenAt(id, 2, false, Instant.parse("2026-01-01T12:00:00.300Z"));
            library.recordListenAt(id, 1, true, Instant.parse("2026-01-01T12:00:00Z"));
            assertEquals(2, library.history(10).getFirst().listenedSeconds()); assertFalse(library.history(10).getFirst().skipped());
            assertEquals("Original", audio.probe(source).artist(), "Server labels must not rewrite original tags");
        }
    }
    @Test void remoteHistoryImportAndMarkersAreAtomicAndIdempotentAcrossRestart() throws Exception {
        Path source = fixture("Remote event", 440, "A", "Ambient"); String trackId; long historyId;
        try (LibraryService library = new LibraryService(directory.resolve("data"))) {
            library.importPaths(List.of(source)); trackId = library.tracks().getFirst().id();
            assertTrue(library.importRemoteListen("account", "event-1", trackId, 3, false, Instant.parse("2026-01-01T12:00:00Z")));
            assertFalse(library.importRemoteListen("account", "event-1", trackId, 3, false, Instant.parse("2026-01-01T12:00:00Z")));
            historyId = library.history(10).getFirst().id(); assertEquals(1, library.history(10).size());
            assertEquals("true", library.setting("historySent.account." + historyId, "false"));
            assertThrows(IllegalArgumentException.class, () -> library.importRemoteListen("account", "broken", "missing", 1, false, Instant.now()));
            assertEquals("false", library.setting("historyReceived.account.broken", "false"));
        }
        try (LibraryService reopened = new LibraryService(directory.resolve("data"))) {
            assertFalse(reopened.importRemoteListen("account", "event-1", trackId, 3, false, Instant.now()));
            assertEquals(1, reopened.history(10).size()); assertEquals("true", reopened.setting("historySent.account." + historyId, "false"));
        }
    }
    @Test void recommendationHonorsNegativeFeedbackExcludedArtistsAndQueueExclusions() throws Exception {
        Path first = fixture("Liked", 440, "Artist A", "Ambient"), second = fixture("Blocked", 550, "Artist B", "Rock"), third = fixture("Disliked", 660, "Artist C", "Jazz");
        try (LibraryService library = new LibraryService(directory.resolve("data"))) {
            library.importPaths(List.of(first, second, third));
            var liked = library.search("Liked").stream().filter(t -> t.title().equals("Liked")).findFirst().orElseThrow();
            library.setFavorite(liked.id(), true); library.excludeArtist("ARTIST B", true);
            library.setReaction(library.search("Disliked").getFirst().id(), LibraryService.Reaction.DISLIKE);
            var service = new RecommendationService(library);
            var result = service.recommend(RecommendationService.WaveMode.MY_WAVE, 20);
            assertEquals(1, result.size()); assertEquals(liked.id(), result.getFirst().track().id()); assertTrue(result.getFirst().explanation().contains("избранном"));
            assertTrue(service.recommend(RecommendationService.WaveMode.DISCOVERY, 20, Set.of(liked.id())).isEmpty());
            assertEquals(1, service.recommend(RecommendationService.WaveMode.FAVORITES, 20).size());
        }
    }
    @Test void waveformAndGainProcessingUseActualSamplesAndKeepOriginalUntouched() throws Exception {
        Path source = fixture("Original", 440, "A", "Ambient"); byte[] checksum = sha(source);
        var original = audio.analyze(source, 128); assertEquals(128, original.waveform().length); assertTrue(Arrays.stream(original.waveform()).max().orElseThrow() > .1);
        AudioEditor editor = new AudioEditor(audio); EditSession session = new EditSession(editor.open(source));
        session.apply(p -> editor.trim(p, 1, 3)); assertEquals(2, session.project().durationSeconds(), .0001);
        assertTrue(session.undo()); assertEquals(4, session.project().durationSeconds(), .01); assertTrue(session.redo());
        session.apply(p -> p.withGainDb(-6).withFades(.15, .15));
        Path output = editor.export(session.project(), directory.resolve("version.wav"), AudioEditor.ExportFormat.WAV);
        var rendered = audio.analyze(output, 128); assertEquals(2, rendered.info().durationSeconds(), .05); assertEquals(original.peakDb() - 6, rendered.peakDb(), .12);
        assertArrayEquals(checksum, sha(source)); assertThrows(IOException.class, () -> editor.export(session.project(), source, AudioEditor.ExportFormat.WAV));
        assertThrows(IOException.class, () -> editor.saveProject(session.project(), source)); assertArrayEquals(checksum, sha(source));
        Path projectFile = directory.resolve("version.auralis.json"); editor.saveProject(session.project(), projectFile); assertEquals(session.project(), editor.loadProject(projectFile));
    }
    @Test void deleteReplaceCopyMoveSpeedPitchAndCrossfadeRenderCorrectTimeline() throws Exception {
        Path source = fixture("Source", 440, "A", "Ambient"), replacement = fixture("Insert", 880, "B", "Rock");
        AudioEditor editor = new AudioEditor(audio); EditProject project = editor.open(source);
        project = editor.deleteRange(project, 1, 2); assertEquals(3, project.durationSeconds(), .01);
        project = editor.replace(project, .5, 1.5, replacement, 1, 3); assertEquals(4, project.durationSeconds(), .01);
        project = editor.copyRange(project, 0, .5, 2); assertEquals(4.5, project.durationSeconds(), .01);
        project = editor.moveRange(project, 0, .5, 4); assertEquals(4.5, project.durationSeconds(), .01);
        project = project.withSpeed(1.25).withPitchSemitones(3).withEffects(List.of(EditProject.Effect.LIMITER));
        Path output = editor.export(project, directory.resolve("edited.flac"), AudioEditor.ExportFormat.FLAC);
        assertEquals(3.6, audio.probe(output).durationSeconds(), .12);
        EditProject crossfade = editor.insert(editor.open(source), 4, replacement, 0, 2).withCrossfade(.25);
        Path crossfaded = editor.export(crossfade, directory.resolve("crossfade.mp3"), AudioEditor.ExportFormat.MP3);
        assertEquals(5.75, audio.probe(crossfaded).durationSeconds(), .15);
    }
    private static byte[] sha(Path path) throws Exception { return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)); }
}
