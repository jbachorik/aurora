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

    private ScrollGeometry() {
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
     * @return {@code 0} when the content fits in the viewport and there is nothing
     *         to scroll
     */
    public static double vvalueFor(double currentVvalue,
                                   double contentHeight,
                                   double viewportHeight,
                                   double itemTop,
                                   double itemBottom,
                                   double margin) {
        double scrollable = contentHeight - viewportHeight;
        if (scrollable <= 0) {
            return 0;
        }
        double visibleTop = currentVvalue * scrollable;
        double target = visibleTop;
        if (itemTop - margin < visibleTop) {
            target = itemTop - margin;
        } else if (itemBottom + margin > visibleTop + viewportHeight) {
            target = itemBottom + margin - viewportHeight;
            // A control taller than the viewport cannot be shown whole. Aligning its
            // bottom would scroll its top away, and the top is where the label
            // saying what the control is lives, so show the top instead.
            target = Math.min(target, itemTop - margin);
        }
        return Math.clamp(target / scrollable, 0, 1);
    }
}
