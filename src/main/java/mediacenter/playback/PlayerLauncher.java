package mediacenter.playback;

import java.nio.file.Path;
import java.util.List;

/**
 * Starts an external player and returns when it has finished.
 *
 * <p>Implementations block, so calls belong on a background thread. The
 * abstraction also lets the playback lifecycle be tested without a real player.
 */
@FunctionalInterface
public interface PlayerLauncher {

    /**
     * Plays a file — followed, when the player runs off its end, by the files
     * queued after it — and waits for the player to terminate.
     *
     * <p>The queue is the player's to walk: closing the player abandons
     * whatever of it is left, which is exactly what a viewer who has stopped
     * watching means.
     *
     * <p>Never throws: problems are reported as {@link PlaybackResult.Failed}.
     */
    PlaybackResult play(Path mediaFile, List<Path> playOnwards);

    /** Plays a single file, with nothing to follow it. */
    default PlaybackResult play(Path mediaFile) {
        return play(mediaFile, List.of());
    }
}
