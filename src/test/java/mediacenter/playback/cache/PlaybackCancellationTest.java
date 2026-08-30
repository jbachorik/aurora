package mediacenter.playback.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mediacenter.history.HistoryStore;
import mediacenter.history.PlaybackHistory;
import mediacenter.history.WatchedService;
import mediacenter.history.WatchedStore;
import mediacenter.history.WatchedVideos;
import mediacenter.playback.PlaybackResult;
import mediacenter.playback.PlaybackService;
import mediacenter.playback.cache.MediaDurations.VideoHeader;
import mediacenter.playback.cache.PlaybackPreparer.BufferingControl;

/**
 * The cancel path end to end: a viewer backing out of a buffering wait must
 * end the request with {@link PlaybackResult.Cancelled} — no player, no
 * history entry, no window juggling. Lives in this package for the preparer's
 * test constructor, which lets the share's speed be dictated.
 */
class PlaybackCancellationTest {

    private static final Executor DIRECT = Runnable::run;

    /** Mirrors built here are drained after the test — see {@link #drainCopies}. */
    private final List<MediaMirror> mirrors = new ArrayList<>();

    private MediaMirror mirror(Path directory, long capacityBytes) {
        MediaMirror created = new MediaMirror(directory, () -> capacityBytes);
        mirrors.add(created);
        return created;
    }

    /**
     * A copy thread still writing while JUnit deletes the temp directory is a
     * race the suite loses on a slow runner — the cleanup cannot remove a tree
     * that is growing under it. Every test therefore waits its mirrors out.
     */
    @AfterEach
    void drainCopies() {
        for (MediaMirror created : mirrors) {
            assertTrue(created.awaitIdle(10_000), "a copy outlived the test");
        }
    }

    @Test
    @DisplayName("cancelling while buffering reports Cancelled and never starts the player")
    void cancelledPreparationNeverReachesThePlayer(@TempDir Path temp) throws Exception {
        Path media = temp.resolve("film.mkv");
        Files.write(media, new byte[100_000]);
        MediaMirror mirror = mirror(temp.resolve("cache"), 1L << 30);
        // Measured far below the required rate, so preparation enters the
        // buffering wait — where it finds the cancellation already standing.
        PlaybackPreparer preparer = new PlaybackPreparer(
                mirror,
                file -> OptionalLong.of(10_000),
                file -> new VideoHeader(Optional.of(Duration.ofSeconds(1)), true),
                path -> true,
                10);
        PlaybackHistory history = new PlaybackHistory();
        List<String> order = new ArrayList<>();
        List<PlaybackResult> results = new ArrayList<>();
        PlaybackService service = new PlaybackService(
                (file, queue) -> {
                    order.add("player");
                    return new PlaybackResult.Completed(0, Duration.ofMinutes(1));
                },
                history, new HistoryStore(temp),
                new WatchedService(new WatchedVideos(), new WatchedStore(temp), DIRECT),
                DIRECT, DIRECT, preparer);
        BufferingControl control = new BufferingControl();
        control.cancel();

        service.play(media, List.of(), "Film",
                progress -> order.add("buffering"),
                status -> order.add("status"),
                control,
                () -> order.add("hide"),
                results::add);

        assertInstanceOf(PlaybackResult.Cancelled.class, results.getFirst());
        assertFalse(order.contains("player"), "the player must never start");
        assertFalse(order.contains("hide"), "the window never hides for a cancelled request");
        assertTrue(history.isEmpty(), "nothing was played, nothing is remembered");
        assertFalse(service.isPlaying(), "the service is free for the next request");
        assertFalse(results.getFirst().playerStarted());
    }
}
