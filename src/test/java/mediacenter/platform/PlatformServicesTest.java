package mediacenter.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class PlatformServicesTest {

    @Test
    void detectsTheRunningPlatform() {
        PlatformServices services = PlatformServices.detect();

        assertTrue(services.name() != null && !services.name().isBlank());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void detectsLinux() {
        assertInstanceOf(LinuxPlatformServices.class, PlatformServices.detect());
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void detectsMac() {
        assertInstanceOf(MacPlatformServices.class, PlatformServices.detect());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void detectsWindows() {
        assertInstanceOf(WindowsPlatformServices.class, PlatformServices.detect());
    }

    @Test
    @DisplayName("the application data directory exists and is named as documented")
    void createsTheApplicationDataDirectory() {
        Path directory = PlatformServices.detect().applicationDataDirectory();

        assertTrue(Files.isDirectory(directory), directory + " should exist");
        assertEquals(PlatformServices.APPLICATION_DIRECTORY_NAME, directory.getFileName().toString());
    }

    @Test
    @DisplayName("a registry query result is turned into an install directory")
    void parsesRegistryOutput() {
        String output = """
                HKEY_LOCAL_MACHINE\\SOFTWARE\\VideoLAN\\VLC
                    InstallDir    REG_SZ    C:\\Program Files\\VideoLAN\\VLC
                """;

        assertEquals(Optional.of(Path.of("C:\\Program Files\\VideoLAN\\VLC")),
                WindowsPlatformServices.parseRegistryStringValue(output));
    }

    @Test
    void ignoresUnusableRegistryOutput() {
        assertEquals(Optional.empty(), WindowsPlatformServices.parseRegistryStringValue(""));
        assertEquals(Optional.empty(),
                WindowsPlatformServices.parseRegistryStringValue("ERROR: The system was unable to find"));
    }

    @Test
    void launchingAMissingProgramFailsWithAReadableMessage(@TempDir Path temp) {
        PlatformServices services = PlatformServices.detect();

        IOException failure = assertThrows(IOException.class,
                () -> services.launchExternal(temp.resolve("nope")));

        assertTrue(failure.getMessage().contains("Program not found"));
    }

    @Test
    @DisplayName("without a configured browser the tile just exposes the desktop")
    void openBrowserWithoutAnExecutableDoesNotThrow() {
        PlatformServices services = new LinuxPlatformServices();

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> services.openBrowser(Optional.empty()));
    }
}
