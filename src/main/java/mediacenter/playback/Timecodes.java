package mediacenter.playback;

/**
 * Rendering playback times the way a player's clock reads: {@code 7:05},
 * {@code 1:23:45}. Pure arithmetic, so the on-screen display can be tested
 * without a player.
 */
public final class Timecodes {

    private Timecodes() {
    }

    /** {@code 12:34 / 1:23:45} — where in the file the playback stands. */
    public static String position(long timeMillis, long lengthMillis) {
        return clock(timeMillis) + " / " + clock(lengthMillis);
    }

    /** One clock reading; hours appear only once there are any. */
    public static String clock(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000;
        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = totalSeconds / 3600;
        if (hours > 0) {
            return "%d:%02d:%02d".formatted(hours, minutes, seconds);
        }
        return "%d:%02d".formatted(minutes, seconds);
    }
}
