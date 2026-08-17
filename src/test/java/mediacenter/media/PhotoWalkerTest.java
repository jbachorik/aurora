package mediacenter.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        assertFalse(PhotoWalker.hasPhotos(root));

        Files.createFile(deep.resolve("found.jpg"));
        assertTrue(PhotoWalker.hasPhotos(root));
    }

    @Test
    @DisplayName("an unreachable folder has no photographs rather than throwing")
    void hasPhotosSwallowsAnUnreachableFolder(@TempDir Path temp) throws Exception {
        assertFalse(PhotoWalker.hasPhotos(temp.resolve("offline-share")));
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
}
