package io.auralis.core.library;

import io.auralis.core.audio.FfmpegService;
import io.auralis.core.model.Playlist;
import io.auralis.core.model.Track;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Local persistent library. Public methods serialize SQLite access; callers own background execution. */
public final class LibraryService implements AutoCloseable {
    public enum Reaction { LIKE, DISLIKE, MORE, LESS, NONE }
    public record ImportFailure(Path path, String message) { }
    public record ImportResult(int imported, int duplicates, List<ImportFailure> failures) {
        public ImportResult { failures = List.copyOf(failures); }
    }
    public record HistoryEntry(long id, Track track, Instant playedAt, double listenedSeconds, boolean skipped) { }
    public record Feedback(String trackId, Reaction reaction) { }
    private static final Set<String> EXTENSIONS = Set.of("mp3", "wav", "flac", "m4a", "aac", "ogg", "opus", "aiff", "aif", "wma", "alac", "ape");
    private final Connection db;
    private final Path dataDirectory;
    private final FfmpegService audio;

    public LibraryService(Path dataDirectory) throws IOException { this(dataDirectory, new FfmpegService()); }
    public LibraryService(Path dataDirectory, FfmpegService audio) throws IOException {
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize(); this.audio = Objects.requireNonNull(audio);
        Files.createDirectories(this.dataDirectory);
        try {
            db = DriverManager.getConnection("jdbc:sqlite:" + this.dataDirectory.resolve("library.db"));
            try (Statement statement = db.createStatement()) {
                statement.execute("PRAGMA foreign_keys=ON"); statement.execute("PRAGMA journal_mode=WAL"); statement.execute("PRAGMA busy_timeout=5000");
            }
            migrate();
        } catch (SQLException e) { throw new IOException("Не удалось открыть библиотеку SQLite", e); }
    }
    public Path dataDirectory() { return dataDirectory; }
    public FfmpegService audio() { return audio; }
    private void migrate() throws SQLException {
        int version;
        try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("PRAGMA user_version")) { rs.next(); version = rs.getInt(1); }
        if (version > 1) throw new SQLException("База создана более новой версией Auralis");
        if (version == 0) {
            db.setAutoCommit(false);
            try (Statement s = db.createStatement()) {
                s.execute("CREATE TABLE tracks (id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, genre TEXT NOT NULL, path TEXT NOT NULL UNIQUE, duration REAL NOT NULL, artwork TEXT NOT NULL, content_hash TEXT NOT NULL UNIQUE, imported_at TEXT NOT NULL)");
                s.execute("CREATE TABLE favorites (track_id TEXT PRIMARY KEY REFERENCES tracks(id) ON DELETE CASCADE)");
                s.execute("CREATE TABLE playlists (id TEXT PRIMARY KEY, name TEXT NOT NULL, artwork TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL)");
                s.execute("CREATE TABLE playlist_tracks (playlist_id TEXT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE, track_id TEXT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE, position INTEGER NOT NULL, PRIMARY KEY(playlist_id, track_id))");
                s.execute("CREATE INDEX playlist_order ON playlist_tracks(playlist_id,position)");
                s.execute("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, track_id TEXT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE, played_at TEXT NOT NULL, listened_seconds REAL NOT NULL, skipped INTEGER NOT NULL)");
                s.execute("CREATE INDEX history_recent ON history(played_at DESC)");
                s.execute("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                s.execute("CREATE TABLE feedback (track_id TEXT PRIMARY KEY REFERENCES tracks(id) ON DELETE CASCADE, reaction TEXT NOT NULL)");
                s.execute("CREATE TABLE excluded_artists (artist TEXT PRIMARY KEY)");
                s.execute("CREATE TABLE import_roots (path TEXT PRIMARY KEY)");
                s.execute("PRAGMA user_version=1"); db.commit();
            } catch (SQLException e) { db.rollback(); throw e; }
            finally { db.setAutoCommit(true); }
        }
    }
    public synchronized List<Track> tracks() { return queryTracks("SELECT * FROM tracks ORDER BY imported_at DESC, title", List.of()); }
    public synchronized Optional<Track> track(String id) { return queryTracks("SELECT * FROM tracks WHERE id=?", List.of(id)).stream().findFirst(); }
    /** Catalog labels only: server synchronization never rewrites local audio tags. */
    public synchronized void updateMetadata(String trackId, String title, String artist, String album, String genre) {
        if (track(trackId).isEmpty()) throw new IllegalArgumentException("Трек не найден");
        execute("UPDATE tracks SET title=?,artist=?,album=?,genre=? WHERE id=?", Objects.requireNonNullElse(title, ""), normalizedArtist(Objects.requireNonNullElse(artist, "")), normalizedAlbum(Objects.requireNonNullElse(album, "")), Objects.requireNonNullElse(genre, ""), trackId);
    }
    public synchronized List<Track> search(String query) {
        String needle = Objects.requireNonNullElse(query, "").strip().toLowerCase(Locale.ROOT);
        return tracks().stream().filter(t -> (t.title() + " " + t.artist() + " " + t.album() + " " + t.genre()).toLowerCase(Locale.ROOT).contains(needle)).toList();
    }
    public synchronized List<Track> favorites() { return queryTracks("SELECT t.* FROM tracks t JOIN favorites f ON f.track_id=t.id ORDER BY t.title", List.of()); }
    public synchronized boolean isFavorite(String trackId) { return exists("SELECT 1 FROM favorites WHERE track_id=?", trackId); }
    public synchronized void setFavorite(String trackId, boolean favorite) {
        execute(favorite ? "INSERT OR IGNORE INTO favorites(track_id) VALUES(?)" : "DELETE FROM favorites WHERE track_id=?", trackId);
    }
    public synchronized boolean toggleFavorite(String trackId) { boolean result = !isFavorite(trackId); setFavorite(trackId, result); return result; }
    /** Deletes only the catalog entry; the original audio file is untouched. */
    public synchronized void removeTrack(String trackId) { execute("DELETE FROM tracks WHERE id=?", trackId); }
    public ImportResult importPaths(Collection<Path> paths) { return importPaths(paths, ignored -> { }); }
    public ImportResult importPaths(Collection<Path> paths, Consumer<Path> progress) {
        Objects.requireNonNull(paths); Objects.requireNonNull(progress);
        Set<Path> files = new LinkedHashSet<>(); List<ImportFailure> failures = new ArrayList<>();
        for (Path candidate : paths) {
            Path path = candidate.toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                synchronized (this) { execute("INSERT OR IGNORE INTO import_roots(path) VALUES(?)", path.toString()); }
                try (Stream<Path> stream = Files.walk(path)) { stream.filter(Files::isRegularFile).filter(LibraryService::supportedExtension).map(p -> p.toAbsolutePath().normalize()).forEach(files::add); }
                catch (IOException | UncheckedIOException e) { failures.add(new ImportFailure(path, e.getMessage())); }
            } else if (Files.isRegularFile(path) && supportedExtension(path)) files.add(path);
            else failures.add(new ImportFailure(path, "Файл отсутствует или расширение не поддерживается"));
        }
        int imported = 0, duplicates = 0;
        for (Path file : files) {
            if (Thread.currentThread().isInterrupted()) { failures.add(new ImportFailure(file, "Импорт отменён")); break; }
            progress.accept(file);
            try {
                Path real = file.toRealPath();
                synchronized (this) { if (exists("SELECT 1 FROM tracks WHERE path=?", real.toString())) { duplicates++; continue; } }
                String hash = sha256(real);
                synchronized (this) {
                    if (exists("SELECT 1 FROM tracks WHERE content_hash=?", hash)) {
                        // A moved file retains its stable id, favorites and playlist membership.
                        try (PreparedStatement p = db.prepareStatement("SELECT path FROM tracks WHERE content_hash=?")) {
                            p.setString(1, hash); try (ResultSet rs = p.executeQuery()) {
                                if (rs.next() && !Files.isRegularFile(Path.of(rs.getString(1)))) execute("UPDATE tracks SET path=? WHERE content_hash=?", real.toString(), hash);
                            }
                        }
                        duplicates++; continue;
                    }
                }
                FfmpegService.AudioInfo info = audio.probe(real); audio.validate(real, info.durationSeconds());
                String artwork = "";
                if (info.embeddedArtwork()) artwork = audio.extractArtwork(real, dataDirectory.resolve("artwork").resolve(hash + ".png")).map(Path::toString).orElse("");
                // Many music folders provide an external cover instead of an embedded picture.
                if (artwork.isBlank()) for (String name : List.of("cover.jpg", "cover.png", "folder.jpg", "folder.png")) {
                    Path cover = real.getParent().resolve(name); if (Files.isRegularFile(cover)) { artwork = cover.toString(); break; }
                }
                String id = UUID.randomUUID().toString();
                synchronized (this) {
                    // Recheck after decoding because a second import may run concurrently.
                    if (exists("SELECT 1 FROM tracks WHERE content_hash=?", hash)) { duplicates++; continue; }
                    execute("INSERT INTO tracks VALUES(?,?,?,?,?,?,?,?,?,?)", id, info.title(), normalizedArtist(info.artist()), normalizedAlbum(info.album()), info.genre(), real.toString(), info.durationSeconds(), artwork, hash, Instant.now().toString());
                }
                imported++;
            } catch (IOException | SQLException | RuntimeException e) { failures.add(new ImportFailure(file, Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()))); }
        }
        return new ImportResult(imported, duplicates, failures);
    }
    public synchronized List<Path> importRoots() {
        List<Path> result = new ArrayList<>();
        try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT path FROM import_roots ORDER BY path")) { while (rs.next()) result.add(Path.of(rs.getString(1))); }
        catch (SQLException e) { throw failure(e); }
        return List.copyOf(result);
    }
    public ImportResult rescan() { return importPaths(importRoots()); }
    public synchronized void forgetImportRoot(Path path) { execute("DELETE FROM import_roots WHERE path=?", path.toAbsolutePath().normalize().toString()); }
    public synchronized Playlist createPlaylist(String name) {
        String id = UUID.randomUUID().toString(); execute("INSERT INTO playlists(id,name,created_at) VALUES(?,?,?)", id, validName(name), Instant.now().toString());
        return playlist(id).orElseThrow();
    }
    public synchronized void renamePlaylist(String id, String name) { execute("UPDATE playlists SET name=? WHERE id=?", validName(name), id); }
    public synchronized void deletePlaylist(String id) { execute("DELETE FROM playlists WHERE id=?", id); }
    public synchronized void setPlaylistArtwork(String id, Path artwork) throws IOException {
        if (!Files.isRegularFile(artwork)) throw new FileNotFoundException(artwork.toString());
        String filename = artwork.getFileName().toString(); int dot = filename.lastIndexOf('.');
        String extension = dot < 0 ? ".png" : filename.substring(dot).toLowerCase(Locale.ROOT);
        if (!Set.of(".png", ".jpg", ".jpeg", ".webp").contains(extension)) throw new IOException("Для обложки выберите PNG, JPEG или WebP");
        Path target = dataDirectory.resolve("artwork").resolve("playlist-" + UUID.randomUUID() + extension);
        Files.createDirectories(target.getParent()); Files.copy(artwork, target);
        execute("UPDATE playlists SET artwork=? WHERE id=?", target.toString(), id);
    }
    public synchronized List<Playlist> playlists() {
        List<Playlist> result = new ArrayList<>();
        try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT id,name,artwork FROM playlists ORDER BY created_at")) {
            while (rs.next()) result.add(readPlaylist(rs));
        } catch (SQLException e) { throw failure(e); }
        return List.copyOf(result);
    }
    public synchronized Optional<Playlist> playlist(String id) {
        try (PreparedStatement p = db.prepareStatement("SELECT id,name,artwork FROM playlists WHERE id=?")) {
            p.setString(1, id); try (ResultSet rs = p.executeQuery()) { return rs.next() ? Optional.of(readPlaylist(rs)) : Optional.empty(); }
        } catch (SQLException e) { throw failure(e); }
    }
    private Playlist readPlaylist(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        return new Playlist(id, rs.getString("name"), rs.getString("artwork"), queryTracks("SELECT t.* FROM tracks t JOIN playlist_tracks p ON p.track_id=t.id WHERE p.playlist_id=? ORDER BY p.position", List.of(id)));
    }
    public synchronized void addToPlaylist(String playlistId, String trackId) {
        execute("INSERT OR IGNORE INTO playlist_tracks(playlist_id,track_id,position) SELECT ?,?,COALESCE(MAX(position),-1)+1 FROM playlist_tracks WHERE playlist_id=?", playlistId, trackId, playlistId);
    }
    public synchronized void removeFromPlaylist(String playlistId, String trackId) { execute("DELETE FROM playlist_tracks WHERE playlist_id=? AND track_id=?", playlistId, trackId); }
    public synchronized void reorderPlaylist(String playlistId, List<String> orderedTrackIds) {
        List<String> current = playlist(playlistId).orElseThrow(() -> new IllegalArgumentException("Плейлист не найден")).tracks().stream().map(Track::id).toList();
        if (orderedTrackIds.size() != current.size() || new HashSet<>(orderedTrackIds).size() != current.size() || !new HashSet<>(orderedTrackIds).equals(new HashSet<>(current))) throw new IllegalArgumentException("Порядок должен содержать каждый трек ровно один раз");
        try {
            db.setAutoCommit(false);
            try (PreparedStatement p = db.prepareStatement("UPDATE playlist_tracks SET position=? WHERE playlist_id=? AND track_id=?")) {
                for (int i = 0; i < orderedTrackIds.size(); i++) { p.setInt(1, i); p.setString(2, playlistId); p.setString(3, orderedTrackIds.get(i)); p.addBatch(); }
                p.executeBatch(); db.commit();
            } catch (SQLException e) { db.rollback(); throw e; } finally { db.setAutoCommit(true); }
        } catch (SQLException e) { throw failure(e); }
    }
    public synchronized void replacePlaylistContent(String id, String name, List<String> trackIds) {
        String safeName = validName(name); List<String> ordered = new ArrayList<>(new LinkedHashSet<>(Objects.requireNonNull(trackIds)));
        if (playlist(id).isEmpty()) throw new IllegalArgumentException("Плейлист не найден");
        for (String trackId : ordered) if (trackId == null || track(trackId).isEmpty()) throw new IllegalArgumentException("Трек плейлиста не найден: " + trackId);
        try {
            if (!db.getAutoCommit()) throw new IllegalStateException("Nested playlist transaction is not supported");
            db.setAutoCommit(false);
            try {
                execute("UPDATE playlists SET name=? WHERE id=?", safeName, id); execute("DELETE FROM playlist_tracks WHERE playlist_id=?", id);
                for (int i = 0; i < ordered.size(); i++) execute("INSERT INTO playlist_tracks(playlist_id,track_id,position) VALUES(?,?,?)", id, ordered.get(i), i);
                db.commit();
            } catch (RuntimeException | SQLException e) { db.rollback(); throw e; }
            finally { db.setAutoCommit(true); }
        } catch (SQLException e) { throw failure(e); }
    }
    public Playlist importM3u(Path source) throws IOException {
        List<Path> paths = new ArrayList<>();
        for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
            String value = line.replace("\uFEFF", "").strip();
            if (value.isBlank() || value.startsWith("#")) continue;
            // This local importer deliberately never fetches URLs from a playlist.
            if (value.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) continue;
            Path path = Path.of(value); if (!path.isAbsolute()) path = source.toAbsolutePath().getParent().resolve(path); paths.add(path.normalize());
        }
        importPaths(paths);
        Playlist result = createPlaylist(source.getFileName().toString().replaceFirst("(?i)\\.m3u8?$", ""));
        synchronized (this) {
            for (Path path : paths) {
                if (!Files.isRegularFile(path)) continue;
                String hash = sha256(path);
                List<Track> found = queryTracks("SELECT * FROM tracks WHERE content_hash=?", List.of(hash));
                if (!found.isEmpty()) addToPlaylist(result.id(), found.getFirst().id());
            }
            return playlist(result.id()).orElseThrow();
        }
    }
    public synchronized void exportM3u(String playlistId, Path destination) throws IOException {
        Playlist playlist = playlist(playlistId).orElseThrow(() -> new IllegalArgumentException("Плейлист не найден"));
        Path target = destination.toAbsolutePath().normalize();
        for (Track track : tracks()) if (target.equals(track.path()) || Files.exists(target) && Files.isSameFile(track.path(), target)) throw new IOException("Плейлист нельзя сохранять поверх исходного аудио");
        List<String> lines = new ArrayList<>(); lines.add("#EXTM3U");
        for (Track track : playlist.tracks()) {
            lines.add("#EXTINF:" + (int) track.durationSeconds() + "," + cleanLine(track.artist()) + " - " + cleanLine(track.title()));
            lines.add(track.path().toAbsolutePath().toString());
        }
        writeAtomically(destination, String.join("\n", lines) + "\n");
    }
    public synchronized void recordListen(String trackId, double listenedSeconds, boolean skipped) {
        recordListenAt(trackId, listenedSeconds, skipped, Instant.now());
    }
    public synchronized long recordListenAt(String trackId, double listenedSeconds, boolean skipped, Instant playedAt) {
        if (!Double.isFinite(listenedSeconds) || listenedSeconds < 0) throw new IllegalArgumentException("Invalid listened duration");
        Track track = track(trackId).orElseThrow(() -> new IllegalArgumentException("Трек не найден"));
        execute("INSERT INTO history(track_id,played_at,listened_seconds,skipped) VALUES(?,?,?,?)", trackId, Objects.requireNonNull(playedAt).toString(), Math.min(listenedSeconds, track.durationSeconds()), skipped ? 1 : 0);
        try (Statement statement = db.createStatement(); ResultSet result = statement.executeQuery("SELECT last_insert_rowid()")) { result.next(); return result.getLong(1); }
        catch (SQLException e) { throw failure(e); }
    }
    /** Commits the remote event and both synchronization markers together, including across restarts. */
    public synchronized boolean importRemoteListen(String sourceKey, String remoteEventId, String trackId,
                                                    double seconds, boolean skipped, Instant playedAt) {
        if (sourceKey == null || sourceKey.isBlank() || remoteEventId == null || remoteEventId.isBlank()) throw new IllegalArgumentException("Remote history identity is required");
        String received = "historyReceived." + sourceKey + "." + remoteEventId;
        if (Boolean.parseBoolean(setting(received, "false"))) return false;
        try {
            if (!db.getAutoCommit()) throw new IllegalStateException("Nested history transaction is not supported");
            db.setAutoCommit(false);
            try {
                long localId = recordListenAt(trackId, seconds, skipped, playedAt);
                setSetting(received, "true"); setSetting("historySent." + sourceKey + "." + localId, "true");
                db.commit(); return true;
            } catch (RuntimeException | SQLException e) { db.rollback(); throw e; }
            finally { db.setAutoCommit(true); }
        } catch (SQLException e) { throw failure(e); }
    }
    public synchronized List<HistoryEntry> history(int limit) {
        if (limit < 0 || limit > 100_000) throw new IllegalArgumentException("Invalid history limit");
        List<HistoryEntry> result = new ArrayList<>();
        try (PreparedStatement p = db.prepareStatement("SELECT t.*,h.id AS history_id,h.played_at,h.listened_seconds,h.skipped FROM history h JOIN tracks t ON h.track_id=t.id ORDER BY julianday(h.played_at) DESC,h.id DESC LIMIT ?")) {
            p.setInt(1, limit); try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) result.add(new HistoryEntry(rs.getLong("history_id"), readTrack(rs), Instant.parse(rs.getString("played_at")), rs.getDouble("listened_seconds"), rs.getInt("skipped") != 0));
            }
        } catch (SQLException e) { throw failure(e); }
        return List.copyOf(result);
    }
    public synchronized void clearHistory() { execute("DELETE FROM history"); }
    public synchronized void setReaction(String trackId, Reaction reaction) {
        if (reaction == Reaction.NONE) execute("DELETE FROM feedback WHERE track_id=?", trackId);
        else execute("INSERT INTO feedback(track_id,reaction) VALUES(?,?) ON CONFLICT(track_id) DO UPDATE SET reaction=excluded.reaction", trackId, reaction.name());
    }
    public synchronized Reaction reaction(String trackId) { return feedback().stream().filter(f -> f.trackId().equals(trackId)).map(Feedback::reaction).findFirst().orElse(Reaction.NONE); }
    public synchronized List<Feedback> feedback() {
        List<Feedback> result = new ArrayList<>();
        try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT track_id,reaction FROM feedback")) { while (rs.next()) result.add(new Feedback(rs.getString(1), Reaction.valueOf(rs.getString(2)))); }
        catch (SQLException e) { throw failure(e); }
        return List.copyOf(result);
    }
    public synchronized void excludeArtist(String artist, boolean exclude) { execute(exclude ? "INSERT OR IGNORE INTO excluded_artists(artist) VALUES(?)" : "DELETE FROM excluded_artists WHERE artist=?", artist.strip().toLowerCase(Locale.ROOT)); }
    public synchronized Set<String> excludedArtists() {
        Set<String> result = new HashSet<>();
        try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT artist FROM excluded_artists")) { while (rs.next()) result.add(rs.getString(1)); }
        catch (SQLException e) { throw failure(e); }
        return Set.copyOf(result);
    }
    public synchronized String setting(String key, String fallback) {
        try (PreparedStatement p = db.prepareStatement("SELECT value FROM settings WHERE key=?")) { p.setString(1, key); try (ResultSet rs = p.executeQuery()) { return rs.next() ? rs.getString(1) : fallback; } }
        catch (SQLException e) { throw failure(e); }
    }
    public synchronized void setSetting(String key, String value) { execute("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, Objects.requireNonNull(value)); }
    private List<Track> queryTracks(String sql, List<?> values) {
        List<Track> result = new ArrayList<>();
        try (PreparedStatement p = db.prepareStatement(sql)) { bind(p, values.toArray()); try (ResultSet rs = p.executeQuery()) { while (rs.next()) result.add(readTrack(rs)); } }
        catch (SQLException e) { throw failure(e); }
        return List.copyOf(result);
    }
    private static Track readTrack(ResultSet rs) throws SQLException { return new Track(rs.getString("id"), rs.getString("title"), rs.getString("artist"), rs.getString("album"), rs.getString("genre"), Path.of(rs.getString("path")), rs.getDouble("duration"), rs.getString("artwork")); }
    private boolean exists(String sql, Object... values) {
        try (PreparedStatement p = db.prepareStatement(sql)) { bind(p, values); try (ResultSet rs = p.executeQuery()) { return rs.next(); } }
        catch (SQLException e) { throw failure(e); }
    }
    private void execute(String sql, Object... values) { try (PreparedStatement p = db.prepareStatement(sql)) { bind(p, values); p.executeUpdate(); } catch (SQLException e) { throw failure(e); } }
    private static void bind(PreparedStatement p, Object[] values) throws SQLException { for (int i = 0; i < values.length; i++) p.setObject(i + 1, values[i]); }
    private static IllegalStateException failure(SQLException e) { return new IllegalStateException("Ошибка библиотеки: " + e.getMessage(), e); }
    private static String normalizedArtist(String text) { return text.isBlank() ? "Неизвестный исполнитель" : text; }
    private static String normalizedAlbum(String text) { return text.isBlank() ? "Без альбома" : text; }
    private static String validName(String text) { if (text == null || text.isBlank() || text.strip().length() > 200) throw new IllegalArgumentException("Название должно содержать от 1 до 200 символов"); return text.strip(); }
    private static String cleanLine(String value) { return value.replace('\r', ' ').replace('\n', ' '); }
    public static boolean supportedExtension(Path path) { String name = path.getFileName().toString(); int dot = name.lastIndexOf('.'); return dot >= 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT)); }
    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; int count;
            try (InputStream input = Files.newInputStream(file)) { while ((count = input.read(buffer)) >= 0) { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Импорт отменён"); digest.update(buffer, 0, count); } }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static void writeAtomically(Path destination, String text) throws IOException {
        Path absolute = destination.toAbsolutePath().normalize(); Files.createDirectories(absolute.getParent());
        Path temp = Files.createTempFile(absolute.getParent(), ".auralis-", ".tmp");
        try { Files.writeString(temp, text, StandardCharsets.UTF_8); try { Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); } catch (AtomicMoveNotSupportedException e) { Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING); } }
        finally { Files.deleteIfExists(temp); }
    }
    @Override public synchronized void close() throws IOException { try { db.close(); } catch (SQLException e) { throw new IOException("Ошибка закрытия библиотеки", e); } }
}
