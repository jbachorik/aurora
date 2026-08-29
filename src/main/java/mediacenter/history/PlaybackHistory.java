package mediacenter.history;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Most-recently-played-first list of launched media, bounded so the file stays
 * small and the home screen stays fast.
 */
public final class PlaybackHistory {

    public static final int DEFAULT_CAPACITY = 60;

    private final int capacity;
    private final List<PlaybackHistoryEntry> entries = new ArrayList<>();

    public PlaybackHistory() {
        this(DEFAULT_CAPACITY);
    }

    public PlaybackHistory(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    public static PlaybackHistory of(List<PlaybackHistoryEntry> entries) {
        PlaybackHistory history = new PlaybackHistory();
        history.replaceAll(entries);
        return history;
    }

    /** Records a playback, moving an already-known path back to the front. */
    public synchronized void record(Path mediaPath, String displayTitle, Instant timestamp) {
        entries.removeIf(entry -> entry.mediaPath().equals(mediaPath));
        entries.addFirst(new PlaybackHistoryEntry(mediaPath, displayTitle, timestamp));
        trim();
    }

    /**
     * Follows a file the library has moved, keeping its place in the list and
     * its timestamp — a reorganised shelf is not a fresh viewing.
     *
     * @return whether an entry actually changed
     */
    public synchronized boolean move(Path from, Path to) {
        for (int i = 0; i < entries.size(); i++) {
            PlaybackHistoryEntry entry = entries.get(i);
            if (entry.mediaPath().equals(from)) {
                entries.set(i, new PlaybackHistoryEntry(to, entry.displayTitle(), entry.lastPlayed()));
                return true;
            }
        }
        return false;
    }

    /** Newest first. */
    public synchronized List<PlaybackHistoryEntry> entries() {
        return List.copyOf(entries);
    }

    public synchronized List<PlaybackHistoryEntry> mostRecent(int limit) {
        return List.copyOf(entries.subList(0, Math.min(limit, entries.size())));
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    public synchronized void replaceAll(List<PlaybackHistoryEntry> replacement) {
        entries.clear();
        entries.addAll(replacement);
        entries.sort(Comparator.comparing(PlaybackHistoryEntry::lastPlayed).reversed());
        trim();
    }

    private void trim() {
        while (entries.size() > capacity) {
            entries.removeLast();
        }
    }
}
