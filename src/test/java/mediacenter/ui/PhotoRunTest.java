package mediacenter.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PhotoRunTest {

    private static List<Path> photos(String... names) {
        return java.util.Arrays.stream(names).map(Path::of).toList();
    }

    private static PhotoRun runOf(boolean looping, String... names) {
        PhotoRun run = new PhotoRun(looping);
        run.add(photos(names));
        return run;
    }

    @Test
    @DisplayName("an unfinished run holds on the last picture rather than looping")
    void doesNotLoopWhileStillCollecting() {
        PhotoRun run = runOf(true, "a.jpg", "b.jpg");

        assertEquals(1, run.next(1), "the end of an incomplete run stays where it is");
    }

    @Test
    @DisplayName("a finished run loops back to the beginning")
    void loopsOnceComplete() {
        PhotoRun run = runOf(true, "a.jpg", "b.jpg");
        run.markComplete();

        assertEquals(0, run.next(1));
        assertEquals(1, run.previous(0));
    }

    @Test
    @DisplayName("looking at one folder stops at either end instead of wrapping")
    void aSingleFolderRunStopsAtTheEnds() {
        PhotoRun run = runOf(false, "a.jpg", "b.jpg");
        run.markComplete();

        assertEquals(1, run.next(1));
        assertEquals(0, run.previous(0));
    }

    @Test
    @DisplayName("a truncated walk loops over what it has, without claiming that is all")
    void aTruncatedWalkLoopsButDoesNotClaimATotal() {
        PhotoRun run = runOf(true, "a.jpg", "b.jpg");
        run.markTruncated();
        run.markComplete();

        // Freezing on the last of five thousand would be worse than looping them.
        assertEquals(0, run.next(1));
        assertEquals("2 of 2+", run.counterText(1));
    }

    @Test
    @DisplayName("the counter admits when it does not yet know the total")
    void countsHonestly() {
        PhotoRun run = runOf(true, "a.jpg", "b.jpg", "c.jpg");

        assertEquals("2 of 3+", run.counterText(1));

        run.markComplete();
        assertEquals("2 of 3", run.counterText(1));
    }

    @Test
    void movesForwardAndBackInTheMiddle() {
        PhotoRun run = runOf(true, "a.jpg", "b.jpg", "c.jpg");

        assertEquals(2, run.next(1));
        assertEquals(0, run.previous(1));
    }

    @Test
    @DisplayName("neighbours wrap when the run does, so a wrap is not a cold decode")
    void reportsNeighboursForPrefetching() {
        PhotoRun looping = runOf(true, "a.jpg", "b.jpg", "c.jpg");
        looping.markComplete();
        assertEquals(List.of(2, 1), looping.neighbours(0));

        PhotoRun stopping = runOf(false, "a.jpg", "b.jpg", "c.jpg");
        stopping.markComplete();
        assertEquals(List.of(0, 1), stopping.neighbours(0).stream().distinct().sorted().toList());
    }

    @Test
    @DisplayName("an empty run answers without throwing")
    void survivesAnEmptyRun() {
        PhotoRun run = new PhotoRun(true);
        run.markComplete();

        assertTrue(run.isEmpty());
        assertEquals(0, run.next(0));
        assertEquals(0, run.previous(0));
        assertEquals("", run.counterText(0));
        assertEquals(List.of(), run.neighbours(0));
    }

    @Test
    void findsAPhotographOnceItHasBeenCollected() {
        PhotoRun run = runOf(true, "a.jpg", "b.jpg");

        assertEquals(1, run.indexOf(Path.of("b.jpg")));
        assertEquals(-1, run.indexOf(Path.of("nowhere.jpg")));
    }
}
