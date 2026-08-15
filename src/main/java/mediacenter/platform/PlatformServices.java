package mediacenter.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

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

    /**
     * Best-effort "show the desktop". Callers minimize their own window as well,
     * so a platform without a reliable mechanism can simply do nothing.
     */
    void showDesktop();

    /** Starts an external program detached from this process. */
    void launchExternal(Path executable) throws IOException;

    /**
     * Exposes the desktop, launching the configured browser when one is set.
     *
     * @param browserExecutable optional browser chosen in Settings
     */
    default void openBrowser(Optional<Path> browserExecutable) throws IOException {
        if (browserExecutable.isPresent()) {
            launchExternal(browserExecutable.get());
        } else {
            showDesktop();
        }
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
