package mediacenter.media;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ParentPrefixesTest {

    private static String stripped(String name, String... parents) {
        return ParentPrefixes.withoutParentPrefix(name, List.of(parents));
    }

    @Test
    @DisplayName("a name that does not repeat its folder is left alone")
    void leavesAnUnrelatedNameAlone() {
        assertEquals("Heat.1995.mkv", stripped("Heat.1995.mkv", "Thrillers"));
    }

    @Test
    @DisplayName("the folder's echo goes, and the separators it leaves go with it")
    void dropsTheEchoAndTrimsWhatItLeaves() {
        assertEquals("S01E01 - Pilot.mkv",
                stripped("Breaking Bad - S01E01 - Pilot.mkv", "Breaking Bad"));
    }

    @Test
    @DisplayName("dots against spaces and a bracketed year still count as the same words")
    void matchesAcrossSeparatorsAndBrackets() {
        assertEquals("1080p.mkv",
                stripped("The.Shawshank.Redemption.1994.1080p.mkv",
                        "The Shawshank Redemption (1994)"));
    }

    @Test
    @DisplayName("a grandparent's echo counts as much as the parent's")
    void anyAncestorMayMatch() {
        assertEquals("S01E01.mkv",
                stripped("Breaking.Bad.S01E01.mkv", "Season 1", "Breaking Bad", "TV"));
    }

    @Test
    @DisplayName("when several ancestors match, the longest echo is the one dropped")
    void theLongestMatchWins() {
        assertEquals("Part2.mkv",
                stripped("Show.Special.Part2.mkv", "Show", "Show Special"));
    }

    @Test
    @DisplayName("matching reads words, not letters: The Matrix never bites The Matrixx")
    void matchesWholeWordsOnly() {
        assertEquals("The Matrixx.mkv", stripped("The Matrixx.mkv", "The Matrix"));
    }

    @Test
    @DisplayName("case does not keep an echo alive")
    void matchesRegardlessOfCase() {
        assertEquals("S01E01.mkv", stripped("breaking bad S01E01.mkv", "Breaking Bad"));
    }

    @Test
    @DisplayName("a file that is nothing but the folder's name stays whole")
    void neverReducesANameToItsExtension() {
        assertEquals("The.Shawshank.Redemption.1994.mkv",
                stripped("The.Shawshank.Redemption.1994.mkv",
                        "The Shawshank Redemption (1994)"));
    }

    @Test
    @DisplayName("a folder named exactly like its parent stays whole")
    void neverReducesAFolderToNothing() {
        assertEquals("Season 1", stripped("Season 1", "Season 1"));
    }

    @Test
    @DisplayName("a subfolder drops its parent's echo like any file")
    void appliesToFolderNamesToo() {
        assertEquals("Season 1", stripped("Breaking Bad Season 1", "Breaking Bad"));
    }

    @Test
    @DisplayName("no parents means nothing to drop")
    void noParentsMeansNoChange() {
        assertEquals("Anything.mkv", stripped("Anything.mkv"));
    }
}
