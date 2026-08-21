package mediacenter.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScrollGeometryTest {

    private static final double MARGIN = 24;
    private static final double VIEWPORT = 500;
    private static final double CONTENT = 1500;

    /** The scrollable travel for the fixtures above: 1000px. */
    private static final double SCROLLABLE = CONTENT - VIEWPORT;

    private static double vvalue(double current, double top, double bottom) {
        return ScrollGeometry.vvalueFor(current, CONTENT, VIEWPORT, top, bottom, MARGIN);
    }

    @Test
    @DisplayName("content that already fits needs no scrolling at all")
    void parksAtTheTopWhenEverythingFits() {
        assertEquals(0, ScrollGeometry.vvalueFor(0.5, 300, 500, 100, 200, MARGIN));
    }

    @Test
    @DisplayName("a control already in view does not move the viewport")
    void leavesTheViewportAloneWhenTheControlIsVisible() {
        // Showing 0..500; the control sits at 100..200, clear of both margins.
        assertEquals(0, vvalue(0, 100, 200));
    }

    @Test
    @DisplayName("a control below the fold is brought up with its margin")
    void scrollsDownToReachAControlBelowTheFold() {
        // Showing 0..500; the control ends at 700, so the viewport must end at 724.
        assertEquals((724 - VIEWPORT) / SCROLLABLE, vvalue(0, 600, 700));
    }

    @Test
    @DisplayName("a control above the fold is brought down with its margin")
    void scrollsUpToReachAControlAboveTheFold() {
        // Showing 500..1000; the control starts at 400, so the viewport starts at 376.
        assertEquals(376 / SCROLLABLE, vvalue(0.5, 400, 450));
    }

    @Test
    @DisplayName("the last control cannot scroll past the end of the content")
    void clampsToTheEndOfTheContent() {
        assertEquals(1, vvalue(0, 1400, 1500));
    }

    @Test
    @DisplayName("the first control cannot scroll above the start of the content")
    void clampsToTheStartOfTheContent() {
        assertEquals(0, vvalue(1, 0, 40));
    }

    @Test
    @DisplayName("a control taller than the viewport shows its top rather than its bottom")
    void prefersTheTopOfAnOversizedControl() {
        // 400..1200 cannot fit in 500px. Aligning the bottom would hide the top,
        // and the top is where the label that says what the control is lives.
        assertEquals(376 / SCROLLABLE, vvalue(0, 400, 1200));
    }

    @Test
    @DisplayName("a tile taller than the viewport shows its bottom, where its caption is")
    void prefersTheBottomOfAnOversizedTile() {
        // The same 400..1200 that cannot fit, asked for the other way round: a
        // tile wears its name along the bottom, so the bottom is what to show.
        assertEquals((1224 - VIEWPORT) / SCROLLABLE, ScrollGeometry.vvalueFor(
                0, CONTENT, VIEWPORT, 400, 1200, MARGIN, ScrollGeometry.TooTall.SHOW_BOTTOM));
    }

    @Test
    @DisplayName("a tile taller than the whole row still scrolls to its caption")
    void reachesTheCaptionOfARowThatCannotFit() {
        // The home screen at a low resolution: a 320px row of tiles inside a
        // 268px viewport. The old rule parked this at 0 and the titles were
        // never reachable at all, however far you scrolled.
        double content = 320;
        double viewport = 268;
        double scrollable = content - viewport;

        double parked = ScrollGeometry.vvalueFor(
                0, content, viewport, 15, 299, MARGIN, ScrollGeometry.TooTall.SHOW_BOTTOM);

        assertEquals(1, parked, "the caption end of the row has to be reachable");
        assertEquals(scrollable, parked * scrollable);
    }
}
