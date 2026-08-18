package mediacenter.ui.components;

/**
 * The timings of a slowly scrolling line of text, worked out from lengths alone.
 *
 * <p>Separated from {@link MarqueeLabel} for the same reason as
 * {@link ScrollGeometry}: text only has a width once the toolkit has laid it
 * out, so arithmetic left inline there is arithmetic no test ever sees.
 *
 * <p>The scroll exists to be read, which fixes its shape: a pause long enough
 * to start reading, a glide at reading speed to bring the hidden tail into
 * view, a pause on the tail, and the same glide back. Constant speed matters —
 * an eased scroll rushes precisely the characters in the middle of the name.
 */
public final class MarqueeGeometry {

    /** Reading speed. Slow enough to follow from a sofa, brisk enough to arrive. */
    public static final double PIXELS_PER_SECOND = 32;

    /** How long each end of the line holds still before the scroll turns. */
    public static final double HOLD_SECONDS = 1.4;

    private MarqueeGeometry() {
    }

    /**
     * How many pixels of the text are hidden past the right edge.
     *
     * @return zero when the text fits, i.e. nothing needs to scroll
     */
    public static double overflow(double textWidth, double availableWidth) {
        return Math.max(0, textWidth - availableWidth);
    }

    /** Seconds one glide takes — the same going out and coming back. */
    public static double glideSeconds(double overflow) {
        return overflow / PIXELS_PER_SECOND;
    }

    /** Seconds a full cycle takes: hold, glide out, hold, glide home. */
    public static double cycleSeconds(double overflow) {
        return 2 * (HOLD_SECONDS + glideSeconds(overflow));
    }
}
