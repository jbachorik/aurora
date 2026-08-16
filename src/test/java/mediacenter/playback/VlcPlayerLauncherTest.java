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
                Path.of("\\\\synology\\video\\Movies\\Blade Runner 2049 (2017)\\movie.mkv"));

        assertEquals(List.of(
                "C:\\Program Files\\VideoLAN\\VLC\\vlc.exe",
                "--fullscreen",
                "--play-and-exit",
                "\\\\synology\\video\\Movies\\Blade Runner 2049 (2017)\\movie.mkv"), command);
    }

    @Test
    @DisplayName("Unicode file names reach VLC unchanged")
    void passesUnicodeFileNamesThrough() {
        String unicodeName = "\\\\synology\\video\\Movies\\Amélie (2001)\\Amélie 岸辺.mkv";
        Path media = pathOrSkip(unicodeName);

        List<String> command = VlcPlayerLauncher.commandFor(Path.of("/usr/bin/vlc"), media);

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
