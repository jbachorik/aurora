package mediacenter.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarqueeGeometryTest {

    @Test
    @DisplayName("a name that fits has nothing to scroll")
    void aFittingNameDoesNotOverflow() {
        assertEquals(0, MarqueeGeometry.overflow(300, 400));
        assertEquals(0, MarqueeGeometry.overflow(400, 400));
    }

    @Test
    @DisplayName("the overflow is exactly the hidden part of the name")
    void overflowIsTheHiddenTail() {
        assertEquals(160, MarqueeGeometry.overflow(560, 400));
    }

    @Test
    @DisplayName("the glide moves at reading speed, so a longer tail takes longer")
    void glideTimeGrowsWithTheOverflow() {
        assertEquals(
                160 / MarqueeGeometry.PIXELS_PER_SECOND,
                MarqueeGeometry.glideSeconds(160));
        assertEquals(
                2 * MarqueeGeometry.glideSeconds(160),
                MarqueeGeometry.glideSeconds(320));
    }

    @Test
    @DisplayName("a cycle is two holds and two glides: out, wait, and back")
    void cycleCoversTheRoundTrip() {
        double glide = MarqueeGeometry.glideSeconds(160);

        assertEquals(
                2 * (MarqueeGeometry.HOLD_SECONDS + glide),
                MarqueeGeometry.cycleSeconds(160));
    }
}
