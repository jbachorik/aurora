package mediacenter.history;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The watched marks with their persistence behind them.
 *
 * <p>Reads and mutations apply to the in-memory set immediately — the UI wants
 * to show the mark the moment the key is pressed — while every change is
 * written to disk on the background executor, exactly the split
 * {@link mediacenter.playback.PlaybackService} makes for the history. Safe to
 * call from any thread; a change that changed nothing is not saved.
 */
public final class WatchedService {

    private static final Logger LOG = Logger.getLogger(WatchedService.class.getName());

    private final WatchedVideos watched;
    private final WatchedStore store;
    private final Executor backgroundExecutor;

    public WatchedService(WatchedVideos watched, WatchedStore store, Executor backgroundExecutor) {
        this.watched = watched;
        this.store = store;
        this.backgroundExecutor = backgroundExecutor;
    }

    public boolean isWatched(Path mediaPath) {
        return watched.isWatched(mediaPath);
    }

    public void markWatched(Path mediaPath) {
        if (watched.mark(mediaPath)) {
            save();
        }
    }

    /** Flips one video's mark and returns the new state. */
    public boolean toggleWatched(Path mediaPath) {
        boolean nowWatched = watched.mark(mediaPath);
        if (!nowWatched) {
            watched.reset(mediaPath);
        }
        save();
        return nowWatched;
    }

    /**
     * Clears the marks from everything in the folder and all of its subfolders.
     *
     * @return whether any mark was cleared
     */
    public boolean resetBelow(Path folder) {
        boolean changed = watched.resetBelow(folder);
        if (changed) {
            save();
        }
        return changed;
    }

    private void save() {
        backgroundExecutor.execute(() -> {
            try {
                store.save(watched);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not save watched marks", e);
            }
        });
    }
}
