package mediacenter.platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The command line that opens a website full screen, sized for a sofa.
 *
 * <p>Pure argument-list arithmetic, like {@code VlcPlayerLauncher.commandFor}:
 * never a composed shell string, so spaces and Unicode in paths and addresses
 * need no quoting rules — and the whole thing can be tested without a browser.
 *
 * <p>Chromium-family browsers get the full treatment: app mode (no tabs, no
 * address bar), fullscreen, a device-scale hint so a desktop page reads from
 * across the room, and a profile directory of the media center's own. Branded
 * Google Chrome alone is maximized rather than fullscreened: it stopped
 * loading the bundled extension in 2025, so the {@code F} key that rescues
 * embedded players fooled by an already-fullscreen window is not there — a
 * maximized app window keeps those players' own fullscreen buttons working,
 * at the price of a title bar between films. The
 * profile is not a nicety — a Chromium already running would otherwise swallow
 * the launch into the existing instance and ignore every flag with it, which
 * is the same single-instance trap VLC has on Windows. The dedicated profile
 * also gives the television its own logins, kept apart from anyone's desktop.
 *
 * <p>Firefox knows {@code --kiosk}; anything else gets the address alone and
 * whatever that browser makes of it.
 */
public final class KioskBrowser {

    /** Executable names that take Chromium's command line. */
    private static final List<String> CHROMIUM_FAMILY =
            List.of("chrome", "chromium", "msedge", "edge", "brave", "vivaldi", "opera");

    private KioskBrowser() {
    }

    /**
     * @param browserExecutable  the configured browser program
     * @param url                the address, handed through verbatim
     * @param scalePercent       how much larger to draw everything; 100 says nothing
     * @param profileDirectory   the media center's own browser profile
     * @param extensionDirectory the unpacked media center extension, which
     *                           carries Ctrl+Q and the fullscreen key. For the
     *                           Chromium family only: Firefox refuses unsigned
     *                           extensions from a command line, and branded
     *                           Chrome has stopped honouring the switch, which
     *                           costs nothing worse than the keys not binding
     */
    public static List<String> commandFor(
            Path browserExecutable, String url, int scalePercent, Path profileDirectory,
            Optional<Path> extensionDirectory) {
        String program = fileNameOf(browserExecutable);
        if (isChromiumFamily(program)) {
            return chromiumCommand(
                    browserExecutable, program, url, scalePercent, profileDirectory, extensionDirectory);
        }
        if (program.contains("firefox")) {
            return List.of(browserExecutable.toString(), "--kiosk", url);
        }
        return List.of(browserExecutable.toString(), url);
    }

    static boolean isChromiumFamily(String executableName) {
        String name = executableName.toLowerCase(Locale.ROOT);
        return CHROMIUM_FAMILY.stream().anyMatch(name::contains);
    }

    /** Branded Google Chrome, as opposed to Chromium — "chromium" never contains "chrome". */
    static boolean isBrandedChrome(String executableName) {
        String name = executableName.toLowerCase(Locale.ROOT);
        return name.contains("chrome") && !name.contains("chromium");
    }

    private static List<String> chromiumCommand(
            Path browserExecutable, String program, String url, int scalePercent, Path profileDirectory,
            Optional<Path> extensionDirectory) {
        List<String> command = new ArrayList<>(8);
        command.add(browserExecutable.toString());
        // Without its own profile the launch is handed to any Chromium already
        // running and every flag below is silently ignored.
        command.add("--user-data-dir=" + profileDirectory);
        // A fresh profile would otherwise open with a welcome tour and an
        // offer to become the default browser — on a television.
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        extensionDirectory.ifPresent(extension -> command.add("--load-extension=" + extension));
        if (scalePercent != 100) {
            command.add("--force-device-scale-factor="
                    + String.format(Locale.ROOT, "%.2f", scalePercent / 100.0));
        }
        // A window that already fills the screen fools embedded players into
        // thinking they are fullscreen, and the extension's F key is the way
        // back around that — but branded Chrome refuses --load-extension, so
        // there the window stays a maximized one and the players' own buttons
        // keep working, title bar and all.
        command.add(isBrandedChrome(program) ? "--start-maximized" : "--start-fullscreen");
        // Last, as with VLC: everything after the = is the address, dashes and all.
        command.add("--app=" + url);
        return List.copyOf(command);
    }

    private static String fileNameOf(Path executable) {
        Path name = executable.getFileName();
        return name == null ? executable.toString() : name.toString();
    }
}
