package mediacenter.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import mediacenter.media.MediaRoot;

/**
 * Everything that differs between operating systems.
 *
 * <p>Filesystem traversal, the media model and the JavaFX UI stay
 * platform-independent; only this small interface has to grow when another
 * platform is added.
 */
public interface PlatformServices {

    /** Short platform name, used in logs. */
    String name();

    /** Best guess at where VLC is installed, if it can be found. */
    Optional<Path> findVlc();

    /** Directory for {@code config.json}, {@code history.json}, {@code logs/} and {@code cache/}. */
    Path applicationDataDirectory();

    /** Starts an external program detached from this process. */
    void launchExternal(Path executable) throws IOException;

    /**
     * The command lines that put this computer to sleep, best first.
     *
     * <p>A list of lists because a platform can have more than one mechanism and
     * no way to know from here which of them this particular machine answers to:
     * a desktop Linux without systemd still has a session manager. They are tried
     * in order until one succeeds. Pure data, like the VLC and kiosk-browser
     * command lines, so the wording can be tested without sleeping the machine
     * running the tests.
     *
     * <p>Empty where no mechanism is known, which {@link #sleepComputer()} reports
     * rather than passing off as success.
     */
    default List<List<String>> sleepCommands() {
        return List.of();
    }

    /**
     * Puts the computer to sleep, trying {@link #sleepCommands()} in turn.
     *
     * @throws IOException when no mechanism is known, or every one of them failed
     */
    void sleepComputer() throws IOException;

    /**
     * What a runnable program is called to the viewer here — "program" on Windows
     * and Linux, "application" on macOS. Used to word the Settings buttons and the
     * file-picker titles, so no screen has to name {@code vlc.exe} on a Mac.
     */
    default String executableNoun() {
        return "program";
    }

    /** Where a program picker should open when nothing is configured yet. */
    default Optional<Path> programsDirectory() {
        return Optional.empty();
    }

    /**
     * Turns whatever the file picker returned into something that can actually be
     * started. Everything downstream — the VLC launcher, {@link #launchExternal} —
     * expects a runnable file, and on macOS the picker hands back an application
     * bundle, which is a directory.
     */
    default Path resolveProgram(Path chosen) {
        return chosen;
    }

    /**
     * Undoes {@link #resolveProgram}: given what was stored, the thing the user
     * actually picked. What is stored has to be runnable, which on macOS is a file
     * buried inside an application bundle — not somewhere a picker should reopen,
     * nor a name worth showing to anyone.
     */
    default Path presentableProgram(Path stored) {
        return stored;
    }

    /**
     * What to call a configured program on screen. Never the path: a viewer sitting
     * across a room wants "Google Chrome", not the binary buried inside it.
     */
    default String programLabel(Path stored) {
        if (stored == null) {
            return "";
        }
        Path presentable = presentableProgram(stored);
        Path name = presentable.getFileName();
        return name == null ? presentable.toString() : name.toString();
    }

    /**
     * Volumes the viewer can plug in and take away again — sticks, cards, discs.
     *
     * <p>Offered alongside the configured roots rather than saved with them: they
     * come and go, and a configuration file full of drives that are no longer
     * attached would be worse than not listing them at all. Never fails: a
     * platform that cannot answer simply reports nothing plugged in.
     *
     * <p>Called off the JavaFX thread — it reads the filesystem, and on Windows it
     * asks the operating system.
     */
    default List<MediaRoot> removableVolumes() {
        return List.of();
    }

    /**
     * Extra options this platform needs on the media player's command line.
     *
     * <p>Empty almost everywhere. Windows VLC reuses an instance that is already
     * running: the second launch hands its file to the first, which appends it to
     * the playlist and carries on with what it was doing, so the viewer presses
     * play and nothing happens. The option that turns that off exists only in the
     * builds that have the behaviour — the macOS build rejects it outright and
     * refuses to start — so it is asked for per platform rather than always sent.
     */
    default List<String> playerOptions() {
        return List.of();
    }

    /** Picks the implementation for the running operating system. */
    static PlatformServices detect() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return new WindowsPlatformServices();
        }
        if (osName.contains("mac") || osName.contains("darwin")) {
            return new MacPlatformServices();
        }
        return new LinuxPlatformServices();
    }

    /** Directory name used for application data on every platform. */
    String APPLICATION_DIRECTORY_NAME = "SimpleMediaCenter";
}
