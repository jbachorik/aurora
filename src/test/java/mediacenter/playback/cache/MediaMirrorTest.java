package mediacenter.playback.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mediacenter.playback.cache.MediaMirror.MirrorTask;

class MediaMirrorTest {

    private static final long PLENTY = 1L << 30;

    private static MirrorTask copied(MediaMirror mirror, Path source) throws InterruptedException {
        Optional<MirrorTask> task = mirror.copy(source);
        assertTrue(task.isPresent(), "the copy should start");
        assertTrue(task.get().await(10_000), "the copy should finish promptly");
        return task.get();
    }

    private static Path source(Path directory, String name, int bytes) throws IOException {
        Path file = directory.resolve(name);
        byte[] content = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            content[i] = (byte) i;
        }
        Files.write(file, content);
        return file;
    }

    @Test
    @DisplayName("a finished copy is byte-identical and found again")
    void copiesAndServes(@TempDir Path temp) throws Exception {
        MediaMirror mirror = new MediaMirror(temp.resolve("cache"), () -> PLENTY);
        Path source = source(temp, "episode.mkv", 100_000);

        MirrorTask task = copied(mirror, source);

        assertTrue(task.isDone());
        assertEquals(100_000, task.copiedBytes());
        Optional<Path> copy = mirror.completedCopy(source);
        assertTrue(copy.isPresent());
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(copy.get()));
        assertTrue(String.valueOf(copy.get().getFileName()).endsWith(".mkv"));
    }

    @Test
    @DisplayName("asking twice while a copy runs joins it rather than racing it")
    void joinsARunningCopy(@TempDir Path temp) throws Exception {
        MediaMirror mirror = new MediaMirror(temp.resolve("cache"), () -> PLENTY);
        Path source = source(temp, "episode.mkv", 100_000);

        Optional<MirrorTask> first = mirror.copy(source);
        Optional<MirrorTask> second = mirror.copy(source);

        assertTrue(first.isPresent());
        // Either the same in-flight task, or the first finished so fast that a
        // fresh lookup was the answer; never two competing copies.
        second.ifPresent(task -> assertEquals(first.get().target(), task.target()));
        assertTrue(first.get().await(10_000));
    }

    @Test
    @DisplayName("a source that changed since the copy invalidates it")
    void aChangedSourceInvalidatesTheCopy(@TempDir Path temp) throws Exception {
        MediaMirror mirror = new MediaMirror(temp.resolve("cache"), () -> PLENTY);
        Path source = source(temp, "episode.mkv", 50_000);
        copied(mirror, source);

        Files.write(source, new byte[60_000]);

        assertTrue(mirror.completedCopy(source).isEmpty());
    }

    @Test
    @DisplayName("an unreachable source still serves its copy — that is the point")
    void anUnreachableSourceStillServes(@TempDir Path temp) throws Exception {
        MediaMirror mirror = new MediaMirror(temp.resolve("cache"), () -> PLENTY);
        Path source = source(temp, "episode.mkv", 50_000);
        copied(mirror, source);

        Files.delete(source);

        assertTrue(mirror.completedCopy(source).isPresent());
    }

    @Test
    @DisplayName("the index survives a restart")
    void copiesSurviveARestart(@TempDir Path temp) throws Exception {
        Path cache = temp.resolve("cache");
        Path source = source(temp, "episode.mkv", 50_000);
        copied(new MediaMirror(cache, () -> PLENTY), source);

        MediaMirror reopened = new MediaMirror(cache, () -> PLENTY);

        assertTrue(reopened.completedCopy(source).isPresent());
    }

    @Test
    @DisplayName("least recently used copies make way for new ones")
    void evictsLeastRecentlyUsed(@TempDir Path temp) throws Exception {
        MediaMirror mirror = new MediaMirror(temp.resolve("cache"), () -> 250_000L);
        Path first = source(temp, "first.mkv", 100_000);
        Path second = source(temp, "second.mkv", 100_000);
        Path third = source(temp, "third.mkv", 100_000);

        copied(mirror, first);
        Thread.sleep(5); // last-used stamps must differ
        copied(mirror, second);
        Thread.sleep(5);
        mirror.completedCopy(first); // touch: first is now fresher than second
        copied(mirror, third);

        assertTrue(mirror.completedCopy(first).isPresent(), "recently used survives");
        assertTrue(mirror.completedCopy(second).isEmpty(), "the stale one went");
        assertTrue(mirror.completedCopy(third).isPresent());
        assertTrue(mirror.usedBytes() <= 250_000);
    }

    @Test
    @DisplayName("a file larger than the whole mirror is refused, not thrashed for")
    void refusesWhatCanNeverFit(@TempDir Path temp) throws Exception {
        MediaMirror mirror = new MediaMirror(temp.resolve("cache"), () -> 10_000L);
        Path source = source(temp, "film.mkv", 50_000);

        assertTrue(mirror.copy(source).isEmpty());
    }

    @Test
    @DisplayName("a zero capacity switches the mirror off")
    void zeroCapacityDisables(@TempDir Path temp) throws Exception {
        MediaMirror mirror = new MediaMirror(temp.resolve("cache"), () -> 0L);
        Path source = source(temp, "film.mkv", 1_000);

        assertFalse(mirror.enabled());
        assertTrue(mirror.copy(source).isEmpty());
    }

    @Test
    @DisplayName("two viewings of a network file make it frequently played; local files never")
    void countsPlays(@TempDir Path temp) throws Exception {
        MediaMirror mirror = new MediaMirror(temp.resolve("cache"), () -> PLENTY);
        Path network = Path.of("\\\\synology\\video\\favourite.mkv");
        Path local = Path.of("/media/local.mkv");

        mirror.recordPlayed(network, true);
        assertFalse(mirror.isFrequentlyPlayed(network), "one viewing is not a habit");
        mirror.recordPlayed(network, true);
        assertTrue(mirror.isFrequentlyPlayed(network));

        mirror.recordPlayed(local, false);
        mirror.recordPlayed(local, false);
        assertFalse(mirror.isFrequentlyPlayed(local), "a local file needs no mirror");
    }

    @Test
    @DisplayName("an interrupted copy is cleaned up on the next start")
    void discardsTorsosOnStart(@TempDir Path temp) throws Exception {
        Path cache = temp.resolve("cache");
        MediaMirror mirror = new MediaMirror(cache, () -> PLENTY);
        Path source = source(temp, "film.mkv", 50_000);
        copied(mirror, source);
        // Simulate a crash mid-copy: the file is there but marked incomplete.
        Path index = cache.resolve("mirror-index.json");
        Files.writeString(index, Files.readString(index)
                .replace("\"complete\": true", "\"complete\": false"));

        MediaMirror reopened = new MediaMirror(cache, () -> PLENTY);

        assertTrue(reopened.completedCopy(source).isEmpty());
        try (var listing = Files.list(cache)) {
            assertEquals(1, listing.count(), "only the index remains");
        }
    }
}
