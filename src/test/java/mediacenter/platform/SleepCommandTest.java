package mediacenter.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sleep command lines, asserted the way the VLC and kiosk-browser ones are:
 * argument lists, never composed shell strings, so the whole thing can be tested
 * without ever putting the machine that runs the tests to sleep.
 */
class SleepCommandTest {

    @Test
    @DisplayName("macOS asks pmset, the documented way to sleep now")
    void buildsTheMacCommand() {
        assertEquals(
                List.of(List.of("/usr/bin/pmset", "sleepnow")),
                new MacPlatformServices().sleepCommands());
    }

    @Test
    @DisplayName("Windows suspends through powrprof, the mechanism every version has")
    void buildsTheWindowsCommand() {
        assertEquals(
                List.of(List.of("rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0")),
                new WindowsPlatformServices().sleepCommands());
    }

    @Test
    @DisplayName("Linux tries systemd first and falls back to the session manager")
    void buildsTheLinuxCommands() {
        assertEquals(
                List.of(
                        List.of("systemctl", "suspend"),
                        List.of("loginctl", "suspend")),
                new LinuxPlatformServices().sleepCommands());
    }

    @Test
    @DisplayName("a platform that knows no way to sleep says so rather than failing silently")
    void reportsWhenSleepingIsNotSupported() {
        PlatformServices unsupported = new UnsupportedPlatform();

        assertTrue(unsupported.sleepCommands().isEmpty());
        assertThrows(IOException.class, unsupported::sleepComputer);
    }

    @Test
    @DisplayName("every supported platform offers at least one command")
    void everySupportedPlatformCanSleep() {
        for (PlatformServices services : List.of(
                new MacPlatformServices(), new WindowsPlatformServices(), new LinuxPlatformServices())) {
            assertFalse(services.sleepCommands().isEmpty(), services.name() + " should know how to sleep");
        }
    }

    /** Stands in for a platform the sleep support has not reached. */
    private static final class UnsupportedPlatform extends AbstractPlatformServices {
        @Override
        public String name() {
            return "Unsupported";
        }

        @Override
        public java.util.Optional<java.nio.file.Path> findVlc() {
            return java.util.Optional.empty();
        }

        @Override
        public java.nio.file.Path applicationDataDirectory() {
            return java.nio.file.Path.of(".");
        }
    }
}
