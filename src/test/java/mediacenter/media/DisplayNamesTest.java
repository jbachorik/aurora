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
    void picksTheRuleFromTheExtension() {
        assertEquals("Alien 1979", DisplayNames.forPath(Path.of("/media/Alien.1979.mkv")));
        assertEquals("Alien (1979)", DisplayNames.forPath(Path.of("/media/Alien (1979)")));
    }
}
