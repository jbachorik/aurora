package mediacenter.ui;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.animation.PauseTransition;
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
 * ./gradlew run --args="--snapshot=/tmp/browse.png --snapshot-enter=2"
 * </pre>
 */
public final class SceneSnapshot {

    private static final Logger LOG = Logger.getLogger(SceneSnapshot.class.getName());

    private static final String FILE_ARGUMENT = "--snapshot=";
    private static final String ENTER_ARGUMENT = "--snapshot-enter=";

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
        int presses = valueOf(arguments, ENTER_ARGUMENT).map(SceneSnapshot::parseCount).orElse(0);

        for (int press = 0; press < presses; press++) {
            after(BETWEEN_KEYS.multiply(press + 1.0), () -> pressEnter(scene));
        }
        after(SETTLE.add(BETWEEN_KEYS.multiply(presses)), () -> {
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
    private static void pressEnter(Scene scene) {
        Node focused = scene.getFocusOwner() == null ? scene.getRoot() : scene.getFocusOwner();
        Event.fireEvent(focused, enter(KeyEvent.KEY_PRESSED));
        // The release matters: activation ignores a repeat that never let go, which
        // is how a stuck remote button is kept from relaunching a film.
        Event.fireEvent(focused, enter(KeyEvent.KEY_RELEASED));
    }

    private static KeyEvent enter(javafx.event.EventType<KeyEvent> type) {
        return new KeyEvent(type, "", "", KeyCode.ENTER, false, false, false, false);
    }

    private static void after(Duration delay, Runnable action) {
        PauseTransition pause = new PauseTransition(delay);
        pause.setOnFinished(event -> action.run());
        pause.play();
    }

    private static Optional<String> valueOf(List<String> arguments, String prefix) {
        return arguments.stream()
                .filter(argument -> argument.startsWith(prefix))
                .map(argument -> argument.substring(prefix.length()))
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    private static int parseCount(String value) {
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            LOG.log(Level.WARNING, () -> "Ignoring a key count that is not a number: " + value);
            return 0;
        }
    }
}
