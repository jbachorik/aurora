package mediacenter.media;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Finds photographs in a folder, optionally descending into its subfolders.
 *
 * <p>Reports in batches as it goes rather than at the end. A slideshow can show
 * the first picture as soon as one directory has been listed, and waiting for a
 * whole shelf of holidays to be counted before anything appears is the
 * experience this is built to avoid.
 *
 * <p>Sorts and filters as {@link MediaScanner} does — case-insensitively by file
 * name, skipping what the system hides and what it calls junk. The two must
 * agree: a viewer who opens the third photograph in a grid must be shown the
 * third photograph here.
 *
 * <p>One deliberate difference: a symbolic link to a directory is browsable in
 * the grid and is not descended into here. Following one that points back up the
 * tree would walk for ever, and a slideshow is worth less than a hang.
 *
 * <p>Runs on a background thread and calls back on that same thread; marshalling
 * to the JavaFX thread is the caller's job.
 */
public final class PhotoWalker {

    private static final Logger LOG = Logger.getLogger(PhotoWalker.class.getName());

    /**
     * How deep to descend. A shelf of holidays is a handful of levels; anything
     * deeper is a mistake or a loop, and the walk is recursive.
     */
    private static final int MAX_DEPTH = 12;

    private static final Comparator<Path> BY_FILE_NAME =
            Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER);

    /** What a walk found, and whether it stopped early. */
    public record Walk(long count, boolean truncated) { }

    private PhotoWalker() {
    }

    /**
     * @param recursive whether to descend into subfolders
     * @param limit     the most photographs to collect; reaching it truncates the walk
     * @param onBatch   called with each folder's photographs as they are found
     * @param cancelled consulted between directories; a viewer who has left stops the walk
     * @throws MediaAccessException when the root itself cannot be listed
     */
    public static Walk collect(Path root, boolean recursive, int limit,
            BooleanSupplier cancelled, Consumer<List<Path>> onBatch) throws MediaAccessException {
        // The root is listed here rather than inside the recursion, so that a
        // share which exists but cannot be read is an error rather than an empty
        // slideshow. Deeper failures are a different matter: what has already been
        // collected is still worth showing.
        Listing rootListing = list(root);
        if (rootListing == null) {
            throw new MediaAccessException(root, MediaScanner.cannotAccessMessage(root), null);
        }
        // One is looked for past the limit, so that a folder holding exactly the
        // limit is reported as finished rather than carrying a "+" that says,
        // wrongly, that there is more. The extra one is never handed to onBatch:
        // the viewer must not hold more than the ceiling allows.
        List<Path> collected = new ArrayList<>();
        walk(rootListing, recursive, limit, 0, cancelled, onBatch, collected);
        boolean truncated = anyBeyond(rootListing, recursive, limit, cancelled, collected);
        return new Walk(collected.size(), truncated);
    }

    /**
     * Whether there is any photograph at all beneath this folder. Never throws.
     *
     * <p>Deliberately not routed through {@link #collect}, which looks one past
     * its limit to decide whether it was cut short. Here the first photograph is
     * the whole answer, and the walk must stop on it — this runs for every folder
     * the viewer opens, over a share.
     *
     * <p>Cancellable for the same reason {@link #collect} is, and more urgently: a
     * folder of films whose posters are all claimed as artwork holds no
     * photographs at any level, so this walks the whole subtree to say "no". A
     * viewer who has moved on, or pressed F5, must not leave that running over a
     * share while the next one starts.
     *
     * @param cancelled consulted between directories, exactly as in {@link #collect}
     */
    public static boolean hasPhotos(Path root, BooleanSupplier cancelled) {
        Listing listing = list(root);
        if (listing == null) {
            LOG.log(Level.FINE, () -> "Cannot look for photographs in " + root);
            return false;
        }
        List<Path> found = new ArrayList<>();
        walk(listing, true, 1, 0, cancelled, batch -> { }, found);
        return !found.isEmpty();
    }

    /**
     * Whether the tree holds even one photograph beyond those collected. Asked
     * only after a full walk has hit its ceiling, so the cost falls on the rare
     * library larger than the ceiling, not on every folder.
     *
     * <p>A second traversal, deliberately. Carrying the answer out of the first
     * would be free, but it would mean walking to {@code limit + 1} and handing
     * only {@code limit} to the viewer — and a walker that collects more than it
     * reports is how the count and the run drifted apart once already.
     */
    private static boolean anyBeyond(Listing rootListing, boolean recursive, int limit,
            BooleanSupplier cancelled, List<Path> collected) {
        if (collected.size() < limit) {
            return false;
        }
        // Cancellable like the walk it follows: a viewer who leaves the moment the
        // ceiling is reached must not leave a second traversal of the share
        // running behind them. A cancelled probe reports "no more", which costs
        // nothing — the run it would have marked is being torn down anyway.
        List<Path> probe = new ArrayList<>();
        walk(rootListing, recursive, limit + 1, 0, cancelled, batch -> { }, probe);
        return probe.size() > limit;
    }

    /** A directory's entries, already split and sorted. */
    private record Listing(List<Path> photos, List<Path> subdirectories) { }

    /** @return null when the directory cannot be listed at all */
    private static Listing list(Path directory) {
        List<Path> photos = new ArrayList<>();
        List<Path> subdirectories = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                // Junk first, and for directories as well as files: a Synology
                // share keeps generated thumbnails in "@eaDir" folders, and a
                // slideshow full of those is worse than no slideshow at all.
                if (VideoFiles.isJunk(entry.getFileName().toString())
                        || FileVisibility.isHiddenOrSystem(entry)) {
                    continue;
                }
                // What an entry IS is read through the link, exactly as the scanner
                // reads it, so that a link to a photograph is a photograph in both.
                // Reading the link itself instead would classify it as neither file
                // nor directory and drop it, while the grid went on showing it — and
                // then the grid's third photograph and the slideshow's third are
                // different pictures. It has to reach the name list too, or a linked
                // film's sidecar goes unclaimed and the slideshow shows the poster
                // the grid hid.
                if (Files.isDirectory(entry)) {
                    // Link-ness governs descent, and only descent. A directory
                    // reached through a link is browsable in the grid and is still
                    // not descended into here: one pointing back up the tree would
                    // walk for ever, and the depth cap is a second belt for the
                    // junctions Windows does not report as links at all.
                    if (!Files.isSymbolicLink(entry)) {
                        subdirectories.add(entry);
                    }
                } else if (Files.isRegularFile(entry)) {
                    names.add(entry.getFileName().toString());
                    if (PhotoFiles.isPhoto(entry)) {
                        photos.add(entry);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not list " + directory, e);
            return null;
        }
        // One set per directory, from the rule the scanner uses: asking per
        // photograph would be quadratic, and writing the rule twice would let the
        // grid and the slideshow drift apart.
        Set<String> artwork = ArtworkResolver.artworkNames(names);
        photos.removeIf(photo -> artwork.contains(photo.getFileName().toString()));
        photos.sort(BY_FILE_NAME);
        subdirectories.sort(BY_FILE_NAME);
        return new Listing(photos, subdirectories);
    }

    /** Collects until the limit, the end of the tree, or the viewer leaving. */
    private static void walk(Listing listing, boolean recursive, int limit,
            int depth, BooleanSupplier cancelled, Consumer<List<Path>> onBatch, List<Path> collected) {
        if (cancelled.getAsBoolean() || collected.size() >= limit) {
            return;
        }

        List<Path> batch = new ArrayList<>();
        for (Path photo : listing.photos()) {
            if (collected.size() >= limit) {
                break;
            }
            collected.add(photo);
            batch.add(photo);
        }
        if (!batch.isEmpty()) {
            onBatch.accept(List.copyOf(batch));
        }
        if (collected.size() >= limit) {
            // Nothing below can be wanted, and listing it costs a round trip per
            // directory. This is what makes hasPhotos cheap: it asks for one.
            return;
        }
        if (recursive && depth < MAX_DEPTH) {
            for (Path subdirectory : listing.subdirectories()) {
                if (cancelled.getAsBoolean() || collected.size() >= limit) {
                    return;
                }
                // A share that goes away mid-walk ends that branch and no more:
                // what has already been collected is still worth showing.
                Listing below = list(subdirectory);
                if (below != null) {
                    walk(below, true, limit, depth + 1, cancelled, onBatch, collected);
                }
            }
        }
    }
}
