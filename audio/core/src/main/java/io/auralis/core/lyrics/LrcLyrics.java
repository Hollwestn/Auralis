package io.auralis.core.lyrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local LRC support including repeated timestamps and the global millisecond offset. */
public record LrcLyrics(List<Line> lines, Map<String, String> metadata) {
    public record Line(double seconds, String text) { }
    private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d{1,4}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern META = Pattern.compile("^\\[([a-zA-Z]+):(.*)]$");
    public LrcLyrics { lines = List.copyOf(lines); metadata = Map.copyOf(metadata); }
    public static LrcLyrics read(Path source) throws IOException {
        if (Files.size(source) > 2_097_152) throw new IOException("Файл текста слишком большой");
        return parse(Files.readString(source, StandardCharsets.UTF_8));
    }
    public static LrcLyrics parse(String source) {
        List<Line> lines = new ArrayList<>(); Map<String, String> metadata = new LinkedHashMap<>(); int offset = 0;
        for (String raw : source.replace("\uFEFF", "").split("\\R")) {
            Matcher stamps = TIMESTAMP.matcher(raw); List<Double> times = new ArrayList<>(); int textStart = 0;
            while (stamps.find()) {
                int minutes = Integer.parseInt(stamps.group(1)), seconds = Integer.parseInt(stamps.group(2));
                if (seconds >= 60) continue;
                double fraction = stamps.group(3) == null ? 0 : Integer.parseInt(stamps.group(3)) / Math.pow(10, stamps.group(3).length());
                times.add(minutes * 60.0 + seconds + fraction); textStart = stamps.end();
            }
            if (!times.isEmpty()) {
                String text = raw.substring(textStart).strip(); for (double time : times) lines.add(new Line(time, text));
            } else {
                Matcher meta = META.matcher(raw.strip());
                if (meta.matches()) {
                    String key = meta.group(1).toLowerCase(Locale.ROOT), value = meta.group(2).strip(); metadata.put(key, value);
                    if (key.equals("offset")) try { offset = Integer.parseInt(value); } catch (NumberFormatException ignored) { }
                }
            }
        }
        final double secondsOffset = offset / 1000.0;
        return new LrcLyrics(lines.stream().map(line -> new Line(Math.max(0, line.seconds() + secondsOffset), line.text())).sorted(Comparator.comparingDouble(Line::seconds)).toList(), metadata);
    }
    /** Index of the last line at or before the playback position, or -1 before the first lyric. */
    public int indexAt(double positionSeconds) {
        int low = 0, high = lines.size() - 1, result = -1;
        while (low <= high) { int middle = (low + high) >>> 1; if (lines.get(middle).seconds() <= positionSeconds) { result = middle; low = middle + 1; } else high = middle - 1; }
        return result;
    }
    public Optional<Line> lineAt(double positionSeconds) { int index = indexAt(positionSeconds); return index < 0 ? Optional.empty() : Optional.of(lines.get(index)); }
}
