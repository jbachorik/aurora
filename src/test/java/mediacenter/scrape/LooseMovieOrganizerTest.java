package mediacenter.scrape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mediacenter.scrape.LooseMovieOrganizer.Move;

class LooseMovieOrganizerTest {

    private final LooseMovieOrganizer organizer = new LooseMovieOrganizer();

    @Test
    @DisplayName("a loose film gains a folder named after it, sidecars and all")
    void foldersALooseFilm(@TempDir Path shelf) throws IOException {
        Files.createFile(shelf.resolve("Heat.1995.mkv"));
        Files.createFile(shelf.resolve("Heat.1995.srt"));
        Files.createFile(shelf.resolve("Heat.1995.en.srt"));
        Files.createFile(shelf.resolve("Heat.1995.jpg"));
        Files.createFile(shelf.resolve("Unrelated.txt"));

        List<Move> moves = organizer.organize(shelf, true);

        Path home = shelf.resolve("Heat.1995");
        assertEquals(List.of(new Move(
                shelf.resolve("Heat.1995.mkv"), home.resolve("Heat.1995.mkv"))), moves);
        assertTrue(Files.isRegularFile(home.resolve("Heat.1995.mkv")));
        assertTrue(Files.isRegularFile(home.resolve("Heat.1995.srt")));
        assertTrue(Files.isRegularFile(home.resolve("Heat.1995.en.srt")));
        assertTrue(Files.isRegularFile(home.resolve("Heat.1995.jpg")));
        // What does not share the film's name stays on the shelf.
        assertTrue(Files.isRegularFile(shelf.resolve("Unrelated.txt")));
    }

    @Test
    @DisplayName("an ordered run and a misfiled season are never broken apart")
    void leavesRunsAlone(@TempDir Path shelf) throws IOException {
        Files.createFile(shelf.resolve("01 - Curse of the Black Pearl.mkv"));
        Files.createFile(shelf.resolve("02 - Dead Mans Chest.mkv"));
        Files.createFile(shelf.resolve("Show.S01E01.mkv"));

        assertEquals(List.of(), organizer.organize(shelf, true));

        assertTrue(Files.isRegularFile(shelf.resolve("01 - Curse of the Black Pearl.mkv")));
        assertTrue(Files.isRegularFile(shelf.resolve("Show.S01E01.mkv")));
    }

    @Test
    @DisplayName("a folder named for one of its videos is that film's folder already")
    void leavesAClaimedFolderAlone(@TempDir Path temp) throws IOException {
        Path movie = Files.createDirectory(temp.resolve("Heat (1995)"));
        Files.createFile(movie.resolve("Heat.1995.mkv"));
        Files.createFile(movie.resolve("Making.Of.mkv"));

        assertEquals(List.of(), organizer.organize(movie, false));

        // Neither the film nor its extra was folded away.
        assertTrue(Files.isRegularFile(movie.resolve("Heat.1995.mkv")));
        assertTrue(Files.isRegularFile(movie.resolve("Making.Of.mkv")));
    }

    @Test
    @DisplayName("a grouping folder that claims none of its videos is a shelf")
    void tidiesAGroupingFolder(@TempDir Path temp) throws IOException {
        Path kids = Files.createDirectory(temp.resolve("Kids"));
        Files.createFile(kids.resolve("Frozen.2013.mkv"));
        Files.createFile(kids.resolve("Moana.2016.mkv"));

        List<Move> moves = organizer.organize(kids, false);

        assertEquals(2, moves.size());
        assertTrue(Files.isRegularFile(kids.resolve("Frozen.2013").resolve("Frozen.2013.mkv")));
        assertTrue(Files.isRegularFile(kids.resolve("Moana.2016").resolve("Moana.2016.mkv")));
    }

    @Test
    @DisplayName("samples and films whose folder already exists stay put")
    void skipsSamplesAndCollisions(@TempDir Path shelf) throws IOException {
        Files.createFile(shelf.resolve("sample.mkv"));
        Files.createFile(shelf.resolve("Dune.2021.mkv"));
        Files.createDirectory(shelf.resolve("Dune.2021"));

        assertEquals(List.of(), organizer.organize(shelf, true));

        assertTrue(Files.isRegularFile(shelf.resolve("sample.mkv")));
        assertTrue(Files.isRegularFile(shelf.resolve("Dune.2021.mkv")));
        assertFalse(Files.exists(shelf.resolve("Dune.2021").resolve("Dune.2021.mkv")));
    }

    @Test
    @DisplayName("a shelf with nothing loose, or nothing at all, moves nothing")
    void aQuietShelfStaysQuiet(@TempDir Path shelf) throws IOException {
        assertEquals(List.of(), organizer.organize(shelf, true));
        assertEquals(List.of(), organizer.organize(shelf.resolve("does-not-exist"), true));

        Files.createDirectory(shelf.resolve("Heat (1995)"));
        assertEquals(List.of(), organizer.organize(shelf, true));
    }
}
