package io.auralis.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.util.*;

/** Copies legacy data before the installer is allowed to remove its former application directory. */
public final class DesktopDataMigration {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> APPLICATION_DIRECTORIES = Set.of("app", "runtime", "tools", "installer", "licenses");
    private static final Set<String> DATABASE_FILES = Set.of("library.db", "library.db-wal", "library.db-shm", "library.db-journal");
    private DesktopDataMigration() { }

    /**
     * @return true only after a complete migration has been published; false if the new database
     *         already exists or no legacy database exists. Never moves/deletes legacy data.
     * @throws IOException on any migration failure; callers must not create an empty library then.
     */
    public static synchronized boolean migrateIfNeeded(Path legacyRoot, Path newRoot) throws IOException {
        Path source = Objects.requireNonNull(legacyRoot).toAbsolutePath().normalize();
        Path target = Objects.requireNonNull(newRoot).toAbsolutePath().normalize();
        if (source.startsWith(target) || target.startsWith(source)) throw new IOException("Каталоги старых и новых данных должны быть раздельными");
        if (existingDatabase(target)) return false;
        Path originalDatabase = source.resolve("library.db");
        if (!Files.exists(originalDatabase, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!Files.isRegularFile(originalDatabase, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) throw new IOException("Некорректный каталог старой библиотеки: " + source);
        Path parent = target.getParent();
        if (parent == null || target.getFileName() == null) throw new IOException("Некорректный каталог новых данных");
        Files.createDirectories(parent);
        // A persistent sibling lock avoids deleting/recreating a lock inode while another process waits.
        Path lockPath = parent.resolve("." + target.getFileName() + ".migration.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Миграция уже запущена в другом процессе");
            if (existingDatabase(target)) return false;
            ensureEmptyTarget(target);
            Path realSource = source.toRealPath(), realParent = parent.toRealPath();
            if (realSource.startsWith(realParent.resolve(target.getFileName())) || realParent.startsWith(realSource)) throw new IOException("Каталоги данных пересекаются через ссылку");
            Path staging = Files.createTempDirectory(parent, "." + target.getFileName() + "-migration-");
            boolean published = false;
            try {
                snapshot(originalDatabase, staging.resolve("library.db"));
                copyUserFiles(source, staging);
                rebaseDatabase(staging.resolve("library.db"), source, target);
                rebaseProjects(staging, source, target);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.delete(target); // Empty directory only; never recursive.
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                published = true;
                return true;
            } catch (SQLException | RuntimeException e) {
                throw new IOException("Не удалось перенести старую библиотеку; исходные данные сохранены: " + source, e);
            } catch (IOException e) {
                throw new IOException("Не удалось перенести старую библиотеку; исходные данные сохранены: " + source + ". " + e.getMessage(), e);
            } finally {
                if (!published) deleteStaging(staging);
            }
        } catch (java.nio.channels.OverlappingFileLockException e) {
            throw new IOException("Миграция уже запущена", e);
        }
    }

    private static boolean existingDatabase(Path target) throws IOException {
        if (Files.isSymbolicLink(target)) throw new IOException("Новый каталог данных не должен быть символической ссылкой");
        Path database = target.resolve("library.db");
        if (!Files.exists(database, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Новая база не является обычным файлом: " + database);
        // A zero-byte/corrupt new file must not authorize uninstall of the only working legacy copy.
        try (Connection existing = openReadOnly(database); Statement check = existing.createStatement()) {
            try (ResultSet version = check.executeQuery("PRAGMA user_version")) {
                if (!version.next() || version.getInt(1) != 1) throw new IOException("Новая библиотека имеет неподдерживаемую или незавершённую схему: " + database);
            }
            try (ResultSet integrity = check.executeQuery("PRAGMA quick_check")) {
                if (!integrity.next() || !"ok".equalsIgnoreCase(integrity.getString(1))) throw new IOException("Проверка новой библиотеки не пройдена: " + database);
            }
        } catch (SQLException e) { throw new IOException("Новая библиотека повреждена или недоступна; старая копия не заменена: " + database, e); }
        return true;
    }
    private static void ensureEmptyTarget(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Новый каталог данных занят файлом: " + target);
        try (var entries = Files.list(target)) {
            if (entries.findAny().isPresent()) throw new IOException("Новый каталог содержит файлы, но не library.db; автоматическая перезапись запрещена: " + target);
        }
    }
    private static void snapshot(Path source, Path destination) throws SQLException {
        try (Connection readOnly = openReadOnly(source);
             Statement options = readOnly.createStatement()) {
            // Do not use immutable=1: it would ignore committed transactions still present in WAL.
            options.execute("PRAGMA synchronous=FULL");
            try (PreparedStatement vacuum = readOnly.prepareStatement("VACUUM INTO ?")) {
                vacuum.setString(1, destination.toString()); vacuum.execute();
            }
        }
    }
    private static Connection openReadOnly(Path database) throws SQLException {
        SQLiteConfig config = new SQLiteConfig(); config.setReadOnly(true); config.setBusyTimeout(5000);
        return DriverManager.getConnection("jdbc:sqlite:" + database, config.toProperties());
    }
    private static void copyUserFiles(Path source, Path staging) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(source) && APPLICATION_DIRECTORIES.contains(source.relativize(dir).getName(0).toString().toLowerCase(Locale.ROOT))) return FileVisitResult.SKIP_SUBTREE;
                checkOrdinaryPath(dir, source);
                if (!dir.equals(source)) Files.createDirectory(staging.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file); String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (relative.getNameCount() == 1 && DATABASE_FILES.contains(name) || name.endsWith(".exe") || name.endsWith(".msi") || name.endsWith(".msp")) return FileVisitResult.CONTINUE;
                checkOrdinaryPath(file, source);
                if (!attrs.isRegularFile()) throw new IOException("Неподдерживаемый объект в старых данных: " + file);
                Files.copy(file, staging.resolve(relative), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }
    private static void checkOrdinaryPath(Path path, Path source) throws IOException {
        if (Files.isSymbolicLink(path) || !path.toRealPath().startsWith(source.toRealPath())) throw new IOException("Ссылка в старых данных требует ручного переноса: " + path);
    }
    private static void rebaseDatabase(Path database, Path source, Path target) throws SQLException, IOException {
        try (Connection copy = DriverManager.getConnection("jdbc:sqlite:" + database); Statement options = copy.createStatement()) {
            try (ResultSet version = options.executeQuery("PRAGMA user_version")) {
                if (!version.next() || version.getInt(1) != 1) throw new IOException("Миграция не поддерживает версию старой библиотеки");
            }
            options.execute("PRAGMA journal_mode=DELETE"); options.execute("PRAGMA synchronous=FULL"); options.execute("PRAGMA foreign_keys=ON");
            copy.setAutoCommit(false);
            try {
                rebaseColumn(copy, "tracks", "id", "path", source, target);
                rebaseColumn(copy, "tracks", "id", "artwork", source, target);
                rebaseColumn(copy, "playlists", "id", "artwork", source, target);
                rebaseColumn(copy, "import_roots", "path", "path", source, target);
                // Current settings contain IDs/flags/URLs; an entire absolute path value is safe to rebase.
                rebaseColumn(copy, "settings", "key", "value", source, target);
                copy.commit();
            } catch (SQLException | IOException | RuntimeException e) { copy.rollback(); throw e; }
            finally { copy.setAutoCommit(true); }
            try (ResultSet integrity = options.executeQuery("PRAGMA integrity_check")) {
                if (!integrity.next() || !"ok".equalsIgnoreCase(integrity.getString(1))) throw new IOException("Проверка целостности перенесённой базы не пройдена");
            }
            try (ResultSet foreignKeys = options.executeQuery("PRAGMA foreign_key_check")) {
                if (foreignKeys.next()) throw new IOException("В перенесённой базе нарушены связи библиотеки");
            }
        }
    }
    private record PathUpdate(String id, String path) { }
    private static void rebaseColumn(Connection copy, String table, String key, String column, Path source, Path target) throws SQLException, IOException {
        List<PathUpdate> changes = new ArrayList<>();
        try (Statement query = copy.createStatement(); ResultSet rows = query.executeQuery("SELECT " + key + "," + column + " FROM " + table)) {
            while (rows.next()) {
                String value = rows.getString(2), rebased = rebase(value, source, target);
                if (!Objects.equals(value, rebased)) changes.add(new PathUpdate(rows.getString(1), rebased));
            }
        }
        try (PreparedStatement update = copy.prepareStatement("UPDATE " + table + " SET " + column + "=? WHERE " + key + "=?")) {
            for (PathUpdate value : changes) { update.setString(1, value.path()); update.setString(2, value.id()); update.addBatch(); }
            update.executeBatch();
        }
    }
    private static String rebase(String value, Path source, Path target) throws IOException {
        if (value == null || value.isBlank()) return value;
        Path path;
        try { path = Path.of(value); } catch (InvalidPathException e) { return value; }
        if (!path.isAbsolute()) return value;
        path = path.normalize();
        if (!path.startsWith(source)) return value;
        Path relative = source.relativize(path);
        if (relative.getNameCount() > 0 && APPLICATION_DIRECTORIES.contains(relative.getName(0).toString().toLowerCase(Locale.ROOT))) throw new IOException("Пользовательские данные ссылаются на каталог приложения: " + path);
        return target.resolve(relative).toString();
    }
    private static void rebaseProjects(Path staging, Path source, Path target) throws IOException {
        try (var files = Files.walk(staging)) {
            for (Path file : files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".auralis.json")).toList()) {
                if (Files.size(file) > 4 * 1024 * 1024) throw new IOException("Слишком большой файл проекта при переносе: " + file.getFileName());
                JsonNode project = JSON.readTree(file.toFile());
                if (project == null || project.path("version").asInt() != 1 || !project.path("clips").isArray()) throw new IOException("Неподдерживаемый файл проекта при переносе: " + file.getFileName());
                boolean changed = false;
                for (JsonNode clip : project.path("clips")) {
                    if (!(clip instanceof ObjectNode object) || !clip.path("source").isTextual()) throw new IOException("Повреждённый фрагмент проекта: " + file.getFileName());
                    String old = clip.path("source").asText(), updated = rebase(old, source, target);
                    if (!old.equals(updated)) { object.put("source", updated); changed = true; }
                }
                if (changed) JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), project);
            }
        }
    }
    /** Only the fresh, generated staging directory is eligible for recursive cleanup. */
    private static void deleteStaging(Path staging) throws IOException {
        Path absolute = staging.toAbsolutePath().normalize();
        if (absolute.getParent() == null || !absolute.getFileName().toString().contains("-migration-") || Files.isSymbolicLink(absolute)) throw new IOException("Небезопасный путь временного каталога");
        Files.walkFileTree(absolute, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException { if (error != null) throw error; Files.delete(dir); return FileVisitResult.CONTINUE; }
        });
    }
}
