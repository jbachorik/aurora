package mediacenter.playback.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mediacenter.playback.PlayablePaths;
import mediacenter.playback.cache.MediaDurations.VideoHeader;
import mediacenter.playback.cache.PlaybackPreparer.BufferingControl;
import mediacenter.playback.cache.PlaybackPreparer.BufferingProgress;
import mediacenter.playback.cache.PlaybackPreparer.Prepared;

class PlaybackPreparerTest {

    private static final long PLENTY = 1L << 30;

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

    /** A hundred kilobytes over a nominal second: the "bitrate" is 100 KB/s. */
    private static final int FILE_BYTES = 100_000;
    private static final VideoHeader STREAMABLE_SECOND =
            new VideoHeader(Optional.of(Duration.ofSeconds(1)), true);

    private static Path source(Path directory, String name) throws IOException {
        Path file = directory.resolve(name);
        byte[] content = new byte[FILE_BYTES];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31);
        }
        Files.write(file, content);
        return file;
    }

    /** Every path counts as a network path, so prefetch decisions can be tested locally. */
    private static PlaybackPreparer preparer(MediaMirror mirror, long rate, VideoHeader header) {
        return new PlaybackPreparer(
                mirror, file -> OptionalLong.of(rate), file -> header, path -> true, 10);
    }

    private static Prepared prepared(
            PlaybackPreparer preparer, Path media, List<Path> queue) {
        return preparer.prepare(media, queue, progress -> { }, new BufferingControl())
                .orElseThrow();
    }

    @Test
    @DisplayName("a share faster than the bitrate plays in place, and the queue prefetches behind it")
    void fastEnoughPassesThroughAndPrefetches(@TempDir Path temp) throws Exception {
        MediaMirror mirror = mirror(temp.resolve("cache"), PLENTY);
        Path media = source(temp, "episode.mkv");
        Path next = source(temp, "next.mkv");
        // Ten times the required 125 KB/s: streams fine, and the surplus is
        // wide enough to carry the next episode into the mirror meanwhile.
        PlaybackPreparer preparer = preparer(mirror, 1_250_000, STREAMABLE_SECOND);

        Prepared prepared = prepared(preparer, media, List.of(next));

        assertEquals(media, prepared.mediaFile(), "fast enough to stream in place");
        assertTrue(prepared.notice().isEmpty());
        assertNotEquals(next, prepared.playOnwards().getFirst(),
                "the next episode is guaranteed to land in time, so its copy is handed over");
        long deadline = System.currentTimeMillis() + 10_000;
        while (mirror.completedCopy(next).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertArrayEquals(Files.readAllBytes(next),
                Files.readAllBytes(prepared.playOnwards().getFirst()));
    }

    @Test
    @DisplayName("a local library is left entirely alone")
    void localFilesAreNotPrefetched(@TempDir Path temp) throws IOException {
        MediaMirror mirror = mirror(temp.resolve("cache"), PLENTY);
        Path media = source(temp, "episode.mkv");
        Path next = source(temp, "next.mkv");
        PlaybackPreparer preparer = new PlaybackPreparer(
                mirror, file -> OptionalLong.of(1_250_000), file -> STREAMABLE_SECOND,
                path -> false, 10);

        Prepared prepared = prepared(preparer, media, List.of(next));

        assertEquals(media, prepared.mediaFile());
        assertEquals(List.of(next), prepared.playOnwards());
        assertEquals(0, mirror.usedBytes(), "nothing was copied");
    }

    @Test
    @DisplayName("a surplus too thin to carry a copy leaves the queue alone")
    void thinSurplusSkipsPrefetch(@TempDir Path temp) throws IOException {
        MediaMirror mirror = mirror(temp.resolve("cache"), PLENTY);
        Path media = source(temp, "episode.mkv");
        Path next = source(temp, "next.mkv");
        // Just over the required 125 KB/s: streams, but the leftovers are
        // nowhere near MINIMUM_PREFETCH_SURPLUS.
        PlaybackPreparer preparer = preparer(mirror, 130_000, STREAMABLE_SECOND);

        Prepared prepared = prepared(preparer, media, List.of(next));

        assertEquals(List.of(next), prepared.playOnwards());
        assertEquals(0, mirror.usedBytes());
    }

    @Test
    @DisplayName("an unreadable file is left for the player to complain about")
    void unmeasurableFilePassesThrough(@TempDir Path temp) throws IOException {
        MediaMirror mirror = mirror(temp.resolve("cache"), PLENTY);
        Path media = source(temp, "episode.mkv");
        PlaybackPreparer preparer = new PlaybackPreparer(
                mirror, file -> OptionalLong.empty(), file -> STREAMABLE_SECOND, path -> true, 10);

        Prepared prepared = prepared(preparer, media, List.of());

        assertEquals(media, prepared.mediaFile());
    }

    @Test
    @DisplayName("a slow share is buffered into the mirror before the player starts")
    void slowShareBuffersAhead(@TempDir Path temp) throws IOException {
        MediaMirror mirror = mirror(temp.resolve("cache"), PLENTY);
        Path media = source(temp, "episode.mkv");
        // A tenth of the required rate; the file is tiny, so the lead the
        // arithmetic demands is the whole file and the local copy wins quickly.
        PlaybackPreparer preparer = preparer(mirror, 10_000, STREAMABLE_SECOND);
        List<BufferingProgress> progress = new ArrayList<>();

        Prepared prepared = preparer
                .prepare(media, List.of(), progress::add, new BufferingControl())
                .orElseThrow();

        assertNotEquals(media, prepared.mediaFile(), "the player gets the local copy");
        assertArrayEquals(Files.readAllBytes(media), Files.readAllBytes(prepared.mediaFile()));
        assertTrue(prepared.notice().isEmpty());
    }

    @Test
    @DisplayName("the next title in the queue is buffered along and substituted")
    void slowShareCoversTheNextTitleToo(@TempDir Path temp) throws Exception {
        MediaMirror mirror = mirror(temp.resolve("cache"), PLENTY);
        Path media = source(temp, "e1.mkv");
        Path next = source(temp, "e2.mkv");
        Path later = source(temp, "e3.mkv");
        PlaybackPreparer preparer = preparer(mirror, 10_000, STREAMABLE_SECOND);

        Prepared prepared = prepared(preparer, media, List.of(next, later));

        assertNotEquals(media, prepared.mediaFile());
        assertNotEquals(next, prepared.playOnwards().getFirst(),
                "the next episode plays from its mirror copy");
        assertEquals(later, prepared.playOnwards().get(1),
                "titles beyond the next are not held for");
        assertArrayEquals(Files.readAllBytes(next),
                Files.readAllBytes(prepared.playOnwards().getFirst()));
    }

    @Test
    @DisplayName("cancelling a buffering wait ends the request with no playback")
    void cancellingEndsTheWait(@TempDir Path temp) throws IOException {
        MediaMirror mirror = mirror(temp.resolve("cache"), PLENTY);
        Path media = source(temp, "film.mkv");
        PlaybackPreparer preparer = preparer(mirror, 10_000, STREAMABLE_SECOND);
        BufferingControl control = new BufferingControl();
        control.cancel();

        assertTrue(preparer.prepare(media, List.of(), progress -> { }, control).isEmpty());
    }

    @Test
    @DisplayName("play-now during a buffering wait starts the original immediately, warned")
    void playNowSkipsTheWait(@TempDir Path temp) throws IOException {
        MediaMirror mirror = mirror(temp.resolve("cache"), PLENTY);
        Path media = source(temp, "film.mkv");
        PlaybackPreparer preparer = preparer(mirror, 10_000, STREAMABLE_SECOND);
        BufferingControl control = new BufferingControl();
        control.playNow();

        Prepared prepared = preparer
                .prepare(media, List.of(), progress -> { }, control)
                .orElseThrow();

        assertEquals(media, prepared.mediaFile());
        assertEquals(Optional.of(PlaybackPreparer.SLOW_SHARE_NOTICE), prepared.notice());
    }

    @Test
    @DisplayName("a wait that would take too long plays directly, mirroring for next time")
    void unreasonableWaitPlaysDirectly(@TempDir Path temp) throws Exception {
        MediaMirror mirror = mirror(temp.resolve("cache"), PLENTY);
        Path media = source(temp, "film.mkv");
        // One hundred bytes a second: buffering 100 KB would take a thousand
        // seconds, far past patience.
        PlaybackPreparer preparer = preparer(mirror, 100, STREAMABLE_SECOND);

        Prepared prepared = prepared(preparer, media, List.of());

        assertEquals(media, prepared.mediaFile(), "the original plays, stutters and all");
        assertEquals(Optional.of(PlaybackPreparer.SLOW_SHARE_NOTICE), prepared.notice());
        // The background copy still lands, so the next viewing is local.
        long deadline = System.currentTimeMillis() + 10_000;
        while (mirror.completedCopy(media).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(mirror.completedCopy(media).isPresent());
    }

    @Test
    @DisplayName("with the mirror off a slow share can only be warned about")
    void mirrorOffMeansWarningOnly(@TempDir Path temp) throws IOException {
        MediaMirror mirror = mirror(temp.resolve("cache"), 0L);
        Path media = source(temp, "episode.mkv");
        PlaybackPreparer preparer = preparer(mirror, 10_000, STREAMABLE_SECOND);

        Prepared prepared = prepared(preparer, media, List.of());

        assertEquals(media, prepared.mediaFile());
        assertTrue(prepared.notice().isPresent());
    }

    @Test
    @DisplayName("playing an existing mirror copy prefetches what follows it")
    void existingCopyShortCircuitsAndPrefetches(@TempDir Path temp) throws Exception {
        MediaMirror mirror = mirror(temp.resolve("cache"), PLENTY);
        Path media = source(temp, "episode.mkv");
        Path next = source(temp, "next.mkv");
        MediaMirror.MirrorTask task = mirror.copy(media).orElseThrow();
        assertTrue(task.await(10_000));
        PlaybackPreparer preparer = preparer(mirror, 500_000, STREAMABLE_SECOND);

        Prepared prepared = prepared(preparer, media, List.of(next));

        assertEquals(task.target(), prepared.mediaFile());
        assertNotEquals(next, prepared.playOnwards().getFirst(),
                "with the picture playing locally the next title rides the free bandwidth");
        long deadline = System.currentTimeMillis() + 10_000;
        while (mirror.completedCopy(next).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertArrayEquals(Files.readAllBytes(next),
                Files.readAllBytes(prepared.playOnwards().getFirst()));
    }

    @Test
    @DisplayName("the built-in player's session picks up copies as they land")
    void livePathsResolveAsPrefetchesFinish(@TempDir Path temp) throws Exception {
        MediaMirror mirror = mirror(temp.resolve("cache"), PLENTY);
        Path media = source(temp, "e1.mkv");
        Path next = source(temp, "e2.mkv");
        MediaMirror.MirrorTask first = mirror.copy(media).orElseThrow();
        assertTrue(first.await(10_000));
        PlaybackPreparer preparer = preparer(mirror, 500_000, STREAMABLE_SECOND);

        PlayablePaths session = preparer.embeddedSession(List.of(media, next));

        assertEquals(first.target(), session.playablePath(media));
        long deadline = System.currentTimeMillis() + 10_000;
        while (session.playablePath(next).equals(next) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertNotEquals(next, session.playablePath(next),
                "the copy that landed mid-run answers at the next episode boundary");
    }

    @Test
    @DisplayName("a run that starts by streaming keeps the network to itself")
    void livePathsDoNotPrefetchOverAStreamingRun(@TempDir Path temp) throws IOException {
        MediaMirror mirror = mirror(temp.resolve("cache"), PLENTY);
        Path media = source(temp, "e1.mkv");
        Path next = source(temp, "e2.mkv");
        PlaybackPreparer preparer = preparer(mirror, 500_000, STREAMABLE_SECOND);

        PlayablePaths session = preparer.embeddedSession(List.of(media, next));

        assertEquals(media, session.playablePath(media));
        assertEquals(0, mirror.usedBytes(), "no copy competes with the stream");
    }

    @Test
    @DisplayName("a stream that measures too slow gets a rescue copy and offers a takeover")
    void slowStreamGetsARescueTakeover(@TempDir Path temp) throws Exception {
        MediaMirror mirror = mirror(temp.resolve("cache"), PLENTY);
        Path media = source(temp, "film.mkv");
        // A tenth of the required rate: the entry streams (the built-in player
        // never blocks on buffering) and the session starts a copy behind it.
        PlaybackPreparer preparer = preparer(mirror, 10_000, STREAMABLE_SECOND);
        PlayablePaths session = preparer.embeddedSession(List.of(media));

        assertEquals(media, session.playablePath(media), "the stream starts on the original");
        session.startedFromOriginal(media);

        assertTrue(session.adviceFor(media).isPresent(), "the viewer hears about the rescue");
        long deadline = System.currentTimeMillis() + 10_000;
        while (session.takeoverAt(media, 0).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        Path takeover = session.takeoverAt(media, 0).orElseThrow();
        assertNotEquals(media, takeover);
        assertArrayEquals(Files.readAllBytes(media), Files.readAllBytes(takeover));
        assertNotEquals(media, session.playablePath(media),
                "once landed, the copy also answers for later entries and sessions");
    }

    @Test
    @DisplayName("a stream that measures fast is left alone — until the viewer pauses it")
    void pausingStartsARescueEvenWhenTheProbeSaidFast(@TempDir Path temp) throws Exception {
        MediaMirror mirror = mirror(temp.resolve("cache"), PLENTY);
        Path media = source(temp, "film.mkv");
        PlaybackPreparer preparer = preparer(mirror, 1_250_000, STREAMABLE_SECOND);
        PlayablePaths session = preparer.embeddedSession(List.of(media));

        session.startedFromOriginal(media);
        assertTrue(session.adviceFor(media).isEmpty(), "fast enough: no rescue, no chatter");
        assertEquals(0, mirror.usedBytes());

        // The share degraded mid-film; the viewer pauses. That is the cue.
        session.pausedOnOriginal(media);

        assertTrue(session.adviceFor(media).isPresent());
        long deadline = System.currentTimeMillis() + 10_000;
        while (session.takeoverAt(media, 30_000).isEmpty()
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(session.takeoverAt(media, 30_000).isPresent(),
                "resume finds the local copy ready");
    }

    @Test
    @DisplayName("with no rescue running a resume has nothing to switch to")
    void noRescueMeansNoTakeover(@TempDir Path temp) throws IOException {
        MediaMirror mirror = mirror(temp.resolve("cache"), PLENTY);
        Path media = source(temp, "film.mkv");
        PlaybackPreparer preparer = preparer(mirror, 1_250_000, STREAMABLE_SECOND);
        PlayablePaths session = preparer.embeddedSession(List.of(media));

        assertTrue(session.takeoverAt(media, 10_000).isEmpty());
        assertTrue(session.adviceFor(media).isEmpty());
    }

    @Test
    @DisplayName("UNC paths are network paths; drive letters and POSIX paths are not")
    void recognisesNetworkPaths() {
        assertTrue(PlaybackPreparer.isNetworkPath(Path.of("\\\\synology\\video\\film.mkv")));
        assertTrue(!PlaybackPreparer.isNetworkPath(Path.of("C:\\Media\\film.mkv")));
        assertTrue(!PlaybackPreparer.isNetworkPath(Path.of("/media/film.mkv")));
    }

    @Test
    @DisplayName("a local file never earns a mirror copy through play counts")
    void localPlaysNeverMirror(@TempDir Path temp) throws IOException {
        MediaMirror mirror = mirror(temp.resolve("cache"), PLENTY);
        Path media = source(temp, "local.mkv");
        PlaybackPreparer preparer = new PlaybackPreparer(
                mirror, file -> OptionalLong.of(1_250_000), file -> STREAMABLE_SECOND,
                path -> false, 10);

        preparer.recordPlayed(media);
        preparer.recordPlayed(media);
        preparer.recordPlayed(media);

        assertEquals(0, mirror.usedBytes());
    }
}
