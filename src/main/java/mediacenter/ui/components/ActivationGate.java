package mediacenter.ui.components;

/**
 * Decides whether an Enter/Space key press really means "activate this".
 *
 * <p>Two things make this less obvious than it looks:
 *
 * <ul>
 *   <li>Holding the key produces auto-repeat. A viewer resting a thumb on a
 *       remote button, or a cheap remote whose button sticks, must not launch
 *       the same film over and over.
 *   <li>The key release cannot be relied on. Starting playback hides the media
 *       center, so the release goes to the player and never reaches this
 *       application — arming purely on releases would leave Enter dead.
 * </ul>
 *
 * <p>So activation re-arms on either signal: an observed release, or a quiet
 * gap between presses. Auto-repeat never pauses; a person always does.
 *
 * <p>Pure logic with no JavaFX types, so the rules can be tested directly.
 */
public final class ActivationGate {

    /** Comfortably longer than any auto-repeat interval, shorter than a deliberate second press. */
    public static final long DEFAULT_QUIET_GAP_NANOS = 300_000_000L;

    private final long quietGapNanos;

    private boolean armed = true;
    private boolean seenPress;
    private long previousPressNanos;

    public ActivationGate() {
        this(DEFAULT_QUIET_GAP_NANOS);
    }

    public ActivationGate(long quietGapNanos) {
        this.quietGapNanos = quietGapNanos;
    }

    /**
     * Records an activation key press.
     *
     * @param nowNanos a monotonic timestamp, i.e. {@link System#nanoTime()}
     * @return true when this press should activate the selection
     */
    public boolean pressed(long nowNanos) {
        long sincePreviousPress = seenPress ? nowNanos - previousPressNanos : Long.MAX_VALUE;
        seenPress = true;
        previousPressNanos = nowNanos;

        if (!armed && sincePreviousPress < quietGapNanos) {
            return false;
        }
        armed = false;
        return true;
    }

    /** Records an activation key release, which always re-arms. */
    public void released() {
        armed = true;
    }

    /** Re-arms explicitly, for input that cannot repeat (a mouse double click). */
    public void rearm() {
        armed = true;
    }
}
