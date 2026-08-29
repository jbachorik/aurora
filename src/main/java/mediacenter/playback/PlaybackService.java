package mediacenter.playback;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import mediacenter.history.HistoryStore;
import mediacenter.history.PlaybackHistory;
import mediacenter.history.WatchedService;
import mediacenter.playback.cache.PlaybackPreparer;

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
    private final WatchedService watched;
    private final Executor backgroundExecutor;
    private final Executor uiExecutor;
    private final PlaybackPreparer preparer;
    private final AtomicBoolean playing = new AtomicBoolean();

    public PlaybackService(
            PlayerLauncher playerLauncher,
            PlaybackHistory history,
            HistoryStore historyStore,
            WatchedService watched,
            Executor backgroundExecutor,
            Executor uiExecutor) {
        this(playerLauncher, history, historyStore, watched, backgroundExecutor, uiExecutor, null);
    }

    /**
     * @param preparer checks the throughput of a file's home and buffers ahead
     *                 into the local mirror before the player starts; may be
     *                 null, and playback then goes straight to the player as it
     *                 always did
     */
    public PlaybackService(
            PlayerLauncher playerLauncher,
            PlaybackHistory history,
            HistoryStore historyStore,
            WatchedService watched,
            Executor backgroundExecutor,
            Executor uiExecutor,
            PlaybackPreparer preparer) {
        this.playerLauncher = playerLauncher;
        this.history = history;
        this.historyStore = historyStore;
        this.watched = watched;
        this.backgroundExecutor = backgroundExecutor;
        this.uiExecutor = uiExecutor;
        this.preparer = preparer;
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
        play(mediaFile, playOnwards, displayTitle,
                progress -> { }, status -> { }, new PlaybackPreparer.BufferingControl(),
                () -> { }, onFinished);
    }

    /**
     * @param onBuffering        hears, on the UI executor, how far along the
     *                           pre-play buffering is — the shell draws its
     *                           overlay from this
     * @param onStatus           hears, on the UI executor, one-line notices the
     *                           viewer should see — a slow-share warning
     * @param bufferingControl   the viewer's way out of a buffering wait; the
     *                           shell wires Esc and Enter to it while the
     *                           overlay is up. Cancelling ends the request with
     *                           {@link PlaybackResult.Cancelled} and no player
     * @param beforePlayerStarts runs on the UI executor once preparation is over
     *                           and the player is about to take the screen; the
     *                           shell hides its window here, after the buffering
     *                           overlay has had a screen to appear on
     * @see #play(Path, List, String, Consumer)
     */
    public void play(
            Path mediaFile,
            List<Path> playOnwards,
            String displayTitle,
            Consumer<PlaybackPreparer.BufferingProgress> onBuffering,
            Consumer<String> onStatus,
            PlaybackPreparer.BufferingControl bufferingControl,
            Runnable beforePlayerStarts,
            Consumer<PlaybackResult> onFinished) {
        if (!playing.compareAndSet(false, true)) {
            LOG.fine("Ignoring playback request, a player is already running");
            return;
        }
        backgroundExecutor.execute(() -> {
            PlaybackResult result;
            try {
                // Preparation may swap in local mirror copies; the history below
                // still records the paths the viewer chose, never the copies.
                Path playFile = mediaFile;
                List<Path> playQueue = playOnwards;
                if (preparer != null) {
                    Optional<PlaybackPreparer.Prepared> prepared = preparer.prepare(
                            mediaFile, playOnwards,
                            progress -> uiExecutor.execute(() -> onBuffering.accept(progress)),
                            bufferingControl);
                    if (prepared.isEmpty()) {
                        // Cancelled while buffering: no player, no history entry.
                        playing.set(false);
                        uiExecutor.execute(() -> onFinished.accept(new PlaybackResult.Cancelled()));
                        return;
                    }
                    prepared.get().notice().ifPresent(
                            notice -> uiExecutor.execute(() -> onStatus.accept(notice)));
                    playFile = prepared.get().mediaFile();
                    playQueue = prepared.get().playOnwards();
                }
                uiExecutor.execute(beforePlayerStarts);
                result = playerLauncher.play(playFile, playQueue);
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

    /**
     * Writes one played file into the history — the embedded player's way in.
     * That player knows, per episode, that a frame actually reached the
     * screen, which is better evidence than the external player ever gives.
     * Runs on the background executor; safe to call from the UI thread.
     */
    public void recordPlayed(Path mediaFile, String displayTitle) {
        backgroundExecutor.execute(() -> recordHistory(mediaFile, displayTitle));
    }

    /**
     * Follows a file the library has reorganised: its history entry and its
     * watched mark move with it, so tidying a shelf never un-watches a film or
     * strands a "recently played" tile on a path that no longer exists.
     * Runs on the background executor; safe to call from any thread.
     */
    public void recordFileMoved(Path from, Path to) {
        backgroundExecutor.execute(() -> {
            try {
                if (history.move(from, to)) {
                    historyStore.save(history);
                }
                watched.recordMove(from, to);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not follow a moved file in the history", e);
            }
        });
    }

    private void recordHistory(Path mediaFile, String displayTitle) {
        try {
            history.record(mediaFile, displayTitle, Instant.now());
            historyStore.save(history);
            // The same evidence that puts a file into the history marks it
            // watched: the player took it. What follows in a queue is marked
            // only when the embedded player reports each episode through
            // recordPlayed.
            watched.markWatched(mediaFile);
            if (preparer != null) {
                // Counts toward "frequently played", which is what eventually
                // earns a network file its permanent local mirror copy.
                preparer.recordPlayed(mediaFile);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not update playback history", e);
        }
    }
}
