package mediacenter.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The photographs a viewing is running through, and the rules for moving.
 *
 * <p>Only ever touched on the JavaFX thread. The walk that fills it runs
 * elsewhere and hands its batches over; keeping every mutation on one thread is
 * what makes an ordinary list safe here.
 *
 * <p>Contains no JavaFX, so every rule in it can be tested without a toolkit.
 */
final class PhotoRun {

    private final List<Path> photos = new ArrayList<>();
    private final boolean looping;
    private boolean complete;
    private boolean truncated;

    /** @param looping true for a slideshow, false for looking at one folder */
    PhotoRun(boolean looping) {
        this.looping = looping;
    }

    void add(List<Path> batch) {
        photos.addAll(batch);
    }

    void markComplete() {
        complete = true;
    }

    /**
     * The walk ended at its limit rather than at the end of the tree.
     *
     * <p>Such a run still wraps over what it holds — freezing on the last of five
     * thousand would be worse than looping them — but its counter keeps its "+",
     * because there really are more. Do not "restore" an invariant here that
     * suppresses {@link #markComplete}: wrapping is deliberate.
     */
    void markTruncated() {
        truncated = true;
    }

    /** Whether the walk has ended, however it ended. */
    boolean complete() {
        return complete;
    }

    /**
     * Whether the arrows may wrap. A truncated run wraps over what it has: a
     * library of six thousand photographs should loop over the five thousand
     * collected, not freeze for ever on the last one. It still refuses to claim
     * that is all of them — see {@link #counterText}.
     */
    private boolean wraps() {
        return looping && complete;
    }

    boolean isEmpty() {
        return photos.isEmpty();
    }

    int size() {
        return photos.size();
    }

    Path get(int index) {
        return photos.get(index);
    }

    /**
     * An unmodifiable view, not a copy: this is read on every painting and on
     * every resize, and copying five thousand paths each time is waste.
     */
    List<Path> photosView() {
        return java.util.Collections.unmodifiableList(photos);
    }

    int indexOf(Path photo) {
        return photos.indexOf(photo);
    }

    /**
     * The next photograph. At the end of a finished slideshow this is the first
     * one again; at the end of one that is still being collected it is where you
     * already are, because the last is not yet known.
     */
    int next(int from) {
        if (photos.isEmpty()) {
            return 0;
        }
        if (from + 1 < photos.size()) {
            return from + 1;
        }
        return wraps() ? 0 : from;
    }

    int previous(int from) {
        if (photos.isEmpty()) {
            return 0;
        }
        if (from > 0) {
            return from - 1;
        }
        return wraps() ? photos.size() - 1 : from;
    }

    /** Which photographs to have ready, given where the arrows can go from here. */
    List<Integer> neighbours(int from) {
        if (photos.isEmpty()) {
            return List.of();
        }
        return List.of(previous(from), next(from));
    }

    /** "2 of 3" once everything is known, "2 of 3+" while the walk continues. */
    String counterText(int index) {
        if (photos.isEmpty()) {
            return "";
        }
        // The "+" outlives the walk when it was cut short: there really are more.
        return (index + 1) + " of " + photos.size() + (complete && !truncated ? "" : "+");
    }
}
