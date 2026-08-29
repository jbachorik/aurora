package mediacenter.media;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SeriesFoldersTest {

    @Test
    @DisplayName("episode-tagged files read as a series, whatever the tag's case")
    void recognisesEpisodeTags() {
        assertTrue(SeriesFolders.looksLikeEpisodes(List.of(
                "Breaking.Bad.S01E01.Pilot.mkv",
                "Breaking.Bad.s01e02.Cat's.in.the.Bag.mkv")));
        assertTrue(SeriesFolders.looksLikeEpisodes(List.of(
                "show 1x01.mkv",
                "show 1x02.mkv")));
    }

    @Test
    @DisplayName("season and episode may be parted by a dot, a dash or an underscore")
    void recognisesSeparatedEpisodeTags() {
        // What a DVD ripper writes, and what this folder is actually called on
        // the machine that reported the run not carrying on by itself.
        assertTrue(SeriesFolders.looksLikeEpisodes(List.of(
                "My Name Is Earl - S01.E01 - Pilot [DVDRip-XviD].avi",
                "My Name Is Earl - S01.E02 - Quitter [DVDRip-XviD].avi")));
        assertTrue(SeriesFolders.carriesAnOrderingMark("Show.S01_E01.Pilot.mkv"));
        assertTrue(SeriesFolders.carriesAnOrderingMark("Show - s01-e01 - Pilot.mkv"));
    }

    @Test
    @DisplayName("a spaced-out dash still parts the season from the episode")
    void recognisesASpacedSeparator() {
        // "S01 - E01": the dash carries a space on both sides, not just one
        // character between the numbers.
        assertTrue(SeriesFolders.carriesAnOrderingMark("Show - S01 - E01 - Pilot.mkv"));
        assertTrue(SeriesFolders.carriesAnOrderingMark("Show_S01_-_E01_Pilot.mkv"));
    }

    @Test
    @DisplayName("Ep is read the same as a bare E")
    void recognisesEpAsTheEpisodeMarker() {
        assertTrue(SeriesFolders.carriesAnOrderingMark("Show.S01EP01.mkv"));
        assertTrue(SeriesFolders.carriesAnOrderingMark("Show.S01.Ep01.mkv"));
    }

    @Test
    @DisplayName("a title immediately followed by a dash and the tag still counts")
    void recognisesATagRightAfterADash() {
        assertTrue(SeriesFolders.carriesAnOrderingMark("Title-S01E01.mkv"));
    }

    @Test
    @DisplayName("a parted tag still has to be a tag, not two unrelated numbers")
    void doesNotInventEpisodeTags() {
        // The separator is a short run of punctuation, and what follows it has
        // to be an episode number — otherwise every "...s1. Episode two..."
        // would count.
        assertFalse(SeriesFolders.carriesAnOrderingMark("Best of the 90s. Extras.mkv"));
        assertFalse(SeriesFolders.carriesAnOrderingMark("Series 1 - Episode 1.mkv"));
        assertFalse(SeriesFolders.carriesAnOrderingMark("movie.1280x720.mkv"));
    }

    @Test
    @DisplayName("an ordering prefix on every file reads as one run")
    void recognisesOrderingPrefixes() {
        assertTrue(SeriesFolders.looksLikeEpisodes(List.of(
                "01 - Curse of the Black Pearl.mkv",
                "02 - Dead Mans Chest.mkv",
                "03 - At Worlds End.mkv")));
    }

    @Test
    @DisplayName("one video is a film, not a series")
    void aSingleVideoIsNotASeries() {
        assertFalse(SeriesFolders.looksLikeEpisodes(List.of("Breaking.Bad.S01E01.mkv")));
    }

    @Test
    @DisplayName("one unmarked file breaks the run — every episode must carry its mark")
    void anUnmarkedFileBreaksTheRun() {
        assertFalse(SeriesFolders.looksLikeEpisodes(List.of(
                "Breaking.Bad.S01E01.mkv",
                "Making.Of.mkv")));
    }

    @Test
    @DisplayName("plain films never chain")
    void plainFilmsDoNotChain() {
        assertFalse(SeriesFolders.looksLikeEpisodes(List.of(
                "Blade.Runner.2049.mkv",
                "Heat.1995.mkv")));
    }

    @Test
    @DisplayName("a leading year is a title, not an ordering prefix")
    void aLeadingYearIsNotOrdering() {
        assertFalse(SeriesFolders.looksLikeEpisodes(List.of(
                "2001 - A Space Odyssey.mkv",
                "2010 - The Year We Make Contact.mkv")));
    }

    @Test
    @DisplayName("a resolution tag is not an episode tag")
    void aResolutionIsNotAnEpisodeTag() {
        assertFalse(SeriesFolders.carriesAnOrderingMark("movie.1280x720.mkv"));
        assertTrue(SeriesFolders.carriesAnOrderingMark("show.1x01.mkv"));
    }

    @Test
    @DisplayName("the x form works with a single-digit episode too")
    void recognisesASingleDigitXTag() {
        assertTrue(SeriesFolders.carriesAnOrderingMark("show.1x1.mkv"));
    }
}
