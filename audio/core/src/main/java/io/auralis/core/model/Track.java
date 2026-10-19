package io.auralis.core.model;

import java.nio.file.Path;
import java.util.Objects;

/** A catalog entry. Audio remains at its original path; imports never change it. */
public record Track(String id, String title, String artist, String album, String genre,
                    Path path, double durationSeconds, String artworkPath) {
    public Track {
        Objects.requireNonNull(id); Objects.requireNonNull(path);
        title = title == null || title.isBlank() ? path.getFileName().toString() : title;
        artist = artist == null || artist.isBlank() ? "Неизвестный исполнитель" : artist;
        album = album == null || album.isBlank() ? "Без альбома" : album;
        genre = genre == null ? "" : genre;
        artworkPath = artworkPath == null ? "" : artworkPath;
        if (!Double.isFinite(durationSeconds) || durationSeconds < 0) throw new IllegalArgumentException("Invalid duration");
    }
    public String formattedDuration() {
        long seconds = Math.round(durationSeconds);
        return seconds >= 3600 ? "%d:%02d:%02d".formatted(seconds / 3600, seconds / 60 % 60, seconds % 60)
                : "%d:%02d".formatted(seconds / 60, seconds % 60);
    }
}
