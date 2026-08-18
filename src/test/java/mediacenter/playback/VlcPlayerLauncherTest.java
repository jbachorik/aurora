package mediacenter.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class VlcPlayerLauncherTest {

    @Test
    @DisplayName("the command is an argument list, so spaces and UNC paths need no quoting")
    void buildsTheDocumentedCommandLine() {
        List<String> command = VlcPlayerLauncher.commandFor(
                Path.of("C:\\Program Files\\VideoLAN\\VLC\\vlc.exe"),
                Path.of("\\\\synology\\video\\Movies\\Blade Runner 2049 (2017)\\movie.mkv"),
                0, List.of());

        assertEquals(List.of(
                "C:\\Program Files\\VideoLAN\\VLC\\vlc.exe",
                "--fullscreen",
                "--play-and-exit",
                "\\\\synology\\video\\Movies\\Blade Runner 2049 (2017)\\movie.mkv"), command);
    }

    @Test
    @DisplayName("platform options are passed to VLC ahead of the file")
    void placesPlatformOptionsBeforeTheFile() {
        // Windows VLC hands the file to an instance that is already running and
        // enqueues it there, ignoring --play-and-exit and --fullscreen along with
        // the rest of the command line. The option that switches that off exists
        // only on the platforms that have the behaviour, so it arrives from there.
        List<String> command = VlcPlayerLauncher.commandFor(
                Path.of("C:\\Program Files\\VideoLAN\\VLC\\vlc.exe"),
                Path.of("C:\\video\\episode.mkv"),
                0, List.of("--no-one-instance"));

        assertEquals(List.of(
                "C:\\Program Files\\VideoLAN\\VLC\\vlc.exe",
                "--fullscreen",
                "--play-and-exit",
                "--no-one-instance",
                "C:\\video\\episode.mkv"), command);
    }

    @Test
    @DisplayName("a queue becomes VLC's playlist: the files follow the first, in order")
    void appendsTheQueueAfterTheChosenFile() {
        List<String> command = VlcPlayerLauncher.commandFor(
                Path.of("/usr/bin/vlc"),
                Path.of("/tv/01 - Pilot.mkv"),
                List.of(Path.of("/tv/02 - Second.mkv"), Path.of("/tv/03 - Third.mkv")),
                0, List.of());

        assertEquals(List.of(
                "/usr/bin/vlc",
                "--fullscreen",
                "--play-and-exit",
                "/tv/01 - Pilot.mkv",
                "/tv/02 - Second.mkv",
                "/tv/03 - Third.mkv"), command);
    }

    @Test
    @DisplayName("a platform that asks for nothing extra gets the plain command line")
    void addsNothingWhenThePlatformOffersNoOptions() {
        assertEquals(
                VlcPlayerLauncher.commandFor(Path.of("/usr/bin/vlc"), Path.of("/media/a.mkv")),
                VlcPlayerLauncher.commandFor(Path.of("/usr/bin/vlc"), Path.of("/media/a.mkv"), 0, List.of()));
    }

    @Test
    @DisplayName("the configured buffer becomes VLC's file caching, in milliseconds")
    void asksVlcToBufferTheConfiguredAmount() {
        // --file-caching, not --network-caching: a share mounted by the operating
        // system is opened by VLC's "filesystem" access, and the network option
        // reaches only the modules that fetch over a network themselves.
        Path media = Path.of("/media/a.mkv");

        List<String> command = VlcPlayerLauncher.commandFor(
                Path.of("/usr/bin/vlc"), media, 10, List.of());

        assertTrue(command.contains("--file-caching=10000"), command.toString());
        // Rendered by the platform, not spelled out: Windows prints this same
        // path back with backslashes.
        assertEquals(media.toString(), command.getLast());
    }

    @Test
    @DisplayName("no buffer configured leaves VLC's own caching untouched")
    void saysNothingAboutCachingWhenNoBufferIsSet() {
        List<String> command = VlcPlayerLauncher.commandFor(
                Path.of("/usr/bin/vlc"), Path.of("/media/a.mkv"), 0, List.of());

        assertTrue(command.stream().noneMatch(argument -> argument.startsWith("--file-caching")), command.toString());
    }

    @Test
    @DisplayName("Unicode file names reach VLC unchanged")
    void passesUnicodeFileNamesThrough() {
        String unicodeName = "\\\\synology\\video\\Movies\\Amélie (2001)\\Amélie 岸辺.mkv";
        Path media = pathOrSkip(unicodeName);

        List<String> command = VlcPlayerLauncher.commandFor(Path.of("/usr/bin/vlc"), media, 0, List.of());

        assertEquals(unicodeName, command.getLast());
    }

    /**
     * Builds a path, skipping the test when the JVM's native encoding cannot
     * represent the name at all (a POSIX/ASCII locale, for example).
     */
    private static Path pathOrSkip(String name) {
        try {
            return Path.of(name);
        } catch (java.nio.file.InvalidPathException e) {
            org.junit.jupiter.api.Assumptions.abort(
                    "The native encoding of this JVM cannot represent Unicode file names: "
                            + System.getProperty("sun.jnu.encoding"));
            throw e;
        }
    }

    @Test
    void reportsAMissingConfigurationInsteadOfFailing() {
        PlaybackResult result = new VlcPlayerLauncher(Optional::empty).play(Path.of("/media/a.mkv"));

        assertEquals(VlcPlayerLauncher.VLC_NOT_CONFIGURED, failureMessage(result));
    }

    @Test
    void reportsAnInvalidVlcPath(@TempDir Path temp) {
        VlcPlayerLauncher launcher = new VlcPlayerLauncher(() -> Optional.of(temp.resolve("vlc.exe")));

        PlaybackResult result = launcher.play(temp.resolve("movie.mkv"));

        assertEquals(VlcPlayerLauncher.VLC_MISSING, failureMessage(result));
    }

    @Test
    @DisplayName("an application bundle is reported as unrunnable, not as a path that vanished")
    void reportsAnApplicationBundleRatherThanAMissingPath(@TempDir Path temp) throws IOException {
        Path bundle = Files.createDirectories(temp.resolve("VLC.app"));
        Path media = Files.createFile(temp.resolve("movie.mkv"));

        PlaybackResult result = new VlcPlayerLauncher(() -> Optional.of(bundle)).play(media);

        assertEquals(VlcPlayerLauncher.VLC_NOT_A_PROGRAM, failureMessage(result));
    }

    @Test
    @DisplayName("a file that disappeared before playback is reported, not launched")
    void reportsAMissingMediaFile(@TempDir Path temp) throws IOException {
        Path fakeVlc = Files.createFile(temp.resolve("vlc"));
        VlcPlayerLauncher launcher = new VlcPlayerLauncher(() -> Optional.of(fakeVlc));

        PlaybackResult result = launcher.play(temp.resolve("gone.mkv"));

        assertEquals(VlcPlayerLauncher.MEDIA_MISSING, failureMessage(result));
    }

    @Test
    @DisplayName("an unusable player program is reported as a failure to start")
    void reportsAPlayerThatCannotBeExecuted(@TempDir Path temp) throws IOException {
        Path notExecutable = Files.writeString(temp.resolve("vlc"), "not a program");
        Path media = Files.createFile(temp.resolve("movie.mkv"));

        PlaybackResult result = new VlcPlayerLauncher(() -> Optional.of(notExecutable)).play(media);

        assertEquals(VlcPlayerLauncher.VLC_NOT_STARTED, failureMessage(result));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @DisplayName("waiting for the player to exit yields a completed result")
    void waitsForTheProcessToFinish(@TempDir Path temp) throws IOException {
        Path media = Files.createFile(temp.resolve("movie.mkv"));
        // /bin/echo stands in for VLC: it accepts the arguments and exits at once.
        VlcPlayerLauncher launcher = new VlcPlayerLauncher(() -> Optional.of(Path.of("/bin/echo")));

        PlaybackResult result = launcher.play(media);

        PlaybackResult.Completed completed = assertInstanceOf(PlaybackResult.Completed.class, result);
        assertEquals(0, completed.exitCode());
        assertTrue(result.playerStarted());
    }

    private static String failureMessage(PlaybackResult result) {
        return assertInstanceOf(PlaybackResult.Failed.class, result).userMessage();
    }
}
