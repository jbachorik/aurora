package mediacenter.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import mediacenter.media.MediaRoot;

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
    @DisplayName("a macOS application bundle resolves to the runnable file inside it")
    void resolvesAnApplicationBundleNamedAfterTheBundle(@TempDir Path temp) throws IOException {
        Path bundle = bundleWithExecutables(temp, "VLC.app", "VLC");

        assertEquals(bundle.resolve("Contents/MacOS/VLC"),
                new MacPlatformServices().resolveProgram(bundle));
    }

    @Test
    @DisplayName("a bundle whose executable is named differently still resolves when it is the only one")
    void resolvesTheOnlyExecutableInABundle(@TempDir Path temp) throws IOException {
        Path bundle = bundleWithExecutables(temp, "Firefox.app", "firefox-bin");

        assertEquals(bundle.resolve("Contents/MacOS/firefox-bin"),
                new MacPlatformServices().resolveProgram(bundle));
    }

    @Test
    @DisplayName("an ambiguous bundle is left alone, for macOS itself to open")
    void leavesAmbiguousBundlesUnresolved(@TempDir Path temp) throws IOException {
        Path bundle = bundleWithExecutables(temp, "Odd.app", "one", "two");

        assertEquals(bundle, new MacPlatformServices().resolveProgram(bundle));
    }

    @Test
    @DisplayName("a plain executable is passed through untouched")
    void leavesPlainExecutablesAlone(@TempDir Path temp) throws IOException {
        Path executable = Files.createFile(temp.resolve("vlc"));

        assertEquals(executable, new MacPlatformServices().resolveProgram(executable));
    }

    @Test
    @DisplayName("macOS words its buttons for applications, not for vlc.exe")
    void macTalksAboutApplications() {
        assertEquals("application", new MacPlatformServices().executableNoun());
        assertEquals("program", new WindowsPlatformServices().executableNoun());
        assertEquals("program", new LinuxPlatformServices().executableNoun());
    }

    private static Path bundleWithExecutables(Path parent, String bundleName, String... executables)
            throws IOException {

        Path bundle = parent.resolve(bundleName);
        Path macOsDirectory = Files.createDirectories(bundle.resolve("Contents").resolve("MacOS"));
        for (String executable : executables) {
            Path file = Files.createFile(macOsDirectory.resolve(executable));
            file.toFile().setExecutable(true);
        }
        return bundle;
    }

    @Test
    @DisplayName("removable drives are read out of the drive listing with their labels")
    void readsRemovableDrivesFromTheDriveListing() {
        String listing = """
                DeviceID  VolumeName
                E:        KINGSTON
                F:\s
                G:        MY BIG DISK

                """;

        List<MediaRoot> volumes = WindowsPlatformServices.parseRemovableDrives(listing);

        assertEquals(List.of("KINGSTON", "F:", "MY BIG DISK"),
                volumes.stream().map(MediaRoot::displayName).toList());
        assertEquals(List.of("E:\\", "F:\\", "G:\\"),
                volumes.stream().map(MediaRoot::displayPath).toList());
    }

    @Test
    @DisplayName("nothing plugged in is not an error")
    void readsAnEmptyDriveListing() {
        assertEquals(List.of(), WindowsPlatformServices.parseRemovableDrives("DeviceID  VolumeName\n\n"));
        assertEquals(List.of(), WindowsPlatformServices.parseRemovableDrives(""));
    }

    @Test
    @DisplayName("mounted volumes are found where the desktop puts them")
    @EnabledOnOs(OS.MAC)
    void findsMountedVolumesOnMac() {
        List<MediaRoot> volumes = new MacPlatformServices().removableVolumes();

        // The boot volume is mounted at "/" and must never be offered as removable.
        assertTrue(volumes.stream().noneMatch(volume -> volume.path().equals(Path.of("/"))));
        assertTrue(volumes.stream().allMatch(volume -> Files.isDirectory(volume.path())),
                volumes.toString());
    }

    @Test
    @DisplayName("a bundle whose namesake file cannot be run falls through to the one that can")
    // Windows cannot express the precondition: there is no execute bit to withhold,
    // setExecutable is a no-op, and Files.isExecutable answers from the ACL, where the
    // owner of a freshly created file holds execute rights. The namesake therefore looks
    // runnable and is returned. Bundles are a macOS idea and this class only ever runs
    // there, so the case is checked where it can be built rather than weakened to suit
    // a platform that never meets it.
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void ignoresANamesakeThatIsNotExecutable(@TempDir Path temp) throws IOException {
        Path bundle = temp.resolve("VLC.app");
        Path macOs = Files.createDirectories(bundle.resolve("Contents").resolve("MacOS"));
        Files.createFile(macOs.resolve("VLC"));                 // named right, not runnable
        Path real = Files.createFile(macOs.resolve("vlc-bin"));
        real.toFile().setExecutable(true);

        assertEquals(real, new MacPlatformServices().resolveProgram(bundle));
    }

    @Test
    @DisplayName("a helper command that fails is reported rather than passing for success")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void reportsAFailedHelperCommand() {
        IOException failure = assertThrows(IOException.class,
                () -> AbstractPlatformServices.runToCompletion(List.of("/bin/sh", "-c", "exit 3"), 5000));

        assertTrue(failure.getMessage().contains("3"), failure.getMessage());
    }

    @Test
    @DisplayName("a helper command that succeeds returns quietly")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void acceptsASuccessfulHelperCommand() throws IOException {
        AbstractPlatformServices.runToCompletion(List.of("/bin/echo", "started"), 5000);
    }

    @Test
    @DisplayName("a bundled program is named after its application, not the binary inside it")
    void labelsABundledProgramByItsApplicationName() {
        assertEquals("Google Chrome", new MacPlatformServices()
                .programLabel(Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")));
    }

    @Test
    @DisplayName("a program that is just a binary is named after the file")
    void labelsAPlainProgramByItsFileName() {
        assertEquals("firefox", new MacPlatformServices().programLabel(Path.of("/usr/bin/firefox")));
        // Separators that this machine understands, so the assertion is about the
        // rule and not about which platform is running the test.
        assertEquals("vlc.exe", new WindowsPlatformServices()
                .programLabel(Path.of("/VideoLAN/VLC/vlc.exe")));
    }

    @Test
    @DisplayName("a program stored inside a bundle is presented as the bundle itself")
    void presentsTheBundleRatherThanTheBinaryInsideIt() {
        Path stored = Path.of("/Applications/VLC.app/Contents/MacOS/VLC");

        assertEquals(Path.of("/Applications/VLC.app"),
                new MacPlatformServices().presentableProgram(stored));
    }

    @Test
    @DisplayName("a program that is not inside a bundle is presented unchanged")
    void presentsAPlainProgramUnchanged() {
        Path stored = Path.of("/opt/homebrew/bin/vlc");

        assertEquals(stored, new MacPlatformServices().presentableProgram(stored));
    }

    @Test
    @DisplayName("a platform with no bundles presents whatever it was given")
    void presentsProgramsUnchangedWhereThereAreNoBundles() {
        Path stored = Path.of("C:\\Program Files\\VideoLAN\\VLC\\vlc.exe");

        assertEquals(stored, new WindowsPlatformServices().presentableProgram(stored));
    }

    @Test
    @DisplayName("without a configured browser the tile just exposes the desktop")
    void openBrowserWithoutAnExecutableDoesNotThrow() {
        PlatformServices services = new LinuxPlatformServices();

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> services.openBrowser(Optional.empty()));
    }
}
