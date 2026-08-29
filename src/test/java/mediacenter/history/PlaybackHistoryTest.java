package mediacenter.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlaybackHistoryTest {

    private static final Instant BASE = Instant.parse("2026-01-01T20:00:00Z");

    @Test
    void newestEntryComesFirst() {
        PlaybackHistory history = new PlaybackHistory();

        history.record(Path.of("/media/a.mkv"), "A", BASE);
        history.record(Path.of("/media/b.mkv"), "B", BASE.plusSeconds(60));

        assertEquals(List.of("B", "A"),
                history.entries().stream().map(PlaybackHistoryEntry::displayTitle).toList());
    }

    @Test
    @DisplayName("a moved file keeps its place and its timestamp in the history")
    void aMovedFileKeepsItsEntry() {
        PlaybackHistory history = new PlaybackHistory();
        history.record(Path.of("/media/Heat.1995.mkv"), "Heat 1995", BASE);
        history.record(Path.of("/media/b.mkv"), "B", BASE.plusSeconds(60));

        assertTrue(history.move(
                Path.of("/media/Heat.1995.mkv"),
                Path.of("/media/Heat.1995/Heat.1995.mkv")));

        PlaybackHistoryEntry moved = history.entries().getLast();
        assertEquals(Path.of("/media/Heat.1995/Heat.1995.mkv"), moved.mediaPath());
        assertEquals("Heat 1995", moved.displayTitle());
        assertEquals(BASE, moved.lastPlayed());
        assertFalse(history.move(Path.of("/media/unknown.mkv"), Path.of("/media/x.mkv")));
    }

    @Test
    @DisplayName("replaying a title moves it back to the front instead of duplicating it")
    void replayingMovesTheEntryToTheFront() {
        PlaybackHistory history = new PlaybackHistory();
        history.record(Path.of("/media/a.mkv"), "A", BASE);
        history.record(Path.of("/media/b.mkv"), "B", BASE.plusSeconds(60));

        history.record(Path.of("/media/a.mkv"), "A", BASE.plusSeconds(120));

        assertEquals(2, history.entries().size());
        assertEquals("A", history.entries().getFirst().displayTitle());
        assertEquals(BASE.plusSeconds(120), history.entries().getFirst().lastPlayed());
    }

    @Test
    void theOldestEntriesFallOffTheEnd() {
        PlaybackHistory history = new PlaybackHistory(3);

        for (int i = 0; i < 5; i++) {
            history.record(Path.of("/media/" + i + ".mkv"), "Title " + i, BASE.plusSeconds(i));
        }

        assertEquals(List.of("Title 4", "Title 3", "Title 2"),
                history.entries().stream().map(PlaybackHistoryEntry::displayTitle).toList());
    }

    @Test
    void mostRecentNeverAsksForMoreThanItHas() {
        PlaybackHistory history = new PlaybackHistory();
        assertTrue(history.isEmpty());
        assertTrue(history.mostRecent(10).isEmpty());

        history.record(Path.of("/media/a.mkv"), "A", BASE);

        assertEquals(1, history.mostRecent(10).size());
        assertFalse(history.isEmpty());
    }

    @Test
    void loadedEntriesAreSortedNewestFirst() {
        PlaybackHistory history = PlaybackHistory.of(List.of(
                new PlaybackHistoryEntry(Path.of("/media/old.mkv"), "Old", BASE),
                new PlaybackHistoryEntry(Path.of("/media/new.mkv"), "New", BASE.plusSeconds(600))));

        assertEquals(List.of("New", "Old"),
                history.entries().stream().map(PlaybackHistoryEntry::displayTitle).toList());
    }

    @Test
    void entriesNeedAPathATitleAndATimestamp() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlaybackHistoryEntry(null, "A", BASE));
        assertThrows(IllegalArgumentException.class,
                () -> new PlaybackHistoryEntry(Path.of("/a.mkv"), " ", BASE));
        assertThrows(IllegalArgumentException.class,
                () -> new PlaybackHistoryEntry(Path.of("/a.mkv"), "A", null));
    }

    @Test
    @DisplayName("history survives a save/load round trip")
    void roundTripsThroughTheStore(@TempDir Path temp) {
        HistoryStore store = new HistoryStore(temp);
        PlaybackHistory history = new PlaybackHistory();
        history.record(Path.of("\\\\synology\\video\\Movies\\Dune.mkv"), "Dune", BASE);
        history.record(Path.of("/media/Arrival.mkv"), "Arrival", BASE.plusSeconds(30));

        assertTrue(store.save(history));
        PlaybackHistory reloaded = store.load();

        assertEquals(List.of("Arrival", "Dune"),
                reloaded.entries().stream().map(PlaybackHistoryEntry::displayTitle).toList());
        assertEquals(Path.of("\\\\synology\\video\\Movies\\Dune.mkv"),
                reloaded.entries().getLast().mediaPath());
        assertEquals(BASE, reloaded.entries().getLast().lastPlayed());
    }

    @Test
    void missingHistoryFileLoadsAsEmpty(@TempDir Path temp) {
        assertTrue(new HistoryStore(temp).load().isEmpty());
    }

    @Test
    void corruptHistoryIsQuarantined(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("history.json"), "not json at all");

        assertTrue(new HistoryStore(temp).load().isEmpty());
        assertTrue(Files.isRegularFile(temp.resolve("history.json.corrupt")));
    }

    @Test
    void entriesWithoutAPathAreSkipped(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("history.json"),
                "{\"entries\":[{\"title\":\"Broken\"},{\"path\":\"/media/ok.mkv\",\"title\":\"Ok\"}]}");

        PlaybackHistory history = new HistoryStore(temp).load();

        assertEquals(1, history.entries().size());
        assertEquals("Ok", history.entries().getFirst().displayTitle());
    }
}
