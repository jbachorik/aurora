package mediacenter.scrape;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Answers how long a video file runs, when anyone can tell.
 *
 * <p>An interface rather than the libVLC probe directly, for the two usual
 * reasons: the collector that asks can be tested with a lambda, and "no
 * probe" is just one that always answers empty — which is also what the real
 * one answers whenever libVLC is not around to ask.
 */
@FunctionalInterface
public interface MediaDurationProbe {

    /** The file's running time, or empty when it cannot be determined. */
    Optional<Duration> durationOf(Path video);

    /** The probe used where no libVLC exists: it has no opinion about anything. */
    static MediaDurationProbe none() {
        return video -> Optional.empty();
    }
}
