package mediacenter.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mediacenter.history.HistoryStore;
import mediacenter.history.PlaybackHistory;
import mediacenter.history.WatchedService;
import mediacenter.history.WatchedStore;
import mediacenter.history.WatchedVideos;

class PlaybackServiceTest {

    /** Runs everything inline so the lifecycle can be asserted without waiting. */
    private static final Executor DIRECT = Runnable::run;

    private final Path media = Path.of("\\\\synology\\video\\Movies\\Dune.mkv");
    private final WatchedVideos watched = new WatchedVideos();

    private WatchedService watchedService(Path temp) {
        return new WatchedService(watched, new WatchedStore(temp), DIRECT);
    }

    @Test
    @DisplayName("a completed playback lands in the history and is persisted")
    void recordsCompletedPlayback(@TempDir Path temp) {
        PlaybackHistory history = new PlaybackHistory();
        HistoryStore store = new HistoryStore(temp);
        FakePlayerLauncher launcher = FakePlayerLauncher.succeeding();
        PlaybackService service =
                new PlaybackService(launcher, history, store, watchedService(temp), DIRECT, DIRECT);
        List<PlaybackResult> results = new ArrayList<>();

        service.play(media, "Dune", results::add);

        assertEquals(List.of(media), launcher.played());
        assertEquals(1, results.size());
        assertInstanceOf(PlaybackResult.Completed.class, results.getFirst());
        assertEquals("Dune", history.entries().getFirst().displayTitle());
        assertTrue(Files.isRegularFile(temp.resolve("history.json")));
        // Being taken by the player is what "watched" means here.
        assertTrue(watched.isWatched(media));
        assertTrue(Files.isRegularFile(temp.resolve("watched.json")));
        assertFalse(service.isPlaying());
    }

    @Test
    @DisplayName("a player that never started is reported but not remembered")
    void doesNotRecordFailedPlayback(@TempDir Path temp) {
        PlaybackHistory history = new PlaybackHistory();
        PlaybackService service = new PlaybackService(
                FakePlayerLauncher.failingWith("VLC could not be started."),
                history, new HistoryStore(temp), watchedService(temp), DIRECT, DIRECT);
        List<PlaybackResult> results = new ArrayList<>();

        service.play(media, "Dune", results::add);

        PlaybackResult result = results.getFirst();
        assertInstanceOf(PlaybackResult.Failed.class, result);
        assertEquals("VLC could not be started.",
                ((PlaybackResult.Failed) result).userMessage());
        assertTrue(history.isEmpty());
        assertFalse(watched.isWatched(media));
    }

    @Test
    @DisplayName("the queue rides along to the player; only the chosen file is remembered")
    void passesTheQueueThroughAndRecordsOnlyTheChosenFile(@TempDir Path temp) {
        PlaybackHistory history = new PlaybackHistory();
        FakePlayerLauncher launcher = FakePlayerLauncher.succeeding();
        PlaybackService service = new PlaybackService(
                launcher, history, new HistoryStore(temp), watchedService(temp), DIRECT, DIRECT);
        Path episodeTwo = Path.of("\\\\synology\\video\\TV\\e2.mkv");
        Path episodeThree = Path.of("\\\\synology\\video\\TV\\e3.mkv");

        service.play(media, List.of(episodeTwo, episodeThree), "Dune", result -> { });

        assertEquals(List.of(media), launcher.played());
        assertEquals(List.of(List.of(episodeTwo, episodeThree)), launcher.queues());
        // The player walks the queue on its own; nothing here knows how far it
        // got, so only the chosen start belongs in the history.
        assertEquals(1, history.entries().size());
        assertEquals(media, history.entries().getFirst().mediaPath());
    }

