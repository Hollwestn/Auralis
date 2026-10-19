package io.auralis.server;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

final class ApiModels {
    private ApiModels() {}
    record Register(@NotBlank @Email @Size(max=254) String email,
                    @NotBlank @Size(min=10,max=128) String password,
                    @NotBlank @Size(max=100) String displayName) {}
    record Login(@NotBlank @Email @Size(max=254) String email, @NotBlank @Size(max=128) String password) {}
    record User(String id, String email, String displayName) {}
    record Session(String token, User user) {}
    record Track(String id, String title, String artist, String album, String genre,
                 double durationSeconds, long sizeBytes, String mimeType, String source, String coverUrl) {}
    record Playlist(String id, String name, List<String> trackIds, Instant updatedAt, long version) {}
    record PlaylistInput(@NotBlank @Size(max=200) String name, @Size(max=10000) List<String> trackIds, @Min(0) Long version) {}
    record HistoryInput(@NotBlank String trackId, @DecimalMin("0") @DecimalMax("86400") double playedSeconds, boolean completed, boolean skipped,
                        @Pattern(regexp="[A-Za-z0-9._:-]{1,100}") String eventId, Instant playedAt) {}
    record History(String id, String trackId, double playedSeconds, boolean completed, boolean skipped, Instant playedAt, String eventId) {}
    record Preference(@NotNull @Size(max=100) List<@NotBlank @Size(max=100) String> genres,
                      @NotNull @Size(max=100) List<@NotBlank @Size(max=300) String> artists) {}
    record FeedbackInput(@NotBlank String trackId, @NotBlank String action) {}
    record Recommendation(Track track, double score, List<String> reasons) {}
    record Sync(long version, Map<String,Object> settings, Map<String,Object> playback, Instant updatedAt) {}
    record SyncInput(@Min(0) long version, @NotNull Map<String,Object> settings, @NotNull Map<String,Object> playback) {}
    record ImportInput(@NotBlank @Size(max=1000) String path) {}
}
