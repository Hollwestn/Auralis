package io.auralis.desktop.playback;

import io.auralis.core.audio.FfmpegService;
import io.auralis.core.model.Track;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.scene.media.*;
import javafx.util.Duration;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** Owns one native MediaPlayer; controls and observable properties belong to the JavaFX thread. */
public final class PlaybackService implements AutoCloseable {
    public enum Repeat { OFF, ALL, ONE }
    private final ExecutorService worker;
    private final Path cache;
    private final FfmpegService ffmpeg = new FfmpegService();
    private final List<Track> queue = new ArrayList<>();
    private int index = -1, convertingGeneration = -1;
    private volatile int generation;
    private volatile boolean closed;
    private MediaPlayer player;
    private boolean desiredPlaying, loading, ended;
    private double pendingStart, rate = 1, balance;
    private final ObjectProperty<Track> current = new SimpleObjectProperty<>();
    private final BooleanProperty playing = new SimpleBooleanProperty(false), ready = new SimpleBooleanProperty(false);
    private final DoubleProperty position = new SimpleDoubleProperty(0), duration = new SimpleDoubleProperty(0), volume = new SimpleDoubleProperty(.75);
    private final double[] gains = new double[10];
    private boolean shuffle, eqEnabled = true;
    private Repeat repeat = Repeat.OFF;
    private double sleepGain = 1;
    private Consumer<String> errorHandler = ignored -> { };
    private Consumer<Track> finishedHandler = ignored -> { };
    private AudioSpectrumListener spectrumListener;
    private ContinuousPcmPlayer continuous;
    private boolean continuousEnabled;
    private double crossfadeSeconds;
    private Map<String,Double> trackGains=Map.of();
    private Set<String> noFadeAlbums=Set.of();
    public void configureContinuous(boolean enabled,double crossfade,Map<String,Double> gains,Set<String> albums){boolean changed=continuousEnabled!=enabled;continuousEnabled=enabled;crossfadeSeconds=Math.clamp(crossfade,0,12);trackGains=Map.copyOf(gains);noFadeAlbums=Set.copyOf(albums);if(changed&&current.get()!=null)play(current.get(),position.get(),desiredPlaying&&!ended);else updateContinuous();}
    private ContinuousPcmPlayer.Options continuousOptions(){return new ContinuousPcmPlayer.Options(crossfadeSeconds,Math.clamp(volume.get(),0,1)*sleepGain,rate,balance,eqEnabled,gains,trackGains,noFadeAlbums);}
    private void updateContinuous(){if(continuous!=null){continuous.configure(continuousOptions());continuous.update(queue,index,repeat,shuffle);}}
    private Runnable queueChanged = () -> {};
    private final Deque<Runnable> queueUndo = new ArrayDeque<>();
    public void onQueueChanged(Runnable callback){queueChanged=Objects.requireNonNull(callback);}
    public void insertNext(Track track){insertQueue(track,Math.max(0,index+1));}
    public void insertQueue(Track track,int at){ensureOpen();if(at<0||at>queue.size())throw new IllegalArgumentException("Invalid queue index");boolean empty=queue.isEmpty();queue.add(at,Objects.requireNonNull(track));if(empty){index=0;play(track,0,false);}else if(index>=at)index++;updateContinuous();queueChanged.run();}
    public void moveQueueItem(int from,int to){moveQueueItem(from,to,true);}
    private void moveQueueItem(int from,int to,boolean undo){ensureOpen();if(from<0||to<0||from>=queue.size()||to>=queue.size())throw new IllegalArgumentException("Invalid queue index");Track track=queue.remove(from);queue.add(to,track);index=index==from?to:from<index&&to>=index?index-1:from>index&&to<=index?index+1:index;if(undo){queueUndo.push(()->moveQueueItem(to,from,false));trimQueueUndo();}updateContinuous();queueChanged.run();}
    public void removeQueueItem(int at){ensureOpen();if(at<0||at>=queue.size())throw new IllegalArgumentException("Invalid queue index");Track removed=queue.get(at);boolean active=at==index,autoplay=desiredPlaying&&!ended;queue.remove(at);queueUndo.push(()->insertQueue(removed,Math.min(at,queue.size())));trimQueueUndo();if(queue.isEmpty()){++generation;dispose();index=-1;current.set(null);position.set(0);duration.set(0);desiredPlaying=false;}else if(active){index=Math.min(at,queue.size()-1);play(queue.get(index),0,autoplay);}else if(at<index)index--;updateContinuous();queueChanged.run();}
    private void trimQueueUndo(){while(queueUndo.size()>20)queueUndo.removeLast();}
    public boolean canUndoQueue(){return !queueUndo.isEmpty();}
    public void undoQueue(){ensureOpen();if(!queueUndo.isEmpty())queueUndo.pop().run();}

