package mediacenter.ui.components;

/**
 * Where a scroll pane has to be parked to show something, worked out from
 * lengths alone.
 *
 * <p>Separated from the panes that use it for the same reason as
 * {@link GridGeometry}: it can then be reasoned about — and tested — without a
 * rendered scene. A viewport only has a size once the toolkit has laid it out,
 * so arithmetic left inline here is arithmetic no test ever sees.
 *
 * <p>This exists because a pane that owns the arrow keys owes the viewport a
 * scroll. Both {@link TileGrid} and the settings page filter arrow presses to
 * move focus themselves, which stops the skin from ever scrolling on their
 * behalf; without this, focus lands on a control below the fold and the user
 * sees nothing happen.
 */
public final class ScrollGeometry {

    /**
     * Which end of an item too tall to fit is the end worth showing.
     *
     * <p>Something taller than the viewport cannot be shown whole, so one end
     * has to be chosen, and the right end is wherever the words are. A settings
     * row is labelled along its top; a tile wears its caption along its bottom,
     * and parking such a tile at its top shows a coloured rectangle and hides
     * the only thing that says what it is.
     */
    public enum TooTall {

        /** The top, where a settings row carries the label naming the control. */
        SHOW_TOP,

        /** The bottom, where a tile carries the caption naming what it is. */
        SHOW_BOTTOM
    }

    private ScrollGeometry() {
    }

    /**
     * The {@code vvalue} that brings {@code itemTop..itemBottom} into view,
     * showing the top of anything too tall to fit.
     *
     * @see #vvalueFor(double, double, double, double, double, double, TooTall)
     */
    public static double vvalueFor(double currentVvalue,
                                   double contentHeight,
                                   double viewportHeight,
                                   double itemTop,
                                   double itemBottom,
                                   double margin) {
        return vvalueFor(currentVvalue, contentHeight, viewportHeight,
                itemTop, itemBottom, margin, TooTall.SHOW_TOP);
    }

    /**
     * The {@code vvalue} that brings {@code itemTop..itemBottom} into view, leaving
     * {@code margin} pixels of room where it can.
     *
     * <p>All measurements are in content pixels, counted from the top of the
     * content; the answer is the 0..1 fraction a {@code ScrollPane} wants.
     *
     * @param currentVvalue where the pane is parked now, so that a control which
     *                      is already visible does not cause a jump
     * @param tooTall       which end to show when the item cannot fit at all
     * @return {@code 0} when the content fits in the viewport and there is nothing
     *         to scroll
     */
    public static double vvalueFor(double currentVvalue,
                                   double contentHeight,
                                   double viewportHeight,
                                   double itemTop,
                                   double itemBottom,
                                   double margin,
                                   TooTall tooTall) {
        double scrollable = contentHeight - viewportHeight;
        if (scrollable <= 0) {
            return 0;
        }
        double visibleTop = currentVvalue * scrollable;
        double above = itemTop - margin;
        double below = itemBottom + margin - viewportHeight;
        double target = visibleTop;
        if (below > above) {
            // Taller than the viewport, margins and all: both ends cannot be
            // had, so the caller's answer decides, and it decides the same way
            // wherever the pane happens to be parked.
            target = tooTall == TooTall.SHOW_TOP ? above : below;
        } else if (above < visibleTop) {
            target = above;
        } else if (below > visibleTop) {
            target = below;
        }
        return Math.clamp(target / scrollable, 0, 1);
    }
}
