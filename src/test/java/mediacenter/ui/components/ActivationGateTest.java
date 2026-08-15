package mediacenter.ui.components;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules that stop a held Enter from relaunching a film forever without ever
 * leaving Enter unusable.
 */
class ActivationGateTest {

    private static final long QUIET_GAP = ActivationGate.DEFAULT_QUIET_GAP_NANOS;
    private static final long REPEAT_INTERVAL = TimeUnit.MILLISECONDS.toNanos(33);

    private final ActivationGate gate = new ActivationGate();

    @Test
    void theFirstPressActivates() {
        assertTrue(gate.pressed(now(0)));
    }

    @Test
    @DisplayName("press, release, press activates twice — the normal case")
    void aReleasedKeyRearmsImmediately() {
        assertTrue(gate.pressed(now(0)));
        gate.released();

        assertTrue(gate.pressed(now(40)), "a fresh press after a release must activate at once");
    }

    @Test
    @DisplayName("auto-repeat from a held key activates exactly once")
    void heldKeyActivatesOnce() {
        long timestamp = 0;
        assertTrue(gate.pressed(now(timestamp)));

        int extraActivations = 0;
        // Two seconds of a stuck button, with no release ever observed.
        for (int repeat = 0; repeat < 60; repeat++) {
            timestamp += 33;
            if (gate.pressed(now(timestamp))) {
                extraActivations++;
            }
        }

        assertFalse(extraActivations > 0, "a held key must not activate again, got " + extraActivations);
    }

    @Test
    @DisplayName("a lost release cannot disable Enter: a quiet gap re-arms too")
    void aQuietGapRearmsWhenTheReleaseWasNeverSeen() {
        // Starting playback hides the window, so this release goes to the player.
        assertTrue(gate.pressed(0));

        assertTrue(gate.pressed(QUIET_GAP + 1),
                "a deliberate press after a pause must work even with no release seen");
    }

    @Test
    void pressesInsideTheQuietGapAreIgnoredUntilTheKeyIsReleased() {
        assertTrue(gate.pressed(0));

        assertFalse(gate.pressed(QUIET_GAP - 1));

        gate.released();
        assertTrue(gate.pressed(QUIET_GAP));
    }

    @Test
    @DisplayName("the repeat stream keeps the gate shut for as long as the key is down")
    void aContinuousStreamNeverReopensTheGate() {
        long timestamp = 0;
        assertTrue(gate.pressed(timestamp));

        for (int repeat = 0; repeat < 500; repeat++) {
            timestamp += REPEAT_INTERVAL;
            assertFalse(gate.pressed(timestamp), "repeat " + repeat + " must not activate");
        }

        // …and the moment the button is finally let go, Enter works again.
        gate.released();
        assertTrue(gate.pressed(timestamp + REPEAT_INTERVAL));
    }

    @Test
    void anExplicitRearmAlwaysActivates() {
        assertTrue(gate.pressed(0));
        gate.rearm();

        assertTrue(gate.pressed(1), "a double click cannot repeat and must always activate");
    }

    private static long now(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }
}
