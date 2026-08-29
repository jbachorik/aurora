package mediacenter.scrape;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MovieMatcherTest {

    private static MovieEvidence evidence(String folderName, String fileName, Integer year) {
        return new MovieEvidence(folderName, fileName, Optional.ofNullable(year));
    }

    private static TvdbCandidate candidate(long id, String name, Integer year) {
        return new TvdbCandidate(id, name, List.of(),
                Optional.ofNullable(year), Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    @DisplayName("a clear title with the right year wins the match")
    void picksTheObviousCandidate() {
        MovieEvidence evidence = evidence("Blade Runner 2049 (2017)", "Blade.Runner.2049.2017.mkv", 2017);
        TvdbCandidate bladeRunner = candidate(12362, "Blade Runner 2049", 2017);
        TvdbCandidate original = candidate(228, "Blade Runner", 1982);

        assertEquals(Optional.of(bladeRunner), MovieMatcher.pick(
                evidence, Optional.empty(), List.of(bladeRunner, original)));
    }

    @Test
    @DisplayName("the year is what tells a remake from its original")
    void theYearBreaksTheTie() {
        TvdbCandidate newDune = candidate(1, "Dune", 2021);
        TvdbCandidate oldDune = candidate(2, "Dune", 1984);

        assertEquals(Optional.of(newDune), MovieMatcher.pick(
                evidence("Dune (2021)", "Dune.2021.2160p.mkv", 2021),
                Optional.empty(),
                List.of(newDune, oldDune)));
        assertEquals(Optional.of(oldDune), MovieMatcher.pick(
                evidence("Dune (1984)", "Dune.1984.mkv", 1984),
                Optional.empty(),
                List.of(newDune, oldDune)));
    }

    @Test
    @DisplayName("a much-remade title with no year anywhere is left unidentified")
    void noYearMeansNoTieBreaker() {
        TvdbCandidate newDune = candidate(1, "Dune", 2021);
        TvdbCandidate oldDune = candidate(2, "Dune", 1984);

        assertEquals(Optional.empty(), MovieMatcher.pick(
                evidence("Dune", "Dune.mkv", null),
                Optional.empty(),
                List.of(newDune, oldDune)));
    }

    @Test
    @DisplayName("the language model's guess rescues a messy name — including its year")
    void theGuessCarriesAMessyName() {
        MovieEvidence evidence = evidence("BR2049.2160p.HDR.x265-GRP", "br2049.mkv", null);
        TvdbCandidate bladeRunner = candidate(12362, "Blade Runner 2049", 2017);
        TvdbCandidate original = candidate(228, "Blade Runner", 1982);

        assertEquals(Optional.of(bladeRunner), MovieMatcher.pick(
                evidence,
                Optional.of(new TitleGuess("Blade Runner 2049", Optional.of(2017))),
                List.of(bladeRunner, original)));

        // Without the guess, the folder name alone earns nothing.
        assertEquals(Optional.empty(), MovieMatcher.pick(
                evidence, Optional.empty(), List.of(bladeRunner, original)));
    }

    @Test
    @DisplayName("a lone candidate still has to look like the folder")
    void anUnrelatedCandidateLoses() {
        assertEquals(Optional.empty(), MovieMatcher.pick(
                evidence("Heat (1995)", "Heat.1995.mkv", 1995),
                Optional.empty(),
                List.of(candidate(99, "Heartbeat", 1995))));
    }

    @Test
    @DisplayName("a title that is itself a year still matches as a title")
    void aTitleThatIsAYearStillMatches() {
        // The film "2012" came out in 2009. Its folder name never reads as a
        // year hint — a leading year is a title — so the year comes from the
        // guess, and the title comparison keeps the "2012" it is named by.
        TvdbCandidate the2012Film = candidate(7, "2012", 2009);
        assertEquals(Optional.of(the2012Film), MovieMatcher.pick(
                evidence("2012", "2012.1080p.mkv", null),
                Optional.of(new TitleGuess("2012", Optional.of(2009))),
                List.of(the2012Film)));
    }
}
