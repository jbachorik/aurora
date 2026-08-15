package mediacenter.history;

import java.nio.file.Path;
import java.time.Instant;

/**
 * One "recently played" record.
 *
 * <p>Only the fact that an item was launched is stored — exact resume positions
 * are VLC's business.
 */
public record PlaybackHistoryEntry(Path mediaPath, String displayTitle, Instant lastPlayed) {

    public PlaybackHistoryEntry {
        if (mediaPath == null) {
            throw new IllegalArgumentException("Media path must not be null");
        }
        if (displayTitle == null || displayTitle.isBlank()) {
            throw new IllegalArgumentException("Display title must not be blank");
        }
        if (lastPlayed == null) {
            throw new IllegalArgumentException("Timestamp must not be null");
        }
    }
}
