package mediacenter.scrape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import mediacenter.scrape.SeriesMatcher.Candidate;

class SeriesMatcherTest {

    private static SeriesEvidence evidence(String folderName, Map<Integer, Integer> episodesPerSeason) {
        return new SeriesEvidence(folderName, new TreeMap<>(episodesPerSeason), List.of());
    }

    private static TitleCandidate candidate(long id, String name, Integer year) {
        return new TitleCandidate(id, name, List.of(),
                Optional.ofNullable(year), Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    @DisplayName("normalisation strips noise down to the claim being made")
    void normalises() {
        assertEquals("office", SeriesMatcher.normalize("The Office"));
        assertEquals("office", SeriesMatcher.normalize("office"));
        assertEquals("chernobyl", SeriesMatcher.normalize("Chernobyl (2019)"));
        assertEquals("s w a t", SeriesMatcher.normalize("S.W.A.T. 2017"));
    }

    @Test
    @DisplayName("similarity reads the same title through different spellings")
    void measuresSimilarity() {
        assertEquals(1.0, SeriesMatcher.similarity("The Office", "office"));
        assertTrue(SeriesMatcher.similarity("Game of Thrones", "Game.of.Thrones") > 0.9);
        assertTrue(SeriesMatcher.similarity("Game of Thrones", "House of Cards") < 0.5);
    }

    @Test
    @DisplayName("seasons on disk must fit inside the candidate's aired seasons")
    void scoresStructure() {
        SeriesEvidence evidence = evidence("Show", Map.of(1, 7, 2, 13));

        // Both seasons exist and hold at least what is on disk.
        assertEquals(1.0, SeriesMatcher.structureScore(evidence, Map.of(1, 7, 2, 13)));
        // A half-ripped season still fits.
        assertEquals(1.0, SeriesMatcher.structureScore(evidence, Map.of(1, 10, 2, 13)));
        // A season the candidate never aired cannot be this series' season.
        assertEquals(0.5, SeriesMatcher.structureScore(evidence, Map.of(1, 7)));
        // More episodes on disk than ever aired is the same kind of strike.
        assertEquals(0.5, SeriesMatcher.structureScore(evidence, Map.of(1, 7, 2, 6)));
    }

    @Test
    @DisplayName("a clear title with the right shape wins the match")
    void picksTheObviousCandidate() {
        SeriesEvidence evidence = evidence("Breaking.Bad.S01-S05.1080p", Map.of(1, 7, 2, 13));
        TitleCandidate breakingBad = candidate(81189, "Breaking Bad", 2008);
        TitleCandidate unrelated = candidate(1, "Baking Bread", 2015);

        Optional<TitleCandidate> picked = SeriesMatcher.pick(
                evidence,
                Optional.of(new TitleGuess("Breaking Bad", Optional.of(2008))),
                List.of(
                        new Candidate(breakingBad, Optional.of(Map.of(1, 7, 2, 13, 3, 13))),
                        new Candidate(unrelated, Optional.of(Map.of(1, 10)))));

        assertEquals(Optional.of(breakingBad), picked);
    }

    @Test
    @DisplayName("the language model's guess rescues a folder name rules cannot read")
    void theGuessCarriesAMessyFolderName() {
        SeriesEvidence evidence = evidence("BrBa.COMPLETE.720p.x264-GRP", Map.of(1, 7));
        TitleCandidate breakingBad = candidate(81189, "Breaking Bad", 2008);

        assertEquals(Optional.of(breakingBad), SeriesMatcher.pick(
                evidence,
                Optional.of(new TitleGuess("Breaking Bad", Optional.empty())),
                List.of(new Candidate(breakingBad, Optional.of(Map.of(1, 7, 2, 13))))));

        // Without the guess, the folder name alone earns nothing.
        assertEquals(Optional.empty(), SeriesMatcher.pick(
                evidence,
                Optional.empty(),
                List.of(new Candidate(breakingBad, Optional.of(Map.of(1, 7, 2, 13))))));
    }

    @Test
    @DisplayName("the season shape tells a remake from its original")
    void theShapeBreaksTheTie() {
        // Two candidates with the very same name; only their seasons differ.
        SeriesEvidence evidence = evidence("The Office", Map.of(5, 28));
        TitleCandidate american = candidate(73244, "The Office", 2005);
        TitleCandidate british = candidate(78107, "The Office", 2001);

        Optional<TitleCandidate> picked = SeriesMatcher.pick(
                evidence,
                Optional.empty(),
                List.of(
                        new Candidate(american, Optional.of(Map.of(1, 6, 5, 28))),
                        new Candidate(british, Optional.of(Map.of(1, 6, 2, 6)))));

        assertEquals(Optional.of(american), picked);
    }

    @Test
    @DisplayName("two candidates too close to call is no match at all")
    void ambiguityLosesTheMatch() {
        SeriesEvidence evidence = evidence("The Office", Map.of(1, 6));
        TitleCandidate american = candidate(73244, "The Office", 2005);
        TitleCandidate british = candidate(78107, "The Office", 2001);

        // Season one of six episodes fits both; nothing tells them apart.
        assertEquals(Optional.empty(), SeriesMatcher.pick(
                evidence,
                Optional.empty(),
                List.of(
                        new Candidate(american, Optional.of(Map.of(1, 6))),
                        new Candidate(british, Optional.of(Map.of(1, 6))))));
    }

    @Test
    @DisplayName("an alias counts as much as the name itself")
    void aliasesCount() {
        SeriesEvidence evidence = evidence("La Casa de Papel", Map.of(1, 13));
        TitleCandidate moneyHeist = new TitleCandidate(
                327417, "Money Heist", List.of("La Casa de Papel"),
                Optional.of(2017), Optional.empty(), Optional.empty(), Optional.empty());

        assertEquals(Optional.of(moneyHeist), SeriesMatcher.pick(
                evidence,
                Optional.empty(),
                List.of(new Candidate(moneyHeist, Optional.of(Map.of(1, 13, 2, 9))))));
    }

    @Test
    @DisplayName("no episode data is no opinion — the title stands alone")
    void missingEpisodeDataStaysNeutral() {
        SeriesEvidence evidence = evidence("Chernobyl", Map.of(1, 5));
        TitleCandidate chernobyl = candidate(360893, "Chernobyl", 2019);

        assertEquals(Optional.of(chernobyl), SeriesMatcher.pick(
                evidence,
                Optional.empty(),
                List.of(new Candidate(chernobyl, Optional.empty()))));
    }
}
