package mediacenter.ui;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.util.Duration;

/**
 * Renders the running interface to a PNG and quits, so a layout can be inspected
 * without anyone watching the screen.
 *
 * <p>The application draws itself into an image rather than the desktop being
 * captured, which means no screen-recording permission is involved: on macOS that
 * permission is refused outright to an unsigned parent process, and on the target
 * machine there is nobody sitting in front of the television to take a picture.
 *
 * <pre>
 * ./gradlew run --args="--snapshot=/tmp/home.png"
 * ./gradlew run --args="--snapshot=/tmp/browse.png --snapshot-keys=ENTER,ENTER"
 * </pre>
 */
public final class SceneSnapshot {

    private static final Logger LOG = Logger.getLogger(SceneSnapshot.class.getName());

    private static final String FILE_ARGUMENT = "--snapshot=";
    private static final String KEYS_ARGUMENT = "--snapshot-keys=";

    /** Long enough for artwork to decode and the entrance motion to finish. */
    private static final Duration SETTLE = Duration.seconds(3);

    /** Between synthetic key presses, so each navigation completes before the next. */
    private static final Duration BETWEEN_KEYS = Duration.seconds(1.5);

    private SceneSnapshot() {
    }

    /**
     * Arms the snapshot when the arguments ask for one, and otherwise does nothing.
     *
     * @param arguments the raw application parameters
     */
    public static void scheduleIfRequested(Scene scene, List<String> arguments) {
        Optional<Path> target = valueOf(arguments, FILE_ARGUMENT).map(Path::of);
        if (target.isEmpty()) {
            return;
        }
        List<KeyCode> keys = valueOf(arguments, KEYS_ARGUMENT)
                .map(SceneSnapshot::parseKeys)
                .orElse(List.of());

        for (int index = 0; index < keys.size(); index++) {
            KeyCode key = keys.get(index);
            after(BETWEEN_KEYS.multiply(index + 1.0), () -> press(scene, key));
        }
        after(SETTLE.add(BETWEEN_KEYS.multiply(keys.size())), () -> {
            capture(scene, target.get());
            Platform.exit();
        });
    }

    private static void capture(Scene scene, Path target) {
        try {
            WritableImage image = scene.snapshot(null);
            PixelReader reader = image.getPixelReader();
            try (OutputStream out = Files.newOutputStream(target)) {
                PngWriter.write((int) image.getWidth(), (int) image.getHeight(), reader::getArgb, out);
            }
            LOG.log(Level.INFO, () -> "Snapshot written to " + target);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not write the snapshot to " + target, e);
        }
    }

    /**
     * Navigation is driven by sending the key to whatever holds focus rather than by
     * a screen robot, which would need the very permission this class avoids.
     */
    private static void press(Scene scene, KeyCode key) {
        Node focused = scene.getFocusOwner() == null ? scene.getRoot() : scene.getFocusOwner();
        Event.fireEvent(focused, keyEvent(KeyEvent.KEY_PRESSED, key));
        // The release matters: activation ignores a repeat that never let go, which
        // is how a stuck remote button is kept from relaunching a film.
        Event.fireEvent(focused, keyEvent(KeyEvent.KEY_RELEASED, key));
    }

    private static KeyEvent keyEvent(javafx.event.EventType<KeyEvent> type, KeyCode key) {
        return new KeyEvent(type, "", "", key, false, false, false, false);
    }

    /** Key names as {@link KeyCode} spells them, for example {@code DOWN,DOWN,ENTER}. */
    private static List<KeyCode> parseKeys(String value) {
        List<KeyCode> keys = new java.util.ArrayList<>();
        for (String name : value.split(",")) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                keys.add(KeyCode.valueOf(trimmed.toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                LOG.log(Level.WARNING, () -> "Ignoring a key that does not exist: " + trimmed);
            }
        }
        return List.copyOf(keys);
    }

    /**
     * Timed on an ordinary thread rather than with an animation, which would stop
     * with the rendering pulse: a display that has gone to sleep leaves a
     * {@code PauseTransition} that never finishes, and the application then never
     * takes its picture and never quits.
     */
    private static void after(Duration delay, Runnable action) {
        Thread timer = new Thread(() -> {
            try {
                Thread.sleep(Math.round(delay.toMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Platform.runLater(action);
        }, "scene-snapshot");
        timer.setDaemon(true);
        timer.start();
    }

    private static Optional<String> valueOf(List<String> arguments, String prefix) {
        return arguments.stream()
                .filter(argument -> argument.startsWith(prefix))
                .map(argument -> argument.substring(prefix.length()))
                .filter(value -> !value.isBlank())
                .findFirst();
    }

}
