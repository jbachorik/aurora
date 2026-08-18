package mediacenter.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WatchedVideosTest {

    private static final Executor DIRECT = Runnable::run;

    @Test
    void marksAndResetsOneVideo() {
        WatchedVideos watched = new WatchedVideos();
        Path video = Path.of("/media/Movies/Dune.mkv");

        assertFalse(watched.isWatched(video));
        assertTrue(watched.mark(video));
        assertTrue(watched.isWatched(video));
        // Marking again changes nothing — the set is the point.
        assertFalse(watched.mark(video));

        assertTrue(watched.reset(video));
        assertFalse(watched.isWatched(video));
        assertFalse(watched.reset(video));
    }

    @Test
    @DisplayName("resetting a folder clears the marks from every subfolder below it")
    void resetsAFolderRecursively() {
        WatchedVideos watched = new WatchedVideos();
        watched.mark(Path.of("/media/TV/Show/s01e01.mkv"));
        watched.mark(Path.of("/media/TV/Show/Season 2/s02e01.mkv"));
        watched.mark(Path.of("/media/Movies/Dune.mkv"));

        assertTrue(watched.resetBelow(Path.of("/media/TV")));

        assertFalse(watched.isWatched(Path.of("/media/TV/Show/s01e01.mkv")));
        assertFalse(watched.isWatched(Path.of("/media/TV/Show/Season 2/s02e01.mkv")));
        assertTrue(watched.isWatched(Path.of("/media/Movies/Dune.mkv")));
        // Nothing left below the folder: a second reset has nothing to do.
        assertFalse(watched.resetBelow(Path.of("/media/TV")));
    }

    @Test
    @DisplayName("a folder reset is component-wise, not a string prefix match")
    void resetDoesNotBleedIntoSiblingsSharingThePrefix() {
        WatchedVideos watched = new WatchedVideos();
        watched.mark(Path.of("/media/TV Extras/blooper.mkv"));

        assertFalse(watched.resetBelow(Path.of("/media/TV")));

        assertTrue(watched.isWatched(Path.of("/media/TV Extras/blooper.mkv")));
    }

    @Test
    void toggleFlipsTheMarkAndReportsTheNewState(@TempDir Path temp) {
        WatchedVideos watched = new WatchedVideos();
        WatchedService service = new WatchedService(watched, new WatchedStore(temp), DIRECT);
        Path video = Path.of("/media/Movies/Arrival.mkv");

        assertTrue(service.toggleWatched(video));
        assertTrue(watched.isWatched(video));
        assertFalse(service.toggleWatched(video));
        assertFalse(watched.isWatched(video));
    }

    @Test
    @DisplayName("the marks survive a save/load round trip")
    void roundTripsThroughTheStore(@TempDir Path temp) {
        WatchedStore store = new WatchedStore(temp);
        WatchedVideos watched = new WatchedVideos();
        watched.mark(Path.of("\\\\synology\\video\\Movies\\Dune.mkv"));
        watched.mark(Path.of("/media/Movies/Arrival.mkv"));

        assertTrue(store.save(watched));
        WatchedVideos reloaded = store.load();

        assertTrue(reloaded.isWatched(Path.of("\\\\synology\\video\\Movies\\Dune.mkv")));
        assertTrue(reloaded.isWatched(Path.of("/media/Movies/Arrival.mkv")));
        assertEquals(2, reloaded.paths().size());
    }

    @Test
    @DisplayName("the service persists every change through the store")
    void serviceSavesChanges(@TempDir Path temp) {
        WatchedStore store = new WatchedStore(temp);
        WatchedService service = new WatchedService(new WatchedVideos(), store, DIRECT);
        Path video = Path.of("/media/Movies/Dune.mkv");

        service.markWatched(video);

        assertTrue(Files.isRegularFile(temp.resolve("watched.json")));
        assertTrue(store.load().isWatched(video));

        service.resetBelow(Path.of("/media/Movies"));

        assertFalse(store.load().isWatched(video));
    }

    @Test
    void missingWatchedFileLoadsAsEmpty(@TempDir Path temp) {
        assertEquals(List.of(), new WatchedStore(temp).load().paths());
    }

    @Test
    void corruptWatchedFileIsQuarantined(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("watched.json"), "not json at all");

        assertEquals(List.of(), new WatchedStore(temp).load().paths());
        assertTrue(Files.isRegularFile(temp.resolve("watched.json.corrupt")));
    }

    @Test
    void entriesWithoutAPathAreSkipped(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("watched.json"),
                "{\"watched\":[{\"title\":\"Broken\"},{\"path\":\"/media/ok.mkv\"}]}");

        assertEquals(List.of(Path.of("/media/ok.mkv")), new WatchedStore(temp).load().paths());
    }
}
