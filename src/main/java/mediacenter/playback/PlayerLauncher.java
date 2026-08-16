package mediacenter.playback;

import java.nio.file.Path;

/**
 * Starts an external player for one file and returns when it has finished.
 *
 * <p>Implementations block, so calls belong on a background thread. The
 * abstraction also lets the playback lifecycle be tested without a real player.
 */
@FunctionalInterface
public interface PlayerLauncher {

    /**
     * Plays a single file and waits for the player to terminate.
     *
     * <p>Never throws: problems are reported as {@link PlaybackResult.Failed}.
     */
    PlaybackResult play(Path mediaFile);
}
