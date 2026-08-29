package mediacenter.history;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which video files have been watched.
 *
 * <p>Just a set of paths: a video either carries the mark or it does not.
 * Unlike {@link PlaybackHistory} this is unbounded — the mark is the point,
 * so it must never fall off the end of a list.
 */
public final class WatchedVideos {

    private final Set<Path> paths = new HashSet<>();

    public static WatchedVideos of(Collection<Path> paths) {
        WatchedVideos watched = new WatchedVideos();
        watched.paths.addAll(paths);
        return watched;
    }

    public synchronized boolean isWatched(Path mediaPath) {
        return paths.contains(mediaPath);
    }

    /** Marks one video watched; returns whether that changed anything. */
    public synchronized boolean mark(Path mediaPath) {
        return paths.add(mediaPath);
    }

    /** Clears the mark from one video; returns whether that changed anything. */
    public synchronized boolean reset(Path mediaPath) {
        return paths.remove(mediaPath);
    }

    /**
     * Carries a mark along with a file the library has moved — reorganising a
     * shelf must not un-watch anything. A file that carried no mark carries
     * nothing to its new home.
     *
     * @return whether a mark actually travelled
     */
    public synchronized boolean move(Path from, Path to) {
        if (!paths.remove(from)) {
            return false;
        }
        paths.add(to);
        return true;
    }

    /**
     * Clears the mark from every video in the folder and all of its subfolders.
     * Component-wise, not textual: resetting {@code /media/tv} leaves
     * {@code /media/tvshows} alone.
     *
     * @return whether any mark was cleared
     */
    public synchronized boolean resetBelow(Path folder) {
        return paths.removeIf(path -> path.startsWith(folder));
    }

    /** Every marked path, in a stable order so the saved file diffs cleanly. */
    public synchronized List<Path> paths() {
        return paths.stream()
                .sorted(Comparator.comparing(Path::toString))
                .toList();
    }
}
