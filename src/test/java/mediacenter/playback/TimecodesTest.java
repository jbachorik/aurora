package mediacenter.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TimecodesTest {

    @Test
    @DisplayName("short files read as minutes and seconds")
    void readsMinutesAndSeconds() {
        assertEquals("0:00", Timecodes.clock(0));
        assertEquals("7:05", Timecodes.clock(425_000));
    }

    @Test
    @DisplayName("hours appear only once there are any")
    void hoursOnlyWhenPresent() {
        assertEquals("59:59", Timecodes.clock(3_599_000));
        assertEquals("1:00:00", Timecodes.clock(3_600_000));
        assertEquals("2:03:04", Timecodes.clock(7_384_000));
    }

    @Test
    @DisplayName("a clock never runs backwards past zero")
    void clampsNegativeTimes() {
        assertEquals("0:00", Timecodes.clock(-500));
    }

    @Test
    @DisplayName("the position pairs the two readings")
    void rendersThePosition() {
        assertEquals("12:34 / 1:23:45", Timecodes.position(754_000, 5_025_000));
    }
}
