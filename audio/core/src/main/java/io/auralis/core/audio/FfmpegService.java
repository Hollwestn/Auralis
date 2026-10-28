package io.auralis.core.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Synchronous, bounded-memory FFmpeg operations. Call from a worker, never the UI thread. */
public final class FfmpegService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String ffmpeg;
    private final String ffprobe;
    public record AudioInfo(String title, String artist, String album, String genre, double durationSeconds,
                            String codec, int sampleRate, int channels, long bitrate, boolean embeddedArtwork) { }
    public record Analysis(AudioInfo info, double peakDb, double rmsDb, double[] waveform) { }

    public FfmpegService() { this(resolveExecutable("ffmpeg"), resolveExecutable("ffprobe")); }
    public FfmpegService(String ffmpeg, String ffprobe) { this.ffmpeg = ffmpeg; this.ffprobe = ffprobe; }
    public String executable() { return ffmpeg; }
    private static String resolveExecutable(String name) {
        String configured = System.getenv("AURALIS_" + name.toUpperCase(Locale.ROOT));
        if (configured != null && !configured.isBlank()) return configured;
        String appHome = System.getProperty("auralis.home", "");
        if (!appHome.isBlank()) {
            Path packaged = Path.of(appHome).resolve("tools").resolve(name + (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? ".exe" : ""));
            if (Files.isRegularFile(packaged)) return packaged.toString();
        }
        Path bundled = Path.of("C:/Program Files/FFmpeg/bin/" + name + ".exe");
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") && Files.isRegularFile(bundled) ? bundled.toString() : name;
    }
    public boolean available() {
        try { run(List.of(ffmpeg, "-version"), Duration.ofSeconds(30)); run(List.of(ffprobe, "-version"), Duration.ofSeconds(30)); return true; }
        catch (IOException e) { return false; }
    }
    public AudioInfo probe(Path source) throws IOException {
        if (!Files.isRegularFile(source)) throw new FileNotFoundException(source.toString());
        JsonNode root = JSON.readTree(run(List.of(ffprobe, "-v", "error", "-show_format", "-show_streams", "-of", "json", source.toAbsolutePath().toString()), Duration.ofSeconds(30)));
        JsonNode audio = null; boolean artwork = false;
        for (JsonNode stream : root.path("streams")) {
            if (stream.path("codec_type").asText().equals("audio") && audio == null) audio = stream;
            if (stream.path("disposition").path("attached_pic").asInt() == 1) artwork = true;
        }
        if (audio == null) throw new IOException("В файле нет аудиодорожки: " + source.getFileName());
        JsonNode format = root.path("format");
        double duration = positiveNumber(format.path("duration").asText(), positiveNumber(audio.path("duration").asText(), 0));
        if (duration <= 0) throw new IOException("Не удалось определить длительность: " + source.getFileName());
        JsonNode tags = format.path("tags");
        String title = tag(tags, audio.path("tags"), "title");
        if (title.isBlank()) title = source.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        return new AudioInfo(title, tag(tags, audio.path("tags"), "artist"), tag(tags, audio.path("tags"), "album"),
                tag(tags, audio.path("tags"), "genre"), duration, audio.path("codec_name").asText(), audio.path("sample_rate").asInt(),
                audio.path("channels").asInt(), format.path("bit_rate").asLong(audio.path("bit_rate").asLong()), artwork);
    }
    private static double positiveNumber(String text, double fallback) {
        try { double value = Double.parseDouble(text); return Double.isFinite(value) && value > 0 ? value : fallback; } catch (NumberFormatException e) { return fallback; }
    }
    private static String tag(JsonNode first, JsonNode second, String key) {
        for (JsonNode tags : List.of(first, second)) {
            Iterator<Map.Entry<String, JsonNode>> fields = tags.fields();
            while (fields.hasNext()) { var field = fields.next(); if (field.getKey().equalsIgnoreCase(key)) return field.getValue().asText(); }
        }
        return "";
    }
    /** Full decode catches truncated/corrupt packets before a file enters the library. */
    public void validate(Path source, double durationSeconds) throws IOException {
        run(List.of(ffmpeg, "-nostdin", "-v", "error", "-xerror", "-i", source.toAbsolutePath().toString(), "-map", "0:a:0", "-f", "null", "-"),
                Duration.ofSeconds(Math.min(3600, Math.max(60, (long) durationSeconds * 2))));
    }
    public Optional<Path> extractArtwork(Path source, Path destination) throws IOException {
        Files.createDirectories(destination.toAbsolutePath().getParent());
        try {
            run(List.of(ffmpeg, "-nostdin", "-v", "error", "-i", source.toAbsolutePath().toString(), "-an", "-map", "0:v:0", "-frames:v", "1", "-vf", "scale=600:600:force_original_aspect_ratio=decrease", "-y", destination.toAbsolutePath().toString()), Duration.ofSeconds(30));
            return Files.size(destination) > 0 ? Optional.of(destination) : Optional.empty();
        } catch (IOException e) { Files.deleteIfExists(destination); return Optional.empty(); }
    }
    public double[] waveform(Path source, int points) throws IOException { return analyze(source, points).waveform(); }
    public Analysis analyze(Path source, int points) throws IOException {
        if (points < 16 || points > 200_000) throw new IllegalArgumentException("Waveform points must be 16..200000");
        AudioInfo info = probe(source);
        int sampleRate = Math.max(8000, info.sampleRate());
        int channels = Math.max(1, info.channels());
        long samplesPerBucket = Math.max(1, (long) Math.ceil(info.durationSeconds() * sampleRate * channels / points));
        double[] peaks = new double[points];
        Process process = new ProcessBuilder(ffmpeg, "-nostdin", "-v", "error", "-i", source.toAbsolutePath().toString(), "-map", "0:a:0", "-ac", Integer.toString(channels), "-ar", Integer.toString(sampleRate), "-f", "s16le", "-").start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> error = executor.submit(() -> readBounded(process.getErrorStream(), 65536));
            Future<Analysis> read = executor.submit(() -> {
                long count = 0; double squares = 0, maximum = 0;
                try (var input = new BufferedInputStream(process.getInputStream(), 65536)) {
                    int low;
                    while ((low = input.read()) >= 0) {
                        int high = input.read(); if (high < 0) break;
                        double value = (short) ((high << 8) | low) / 32768.0;
                        double absolute = Math.abs(value);
                        int bucket = (int) Math.min(points - 1, count / samplesPerBucket);
                        peaks[bucket] = Math.max(peaks[bucket], absolute);
                        maximum = Math.max(maximum, absolute); squares += value * value; count++;
                    }
                }
                if (count == 0) throw new IOException("Декодер не вернул аудиоданные");
                return new Analysis(info, decibels(maximum), decibels(Math.sqrt(squares / count)), peaks);
            });
            try {
                if (!process.waitFor(Math.min(3600, Math.max(60, (long) info.durationSeconds() * 2)), TimeUnit.SECONDS)) throw new IOException("FFmpeg: время анализа истекло");
                if (process.exitValue() != 0) throw new IOException("FFmpeg: " + new String(error.get(10, TimeUnit.SECONDS), java.nio.charset.StandardCharsets.UTF_8));
                return read.get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Анализ отменён", e); }
            catch (ExecutionException | TimeoutException e) { throw new IOException("Ошибка анализа аудио", e); }
            finally { if (process.isAlive()) process.destroyForcibly(); }
        }
    }
    private static double decibels(double amplitude) { return amplitude > 0 ? 20 * Math.log10(amplitude) : -120; }
    /** Writes a playable PCM WAV copy when JavaFX cannot decode the original codec. */
    public Path playbackCopy(Path source, Path destination) throws IOException {
        Files.createDirectories(destination.toAbsolutePath().getParent());
        run(List.of(ffmpeg, "-nostdin", "-v", "error", "-i", source.toAbsolutePath().toString(), "-map", "0:a:0", "-vn", "-c:a", "pcm_s16le", "-y", destination.toAbsolutePath().toString()), Duration.ofMinutes(30));
        return destination;
    }
    public static String run(List<String> command, Duration timeout) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> output = executor.submit(() -> readBounded(process.getInputStream(), 1_048_576));
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new IOException("Превышено время выполнения " + Path.of(command.getFirst()).getFileName());
                String text = new String(output.get(10, TimeUnit.SECONDS), java.nio.charset.StandardCharsets.UTF_8);
                if (process.exitValue() != 0) throw new IOException("Ошибка " + Path.of(command.getFirst()).getFileName() + ": " + text.strip());
                return text;
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Операция отменена", e); }
            catch (ExecutionException | TimeoutException e) { throw new IOException("Ошибка чтения ответа процесса", e); }
            finally { if (process.isAlive()) process.destroyForcibly(); }
        }
    }
    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        try (input; var result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384]; int read;
            while ((read = input.read(buffer)) >= 0) if (result.size() < limit) result.write(buffer, 0, Math.min(read, limit - result.size()));
            return result.toByteArray();
        }
    }
}
