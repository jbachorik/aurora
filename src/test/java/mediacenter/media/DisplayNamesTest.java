package mediacenter.media;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DisplayNamesTest {

    @Test
    @DisplayName("the specification's example loses its extension and its dots")
    void normalizesDottedReleaseName() {
        assertEquals(
                "Blade Runner 2049 2017 1080p BluRay x264",
                DisplayNames.forFileName("Blade.Runner.2049.2017.1080p.BluRay.x264.mkv"));
    }

    @Test
    void replacesUnderscores() {
        assertEquals("The Thing 1982", DisplayNames.forFileName("The_Thing_1982.mkv"));
    }

    @Test
    @DisplayName("dots are kept when the name already reads as words")
    void leavesDeliberateDotsAlone() {
        assertEquals("S.W.A.T. 2017", DisplayNames.forFileName("S.W.A.T. 2017.mkv"));
    }

    @Test
    void collapsesAndTrimsWhitespace() {
        assertEquals("Arrival 2016", DisplayNames.forFileName("  Arrival   2016 .mkv"));
    }

    @Test
    void usesDirectoryNameForDirectories() {
        assertEquals("Blade Runner 2049 (2017)",
                DisplayNames.forDirectory(Path.of("/media/Movies/Blade Runner 2049 (2017)")));
    }

    @Test
    void keepsNamesWithoutSeparatorsUnchanged() {
        assertEquals("Dune", DisplayNames.forFileName("Dune.mkv"));
    }

    @Test
    @DisplayName("an ordering prefix is dropped, since the grid is already in order")
    void dropsAnOrderingPrefix() {
        assertEquals("Dead Mans Chest", DisplayNames.forFileName("02 - Dead Mans Chest.mkv"));
    }

    @Test
    @DisplayName("an ordering prefix is dropped from a dotted name too")
    void dropsAnOrderingPrefixFromADottedName() {
        assertEquals("Dead Mans Chest", DisplayNames.forFileName("01.Dead.Mans.Chest.mkv"));
    }

    @Test
    @DisplayName("a number that is part of the title survives, having no separator after it")
    void keepsALeadingNumberThatBelongsToTheTitle() {
        assertEquals("12 Angry Men", DisplayNames.forFileName("12 Angry Men.mkv"));
    }

    @Test
    @DisplayName("a leading year is never mistaken for an ordering prefix")
    void keepsALeadingYear() {
        assertEquals("2001 - A Space Odyssey",
                DisplayNames.forFileName("2001 - A Space Odyssey.mkv"));
    }

    @Test
    @DisplayName("a name that is nothing but a number is left alone")
    void keepsANameThatIsOnlyANumber() {
        assertEquals("300", DisplayNames.forFileName("300.mkv"));
    }

    @Test
    void dropsAnOrderingPrefixFromADirectory() {
        assertEquals("Season One", DisplayNames.forDirectory(Path.of("/media/TV/01 - Season One")));
    }

    @Test
    @DisplayName("a bracketed release tag is dropped, wherever it sits")
    void dropsBracketedTags() {
        assertEquals("My Name Is Earl - S01.E01 - Pilot",
                DisplayNames.forFileName("My Name Is Earl - S01.E01 - Pilot [DVDRip-XviD].avi"));
        assertEquals("Arrival 2016",
                DisplayNames.forFileName("Arrival 2016 [1080p][x264][EN].mkv"));
        assertEquals("Some Show 01",
                DisplayNames.forFileName("[FakeSubs] Some Show 01 [1080p].mkv"));
        assertEquals("Season One", DisplayNames.forDirectory(Path.of("/media/TV/Season One [PAL]")));
    }

    @Test
    @DisplayName("a trailing episode tag is dropped, since the grid already lays episodes out in order")
    void dropsATrailingEpisodeTag() {
        // A viewer's own rename of an episode file: the nickname is what matters,
        // the tag at the end just repeats what the grid position already shows.
        assertEquals("bad parents", DisplayNames.forFileName("bad parents-S07E03.avi"));
        assertEquals("j springfield pirate",
                DisplayNames.forFileName("j springfield pirate-S07E16.avi"));
        assertEquals("Show", DisplayNames.forFileName("Show.S01E01.avi"));
    }

    @Test
    @DisplayName("an episode tag in the middle of a longer name is left alone")
    void keepsAMidNameEpisodeTag() {
        // Something meaningful — the episode's own title — follows the tag here,
        // so only a *trailing* tag is read as noise worth dropping.
        assertEquals("My Name Is Earl - S01.E01 - Pilot",
                DisplayNames.forFileName("My Name Is Earl - S01.E01 - Pilot.avi"));
    }

    @Test
    @DisplayName("brackets are left where dropping them would leave nothing behind")
    void keepsANameThatIsEntirelyBracketed() {
        // [REC] is a real film, and an empty caption says less than a bracket does.
        assertEquals("[REC]", DisplayNames.forFileName("[REC].mkv"));
    }

    @Test
    void picksTheRuleFromTheExtension() {
        assertEquals("Alien 1979", DisplayNames.forPath(Path.of("/media/Alien.1979.mkv")));
        assertEquals("Alien (1979)", DisplayNames.forPath(Path.of("/media/Alien (1979)")));
    }
}
