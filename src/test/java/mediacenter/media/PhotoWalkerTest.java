package mediacenter.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PhotoWalkerTest {

    /** Separator-independent, because the suite also runs on Windows. */
    private static List<String> namesOf(List<Path> paths, Path root) {
        return paths.stream()
                .map(path -> root.relativize(path).toString().replace('\\', '/'))
                .toList();
    }

    private static List<Path> collectAll(Path root, boolean recursive) throws MediaAccessException {
        List<Path> found = new ArrayList<>();
        PhotoWalker.collect(root, recursive, 1000, () -> false, found::addAll);
        return found;
    }

    @Test
    @DisplayName("each folder's photographs come before its subfolders'")
    void walksDepthFirstInFolderOrder(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("02 - b.jpg"));
        Files.createFile(root.resolve("01 - a.jpg"));
        Path crete = Files.createDirectory(root.resolve("Crete"));
        Files.createFile(crete.resolve("beach.jpg"));
        Path athens = Files.createDirectory(root.resolve("Athens"));
        Files.createFile(athens.resolve("ruins.jpg"));

        assertEquals(
                List.of("01 - a.jpg", "02 - b.jpg", "Athens/ruins.jpg", "Crete/beach.jpg"),
                namesOf(collectAll(root, true), root));
    }

    @Test
    @DisplayName("looking at one folder does not descend into its subfolders")
    void doesNotRecurseWhenNotAskedTo(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("here.jpg"));
        Path below = Files.createDirectory(root.resolve("below"));
        Files.createFile(below.resolve("deeper.jpg"));

        assertEquals(List.of("here.jpg"), namesOf(collectAll(root, false), root));
    }

    @Test
    @DisplayName("order matches the grid, which ignores case")
    void ordersTheSameWayTheGridDoes(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("Zebra.jpg"));
        Files.createFile(root.resolve("apple.jpg"));

        assertEquals(List.of("apple.jpg", "Zebra.jpg"), namesOf(collectAll(root, false), root));
    }

    @Test
    @DisplayName("the first photograph is reported before the walk has finished")
    void reportsInBatchesAsItGoes(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("first.jpg"));
        Path deep = Files.createDirectories(root.resolve("a/b/c"));
        Files.createFile(deep.resolve("last.jpg"));

        List<Integer> batchSizes = new ArrayList<>();
        PhotoWalker.collect(root, true, 1000, () -> false, batch -> batchSizes.add(batch.size()));

        assertTrue(batchSizes.size() >= 2, "expected more than one batch, got " + batchSizes);
    }

    @Test
    void ignoresEverythingThatIsNotAPhotograph(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("movie.mkv"));
        Files.createFile(root.resolve("notes.txt"));
        Files.createFile(root.resolve("IMG_1.heic"));
        Files.createFile(root.resolve("Thumbs.db"));
        Files.createFile(root.resolve(".hidden.jpg"));

        assertTrue(collectAll(root, true).isEmpty());
    }

    @Test
    @DisplayName("a link pointing back up the tree does not walk forever")
    void doesNotFollowSymbolicLinks(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("a.jpg"));
        Path child = Files.createDirectory(root.resolve("child"));
        try {
            Files.createSymbolicLink(child.resolve("up"), root);
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.abort("This filesystem has no symbolic links");
        }

        assertEquals(1, collectAll(root, true).size());
    }

    @Test
    @DisplayName("reaching the limit is reported, not passed off as a finished walk")
    void reportsTruncation(@TempDir Path root) throws Exception {
        for (int i = 0; i < 10; i++) {
            Files.createFile(root.resolve("photo-" + i + ".jpg"));
        }

        PhotoWalker.Walk walk = PhotoWalker.collect(root, true, 4, () -> false, batch -> { });

        assertEquals(4, walk.count());
        assertTrue(walk.truncated());
    }

    @Test
    void aCompletedWalkIsNotTruncated(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("only.jpg"));

        assertFalse(PhotoWalker.collect(root, true, 1000, () -> false, batch -> { }).truncated());
    }

    @Test
    @DisplayName("a folder holding exactly the limit has not been cut short")
    void aFolderOfExactlyTheLimitIsNotTruncated(@TempDir Path root) throws Exception {
        for (int i = 0; i < 4; i++) {
            Files.createFile(root.resolve("photo-" + i + ".jpg"));
        }

        PhotoWalker.Walk walk = PhotoWalker.collect(root, true, 4, () -> false, batch -> { });

        assertEquals(4, walk.count());
        assertFalse(walk.truncated());
    }

    @Test
    @DisplayName("a folder that cannot be listed at all is an error, not an empty slideshow")
    void refusesAnUnreachableRoot(@TempDir Path temp) throws Exception {
        Path missing = temp.resolve("offline-share");

        assertThrows(MediaAccessException.class,
                () -> PhotoWalker.collect(missing, true, 1000, () -> false, batch -> { }));
    }

    @Test
    void answersWhetherAFolderHasAnyPhotographsBeneathIt(@TempDir Path root) throws Exception {
        Path deep = Files.createDirectories(root.resolve("a/b"));
        assertFalse(PhotoWalker.hasPhotos(root, () -> false));

        Files.createFile(deep.resolve("found.jpg"));
        assertTrue(PhotoWalker.hasPhotos(root, () -> false));
    }

    @Test
    @DisplayName("an unreachable folder has no photographs rather than throwing")
    void hasPhotosSwallowsAnUnreachableFolder(@TempDir Path temp) throws Exception {
        assertFalse(PhotoWalker.hasPhotos(temp.resolve("offline-share"), () -> false));
    }

    @Test
    @DisplayName("a film's artwork is not a photograph to show, here as in the grid")
    void skipsArtwork(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("Blade Runner 2049.mkv"));
        Files.createFile(root.resolve("poster.jpg"));
        Files.createFile(root.resolve("Blade Runner 2049.jpg"));
        Files.createFile(root.resolve("holiday.jpg"));

        assertEquals(List.of("holiday.jpg"), namesOf(collectAll(root, false), root));
    }

    @Test
    @DisplayName("a cancelled walk stops instead of running on against a closed page")
    void stopsWhenCancelled(@TempDir Path root) throws Exception {
        for (int i = 0; i < 50; i++) {
            Files.createFile(root.resolve("photo-" + i + ".jpg"));
        }
        Path below = Files.createDirectory(root.resolve("below"));
        Files.createFile(below.resolve("deep.jpg"));

        List<Path> found = new ArrayList<>();
        PhotoWalker.collect(root, true, 1000, () -> !found.isEmpty(), found::addAll);

        // Cancellation is consulted between directories, so the root's own batch
        // still arrives; what must not happen is descending after that.
        assertEquals(50, found.size());
        assertTrue(found.stream().noneMatch(path -> path.endsWith("deep.jpg")), found.toString());
    }

    @Test
    @DisplayName("the walker and the grid hold the same photographs, in the same order")
    void agreesWithTheGridAboutWhatAFolderHolds(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("Blade Runner 2049.mkv"));
        Files.createFile(root.resolve("Blade Runner 2049.jpg"));
        Files.createFile(root.resolve("folder.jpg"));
        Files.createFile(root.resolve("Zermatt.jpg"));
        Files.createFile(root.resolve("apple.png"));
        Files.createFile(root.resolve("Thumbs.db"));
        Files.createFile(root.resolve(".hidden.jpg"));
        Path targets = Files.createDirectory(root.resolve("targets"));
        Path photograph = Files.createFile(targets.resolve("original.jpg"));
        Path film = Files.createFile(targets.resolve("original.mkv"));
        try {
            // A link to a photograph is a photograph in the grid, so it has to be
            // one here; and a linked film still claims its sidecar as artwork.
            Files.createSymbolicLink(root.resolve("linked.jpg"), photograph);
            Files.createSymbolicLink(root.resolve("Trainspotting.mkv"), film);
            Files.createFile(root.resolve("Trainspotting.jpg"));
        } catch (UnsupportedOperationException | IOException e) {
            // Without links the rest of the mix still has to agree.
        }

        List<Path> grid = new MediaScanner().scan(root).stream()
                .filter(item -> item.type() == MediaItemType.IMAGE)
                .map(MediaItem::path)
                .toList();

        assertEquals(grid, collectAll(root, false));
    }

    @Test
    @DisplayName("a viewer who leaves at the ceiling leaves no probe running behind them")
    void doesNotProbeForMorePhotographsOnceCancelled(@TempDir Path root) throws Exception {
        for (int i = 0; i < 10; i++) {
            Files.createFile(root.resolve("photo-" + i + ".jpg"));
        }

        AtomicBoolean viewerHasLeft = new AtomicBoolean();
        PhotoWalker.Walk walk = PhotoWalker.collect(root, true, 4, viewerHasLeft::get,
                batch -> viewerHasLeft.set(true));

        assertEquals(4, walk.count());
        // The probe that decides "truncated" is cancelled before it starts, and a
        // cancelled probe reports no more: the run it would have marked with a "+"
        // is being torn down anyway.
        assertFalse(walk.truncated(), "the truncation probe walked on after cancellation");
    }

    @Test
    @DisplayName("a viewer who has moved on stops the search for photographs")
    void hasPhotosStopsWhenCancelled(@TempDir Path root) throws Exception {
        // No photograph above the deep one, so answering at all means descending —
        // the shelf of films whose posters are all artwork, in miniature.
        Path deep = Files.createDirectories(root.resolve("a/b"));
        Files.createFile(deep.resolve("found.jpg"));

        assertTrue(PhotoWalker.hasPhotos(root, () -> false));

        // Cancelled from the second consultation on: the root is listed, and then
        // the descent that would have found the photograph is abandoned.
        AtomicInteger asked = new AtomicInteger();
        assertFalse(PhotoWalker.hasPhotos(root, () -> asked.incrementAndGet() > 1));
        assertTrue(asked.get() >= 2, "cancellation was never consulted between directories");
    }

    @Test
    @DisplayName("finding one photograph is the whole answer: hasPhotos does not walk on")
    void hasPhotosStopsAtTheFirstPhotograph(@TempDir Path root) throws Exception {
        Path first = Files.createFile(root.resolve("first.jpg"));
        Path locked = Files.createDirectory(root.resolve("locked"));
        Files.createFile(locked.resolve("deeper.jpg"));

        try {
            refuseListing(locked);
            List<LogRecord> descents = new ArrayList<>();
            Logger walker = Logger.getLogger(PhotoWalker.class.getName());
            Handler handler = recordingHandler(descents);
            Level level = walker.getLevel();
            boolean useParents = walker.getUseParentHandlers();
            walker.setLevel(Level.ALL);
            walker.setUseParentHandlers(false);
            walker.addHandler(handler);
            try {
                assertTrue(PhotoWalker.hasPhotos(root, () -> false));
                assertTrue(descents.isEmpty(),
                        "hasPhotos listed a subfolder after it already had its answer: " + descents);

                // Control: with the photograph gone it must descend, which proves
                // the detector above fires rather than being blind to a descent.
                Files.delete(first);
                descents.clear();
                assertFalse(PhotoWalker.hasPhotos(root, () -> false));
                assertFalse(descents.isEmpty(), "a descent leaves no trace, so the assertion above proves nothing");
            } finally {
                walker.removeHandler(handler);
                walker.setLevel(level);
                walker.setUseParentHandlers(useParents);
            }
        } finally {
            allowListing(locked);
        }
    }

    /**
     * Makes a directory refuse to be listed, so that descending into it leaves a
     * trace in the log. Aborts where that cannot be arranged: Windows has no POSIX
     * permissions, and root ignores the ones it has.
     */
    private static void refuseListing(Path directory) throws IOException {
        try {
            Files.setPosixFilePermissions(directory, Set.of());
        } catch (UnsupportedOperationException e) {
            Assumptions.abort("This filesystem has no POSIX permissions");
        }
        try (DirectoryStream<Path> ignored = Files.newDirectoryStream(directory)) {
            Assumptions.abort("This user can list a directory that permits nothing");
        } catch (IOException expected) {
            // Good: a descent into it will now fail, and a failure is logged.
        }
    }

    /** Undone before the temporary folder is cleaned up, which needs to read it. */
    private static void allowListing(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException | IOException e) {
            // Never locked in the first place.
        }
    }

    private static Handler recordingHandler(List<LogRecord> into) {
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                into.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        return handler;
    }
}
