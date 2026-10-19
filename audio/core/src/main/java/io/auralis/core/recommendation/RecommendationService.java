package io.auralis.core.recommendation;

import io.auralis.core.library.LibraryService;
import io.auralis.core.model.Track;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Explainable content ranking using only this user's local catalog and explicit metadata. */
public final class RecommendationService {
    public enum WaveMode { MY_WAVE, DISCOVERY, FAVORITES, FOCUS, ENERGY, NIGHT, RELAX, FAMILIAR }
    public record Recommendation(Track track, double score, List<String> reasons) {
        public Recommendation { reasons = List.copyOf(reasons); }
        public String explanation() { return String.join(" · ", reasons); }
    }
    private final LibraryService library;
    public RecommendationService(LibraryService library) { this.library = Objects.requireNonNull(library); }
    public List<Recommendation> recommend(WaveMode mode, int limit) { return recommend(mode, limit, Set.of()); }
    public List<Recommendation> recommend(WaveMode mode, int limit, Set<String> excludedTrackIds) {
        if (limit < 0 || limit > 10000) throw new IllegalArgumentException("Invalid recommendation limit");
        List<Track> tracks = library.tracks();
        Map<String, Track> byId = tracks.stream().collect(Collectors.toMap(Track::id, t -> t));
        Set<String> favorites = library.favorites().stream().map(Track::id).collect(Collectors.toSet());
        Map<String, LibraryService.Reaction> reactions = new HashMap<>();
        Map<String, Double> artists = new HashMap<>(), genres = new HashMap<>();
        for (String genre : split(library.setting("preferences.genres", library.setting("genres", "")))) genres.merge(genre, 3.0, Double::sum);
        for (String artist : split(library.setting("preferences.artists", library.setting("artists", "")))) artists.merge(artist, 3.0, Double::sum);
        for (String id : favorites) { Track track = byId.get(id); if (track != null) preference(track, 2.0, artists, genres); }
        for (var feedback : library.feedback()) {
            reactions.put(feedback.trackId(), feedback.reaction()); Track track = byId.get(feedback.trackId());
            if (track != null) preference(track, switch (feedback.reaction()) { case LIKE, MORE -> 2.5; case LESS -> -1.0; case DISLIKE -> -2.0; case NONE -> 0; }, artists, genres);
        }
        Map<String, Integer> playCounts = new HashMap<>(); Map<String, Instant> lastPlayed = new HashMap<>();
        Map<String, Double> trackAffinity = new HashMap<>();
        Instant now = Instant.now();
        for (var entry : library.history(3000)) {
            Track track = entry.track();
            playCounts.merge(track.id(), 1, Integer::sum); lastPlayed.merge(track.id(), entry.playedAt(), (a, b) -> a.isAfter(b) ? a : b);
            double completion = Math.min(1, entry.listenedSeconds() / Math.max(1, track.durationSeconds()));
            double recency = Math.exp(-Math.max(0, Duration.between(entry.playedAt(), now).toDays()) / 90.0);
            // A skip merely weakens the positive listening signal; it is never treated as a dislike.
            double signal = completion * recency * (entry.skipped() ? 0.12 : 0.45);
            preference(track, signal, artists, genres); trackAffinity.merge(track.id(), signal, Double::sum);
        }
        Set<String> excludedArtists = library.excludedArtists(); List<Recommendation> candidates = new ArrayList<>();
        for (Track track : tracks) {
            if (excludedTrackIds.contains(track.id()) || excludedArtists.contains(normalize(track.artist())) || reactions.get(track.id()) == LibraryService.Reaction.DISLIKE || !Files.isRegularFile(track.path())) continue;
            if (mode == WaveMode.FAVORITES && !favorites.contains(track.id())) continue;
            List<String> reasons = new ArrayList<>(); double score = 0.25;
            double artistWeight = artists.getOrDefault(normalize(track.artist()), 0.0);
            double genreWeight = genres.getOrDefault(normalize(track.genre()), 0.0);
            score += bounded(artistWeight, 4.0) + bounded(genreWeight, 3.0);
            if (artistWeight > 0.5) reasons.add("Близко к вашим любимым исполнителям");
            if (genreWeight > 0.5 && !track.genre().isBlank()) reasons.add("Ваш жанр: " + track.genre());
            if (favorites.contains(track.id())) { score += 2; reasons.add("В избранном"); }
            LibraryService.Reaction reaction = reactions.getOrDefault(track.id(), LibraryService.Reaction.NONE);
            if (reaction == LibraryService.Reaction.LIKE || reaction == LibraryService.Reaction.MORE) { score += 3; reasons.add("Вы просили больше такой музыки"); }
            if (reaction == LibraryService.Reaction.LESS) { score -= 4; reasons.add("Понижено по вашей оценке"); }
            boolean heard = playCounts.containsKey(track.id());
            if (mode == WaveMode.DISCOVERY && !heard) { score += 4; reasons.add("Ещё не звучало в Auralis"); }
            if (mode == WaveMode.MY_WAVE && !heard) { score += 0.7; reasons.add("Новая музыка из вашей библиотеки"); }
            if (mode == WaveMode.FAMILIAR && heard) { score += Math.min(4, trackAffinity.getOrDefault(track.id(), 0.0)); reasons.add("Вы уже слушали этот трек"); }
            String genre = normalize(track.genre());
            List<String> modeGenres = switch (mode) {
                case FOCUS -> List.of("ambient", "classical", "instrumental", "soundtrack", "lo-fi", "piano", "эмбиент", "классика");
                case ENERGY -> List.of("dance", "electronic", "rock", "metal", "house", "techno", "drum", "танцевальная", "рок");
                case NIGHT -> List.of("ambient", "jazz", "soul", "downtempo", "trip-hop", "джаз");
                case RELAX -> List.of("ambient", "acoustic", "chill", "classical", "piano", "эмбиент", "акустика");
                default -> List.of();
            };
            if (!genre.isBlank() && modeGenres.stream().anyMatch(genre::contains)) { score += 3; reasons.add("Жанровые метаданные подходят режиму"); }
            Instant recent = lastPlayed.get(track.id());
            if (recent != null) {
                long minutes = Math.max(0, Duration.between(recent, now).toMinutes());
                if (minutes < 30) { score -= 5; reasons.add("Недавний повтор получает меньший приоритет"); }
                else if (minutes < 24 * 60) score -= 1.0;
                score -= Math.min(2, playCounts.get(track.id()) * 0.08);
            }
            if (reasons.isEmpty()) reasons.add("Из вашей библиотеки: предпочтения уточняются по прослушиваниям");
            candidates.add(new Recommendation(track, score, reasons));
        }
        candidates.sort(Comparator.comparingDouble(Recommendation::score).reversed().thenComparing(r -> r.track().artist()).thenComparing(r -> r.track().title()).thenComparing(r -> r.track().id()));
        // Greedy diversification breaks up runs by the same artist without inventing user preferences.
        List<Recommendation> result = new ArrayList<>(); Map<String, Integer> selectedArtists = new HashMap<>();
        while (!candidates.isEmpty() && result.size() < limit) {
            Recommendation best = null; double bestScore = -Double.MAX_VALUE;
            for (Recommendation candidate : candidates) {
                double score = candidate.score() - selectedArtists.getOrDefault(normalize(candidate.track().artist()), 0) * 1.5;
                if (!result.isEmpty() && normalize(result.getLast().track().artist()).equals(normalize(candidate.track().artist()))) score -= 1;
                if (best == null || score > bestScore) { best = candidate; bestScore = score; }
            }
            result.add(best); selectedArtists.merge(normalize(best.track().artist()), 1, Integer::sum); candidates.remove(best);
        }
        return List.copyOf(result);
    }
    private static double bounded(double value, double max) { return Math.copySign(max * (1 - Math.exp(-Math.abs(value) / max)), value); }
    private static List<String> split(String value) { return Arrays.stream(value.split("[,;\n]")).map(RecommendationService::normalize).filter(s -> !s.isBlank()).toList(); }
    private static String normalize(String text) { return text.strip().toLowerCase(Locale.ROOT); }
    private static void preference(Track track, double weight, Map<String, Double> artists, Map<String, Double> genres) {
        if (!track.artist().equals("Неизвестный исполнитель")) artists.merge(normalize(track.artist()), weight, Double::sum);
        if (!track.genre().isBlank()) genres.merge(normalize(track.genre()), weight, Double::sum);
    }
}