    @Test
    @DisplayName("VLC being killed externally is still a normal end of playback")
    void nonZeroExitCodeIsStillACompletedPlayback(@TempDir Path temp) {
        PlaybackHistory history = new PlaybackHistory();
        PlaybackService service = new PlaybackService(
                FakePlayerLauncher.behavingAs(file ->
                        new PlaybackResult.Completed(137, java.time.Duration.ofMinutes(3))),
                history, new HistoryStore(temp), watchedService(temp), DIRECT, DIRECT);
        List<PlaybackResult> results = new ArrayList<>();

        service.play(media, "Dune", results::add);

        assertEquals(137, ((PlaybackResult.Completed) results.getFirst()).exitCode());
        assertEquals(1, history.entries().size());
    }

    @Test
    void anUnexpectedFailureInsideThePlayerIsTurnedIntoAMessage(@TempDir Path temp) {
        PlaybackService service = new PlaybackService(
                FakePlayerLauncher.behavingAs(file -> {
                    throw new IllegalStateException("boom");
                }),
                new PlaybackHistory(), new HistoryStore(temp), watchedService(temp), DIRECT, DIRECT);
        List<PlaybackResult> results = new ArrayList<>();

        service.play(media, "Dune", results::add);

        assertInstanceOf(PlaybackResult.Failed.class, results.getFirst());
        assertFalse(service.isPlaying());
    }

    @Test
    @DisplayName("with a preparer the window-hiding callback still precedes the player")
    void runsThePreparerBeforeThePlayer(@TempDir Path temp) throws Exception {
        Path localMedia = temp.resolve("clip.mkv");
        // Large enough that even a busy CI disk measures far above the assumed
        // required rate, keeping this on the fast pass-through path.
        Files.write(localMedia, new byte[8 << 20]);
        List<String> order = new ArrayList<>();
        FakePlayerLauncher launcher = FakePlayerLauncher.behavingAs(file -> {
            order.add("player");
            return new PlaybackResult.Completed(0, java.time.Duration.ofMinutes(1));
        });
        // A real preparer over a local file: measured fast, passed through.
        mediacenter.playback.cache.MediaMirror mirror = new mediacenter.playback.cache.MediaMirror(
                temp.resolve("cache"), () -> 1L << 30);
        PlaybackService service = new PlaybackService(
                launcher, new PlaybackHistory(), new HistoryStore(temp), watchedService(temp),
                DIRECT, DIRECT, new mediacenter.playback.cache.PlaybackPreparer(mirror));

        service.play(localMedia, List.of(), "Clip",
                progress -> order.add("buffering"),
                status -> order.add("status:" + status),
                new mediacenter.playback.cache.PlaybackPreparer.BufferingControl(),
                () -> order.add("hide"),
                result -> order.add("finished"));

        assertEquals(List.of("hide", "player", "finished"), order);
        assertEquals(List.of(localMedia), launcher.played());
        // Nothing here should have started a copy; drained so the temp
        // directory is never deleted under one if that ever changes.
        assertTrue(mirror.awaitIdle(10_000));
    }

    @Test
    @DisplayName("only one player runs at a time")
    void ignoresASecondRequestWhileAPlayerIsRunning(@TempDir Path temp) {
        PlaybackHistory history = new PlaybackHistory();
        HistoryStore store = new HistoryStore(temp);
        AtomicBoolean playingDuringPlayback = new AtomicBoolean();
        List<PlaybackResult> results = new ArrayList<>();
        PlaybackService[] holder = new PlaybackService[1];

        holder[0] = new PlaybackService(
                FakePlayerLauncher.behavingAs(file -> {
                    playingDuringPlayback.set(holder[0].isPlaying());
                    // A second request arriving mid-playback must be dropped.
                    holder[0].play(Path.of("/media/other.mkv"), "Other", results::add);
                    return new PlaybackResult.Completed(0, java.time.Duration.ofMinutes(1));
                }),
                history, store, watchedService(temp), DIRECT, DIRECT);

        holder[0].play(media, "Dune", results::add);

        assertTrue(playingDuringPlayback.get());
        assertEquals(1, results.size());
        assertEquals(1, history.entries().size());
    }
}
