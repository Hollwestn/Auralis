package io.auralis.desktop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class DesktopPathsTest {
 @TempDir Path home;
 @Test void linuxUsesXdgAndRejectsRelativeBase(){assertEquals(home.resolve(".local/share/auralis"),DesktopPaths.data("Linux",home,Map.of()));assertEquals(home.resolve(".local/share/auralis"),DesktopPaths.data("Linux",home,Map.of("XDG_DATA_HOME","relative")));assertEquals(home.resolve("custom/auralis"),DesktopPaths.data("Linux",home,Map.of("XDG_DATA_HOME",home.resolve("custom").toString(),"LOCALAPPDATA",home.resolve("wrong").toString())));}
 @Test void windowsLocationsStayCompatible(){assertEquals(home.resolve("local/AuralisData"),DesktopPaths.data("Windows 11",home,Map.of("LOCALAPPDATA",home.resolve("local").toString())));assertEquals(home.resolve("local/Auralis"),DesktopPaths.legacy("Windows 11",home,Map.of("LOCALAPPDATA",home.resolve("local").toString())));}
 @Test void recognizesPriorLinuxLibraryWithoutMovingIt()throws Exception{Path old=home.resolve("AuralisData");Files.createDirectories(old);Files.writeString(old.resolve("library.db"),"fixture");assertEquals(old,DesktopPaths.legacy("Linux",home,Map.of()));assertTrue(Files.exists(old.resolve("library.db")));}
}
