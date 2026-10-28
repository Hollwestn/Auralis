package io.auralis.desktop.playback;

import io.auralis.core.audio.FfmpegService;
import io.auralis.core.model.Track;
import javafx.application.Platform;
import javafx.scene.media.MediaPlayer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** Native decoder/status tests with muted output; they make no assertion about perceived sound quality. */
@Timeout(60)
class PlaybackServiceIntegrationTest {
    @TempDir Path directory;
    private static final AtomicBoolean started = new AtomicBoolean();
    private final FfmpegService audio = new FfmpegService();
    private ExecutorService worker;
    private PlaybackService service;
    private final AtomicReference<String> mediaError = new AtomicReference<>();

    @BeforeAll static void startJavafx() throws Exception {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        try { Platform.startup(() -> { Platform.setImplicitExit(false); started.set(true); ready.complete(null); }); }
        catch (IllegalStateException alreadyStarted) { Platform.runLater(() -> { started.set(true); ready.complete(null); }); }
        ready.get(20, TimeUnit.SECONDS);
    }
    @AfterAll static void stopJavafx() { if (started.get()) Platform.exit(); }
    @BeforeEach void setup() throws Exception {
        Assumptions.assumeTrue(audio.available(), "FFmpeg is needed for native playback fixtures");
        worker = Executors.newVirtualThreadPerTaskExecutor();
        service = fx(() -> { PlaybackService result = new PlaybackService(worker, directory.resolve("cache")); result.volumeProperty().set(0); result.onError(mediaError::set); return result; });
    }
    @AfterEach void cleanup() throws Exception {
        if (service != null) fx(() -> { service.close(); return null; }); if (worker != null) worker.close();
        // OpenJFX 25 Locator opens a second FileURLConnection while probing content length.
        // Its header stream is reclaimed by the JVM cleaner, independently of MediaPlayer.dispose().
        // Request collection only in fixture cleanup; playback code never forces a JVM collection.
        System.gc();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        List<Path> files;
        try (var paths = Files.walk(directory)) { files = paths.filter(Files::isRegularFile).toList(); }
        for (Path file : files) {
            assertTrue(file.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize()));
            while (Files.exists(file)) try { Files.delete(file); } catch (java.io.IOException e) { if (System.nanoTime() >= deadline) throw e; Thread.sleep(50); }
        }
    }
    private Track fixture(String name, String extension) throws Exception {
        Path source = directory.resolve(name + extension);
        FfmpegService.run(List.of(audio.executable(), "-nostdin", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=12:sample_rate=48000", "-y", source.toString()), Duration.ofSeconds(30));
        return new Track(name, name, "Auralis test signal", "Tests", "", source, 12, "");
    }
    @Test void nativeWavPlaysSeeksPausesAndReceivesSpectrumWithEqApplied() throws Exception {
        Track track = fixture("native", ".wav"); AtomicInteger frames = new AtomicInteger();
        fx(() -> { service.setSpectrumListener((timestamp, duration, magnitudes, phases) -> frames.incrementAndGet()); service.setGain(3, 6); service.restore(track, 2, true); return null; });
        await(() -> service.readyProperty().get() && service.playingProperty().get());
        assertEquals(MediaPlayer.Status.PLAYING, fx(service::nativeStatus));
        assertTrue(fx(() -> service.positionProperty().get()) >= 1.8, "restored position is retained during ready callback");
        assertEquals(10, fx(service::appliedEqBandCount)); assertEquals(6, fx(() -> service.appliedEqGain(3)), .001);
        fx(() -> { service.seek(5); return null; }); await(() -> service.positionProperty().get() >= 4.9);
        fx(() -> { service.pause(); return null; }); await(() -> !service.playingProperty().get());
        fx(() -> { service.toggle(); return null; }); await(() -> service.playingProperty().get());
        await(() -> frames.get() > 0); assertNull(mediaError.get());
        fx(() -> { service.setQueue(List.of(), 0); return null; });
        assertEquals(MediaPlayer.Status.DISPOSED, fx(service::nativeStatus)); assertNull(fx(service::current)); assertFalse(fx(() -> service.readyProperty().get()));
    }
    @Test void queueWrapShuffleAndDisposalHaveStableBoundaries() throws Exception {
        Track first = fixture("queue", ".wav");
        Track second = new Track("second", "Second", "A", "", "", first.path(), 12, ""), third = new Track("third", "Third", "A", "", "", first.path(), 12, "");
        fx(() -> { service.setQueue(List.of(first, second, third), 2); return null; }); await(() -> service.readyProperty().get());
        fx(() -> { service.next(); return null; }); assertEquals("third", fx(() -> service.current().id()));
        assertEquals(PlaybackService.Repeat.ALL, fx(service::cycleRepeat));
        fx(() -> { service.next(); return null; }); assertEquals(first.id(), fx(() -> service.current().id()));
        fx(() -> { service.previous(); return null; }); assertEquals("third", fx(() -> service.current().id()));
        fx(() -> { service.setShuffle(true); return null; });
        for (int i = 0; i < 12; i++) {
            String before = fx(() -> service.current().id()); fx(() -> { service.next(); return null; });
            assertNotEquals(before, fx(() -> service.current().id()));
        }
        fx(() -> { service.close(); service.close(); return null; }); assertEquals(MediaPlayer.Status.DISPOSED, fx(service::nativeStatus));
        assertThrows(ExecutionException.class, () -> fx(() -> { service.next(); return null; }));
    }
    @Test void immediateToggleDuringFlacConversionRetainsRestorePosition() throws Exception {
        Track track = fixture("lossless", ".flac");
        fx(() -> { service.restore(track, 4); service.toggle(); return null; });
        await(() -> service.readyProperty().get() && service.playingProperty().get());
        assertEquals(track.id(), fx(() -> service.current().id())); assertTrue(fx(() -> service.positionProperty().get()) >= 3.8);
        assertNull(mediaError.get()); assertTrue(Files.isRegularFile(track.path()));
        try (var files = Files.list(directory.resolve("cache"))) { assertTrue(files.anyMatch(p -> p.toString().endsWith(".wav"))); }
    }
    @Test void naturalEndRepeatsOneThenAdvancesAndRestartsStoppedTrack() throws Exception {
        Track first = fixture("end", ".wav"), second = new Track("end-two", "Second", "A", "", "", first.path(), 12, "");
        AtomicInteger completed = new AtomicInteger();
        fx(() -> { service.onFinished(t -> completed.incrementAndGet()); service.cycleRepeat(); service.cycleRepeat(); service.setQueue(List.of(first, second), 0); return null; });
        await(() -> service.readyProperty().get() && service.playingProperty().get());
        fx(() -> { service.seek(11.8); return null; });
        await(() -> completed.get() >= 1 && service.positionProperty().get() < 2 && service.playingProperty().get());
        assertEquals(first.id(), fx(() -> service.current().id()));
        fx(() -> { service.cycleRepeat(); service.seek(11.8); return null; });
        await(() -> service.current().id().equals(second.id()) && service.readyProperty().get() && service.playingProperty().get());
        fx(() -> { service.seek(11.8); return null; }); await(() -> !service.playingProperty().get() && service.positionProperty().get() > 11.5);
        fx(() -> { service.toggle(); return null; }); await(() -> service.playingProperty().get() && service.positionProperty().get() < 2);
        assertNull(mediaError.get());
    }
    @Test void queueSnapshotRestoresDuplicateEntriesIndexSeekAndRepeat() throws Exception {
        Track first = fixture("snapshot", ".wav"), other = new Track("other", "Other", "A", "", "", first.path(), 12, "");
        fx(() -> { service.setRepeat(PlaybackService.Repeat.ALL); service.restoreQueue(List.of(first, other, first), 2, 4, false); return null; });
        await(() -> service.readyProperty().get());
        assertEquals(2, fx(service::queueIndex)); assertEquals(List.of(first, other, first), fx(service::queue));
        assertEquals(PlaybackService.Repeat.ALL, fx(service::repeat)); assertFalse(fx(() -> service.playingProperty().get()));
        fx(() -> { service.toggle(); return null; }); await(() -> service.playingProperty().get() && service.positionProperty().get() >= 3.8);
        fx(() -> { service.next(); return null; }); assertEquals(0, fx(service::queueIndex));
        assertThrows(ExecutionException.class, () -> fx(() -> { service.restoreQueue(List.of(first), 1, 0, true); return null; }));
        assertThrows(ExecutionException.class, () -> fx(() -> { service.restoreQueue(List.of(), 0, 0, true); return null; }));
    }
    @Test void removingOtherTracksKeepsNativePlaybackPositionAndAdjustsQueueIndex() throws Exception {
        Track first=fixture("remove-other", ".wav"), second=new Track("second", "Second", "A", "", "", first.path(), 12, ""), third=new Track("third", "Third", "A", "", "", first.path(), 12, "");
        fx(()->{service.restoreQueue(List.of(first,second,first,third),3,4,false);return null;});
        await(()->service.readyProperty().get()&&service.positionProperty().get()>=3.8);
        fx(()->{service.removeTrack(first.id());return null;});
        assertEquals(List.of(second,third),fx(service::queue));assertEquals(1,fx(service::queueIndex));assertEquals(third,fx(service::current));
        assertTrue(fx(()->service.readyProperty().get()));assertTrue(fx(()->service.positionProperty().get())>=3.8);assertFalse(fx(()->service.playingProperty().get()));
        fx(()->{service.removeTrack(second.id());service.removeTrack("not-in-queue");return null;});
        assertEquals(0,fx(service::queueIndex));assertEquals(third,fx(service::current));assertTrue(fx(()->service.positionProperty().get())>=3.8);assertNull(mediaError.get());
    }
    @Test void removingCurrentTrackPreservesPlayingOrPausedIntentAndClearsLastTrack() throws Exception {
        Track first=fixture("remove-current", ".wav"), second=new Track("second", "Second", "A", "", "", first.path(),12,""),third=new Track("third","Third","A","","",first.path(),12,"");
        fx(()->{service.setRepeat(PlaybackService.Repeat.ALL);service.setQueue(List.of(first,second,first,third),0);return null;});await(()->service.readyProperty().get()&&service.playingProperty().get());
        fx(()->{service.removeTrack(first.id());return null;});await(()->service.readyProperty().get()&&service.playingProperty().get());
        assertEquals(List.of(second,third),fx(service::queue));assertEquals(second,fx(service::current));assertEquals(0,fx(service::queueIndex));
        fx(()->{service.pause();service.removeTrack(second.id());return null;});await(()->service.readyProperty().get());
        assertEquals(third,fx(service::current));assertFalse(fx(()->service.playingProperty().get()));
        fx(()->{service.removeTrack(third.id());return null;});
        assertTrue(fx(service::queue).isEmpty());assertNull(fx(service::current));assertEquals(-1,fx(service::queueIndex));assertEquals(0,fx(()->service.positionProperty().get()));assertEquals(0,fx(()->service.durationProperty().get()));assertEquals(MediaPlayer.Status.DISPOSED,fx(service::nativeStatus));assertNull(mediaError.get());
    }
    private void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (mediaError.get() != null) fail("Native media error: " + mediaError.get());
            if (fx(condition)) return; Thread.sleep(40);
        }
        fail("Native media state did not become ready before timeout; error=" + mediaError.get());
    }
    private static <T> T fx(Callable<T> operation) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> { try { result.complete(operation.call()); } catch (Throwable error) { result.completeExceptionally(error); } });
        return result.get(10, TimeUnit.SECONDS);
    }
}
