package mediacenter.playback;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Where the built-in player should read each queued title from, live for the
 * whole run — and the rescue line for a title that is stuttering off a slow
 * share while a local copy downloads behind it.
 *
 * <p>The player keeps its entries on the paths the viewer chose; this decides,
 * at the moment each one starts (and again when the viewer resumes from a
 * pause), which actual file to open. The default implementation is a run with
 * no mirror at all: originals, nothing else.
 */
public interface PlayablePaths {

    /**
     * The file to open for this entry right now — a finished local copy when
     * one exists, the entry's own path otherwise. Safe on the UI thread: the
     * answer comes from memory, never from a stat against a share.
     */
    Path playablePath(Path entry);

    /**
     * The entry began playing from its original path — no local copy existed.
     * An implementation may measure the share and start a rescue copy behind
     * the playback. Call off the UI thread; it may touch the network.
     */
    default void startedFromOriginal(Path entry) {
    }

    /**
     * The viewer paused an entry that is playing from its original path — the
     * classic move when a stream stutters. An implementation may take it as
     * the cue to start (or keep feeding) a rescue copy, which now has the
     * share's whole bandwidth to itself. Call off the UI thread.
     */
    default void pausedOnOriginal(Path entry) {
    }

    /**
     * A local file this entry can switch to at this position, when the copy
     * behind it is far enough ahead that the player can never catch its front.
     * Empty until then. Safe on the UI thread — pure arithmetic over progress
     * counters; the resume key calls it to decide remote or local.
     */
    default Optional<Path> takeoverAt(Path entry, long positionMillis) {
        return Optional.empty();
    }

    /**
     * One line the player may show about this entry — "a local copy is
     * downloading, pause a while" — while a rescue is under way. Safe on the
     * UI thread.
     */
    default Optional<String> adviceFor(Path entry) {
        return Optional.empty();
    }

    /** A run with no mirror: every entry plays from where it is. */
    static PlayablePaths originals() {
        return entry -> entry;
    }
}
