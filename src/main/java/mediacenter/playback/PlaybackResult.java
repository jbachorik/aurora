package mediacenter.playback;

import java.time.Duration;
import java.util.Optional;

/** Outcome of one playback attempt. */
public sealed interface PlaybackResult {

    /**
     * The player process ran and exited.
     *
     * @param exitCode the player's exit code; a non-zero value is not treated as
     *                 an error because VLC may be killed externally
     */
    record Completed(int exitCode, Duration duration) implements PlaybackResult {
    }

    /**
     * The player never ran.
     *
     * @param userMessage short message that is safe to show on screen
     */
    record Failed(String userMessage, Optional<Throwable> cause) implements PlaybackResult {

        public Failed {
            if (userMessage == null || userMessage.isBlank()) {
                throw new IllegalArgumentException("A failure needs a user-facing message");
            }
            cause = cause == null ? Optional.empty() : cause;
        }

        public static Failed of(String userMessage) {
            return new Failed(userMessage, Optional.empty());
        }

        public static Failed of(String userMessage, Throwable cause) {
            return new Failed(userMessage, Optional.ofNullable(cause));
        }
    }

    /** True when the player actually started, whatever its exit code was. */
    default boolean playerStarted() {
        return this instanceof Completed;
    }
}
