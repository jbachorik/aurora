package mediacenter.playback;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import mediacenter.history.HistoryStore;
import mediacenter.history.PlaybackHistory;

/**
 * Runs a playback off the UI thread and reports back on it.
 *
 * <p>Contains no JavaFX types: the caller supplies the executor that marshals
 * back to the UI thread, which also makes the whole lifecycle unit-testable.
 */
public final class PlaybackService {

    private static final Logger LOG = Logger.getLogger(PlaybackService.class.getName());

    private final PlayerLauncher playerLauncher;
    private final PlaybackHistory history;
    private final HistoryStore historyStore;
    private final Executor backgroundExecutor;
    private final Executor uiExecutor;
    private final AtomicBoolean playing = new AtomicBoolean();

    public PlaybackService(
            PlayerLauncher playerLauncher,
            PlaybackHistory history,
            HistoryStore historyStore,
            Executor backgroundExecutor,
            Executor uiExecutor) {
        this.playerLauncher = playerLauncher;
        this.history = history;
        this.historyStore = historyStore;
        this.backgroundExecutor = backgroundExecutor;
        this.uiExecutor = uiExecutor;
    }

    /** True while a player is running. */
    public boolean isPlaying() {
        return playing.get();
    }

    /** Plays a single file, with nothing to follow it. */
    public void play(Path mediaFile, String displayTitle, Consumer<PlaybackResult> onFinished) {
        play(mediaFile, List.of(), displayTitle, onFinished);
    }

    /**
     * Starts playback and calls {@code onFinished} on the UI executor once the
     * player has terminated (or immediately failed).
     *
     * <p>The files in {@code playOnwards} follow the first when the player runs
     * off its end — episodes after the chosen one. Only the chosen file enters
     * the history: nothing here knows how far the player actually got.
     *
     * <p>Concurrent requests are ignored: one player at a time is the whole point.
     */
    public void play(
            Path mediaFile,
            List<Path> playOnwards,
            String displayTitle,
            Consumer<PlaybackResult> onFinished) {
        if (!playing.compareAndSet(false, true)) {
            LOG.fine("Ignoring playback request, a player is already running");
            return;
        }
        backgroundExecutor.execute(() -> {
            PlaybackResult result;
            try {
                result = playerLauncher.play(mediaFile, playOnwards);
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "Unexpected failure while playing " + mediaFile, e);
                result = PlaybackResult.Failed.of("Playback failed unexpectedly.", e);
            }
            if (result.playerStarted()) {
                recordHistory(mediaFile, displayTitle);
            }
            PlaybackResult outcome = result;
            playing.set(false);
            uiExecutor.execute(() -> onFinished.accept(outcome));
        });
    }

    private void recordHistory(Path mediaFile, String displayTitle) {
        try {
            history.record(mediaFile, displayTitle, Instant.now());
            historyStore.save(history);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not update playback history", e);
        }
    }
}
