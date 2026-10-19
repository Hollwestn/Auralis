package io.auralis.desktop;

import io.auralis.core.editor.AudioEditor;
import io.auralis.core.editor.EditProject;
import io.auralis.core.editor.EditProject.Clip;
import io.auralis.core.library.LibraryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DesktopDataMigrationTest {
    @TempDir Path temp;

    @Test void snapshotsActiveWalAndPreservesLibraryProjectsAndExternalOriginals() throws Exception {
        Path legacy = temp.resolve("старые данные"), target = temp.resolve("новые данные");
        createSchema(legacy);
        Path managed = write(legacy.resolve("server-cache/song.wav"), "managed music");
        Path external = write(temp.resolve("старые данные-внешние/song.wav"), "external original");
        Path artwork = write(legacy.resolve("artwork/track.png"), "artwork");
        Path externalArtwork = write(temp.resolve("external-cover.png"), "external cover");
        write(legacy.resolve("app/auralis.jar"), "program"); write(legacy.resolve("runtime/bin/java.exe"), "runtime");
        write(legacy.resolve("tools/ffmpeg.exe"), "tool"); write(legacy.resolve("Auralis.exe"), "launcher"); write(legacy.resolve("setup.msi"), "installer");
        Path projectPath = legacy.resolve("projects/session.auralis.json");
        AudioEditor editor = new AudioEditor();
        EditProject project = EditProject.of(managed, 3).withClips(List.of(new Clip(managed, 0, 2, 0, 0, 0), new Clip(external, 0, 1, 0, 0, 0)));
        editor.saveProject(project, projectPath); byte[] originalProject = Files.readAllBytes(projectPath);
        try (Connection writer = connect(legacy)) {
            execute(writer, "PRAGMA wal_autocheckpoint=0");
            insertTrack(writer, "managed", managed, artwork.toString()); insertTrack(writer, "external", external, externalArtwork.toString());
            execute(writer, "INSERT INTO favorites VALUES('managed')");
            execute(writer, "INSERT INTO playlists VALUES(?,?,?,?)", "playlist", "Моя музыка", artwork.toString(), "2026-10-18T00:00:00Z");
            execute(writer, "INSERT INTO playlist_tracks VALUES('playlist','external',0)"); execute(writer, "INSERT INTO playlist_tracks VALUES('playlist','managed',1)");
            execute(writer, "INSERT INTO history(track_id,played_at,listened_seconds,skipped) VALUES('managed','2026-10-18T00:00:00Z',12.5,0)");
            execute(writer, "INSERT INTO import_roots VALUES(?)", managed.getParent().toString()); execute(writer, "INSERT INTO import_roots VALUES(?)", external.getParent().toString());
            execute(writer, "INSERT INTO settings VALUES('theme','MIDNIGHT_PURPLE')");
            execute(writer, "INSERT INTO settings VALUES('lastProject',?)", projectPath.toString());
            execute(writer, "INSERT INTO settings VALUES('externalPath',?)", external.toString());
            execute(writer, "INSERT INTO settings VALUES('serverUrl','https://music.example.test')");
            byte[] databaseBefore = Files.readAllBytes(legacy.resolve("library.db"));
            byte[] walBefore = Files.readAllBytes(legacy.resolve("library.db-wal")); assertTrue(walBefore.length > 32, "Test must retain committed WAL changes");

            assertTrue(DesktopDataMigration.migrateIfNeeded(legacy, target));
            assertArrayEquals(databaseBefore, Files.readAllBytes(legacy.resolve("library.db")), "Read-only source must not checkpoint/rewrite the original database");
            assertArrayEquals(walBefore, Files.readAllBytes(legacy.resolve("library.db-wal")), "Source committed WAL must remain intact");
            assertArrayEquals(originalProject, Files.readAllBytes(projectPath), "Original project JSON must remain unchanged");
            assertEquals("external original", Files.readString(external));
            try (LibraryService migrated = new LibraryService(target)) {
                assertEquals(2, migrated.tracks().size(), "Committed WAL rows must survive snapshot");
                assertEquals(target.resolve("server-cache/song.wav"), migrated.track("managed").orElseThrow().path());
                assertEquals(target.resolve("artwork/track.png").toString(), migrated.track("managed").orElseThrow().artworkPath());
                assertEquals(external, migrated.track("external").orElseThrow().path());
                assertEquals(externalArtwork.toString(), migrated.track("external").orElseThrow().artworkPath());
                assertTrue(migrated.isFavorite("managed")); assertEquals(1, migrated.history(10).size());
                assertEquals(List.of("external", "managed"), migrated.playlist("playlist").orElseThrow().tracks().stream().map(t -> t.id()).toList());
                assertEquals(target.resolve("artwork/track.png").toString(), migrated.playlist("playlist").orElseThrow().artworkPath());
                assertEquals(Set.of(target.resolve("server-cache"), external.getParent()), new HashSet<>(migrated.importRoots()));
                assertEquals("MIDNIGHT_PURPLE", migrated.setting("theme", ""));
                assertEquals(target.resolve("projects/session.auralis.json").toString(), migrated.setting("lastProject", ""));
                assertEquals(external.toString(), migrated.setting("externalPath", "")); assertEquals("https://music.example.test", migrated.setting("serverUrl", ""));
            }
            assertEquals(List.of(target.resolve("server-cache/song.wav"), external), editor.loadProject(target.resolve("projects/session.auralis.json")).clips().stream().map(Clip::source).toList());
            assertFalse(Files.exists(target.resolve("app"))); assertFalse(Files.exists(target.resolve("runtime"))); assertFalse(Files.exists(target.resolve("tools")));
            assertFalse(Files.exists(target.resolve("Auralis.exe"))); assertFalse(Files.exists(target.resolve("setup.msi")));
            assertEquals("managed music", Files.readString(target.resolve("server-cache/song.wav")));
            assertFalse(DesktopDataMigration.migrateIfNeeded(legacy, target), "An existing new library must never be overwritten");
            assertEquals(managed.toString(), scalar(writer, "SELECT path FROM tracks WHERE id='managed'"));
        }
        try (LibraryService reopened = new LibraryService(target)) { assertTrue(reopened.isFavorite("managed")); assertEquals(2, reopened.tracks().size()); }
    }

    @Test void absentLegacyDoesNotCreateAnEmptyNewLibraryAndExistingNewDataWins() throws Exception {
        Path legacy = temp.resolve("legacy"), target = temp.resolve("new");
        assertFalse(DesktopDataMigration.migrateIfNeeded(legacy, target)); assertFalse(Files.exists(target));
        createSchema(target);
        try (LibraryService library = new LibraryService(target)) { library.setSetting("sentinel", "keep"); }
        write(legacy.resolve("library.db"), "not sqlite");
        assertFalse(DesktopDataMigration.migrateIfNeeded(legacy, target));
        try (LibraryService library = new LibraryService(target)) { assertEquals("keep", library.setting("sentinel", "")); }
    }

    @Test void corruptedDatabaseFailsWithoutPublishingAndCanRetryAfterRepair() throws Exception {
        Path legacy = temp.resolve("legacy"), target = temp.resolve("new");
        Path database = write(legacy.resolve("library.db"), "not sqlite"); byte[] original = Files.readAllBytes(database);
        IOException error = assertThrows(IOException.class, () -> DesktopDataMigration.migrateIfNeeded(legacy, target));
        assertTrue(error.getMessage().contains("исходные данные сохранены")); assertFalse(Files.exists(target.resolve("library.db"))); assertArrayEquals(original, Files.readAllBytes(database));
        try (var entries = Files.list(temp)) { assertFalse(entries.anyMatch(p -> p.getFileName().toString().startsWith(".new-migration-"))); }
        Files.delete(database); createSchema(legacy);
        assertTrue(DesktopDataMigration.migrateIfNeeded(legacy, target));
    }

    @Test void targetCollisionAndNestedPathsCannotOverwriteExistingFiles() throws Exception {
        Path legacy = temp.resolve("legacy"), target = temp.resolve("new"); createSchema(legacy);
        Path sentinel = write(target.resolve("keep.txt"), "keep");
        assertThrows(IOException.class, () -> DesktopDataMigration.migrateIfNeeded(legacy, target)); assertEquals("keep", Files.readString(sentinel));
        assertFalse(Files.exists(target.resolve("library.db")));
        assertThrows(IOException.class, () -> DesktopDataMigration.migrateIfNeeded(legacy, legacy.resolve("nested")));
        assertThrows(IOException.class, () -> DesktopDataMigration.migrateIfNeeded(legacy, legacy));
    }

    @Test void unsupportedSchemaFailsAndLeavesLegacyFilesUnchanged() throws Exception {
        Path legacy = temp.resolve("legacy"), target = temp.resolve("new"); createSchema(legacy);
        try (Connection connection = connect(legacy)) { execute(connection, "PRAGMA user_version=999"); }
        Path custom = write(legacy.resolve("projects/work.txt"), "work");
        assertThrows(IOException.class, () -> DesktopDataMigration.migrateIfNeeded(legacy, target));
        assertFalse(Files.exists(target.resolve("library.db"))); assertEquals("work", Files.readString(custom));
        try (Connection connection = connect(legacy)) { assertEquals("999", scalar(connection, "PRAGMA user_version")); }
    }

    @Test void emptyTargetIsSupportedButMalformedSavedProjectCannotBeSilentlyLost() throws Exception {
        Path legacy = temp.resolve("legacy"), target = temp.resolve("new"); createSchema(legacy); Files.createDirectory(target);
        Path project = write(legacy.resolve("projects/broken.auralis.json"), "broken JSON");
        assertThrows(IOException.class, () -> DesktopDataMigration.migrateIfNeeded(legacy, target)); assertEquals("broken JSON", Files.readString(project));
        assertFalse(Files.exists(target.resolve("library.db")));
        Files.delete(project); assertTrue(DesktopDataMigration.migrateIfNeeded(legacy, target)); assertTrue(Files.isRegularFile(target.resolve("library.db")));
    }

    @Test void invalidExistingNewDatabaseCannotAuthorizeSkippingLegacyPreservation() throws Exception {
        Path legacy = temp.resolve("legacy"), target = temp.resolve("new"); createSchema(legacy);
        Path newDatabase = write(target.resolve("library.db"), "");
        assertThrows(IOException.class, () -> DesktopDataMigration.migrateIfNeeded(legacy, target));
        assertEquals(0, Files.size(newDatabase));
        Files.writeString(newDatabase, "corrupt new database");
        assertThrows(IOException.class, () -> DesktopDataMigration.migrateIfNeeded(legacy, target));
        assertEquals("corrupt new database", Files.readString(newDatabase));
        try (LibraryService original = new LibraryService(legacy)) { assertTrue(original.tracks().isEmpty()); }
    }

    private static Path write(Path file, String data) throws IOException { Files.createDirectories(file.getParent()); Files.writeString(file, data); return file; }
    private static void createSchema(Path root) throws IOException { try (LibraryService ignored = new LibraryService(root)) { } }
    private static Connection connect(Path root) throws SQLException { return DriverManager.getConnection("jdbc:sqlite:" + root.resolve("library.db")); }
    private static void execute(Connection connection, String sql, String... args) throws SQLException { try (PreparedStatement statement = connection.prepareStatement(sql)) { for (int i = 0; i < args.length; i++) statement.setString(i + 1, args[i]); statement.execute(); } }
    private static String scalar(Connection connection, String sql) throws SQLException { try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) { assertTrue(result.next()); return result.getString(1); } }
    private static void insertTrack(Connection connection, String id, Path file, String artwork) throws SQLException { execute(connection, "INSERT INTO tracks VALUES(?,?,?,?,?,?,?,?,?,?)", id, id, "artist", "album", "genre", file.toString(), "3.0", artwork, id + "-hash", "2026-10-18T00:00:00Z"); }
}
