package mediacenter.media;

import java.nio.file.Path;

/**
 * A directory could not be read.
 *
 * <p>Carries a message that is safe to show on a TV: no stack traces, no Java
 * exception class names. Technical detail stays in the log.
 */
public class MediaAccessException extends Exception {

    private final Path path;
    private final String userMessage;

    public MediaAccessException(Path path, String userMessage, Throwable cause) {
        super(userMessage + " (" + path + ")", cause);
        this.path = path;
        this.userMessage = userMessage;
    }

    public Path path() {
        return path;
    }

    /** Short, friendly description suitable for display. */
    public String userMessage() {
        return userMessage;
    }
}
