package mediacenter.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    @DisplayName("Windows asks the suspend API properly first, and rundll32 only as a fallback")
    void buildsTheWindowsCommands() {
        List<List<String>> commands = new WindowsPlatformServices().sleepCommands();

        assertEquals(2, commands.size(), commands.toString());
        List<String> powershell = commands.getFirst();
        assertEquals("powershell.exe", powershell.getFirst());
        assertTrue(powershell.getLast().contains("SetSuspendState('Suspend'"), powershell.toString());
        // rundll32 hands SetSuspendState a window handle where the hibernate flag
        // belongs, so it hibernates whenever hibernation is enabled. Kept, because
        // it is the only mechanism that needs nothing installed — but kept second.
        assertEquals(
                List.of("rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0"),
                commands.getLast());
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

    @Test
    @DisplayName("a helper still running when the settling time is up has done its job")
    void treatsAStillRunningHelperAsSuccess() throws IOException {
        // What Windows does: the call does not return until the machine wakes
        // again, which may be tomorrow.
        AbstractPlatformServices.settle(new StubProcess(false, 0), "sleeper", 10);
    }

    @Test
    @DisplayName("a helper that exits cleanly has also done its job")
    void treatsACleanExitAsSuccess() throws IOException {
        AbstractPlatformServices.settle(new StubProcess(true, 0), "sleeper", 10);
    }

    @Test
    @DisplayName("only an early non-zero exit counts as a failure")
    void reportsAnEarlyNonZeroExit() {
        IOException failure = assertThrows(IOException.class,
                () -> AbstractPlatformServices.settle(new StubProcess(true, 3), "sleeper", 10));

        assertTrue(failure.getMessage().contains("sleeper"), failure.getMessage());
        assertTrue(failure.getMessage().contains("3"), failure.getMessage());
    }

    @Test
    @DisplayName("the second candidate is tried only when the first failed early")
    void fallsThroughToTheNextCandidate() throws IOException {
        // Both candidates cannot be real commands in a test, but the ordering
        // that matters is the one the platform declares.
        List<List<String>> windows = new WindowsPlatformServices().sleepCommands();

        assertNotEquals(windows.getFirst(), windows.getLast());
    }

    /** A process that behaves the way a sleep helper does, without being one. */
    private static final class StubProcess extends Process {
        private final boolean finishes;
        private final int exitCode;

        StubProcess(boolean finishes, int exitCode) {
            this.finishes = finishes;
            this.exitCode = exitCode;
        }

        @Override
        public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) {
            return finishes;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public java.io.OutputStream getOutputStream() {
            return java.io.OutputStream.nullOutputStream();
        }

        @Override
        public java.io.InputStream getInputStream() {
            return java.io.InputStream.nullInputStream();
        }

        @Override
        public java.io.InputStream getErrorStream() {
            return java.io.InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public void destroy() {
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
