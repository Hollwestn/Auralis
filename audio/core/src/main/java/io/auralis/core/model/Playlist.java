package io.auralis.core.model;

import java.util.List;

public record Playlist(String id, String name, String artworkPath, List<Track> tracks) {
    public Playlist { tracks = List.copyOf(tracks); }
    public double durationSeconds() { return tracks.stream().mapToDouble(Track::durationSeconds).sum(); }
}
