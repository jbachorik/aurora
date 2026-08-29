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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mediacenter.playback.cache.MediaDurations.VideoHeader;
import mediacenter.playback.cache.PlaybackPreparer.Prepared;

class PlaybackPreparerTest {

    private static final long PLENTY = 1L << 30;

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

    private static PlaybackPreparer preparer(MediaMirror mirror, long rate, VideoHeader header) {
        return new PlaybackPreparer(mirror, file -> OptionalLong.of(rate), file -> header, 10);
    }

    @Test
    @DisplayName("a share faster than the bitrate plays in place, untouched")
    void fastEnoughPassesThrough(@TempDir Path temp) throws IOException {
        MediaMirror mirror = new MediaMirror(temp.resolve("cache"), () -> PLENTY);
        Path media = source(temp, "episode.mkv");
        Path next = source(temp, "next.mkv");
        // Ten times the required 125 KB/s.
        PlaybackPreparer preparer = preparer(mirror, 1_250_000, STREAMABLE_SECOND);

        Prepared prepared = preparer.prepare(media, List.of(next), status -> { });

        assertEquals(media, prepared.mediaFile());
        assertEquals(List.of(next), prepared.playOnwards());
        assertTrue(prepared.notice().isEmpty());
        assertEquals(0, mirror.usedBytes(), "nothing was copied");
    }

    @Test
    @DisplayName("an unreadable file is left for the player to complain about")
    void unmeasurableFilePassesThrough(@TempDir Path temp) throws IOException {
        MediaMirror mirror = new MediaMirror(temp.resolve("cache"), () -> PLENTY);
        Path media = source(temp, "episode.mkv");
        PlaybackPreparer preparer = new PlaybackPreparer(
                mirror, file -> OptionalLong.empty(), file -> STREAMABLE_SECOND, 10);

        Prepared prepared = preparer.prepare(media, List.of(), status -> { });

        assertEquals(media, prepared.mediaFile());
    }

    @Test
    @DisplayName("a slow share is buffered into the mirror before the player starts")
    void slowShareBuffersAhead(@TempDir Path temp) throws IOException {
        MediaMirror mirror = new MediaMirror(temp.resolve("cache"), () -> PLENTY);
        Path media = source(temp, "episode.mkv");
        // A tenth of the required rate; the file is tiny, so the lead the
        // arithmetic demands is the whole file and the local copy wins quickly.
        PlaybackPreparer preparer = preparer(mirror, 10_000, STREAMABLE_SECOND);
        List<String> progress = new ArrayList<>();

        Prepared prepared = preparer.prepare(media, List.of(), progress::add);

        assertNotEquals(media, prepared.mediaFile(), "the player gets the local copy");
        assertArrayEquals(Files.readAllBytes(media), Files.readAllBytes(prepared.mediaFile()));
        assertTrue(prepared.notice().isEmpty());
    }

    @Test
    @DisplayName("the next title in the queue is buffered along and substituted")
    void slowShareCoversTheNextTitleToo(@TempDir Path temp) throws Exception {
        MediaMirror mirror = new MediaMirror(temp.resolve("cache"), () -> PLENTY);
        Path media = source(temp, "e1.mkv");
        Path next = source(temp, "e2.mkv");
        Path later = source(temp, "e3.mkv");
        PlaybackPreparer preparer = preparer(mirror, 10_000, STREAMABLE_SECOND);

        Prepared prepared = preparer.prepare(media, List.of(next, later), status -> { });

        assertNotEquals(media, prepared.mediaFile());
        assertNotEquals(next, prepared.playOnwards().getFirst(),
                "the next episode plays from its mirror copy");
        assertEquals(later, prepared.playOnwards().get(1),
                "titles beyond the next are not held for");
        assertArrayEquals(Files.readAllBytes(next),
                Files.readAllBytes(prepared.playOnwards().getFirst()));
    }

    @Test
    @DisplayName("a wait that would take too long plays directly, mirroring for next time")
    void unreasonableWaitPlaysDirectly(@TempDir Path temp) throws Exception {
        MediaMirror mirror = new MediaMirror(temp.resolve("cache"), () -> PLENTY);
        Path media = source(temp, "film.mkv");
        // One hundred bytes a second: buffering 100 KB would take a thousand
        // seconds, far past patience.
        PlaybackPreparer preparer = preparer(mirror, 100, STREAMABLE_SECOND);

        Prepared prepared = preparer.prepare(media, List.of(), status -> { });

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
        MediaMirror mirror = new MediaMirror(temp.resolve("cache"), () -> 0L);
        Path media = source(temp, "episode.mkv");
        PlaybackPreparer preparer = preparer(mirror, 10_000, STREAMABLE_SECOND);

        Prepared prepared = preparer.prepare(media, List.of(), status -> { });

        assertEquals(media, prepared.mediaFile());
        assertTrue(prepared.notice().isPresent());
    }

    @Test
    @DisplayName("an existing mirror copy is played without probing anything")
    void existingCopyShortCircuits(@TempDir Path temp) throws Exception {
        MediaMirror mirror = new MediaMirror(temp.resolve("cache"), () -> PLENTY);
        Path media = source(temp, "episode.mkv");
        MediaMirror.MirrorTask task = mirror.copy(media).orElseThrow();
        assertTrue(task.await(10_000));
        // A probe that explodes proves it is never consulted.
        PlaybackPreparer preparer = new PlaybackPreparer(
                mirror,
                file -> { throw new AssertionError("probed despite a finished copy"); },
                file -> { throw new AssertionError("parsed despite a finished copy"); },
                10);

        Prepared prepared = preparer.prepare(media, List.of(), status -> { });

        assertEquals(task.target(), prepared.mediaFile());
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
        MediaMirror mirror = new MediaMirror(temp.resolve("cache"), () -> PLENTY);
        Path media = source(temp, "local.mkv");
        PlaybackPreparer preparer = preparer(mirror, 1_250_000, STREAMABLE_SECOND);

        preparer.recordPlayed(media);
        preparer.recordPlayed(media);
        preparer.recordPlayed(media);

        assertEquals(0, mirror.usedBytes());
    }
}
