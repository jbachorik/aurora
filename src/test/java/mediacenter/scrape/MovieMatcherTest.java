package mediacenter.scrape;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MovieMatcherTest {

    private static MovieEvidence evidence(String folderName, String fileName, Integer year) {
        return new MovieEvidence(folderName, fileName, Optional.ofNullable(year), Optional.empty());
    }

    /** Wraps plain candidates the way the scraper does when no runtime was fetched. */
    private static List<MovieMatcher.Candidate> plain(TitleCandidate... candidates) {
        return Arrays.stream(candidates)
                .map(candidate -> new MovieMatcher.Candidate(candidate, Optional.empty()))
                .toList();
    }

    private static TitleCandidate candidate(long id, String name, Integer year) {
        return new TitleCandidate(id, name, List.of(),
                Optional.ofNullable(year), Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    @DisplayName("a clear title with the right year wins the match")
    void picksTheObviousCandidate() {
        MovieEvidence evidence = evidence("Blade Runner 2049 (2017)", "Blade.Runner.2049.2017.mkv", 2017);
        TitleCandidate bladeRunner = candidate(12362, "Blade Runner 2049", 2017);
        TitleCandidate original = candidate(228, "Blade Runner", 1982);

        assertEquals(Optional.of(bladeRunner), MovieMatcher.pick(
                evidence, Optional.empty(), plain(bladeRunner, original)));
    }

    @Test
    @DisplayName("the year is what tells a remake from its original")
    void theYearBreaksTheTie() {
        TitleCandidate newDune = candidate(1, "Dune", 2021);
        TitleCandidate oldDune = candidate(2, "Dune", 1984);

        assertEquals(Optional.of(newDune), MovieMatcher.pick(
                evidence("Dune (2021)", "Dune.2021.2160p.mkv", 2021),
                Optional.empty(),
                plain(newDune, oldDune)));
        assertEquals(Optional.of(oldDune), MovieMatcher.pick(
                evidence("Dune (1984)", "Dune.1984.mkv", 1984),
                Optional.empty(),
                plain(newDune, oldDune)));
    }

    @Test
    @DisplayName("a much-remade title with no year anywhere is left unidentified")
    void noYearMeansNoTieBreaker() {
        TitleCandidate newDune = candidate(1, "Dune", 2021);
        TitleCandidate oldDune = candidate(2, "Dune", 1984);

        assertEquals(Optional.empty(), MovieMatcher.pick(
                evidence("Dune", "Dune.mkv", null),
                Optional.empty(),
                plain(newDune, oldDune)));
    }

    @Test
    @DisplayName("the language model's guess rescues a messy name — including its year")
    void theGuessCarriesAMessyName() {
        MovieEvidence evidence = evidence("BR2049.2160p.HDR.x265-GRP", "br2049.mkv", null);
        TitleCandidate bladeRunner = candidate(12362, "Blade Runner 2049", 2017);
        TitleCandidate original = candidate(228, "Blade Runner", 1982);

        assertEquals(Optional.of(bladeRunner), MovieMatcher.pick(
                evidence,
                Optional.of(new TitleGuess("Blade Runner 2049", Optional.of(2017))),
                plain(bladeRunner, original)));

        // Without the guess, the folder name alone earns nothing.
        assertEquals(Optional.empty(), MovieMatcher.pick(
                evidence, Optional.empty(), plain(bladeRunner, original)));
    }

    @Test
    @DisplayName("a lone candidate still has to look like the folder")
    void anUnrelatedCandidateLoses() {
        assertEquals(Optional.empty(), MovieMatcher.pick(
                evidence("Heat (1995)", "Heat.1995.mkv", 1995),
                Optional.empty(),
                plain(candidate(99, "Heartbeat", 1995))));
    }

    @Test
    @DisplayName("the file's running time can separate what the names cannot")
    void theRuntimeBreaksTheTie() {
        // Two same-named films, neither side knowing a year — but the file
        // runs two and a half hours, and only one of the candidates does.
        MovieEvidence evidence = new MovieEvidence(
                "The Gambler", "The.Gambler.mkv", Optional.empty(),
                Optional.of(Duration.ofMinutes(150)));
        TitleCandidate longFilm = candidate(1, "The Gambler", null);
        TitleCandidate shortFilm = candidate(2, "The Gambler", null);

        assertEquals(Optional.of(longFilm), MovieMatcher.pick(
                evidence,
                Optional.empty(),
                List.of(
                        new MovieMatcher.Candidate(longFilm, Optional.of(150)),
                        new MovieMatcher.Candidate(shortFilm, Optional.of(90)))));

        // With no runtimes fetched the same pair is honestly too close to call.
        assertEquals(Optional.empty(), MovieMatcher.pick(
                evidence, Optional.empty(), plain(longFilm, shortFilm)));
    }

    @Test
    @DisplayName("the runtime witness is lenient: cuts and credits are not another film")
    void runtimeVerdictsAreLenient() {
        // Within ten minutes, or fifteen percent, is the same film.
        assertEquals(1, MovieMatcher.runtimeVerdict(150, 150));
        assertEquals(1, MovieMatcher.runtimeVerdict(142, 150));
        assertEquals(1, MovieMatcher.runtimeVerdict(95, 90));
        // An extended edition lives in the middle ground: no verdict either way.
        assertEquals(0, MovieMatcher.runtimeVerdict(228, 179));
        // Twice the length is simply not the same film.
        assertEquals(-1, MovieMatcher.runtimeVerdict(180, 90));
        assertEquals(-1, MovieMatcher.runtimeVerdict(90, 180));
    }

    @Test
    @DisplayName("a title that is itself a year still matches as a title")
    void aTitleThatIsAYearStillMatches() {
        // The film "2012" came out in 2009. Its folder name never reads as a
        // year hint — a leading year is a title — so the year comes from the
        // guess, and the title comparison keeps the "2012" it is named by.
        TitleCandidate the2012Film = candidate(7, "2012", 2009);
        assertEquals(Optional.of(the2012Film), MovieMatcher.pick(
                evidence("2012", "2012.1080p.mkv", null),
                Optional.of(new TitleGuess("2012", Optional.of(2009))),
                plain(the2012Film)));
    }
}
