package mediacenter.scrape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MovieEvidenceCollectorTest {

    private final MovieEvidenceCollector collector =
            new MovieEvidenceCollector(MediaDurationProbe.none());

    @Test
    @DisplayName("one film in its own folder is exactly what a movie folder is")
    void readsAMovieFolder(@TempDir Path temp) throws IOException {
        Path movie = Files.createDirectory(temp.resolve("Blade Runner 2049 (2017)"));
        Files.createFile(movie.resolve("Blade.Runner.2049.2017.mkv"));
        Files.createFile(movie.resolve("poster.jpg"));
        Files.createDirectory(movie.resolve("Subs"));

        List<MovieEvidence> found = collector.collect(movie);

        assertEquals(1, found.size());
        MovieEvidence evidence = found.getFirst();
        assertEquals("Blade Runner 2049 (2017)", evidence.folderName());
        assertEquals("Blade.Runner.2049.2017.mkv", evidence.videoFileName());
        assertEquals(Optional.of(2017), evidence.yearHint());
        assertEquals(Optional.empty(), evidence.duration());
    }

    @Test
    @DisplayName("a probe that can answer gives the evidence its running time")
    void asksTheProbeForTheFilmItFound(@TempDir Path temp) throws IOException {
        Path movie = Files.createDirectory(temp.resolve("Heat (1995)"));
        Files.createFile(movie.resolve("Heat.1995.mkv"));
        Files.createFile(movie.resolve("sample.mkv"));
        List<Path> asked = new ArrayList<>();
        MovieEvidenceCollector probing = new MovieEvidenceCollector(video -> {
            asked.add(video);
            return Optional.of(Duration.ofMinutes(170));
        });

        MovieEvidence evidence = probing.collect(movie).getFirst();

        assertEquals(Optional.of(Duration.ofMinutes(170)), evidence.duration());
        // The film itself, once — never the sample beside it.
        assertEquals(List.of(movie.resolve("Heat.1995.mkv")), asked);
    }

    @Test
    @DisplayName("a ripper's sample file does not make the folder two films")
    void ignoresSampleFiles(@TempDir Path temp) throws IOException {
        Path movie = Files.createDirectory(temp.resolve("Heat (1995)"));
        Files.createFile(movie.resolve("Heat.1995.mkv"));
        Files.createFile(movie.resolve("sample.mkv"));
        Files.createFile(movie.resolve("heat-sample.avi"));

        List<MovieEvidence> found = collector.collect(movie);

        assertEquals(1, found.size());
        assertEquals("Heat.1995.mkv", found.getFirst().videoFileName());
    }

    @Test
    @DisplayName("a trilogy sharing one folder is one film per video, not a refusal")
    void readsEachFilmOfATrilogySharingAFolder(@TempDir Path temp) throws IOException {
        Path trilogy = Files.createDirectory(temp.resolve("The Lord of the Rings"));
        Files.createFile(trilogy.resolve("01 - The Fellowship of the Ring.mkv"));
        Files.createFile(trilogy.resolve("02 - The Two Towers.mkv"));
        Files.createFile(trilogy.resolve("03 - The Return of the King.mkv"));

        List<MovieEvidence> found = collector.collect(trilogy);

        assertEquals(3, found.size());
        assertEquals(
                List.of("01 - The Fellowship of the Ring.mkv", "02 - The Two Towers.mkv",
                        "03 - The Return of the King.mkv"),
                found.stream().map(MovieEvidence::videoFileName).toList());
        // Every entry shares the folder they were found in.
        assertTrue(found.stream().allMatch(evidence -> evidence.folderName().equals("The Lord of the Rings")));
    }

    @Test
    @DisplayName("what is not one film is not a movie folder")
    void refusesWhatIsNotOneFilm(@TempDir Path temp) throws IOException {
        // A grouping folder with no video of its own.
        Path grouping = Files.createDirectory(temp.resolve("Kids"));
        Files.createDirectory(grouping.resolve("Frozen (2013)"));
        assertEquals(List.of(), collector.collect(grouping));

        // A season misfiled on a Movies shelf, either shape.
        Path tagged = Files.createDirectory(temp.resolve("Misfiled"));
        Files.createFile(tagged.resolve("Show.S01E01.mkv"));
        assertEquals(List.of(), collector.collect(tagged));

        Path seasons = Files.createDirectory(temp.resolve("Also Misfiled"));
        Files.createFile(seasons.resolve("Also.Misfiled.mkv"));
        Files.createDirectory(seasons.resolve("Season 2"));
        assertEquals(List.of(), collector.collect(seasons));
    }

    @Test
    @DisplayName("an unreadable folder yields nothing rather than an error")
    void refusesTheUnreadable(@TempDir Path temp) {
        assertEquals(List.of(), collector.collect(temp.resolve("does-not-exist")));
    }

    @Test
    @DisplayName("a labelled year always counts; a bare one only away from the front")
    void readsYearHints() {
        assertEquals(Optional.of(2017), MovieEvidenceCollector.yearHintOf("Blade Runner 2049 (2017)"));
        assertEquals(Optional.of(2019), MovieEvidenceCollector.yearHintOf("Parasite [2019] 1080p"));
        // The dotted convention: the year is the last plausible bare token…
        assertEquals(Optional.of(2017), MovieEvidenceCollector.yearHintOf("Blade.Runner.2049.2017.mkv"));
        // …and 2049 alone is a title's number, not a release year.
        assertEquals(Optional.empty(), MovieEvidenceCollector.yearHintOf("Blade Runner 2049"));
        // A leading year is a title: "2001 - A Space Odyssey", or all of "2012".
        assertEquals(Optional.empty(), MovieEvidenceCollector.yearHintOf("2001 - A Space Odyssey"));
        assertEquals(Optional.empty(), MovieEvidenceCollector.yearHintOf("2012"));
        assertEquals(Optional.empty(), MovieEvidenceCollector.yearHintOf("Heat"));
        // Glued to letters it is a resolution or a codec tag, not a year.
        assertTrue(MovieEvidenceCollector.yearHintOf("movie.x2019.mkv").isEmpty());
    }
}