    public PlaybackService(ExecutorService worker, Path cache) {
        this.worker = Objects.requireNonNull(worker); this.cache = Objects.requireNonNull(cache);
        volume.addListener((o, a, b) -> { if (player != null) player.setVolume(Math.clamp(b.doubleValue(), 0, 1) * sleepGain); updateContinuous(); });
    }
    public ObjectProperty<Track> currentProperty() { return current; }
    public Track current() { return current.get(); }
    public BooleanProperty playingProperty() { return playing; }
    public ReadOnlyBooleanProperty readyProperty() { return ready; }
    public DoubleProperty positionProperty() { return position; }
    public DoubleProperty durationProperty() { return duration; }
    public DoubleProperty volumeProperty() { return volume; }
    public List<Track> queue() { return List.copyOf(queue); }
    public int queueIndex() { return index; }
    public void onError(Consumer<String> handler) { errorHandler = Objects.requireNonNull(handler); }
    public void onFinished(Consumer<Track> handler) { finishedHandler = Objects.requireNonNull(handler); }
    public void setQueue(List<Track> tracks, int start) {
        ensureOpen(); Objects.requireNonNull(tracks); if (tracks.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Queue contains null track");
        queueUndo.clear();
        queue.clear(); queue.addAll(tracks);
        if (queue.isEmpty()) { ++generation; dispose(); index = -1; current.set(null); position.set(0); duration.set(0); desiredPlaying = false; queueChanged.run(); return; }
        index = Math.clamp(start, 0, queue.size() - 1); play(queue.get(index), 0, true); queueChanged.run();
    }
    public void enqueue(Track track) { ensureOpen(); queue.add(Objects.requireNonNull(track)); updateContinuous(); queueChanged.run(); }
    /** Removes every occurrence; unrelated playback retains its native decoder and position. */
    public void removeTrack(String trackId) {
        ensureOpen(); Objects.requireNonNull(trackId);
        if (queue.stream().noneMatch(track -> track.id().equals(trackId))) return;
        boolean currentRemoved = current.get() != null && current.get().id().equals(trackId);
        boolean autoplay = desiredPlaying && !ended;
        int retainedBefore = 0;
        for (int i = 0; i < index; i++) if (!queue.get(i).id().equals(trackId)) retainedBefore++;
        queue.removeIf(track -> track.id().equals(trackId));
        if (queue.isEmpty()) { setQueue(List.of(), 0); return; }
        if (currentRemoved) {
            index = Math.min(retainedBefore, queue.size() - 1);
            play(queue.get(index), 0, autoplay);
        } else if (index >= 0) index = retainedBefore;
        updateContinuous(); queueChanged.run();
    }
    public void restore(Track track, double seconds) { restore(track, seconds, false); }
    public void restore(Track track, double seconds, boolean autoplay) {
        restoreQueue(List.of(Objects.requireNonNull(track)), 0, seconds, autoplay);
    }
    public void restoreQueue(List<Track> tracks, int selectedIndex, double seconds, boolean autoplay) {
        ensureOpen(); Objects.requireNonNull(tracks);
        if (tracks.isEmpty() || selectedIndex < 0 || selectedIndex >= tracks.size() || tracks.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Invalid restored queue or index");
        if (!Double.isFinite(seconds)) throw new IllegalArgumentException("Invalid playback position");
        List<Track> restored = List.copyOf(tracks); queue.clear(); queue.addAll(restored); index = selectedIndex;
        play(queue.get(index), seconds, autoplay); queueChanged.run();
    }
    private void play(Track track, double start, boolean autoplay) {
        int request = ++generation; dispose(); desiredPlaying = autoplay; loading = true; ended = false; convertingGeneration = -1;
        pendingStart = finitePosition(start, track.durationSeconds()); current.set(track); duration.set(track.durationSeconds()); position.set(pendingStart);
        if(continuousEnabled){openContinuous(request,autoplay);return;}
        String name = track.path().getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".wav") || name.endsWith(".aif") || name.endsWith(".aiff")) open(track.path(), track, request, true);
        else convert(track, request);
    }
    private void openContinuous(int request,boolean autoplay){
        continuous=new ContinuousPcmPlayer(ffmpeg,cache,worker,new ContinuousPcmPlayer.Listener(){
            private void fx(Runnable task){Platform.runLater(()->{if(request==generation&&!closed&&continuous!=null)task.run();});}
            public void track(Track track,int selected){fx(()->{int at=continuous.currentIndex();if(at<0||at>=queue.size()||!queue.get(at).id().equals(track.id()))return;index=at;ended=false;current.set(track);duration.set(track.durationSeconds());queueChanged.run();});}
            public void state(boolean available,boolean active,double seconds,double total){fx(()->{loading=!available;ready.set(available);duration.set(total);position.set(seconds);playing.set(active);if(!active&&seconds>=total-.01&&total>0){ended=true;desiredPlaying=false;}});}
            public void finished(Track track){fx(()->finishedHandler.accept(track));}
            public void spectrum(float[] magnitudes,double seconds){fx(()->{if(spectrumListener!=null)spectrumListener.spectrumDataUpdate(seconds,.05,magnitudes,new float[magnitudes.length]);});}
            public void error(String message){fx(()->fail(message));}
        });
        continuous.start(queue,index,pendingStart,autoplay,continuousOptions(),repeat,shuffle);
    }
    private void convert(Track track, int request) {
        if (request != generation || closed || convertingGeneration == request) return;
        convertingGeneration = request; dispose(); loading = true;
        worker.submit(() -> {
            Path temporary = null;
            try {
                Files.createDirectories(cache);
                String key = track.id() + "|" + track.path().toAbsolutePath() + "|" + Files.size(track.path()) + "|" + Files.getLastModifiedTime(track.path()).toMillis();
                Path converted = cache.resolve(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)) + ".wav");
                if (!Files.isRegularFile(converted) || Files.size(converted) < 44) {
                    temporary = Files.createTempFile(cache, "decode-", ".part.wav");
                    ffmpeg.playbackCopy(track.path(), temporary);
                    // Another request may have completed the same cache key while this one decoded.
                    if (!Files.isRegularFile(converted)) try { Files.move(temporary, converted); temporary = null; } catch (FileAlreadyExistsException ignored) { }
                }
                Platform.runLater(() -> { if (request == generation && !closed) open(converted, track, request, false); });
            } catch (Exception e) { Platform.runLater(() -> { if (request == generation && !closed) fail("Не удалось декодировать: " + e.getMessage()); }); }
            finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { } }
        });
    }
    private void open(Path path, Track track, int request, boolean allowFallback) {
        if (request != generation || closed) return;
        try {
            Media media = new Media(path.toUri().toString()); MediaPlayer active = new MediaPlayer(media); player = active;
            active.setVolume(Math.clamp(volume.get(), 0, 1) * sleepGain); active.setRate(rate); active.setBalance(balance);
            applySpectrum();
            active.setOnReady(() -> {
                if (active != player || request != generation || closed) return;
                loading = false; ready.set(true);
                double total = active.getTotalDuration().toSeconds(); if (Double.isFinite(total) && total > 0) duration.set(total);
                applyEq(); active.seek(Duration.seconds(finitePosition(pendingStart, duration.get())));
                if (desiredPlaying) active.play();
            });
            active.currentTimeProperty().addListener((o, a, b) -> { if (active == player && !closed && !loading) position.set(b.toSeconds()); });
            active.statusProperty().addListener((o, a, b) -> { if (active == player) playing.set(b == MediaPlayer.Status.PLAYING); });
            Runnable handleError = () -> {
                if (active != player || request != generation || closed) return;
                if (allowFallback) convert(track, request);
                else fail("Ошибка аудио: " + (active.getError() != null ? active.getError().getMessage() : media.getError()));
            };
            active.setOnError(handleError); media.setOnError(handleError);
            active.setOnEndOfMedia(() -> {
                if (active != player || request != generation || closed) return;
                ended = true; playing.set(false); finishedHandler.accept(track);
                // A listener may replace the queue when it replenishes My Wave.
                if (active != player || request != generation || closed) return;
                // Reopen at zero: some native backends retain PLAYING at EOS and ignore play()
                // immediately after seek(), leaving observable state and playback out of sync.
                if (repeat == Repeat.ONE) play(track, 0, desiredPlaying);
                else advance(true);
            });
        } catch (Exception e) { if (allowFallback) convert(track, request); else fail("Воспроизведение недоступно: " + e.getMessage()); }
    }
    private void fail(String message) { loading = false; desiredPlaying = false; dispose(); errorHandler.accept(message); }
    public void toggle() {
        ensureOpen();
        if(continuous!=null){if(ended&&current.get()!=null){play(current.get(),0,true);return;}desiredPlaying=!desiredPlaying;continuous.pause(!desiredPlaying);return;}
        if (loading || player != null && !ready.get()) { desiredPlaying = !desiredPlaying; return; }
        if (player == null) { if (!queue.isEmpty()) { if (index < 0) index = 0; play(queue.get(index), 0, true); } return; }
        if (ended && current.get() != null) { play(current.get(), 0, true); return; }
        desiredPlaying = !playing.get();
        if (desiredPlaying) player.play(); else player.pause();
    }
    public void pause() { desiredPlaying = false; if (player != null) player.pause(); if(continuous!=null)continuous.pause(true); }
    public void setSleepGain(double value) { sleepGain = Math.clamp(value, 0, 1); if (player != null) player.setVolume(volume.get() * sleepGain); updateContinuous(); }
    public void seek(double seconds) {
        pendingStart = finitePosition(seconds, duration.get()); ended = false; position.set(pendingStart);
        if(continuous!=null&&current.get()!=null){play(current.get(),pendingStart,desiredPlaying);return;}
        if (player != null && ready.get()) player.seek(Duration.seconds(pendingStart));
    }
    public void previous() {
        ensureOpen(); if (position.get() > 3) { seek(0); return; }
        if (!queue.isEmpty()) { index = index > 0 ? index - 1 : repeat == Repeat.ALL ? queue.size() - 1 : 0; play(queue.get(index), 0, true); }
    }
    public void next() { ensureOpen(); advance(false); }
    private void advance(boolean endOfTrack) {
        if (queue.isEmpty()) return;
        int next;
        if (shuffle && queue.size() > 1) {
            next = java.util.concurrent.ThreadLocalRandom.current().nextInt(queue.size() - 1);
            if (next >= index) next++;
        } else next = index + 1;
        if (next >= queue.size()) {
            if (repeat == Repeat.ALL) next = 0;
            else { desiredPlaying = false; if (player != null) player.pause(); if(continuous!=null)continuous.pause(true); if (endOfTrack) { ended = true; position.set(duration.get()); } return; }
        }
        index = next; play(queue.get(index), 0, true);queueChanged.run();
    }
    public void setShuffle(boolean value) { shuffle = value; updateContinuous(); }
    public boolean shuffle() { return shuffle; }
    public Repeat cycleRepeat() { repeat = Repeat.values()[(repeat.ordinal() + 1) % 3]; updateContinuous();return repeat; }
    public Repeat repeat() { return repeat; }
    public void setRepeat(Repeat value) { repeat = Objects.requireNonNull(value); updateContinuous(); }
    public void setRate(double value) { if (!Double.isFinite(value) || value < .25 || value > 4) throw new IllegalArgumentException("Rate must be .25..4"); rate = value; if (player != null) player.setRate(value);if(continuous!=null&&current.get()!=null)play(current.get(),position.get(),desiredPlaying); }
    public void setBalance(double value) { if (!Double.isFinite(value) || value < -1 || value > 1) throw new IllegalArgumentException("Balance must be -1..1"); balance = value; if (player != null) player.setBalance(value); updateContinuous(); }
    public void setEqEnabled(boolean enabled) { eqEnabled = enabled; applyEq(); }
    public void setGain(int band, double value) { if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid gain"); gains[band] = Math.clamp(value, -12, 12); applyEq(); }
    public double gain(int band) { return gains[band]; }
    public int appliedEqBandCount() { return continuous!=null?10:player == null ? 0 : player.getAudioEqualizer().getBands().size(); }
    public double appliedEqGain(int band) { return continuous!=null?gains[band]:player == null ? Double.NaN : player.getAudioEqualizer().getBands().get(band).getGain(); }
    public MediaPlayer.Status nativeStatus() { return continuous!=null?playing.get()?MediaPlayer.Status.PLAYING:ready.get()?MediaPlayer.Status.PAUSED:MediaPlayer.Status.UNKNOWN:player == null ? MediaPlayer.Status.DISPOSED : player.getStatus(); }
    private void applyEq() {
        updateContinuous();
        if (player == null) return; AudioEqualizer eq = player.getAudioEqualizer(); eq.setEnabled(eqEnabled);
        for (int i = 0; i < Math.min(gains.length, eq.getBands().size()); i++) eq.getBands().get(i).setGain(gains[i]);
    }
    public void setSpectrumListener(AudioSpectrumListener listener) { spectrumListener = listener; applySpectrum(); }
    private void applySpectrum() { if (player != null) { player.setAudioSpectrumNumBands(512); player.setAudioSpectrumInterval(.05); player.setAudioSpectrumListener(spectrumListener); } }
    private void dispose() {
        ContinuousPcmPlayer oldContinuous=continuous;continuous=null;if(oldContinuous!=null)oldContinuous.close();
        ready.set(false); loading = false;
        MediaPlayer old = player; player = null;
        if (old != null) { old.setOnReady(null); old.setOnError(null); old.setOnEndOfMedia(null); old.setAudioSpectrumListener(null); old.getMedia().setOnError(null); old.dispose(); }
        playing.set(false);
    }
    private static double finitePosition(double value, double maximum) { if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid playback position"); return Math.clamp(value, 0, Double.isFinite(maximum) ? Math.max(0, maximum) : 0); }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Playback service is closed"); }
    @Override public void close() { if (closed) return; closed = true; ++generation; desiredPlaying = false; dispose(); queue.clear(); index = -1; }
}
