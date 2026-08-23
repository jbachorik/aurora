package mediacenter.platform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The bundled browser extension that gives the kiosk browser its two keys.
 *
 * <p>{@code Ctrl+Q} closes it — the same key that closes VLC, so leaving
 * whatever is on screen is one gesture everywhere. {@code F} puts the player
 * full screen, which the site's own button frequently cannot: a page handed a
 * window that already fills the screen fools any player that reads its
 * fullscreen state off the window instead of off {@code document.fullscreenElement},
 * and the button then does nothing at all.
 *
 * <p>The extension ships inside the application jar and is written out to the
 * data directory on every launch — three small files, rewritten unconditionally
 * so an updated application never runs beside a stale copy. Chromium loads
 * unpacked extensions by directory; nothing is installed into the browser
 * itself, and removing the media center leaves no trace in anyone's browsing.
 *
 * <p>Only the Chromium family takes {@code --load-extension} — and branded
 * Google Chrome stopped honouring it in 2025, which is why Ctrl+W remains
 * documented as the fallback that works everywhere.
 */
public final class QuitExtension {

    private static final Logger LOG = Logger.getLogger(QuitExtension.class.getName());

    /** Resources are not listable, so the extension's files are named here. */
    static final List<String> FILES =
            List.of("manifest.json", "quit.js", "background.js", "fullscreen.js");

    private static final String RESOURCE_DIRECTORY = "/mediacenter/browser-extension/";

    private QuitExtension() {
    }

    /**
     * Writes the extension beside the browser profile and answers where, or
     * empty when it could not be written — the kiosk then simply launches
     * without it, and Ctrl+W still works.
     */
    public static Optional<Path> ensureInstalled(Path applicationDataDirectory) {
        Path directory = applicationDataDirectory.resolve("browser-extension");
        try {
            Files.createDirectories(directory);
            for (String file : FILES) {
                try (InputStream resource = QuitExtension.class
                        .getResourceAsStream(RESOURCE_DIRECTORY + file)) {
                    if (resource == null) {
                        throw new IOException("Missing bundled resource " + file);
                    }
                    Files.copy(resource, directory.resolve(file), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return Optional.of(directory);
        } catch (IOException | RuntimeException failure) {
            LOG.log(Level.WARNING, "The quit-key extension could not be written", failure);
            return Optional.empty();
        }
    }
}
