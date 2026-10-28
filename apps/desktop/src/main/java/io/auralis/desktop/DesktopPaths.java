package io.auralis.desktop;

import java.nio.file.*;
import java.util.Map;

/** Keep user data outside packages; honor absolute XDG paths on Linux. */
public final class DesktopPaths {
    private DesktopPaths() {}
    public static Path data(String os, Path home, Map<String,String> env) {
        if (os.toLowerCase(java.util.Locale.ROOT).startsWith("linux")) {
            String configured=env.get("XDG_DATA_HOME");
            Path base=configured==null||configured.isBlank()?home.resolve(".local/share"):Path.of(configured);
            if(!base.isAbsolute())base=home.resolve(".local/share");
            return base.resolve("auralis");
        }
        String local=env.get("LOCALAPPDATA");return (local==null||local.isBlank()?home:Path.of(local)).resolve("AuralisData");
    }
    public static Path legacy(String os, Path home, Map<String,String> env) {
        if(os.toLowerCase(java.util.Locale.ROOT).startsWith("linux")) {
            Path previous=home.resolve("AuralisData");return Files.isRegularFile(previous.resolve("library.db"))?previous:home.resolve("Auralis");
        }
        String local=env.get("LOCALAPPDATA");return (local==null||local.isBlank()?home:Path.of(local)).resolve("Auralis");
    }
}
