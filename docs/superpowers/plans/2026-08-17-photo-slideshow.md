# Photo Slideshow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Browse photographs in the media center and play a folder of them as a recursive slideshow that starts showing pictures immediately.

**Architecture:** Photographs become a third `MediaItemType` so the existing scanner and tile grid render them with no new drawing code. A `PhotoWalker` traverses on a background thread and publishes batches to the JavaFX thread, which owns the list — so the viewer indexes an ordinary `ArrayList` with no locking. A full-screen `PhotoView` holds exactly three decoded images at screen size.

**Tech Stack:** Java 25, JavaFX (`javafx.controls` only), JUnit 5, Gradle Kotlin DSL. No third-party libraries.

**Spec:** `docs/superpowers/specs/2026-08-16-photo-slideshow-design.md`

## Global Constraints

- **No new modules.** `module-info.java` requires exactly `javafx.controls` and `java.logging`. Do not add `java.desktop`.
- **No runtime dependencies.** EXIF is parsed by hand for this reason.
- **The JavaFX thread never does I/O.** Every directory walk, every attribute read and every EXIF read happens on `context.backgroundExecutor()`, marshalled back with `FxTasks`. Image decoding happens on JavaFX's own background loader. This rule has no exceptions in this feature.
- **Tests never render a scene.** There is no JavaFX toolkit in the test JVM, so a JavaFX `Image` cannot be constructed in a test. Classes that must be tested are kept free of it.
- **The slideshow must agree with the grid.** A photograph opened from the grid must be the photograph shown. That means `PhotoWalker` sorts and filters *exactly* as `MediaScanner` does — case-insensitively by file name, skipping hidden and system entries.
- **Windows is the target.** `.github/workflows/ci.yml` runs the suite on `windows-2022`. No test may assume `/` as a path separator.
- **Screenshot verification** uses `./gradlew run --args="--snapshot=/tmp/x.png --snapshot-keys=RIGHT,ENTER"`. The display must be awake or the run hangs.
- **Comments explain why.** Match the surrounding style.
- **Commit after every task.**

---

### Task 1: Recognising a photograph

**Files:**
- Create: `src/main/java/mediacenter/media/PhotoFiles.java`
- Test: `src/test/java/mediacenter/media/PhotoFilesTest.java`

**Interfaces:**
- Consumes: `VideoFiles.extensionOf(String)`, `VideoFiles.isJunk(String)`.
- Produces: `PhotoFiles.PHOTO_EXTENSIONS` (`Set<String>`), `PhotoFiles.isPhoto(Path)`, `PhotoFiles.isPhotoFileName(String)`.

- [ ] **Step 1: Write the failing test**

```java
package mediacenter.media;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PhotoFilesTest {

    @Test
    void recognisesTheFormatsJavaFxCanDecode() {
        assertTrue(PhotoFiles.isPhotoFileName("beach.jpg"));
        assertTrue(PhotoFiles.isPhotoFileName("beach.JPEG"));
        assertTrue(PhotoFiles.isPhotoFileName("poster.png"));
        assertTrue(PhotoFiles.isPhotoFileName("animation.gif"));
        assertTrue(PhotoFiles.isPhotoFileName("scan.bmp"));
    }

    @Test
    @DisplayName("formats JavaFX cannot decode are not photographs, whatever they contain")
    void rejectsFormatsThatCannotBeDecoded() {
        assertFalse(PhotoFiles.isPhotoFileName("IMG_0001.heic"));
        assertFalse(PhotoFiles.isPhotoFileName("IMG_0001.HEIC"));
        assertFalse(PhotoFiles.isPhotoFileName("photo.webp"));
        assertFalse(PhotoFiles.isPhotoFileName("scan.tiff"));
    }

    @Test
    void isNotConfusedByVideo() {
        assertFalse(PhotoFiles.isPhotoFileName("movie.mkv"));
        assertFalse(PhotoFiles.isPhotoFileName("noextension"));
    }

    @Test
    @DisplayName("junk and dot-files are not photographs")
    void treatsJunkAsNotAPhotograph() {
        assertFalse(PhotoFiles.isPhotoFileName("Thumbs.db"));
        assertFalse(PhotoFiles.isPhotoFileName(".hidden.jpg"));
    }

    @Test
    void acceptsAPath() {
        assertTrue(PhotoFiles.isPhoto(Path.of("/media/Holidays/beach.jpg")));
        assertFalse(PhotoFiles.isPhoto(null));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*PhotoFilesTest*'`
Expected: FAIL — `compileTestJava` reports `cannot find symbol: PhotoFiles`.

- [ ] **Step 3: Write minimal implementation**

```java
package mediacenter.media;

import java.nio.file.Path;
import java.util.Set;

/**
 * Which files are photographs, for browsing and for slideshows.
 *
 * <p>Deliberately only the formats JavaFX itself can decode. HEIC in particular
 * is what every recent iPhone produces and JavaFX cannot read one at all, so it
 * is not recognised here — a photograph that would only ever appear as a broken
 * frame is better left out of the set entirely.
 */
public final class PhotoFiles {

    public static final Set<String> PHOTO_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "bmp");

    private PhotoFiles() {
    }

    public static boolean isPhoto(Path path) {
        return path != null && path.getFileName() != null
                && isPhotoFileName(path.getFileName().toString());
    }

    public static boolean isPhotoFileName(String fileName) {
        return fileName != null
                && !VideoFiles.isJunk(fileName)
                && PHOTO_EXTENSIONS.contains(VideoFiles.extensionOf(fileName));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*PhotoFilesTest*'`
Expected: PASS, five tests. `VideoFiles.isJunk` already rejects the named junk
files and anything beginning with a dot, so the last test passes by delegation.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mediacenter/media/PhotoFiles.java src/test/java/mediacenter/media/PhotoFilesTest.java
git commit -m "Recognise the photo formats JavaFX can actually decode"
```

---

### Task 2: One rule for what is hidden

`MediaScanner` decides visibility with private helpers. `PhotoWalker` must make
the *same* decision or the slideshow will contain files the grid hides, so the
rule moves somewhere both can reach before either needs it.

**Files:**
- Create: `src/main/java/mediacenter/media/FileVisibility.java`
- Modify: `src/main/java/mediacenter/media/MediaScanner.java`
- Test: `src/test/java/mediacenter/media/FileVisibilityTest.java`

**Interfaces:**
- Produces: `FileVisibility.isHiddenOrSystem(Path)` — `boolean`. True when the
  entry is hidden or a system file, and **true when it is not there at all**:
  `MediaScanner` skips an entry whose attributes it cannot read, so this must
  skip the same ones or the grid and the slideshow disagree about a folder. A
  filesystem with no DOS view simply has no hidden bit, and returns false.

- [ ] **Step 1: Write the failing test**

```java
package mediacenter.media;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class FileVisibilityTest {

    @Test
    void anOrdinaryFileIsVisible(@TempDir Path temp) throws IOException {
        assertFalse(FileVisibility.isHiddenOrSystem(Files.createFile(temp.resolve("beach.jpg"))));
    }

    @Test
    @DisplayName("an entry that is not there is skipped, exactly as the scanner skips it")
    void anEntryThatIsNotThereIsSkipped(@TempDir Path temp) {
        assertTrue(FileVisibility.isHiddenOrSystem(temp.resolve("gone.jpg")));
    }

    @Test
    @DisplayName("on Windows the hidden attribute is honoured")
    @EnabledOnOs(OS.WINDOWS)
    void honoursTheHiddenAttribute(@TempDir Path temp) throws IOException {
        Path hidden = Files.createFile(temp.resolve("hidden.jpg"));
        Files.setAttribute(hidden, "dos:hidden", true);

        assertTrue(FileVisibility.isHiddenOrSystem(hidden));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*FileVisibilityTest*'`
Expected: FAIL — `cannot find symbol: FileVisibility`.

- [ ] **Step 3: Write minimal implementation**

```java
package mediacenter.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributes;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Whether the operating system considers an entry hidden.
 *
 * <p>Shared so that the browse grid and a slideshow reach the same verdict. They
 * must: a photograph the grid hides and the slideshow shows would make the two
 * disagree about what is in a folder, and the viewer would open a picture the
 * viewer never chose.
 */
public final class FileVisibility {

    private static final Logger LOG = Logger.getLogger(FileVisibility.class.getName());

    private FileVisibility() {
    }

    /**
     * @return true when the entry is hidden, or when it cannot be described at
     *         all — the scanner skips such an entry, and the slideshow must skip
     *         the same ones or the two disagree about what a folder contains
     */
    public static boolean isHiddenOrSystem(Path entry) {
        try {
            // On Windows this single call also yields the hidden/system flags.
            DosFileAttributes attributes = Files.readAttributes(entry, DosFileAttributes.class);
            return attributes.isHidden() || attributes.isSystem();
        } catch (IOException | RuntimeException e) {
            // UnsupportedOperationException lands here on filesystems without the
            // DOS view, where there is no hidden attribute to consult.
            if (!Files.exists(entry)) {
                // Gone, or unreadable: the scanner drops these, so this does too.
                return true;
            }
            // No DOS view on this filesystem, which simply means no hidden bit.
            LOG.log(Level.FINEST, "No DOS attributes for " + entry, e);
            return false;
        }
    }
}
```

- [ ] **Step 4: Point `MediaScanner` at it**

`MediaScanner` has private `readAttributes(Path)` and
`isHiddenOrSystem(BasicFileAttributes)` in its `-- attributes --` section. Leave
them where they are — they are on the hot path of a listing that already has the
attributes in hand, and changing that is not this feature's business. Add one
comment above `isHiddenOrSystem` so the duplication is deliberate rather than
accidental:

```java
    // The same verdict as FileVisibility.isHiddenOrSystem, reached from
    // attributes this listing has already read.
```

- [ ] **Step 5: Run the suite**

Run: `./gradlew test`
Expected: PASS, whole suite. The Windows-only case is skipped elsewhere; that is the platform whose hidden attribute this exists for.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/mediacenter/media src/test/java/mediacenter/media/FileVisibilityTest.java
git commit -m "Share one rule for what the operating system hides"
```

---

### Task 3: Photographs in the browse grid

**Files:**
- Modify: `src/main/java/mediacenter/media/MediaItemType.java`
- Modify: `src/main/java/mediacenter/media/MediaItem.java`
- Modify: `src/main/java/mediacenter/media/MediaScanner.java`
- Modify: `src/main/java/mediacenter/media/ArtworkResolver.java`
- Modify: `src/main/java/mediacenter/ui/components/MediaTile.java`
- Modify: `src/main/java/mediacenter/ui/BrowseView.java`
- Test: `src/test/java/mediacenter/media/MediaScannerTest.java`

**Interfaces:**
- Consumes: `PhotoFiles.isPhoto(Path)`, `ArtworkResolver.selectCover(Collection<String>)`.
- Produces: `MediaItemType.IMAGE`, `MediaItem.isImage()`, `MediaItem.image(Path, String, Optional<Path>, long)`, `ArtworkResolver.artworkNames(Collection<String>)` → `Set<String>`.

**Two traps this task exists to avoid**, both found by review:

1. `ArtworkResolver` treats `poster.jpg`, `folder.jpg`, `cover.jpg` and a
   `<video-name>.jpg` sidecar as *artwork*. Those are photographs by extension,
   so listing photographs naively puts a "poster" tile beside every film.
2. `MediaScanner` shows a lone film under its folder's name, gated on
   `videos.size() == 1`. Photographs must not count toward that, or a folder
   holding one film and its poster reverts to showing the file name.

- [ ] **Step 1: Write the failing test**

Add to `MediaScannerTest` (note the fully-qualified `Optional` — this test class
has no `import java.util.Optional`, matching its existing style):

```java
    @Test
    @DisplayName("photographs are listed beside videos, and are their own thumbnails")
    void listsPhotographs(@TempDir Path temp) throws Exception {
        Files.createFile(temp.resolve("beach.jpg"));
        // Two videos, so the single-video folder-name rule stays out of the way.
        Files.createFile(temp.resolve("one.mkv"));
        Files.createFile(temp.resolve("two.mkv"));
        Files.createFile(temp.resolve("notes.txt"));

        List<MediaItem> items = scanner.scan(temp);

        assertEquals(List.of("beach", "one", "two"),
                items.stream().map(MediaItem::displayName).toList());
        MediaItem photo = items.getFirst();
        assertTrue(photo.isImage());
        assertEquals(java.util.Optional.of(temp.resolve("beach.jpg")), photo.artworkPath());
    }

    @Test
    @DisplayName("a film's poster is artwork, not a photograph to browse")
    void doesNotListArtworkAsAPhotograph(@TempDir Path temp) throws Exception {
        Files.createFile(temp.resolve("Blade Runner 2049.mkv"));
        Files.createFile(temp.resolve("poster.jpg"));

        List<MediaItem> items = scanner.scan(temp);

        assertEquals(1, items.size(), "the poster is the film's artwork: " + items);
        assertTrue(items.getFirst().isVideo());
    }

    @Test
    @DisplayName("a film's sidecar image is artwork wherever the listing happens to reach it")
    void doesNotListASidecarAsAPhotograph(@TempDir Path temp) throws Exception {
        Files.createFile(temp.resolve("movie.mkv"));
        Files.createFile(temp.resolve("movie.jpg"));
        Files.createFile(temp.resolve("holiday.jpg"));
        // A second film, so the single-video folder-name rule stays out of the
        // way — this test is about the sidecar, not about naming.
        Files.createFile(temp.resolve("other.mkv"));

        List<MediaItem> items = scanner.scan(temp);

        assertEquals(List.of("holiday", "movie", "other"),
                items.stream().map(MediaItem::displayName).toList());
    }

    @Test
    @DisplayName("a folder image is the folder's artwork even where there is no film")
    void treatsAFolderImageAsArtworkEvenWithoutVideos(@TempDir Path temp) throws Exception {
        Files.createFile(temp.resolve("folder.jpg"));
        Files.createFile(temp.resolve("holiday.jpg"));

        List<MediaItem> items = scanner.scan(temp);

        assertEquals(List.of("holiday"), items.stream().map(MediaItem::displayName).toList());
    }

    @Test
    @DisplayName("a photograph does not stop a lone film borrowing its folder's name")
    void photographsDoNotCountTowardTheSingleVideoRule(@TempDir Path temp) throws Exception {
        Path folder = Files.createDirectory(temp.resolve("Blade Runner 2049 (2017)"));
        Files.createFile(folder.resolve("Blade.Runner.2049.mkv"));
        Files.createFile(folder.resolve("holiday.png"));

        List<MediaItem> items = scanner.scan(folder);

        assertEquals("Blade Runner 2049 (2017)",
                items.stream().filter(MediaItem::isVideo).findFirst().orElseThrow().displayName());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*MediaScannerTest*'`
Expected: FAIL — `cannot find symbol: isImage`.

- [ ] **Step 3: Write minimal implementation**

In `MediaItemType.java`:

```java
    /** A photograph, shown full screen rather than handed to the player. */
    IMAGE
```

In `MediaItem.java`, beside the existing factories:

```java
    /** A photograph is its own thumbnail, so the artwork is the file itself. */
    public static MediaItem image(Path path, String displayName, Optional<Path> artwork, long lastModified) {
        return new MediaItem(path, displayName, MediaItemType.IMAGE, artwork, lastModified);
    }

    public boolean isImage() {
        return type == MediaItemType.IMAGE;
    }
```

In `MediaScanner`, collect photographs alongside videos in the listing loop, then
build them **after the loop closes** — the artwork rule needs the complete list
of names, and `DirectoryStream` order is unspecified, so a sidecar tested inside
the loop gives a different answer depending on what the filesystem returned
first.

```java
        // in the listing loop, beside the existing video branch
        } else if (PhotoFiles.isPhotoFileName(fileName)) {
            photos.add(entry);
            photoTimestamps.add(attributes.lastModifiedTime().toMillis());
        }

        // after the try-with-resources closes, over the completed fileNames
        Set<String> artwork = ArtworkResolver.artworkNames(fileNames);
        List<MediaItem> photoItems = new ArrayList<>();
        for (int i = 0; i < photos.size(); i++) {
            Path photo = photos.get(i);
            if (artwork.contains(photo.getFileName().toString())) {
                // A film's poster or sidecar: the grid shows it as that film's
                // artwork, and the slideshow skips it for the same reason.
                continue;
            }
            photoItems.add(MediaItem.image(photo, DisplayNames.forFile(photo),
                    Optional.of(photo), photoTimestamps.get(i)));
        }

        // videoItems sorts its own list, so the two are combined and sorted again
        List<MediaItem> files = new ArrayList<>(videoItems(videos, videoTimestamps, ...));
        files.addAll(photoItems);
        files.sort(BY_FILE_NAME);
        items.addAll(files);
```

Declare `photos` and `photoTimestamps` beside the existing `videos` and its
timestamps, and pass the video list to `videoItems` unchanged — **photographs must
not be added to `videos`**, or a folder holding one film and one photograph stops
borrowing the folder's name for the film.

In `MediaTile.placeholder()`, the symbol is currently
`item.isDirectory() ? "▤" : "▶"`. A photograph is not played:

```java
        Label symbol = new Label(switch (item.type()) {
            case DIRECTORY -> "▤";
            case IMAGE -> "▣";
            case VIDEO -> "▶";
        });
```

In `BrowseView`, the empty message reads `"This folder has no videos."`. Change
it to `"This folder has nothing to show."`

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test`
Expected: PASS, whole suite.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java/mediacenter/media/MediaScannerTest.java
git commit -m "List photographs in the browse grid"
```

---

### Task 4: Walking a folder for photographs

**Files:**
- Create: `src/main/java/mediacenter/media/PhotoWalker.java`
- Test: `src/test/java/mediacenter/media/PhotoWalkerTest.java`

**Interfaces:**
- Consumes: `PhotoFiles.isPhoto(Path)`, `FileVisibility.isHiddenOrSystem(Path)`, `MediaAccessException`, `MediaScanner.cannotAccessMessage(Path)` (package-private static, same package), `ArtworkResolver.artworkNames(Collection<String>)` (added in Task 3).
- Produces:
  - `record PhotoWalker.Walk(long count, boolean truncated)`
  - `PhotoWalker.collect(Path root, boolean recursive, int limit, BooleanSupplier cancelled, Consumer<List<Path>> onBatch)` → `Walk`, **`throws MediaAccessException`** — it is a checked exception with a `(Path, String, Throwable)` constructor, so both the `throws` clause and the arguments matter. Called on a background thread; `onBatch` runs on that thread.
  - `PhotoWalker.hasPhotos(Path root)` → `boolean`, never throws.

**Why the signature has `recursive`:** the spec gives the Slideshow tile the whole
subtree and gives one activated photograph *its own folder only*. Without this
parameter both would recurse.

**Why `Walk` carries `truncated`:** reaching the limit must be distinguishable
from finishing, or the counter drops its `+` and claims a truncated set is the
whole library.

**Why it takes a `cancelled` supplier:** leaving the viewer must stop the walk.
Nothing else can — the shell only pops a stack, and a walk over a share would
otherwise run on for thousands of entries against a page nobody is looking at.

**Why it excludes artwork:** `poster.jpg`, `folder.jpg`, `cover.jpg` and a
`<video>.jpg` sidecar are photographs by extension. Task 3 keeps them out of the
grid; if the walk does not do the same, every film folder grows a Slideshow tile
and arrowing through a mixed folder lands on the poster the grid hid.

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*PhotoWalkerTest*'`
Expected: FAIL — `cannot find symbol: PhotoWalker`.

- [ ] **Step 3: Write minimal implementation**

```java
package mediacenter.media;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
     */
    public static boolean hasPhotos(Path root) {
        Listing listing = list(root);
        if (listing == null) {
            LOG.log(Level.FINE, () -> "Cannot look for photographs in " + root);
            return false;
        }
        List<Path> found = new ArrayList<>();
        walk(listing, true, 1, 0, () -> false, batch -> { }, found);
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
                // Links are not followed: one pointing back up the tree would walk
                // for ever, and the depth cap is a second belt for junctions that
                // Windows does not report as links at all.
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    subdirectories.add(entry);
                } else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
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
            return true;
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*PhotoWalkerTest*'`
Expected: PASS, fourteen tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mediacenter/media/PhotoWalker.java src/test/java/mediacenter/media/PhotoWalkerTest.java
git commit -m "Walk a folder for photographs, reporting as it goes"
```

---

### Task 5: EXIF orientation

**Files:**
- Create: `src/main/java/mediacenter/media/ExifOrientation.java`
- Test: `src/test/java/mediacenter/media/ExifOrientationTest.java`

**Interfaces:**
- Produces: `ExifOrientation.degreesFor(Path)` → `0`, `90`, `180` or `270`. Never throws. **Reads the file, so it must only ever be called from a background thread.**

- [ ] **Step 1: Write the failing test**

```java
package mediacenter.media;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExifOrientationTest {

    /** A JPEG whose APP1 segment carries nothing but an orientation tag. */
    private static Path jpegWithOrientation(Path directory, String name, int orientation) throws IOException {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        header.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
        tiff.write(header.array());

        ByteBuffer ifd = ByteBuffer.allocate(2 + 12 + 4).order(ByteOrder.BIG_ENDIAN);
        ifd.putShort((short) 1);            // one entry
        ifd.putShort((short) 0x0112);       // Orientation
        ifd.putShort((short) 3);            // SHORT
        ifd.putInt(1);                      // one value
        ifd.putShort((short) orientation);  // value, left-aligned in four bytes
        ifd.putShort((short) 0);
        ifd.putInt(0);                      // no next IFD
        tiff.write(ifd.array());

        byte[] exifBody = tiff.toByteArray();
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        jpeg.write(new byte[] {(byte) 0xFF, (byte) 0xD8});
        jpeg.write(new byte[] {(byte) 0xFF, (byte) 0xE1});
        int segmentLength = 2 + 6 + exifBody.length;
        jpeg.write((segmentLength >> 8) & 0xFF);
        jpeg.write(segmentLength & 0xFF);
        jpeg.write(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        jpeg.write(exifBody);
        jpeg.write(new byte[] {(byte) 0xFF, (byte) 0xD9});

        Path file = directory.resolve(name);
        Files.write(file, jpeg.toByteArray());
        return file;
    }

    @Test
    @DisplayName("a photograph taken sideways reports the rotation that puts it right")
    void readsTheOrientationTag(@TempDir Path temp) throws IOException {
        assertEquals(0, ExifOrientation.degreesFor(jpegWithOrientation(temp, "up.jpg", 1)));
        assertEquals(180, ExifOrientation.degreesFor(jpegWithOrientation(temp, "down.jpg", 3)));
        assertEquals(90, ExifOrientation.degreesFor(jpegWithOrientation(temp, "left.jpg", 6)));
        assertEquals(270, ExifOrientation.degreesFor(jpegWithOrientation(temp, "right.jpg", 8)));
    }

    @Test
    @DisplayName("a photograph with nothing to say about orientation is left alone")
    void defaultsToNoRotation(@TempDir Path temp) throws IOException {
        Path plain = Files.write(temp.resolve("plain.jpg"),
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});
        assertEquals(0, ExifOrientation.degreesFor(plain));
        assertEquals(0, ExifOrientation.degreesFor(temp.resolve("missing.jpg")));
        assertEquals(0, ExifOrientation.degreesFor(null));
    }

    @Test
    @DisplayName("the six bytes that spell Exif are not EXIF when they are in the pixels")
    void doesNotMistakePixelsForAnExifSegment(@TempDir Path temp) throws IOException {
        // A JPEG with no APP1 at all, whose scan data happens to contain the
        // marker bytes. Scanning for the pattern rather than parsing segments
        // would read the following bytes as a TIFF header.
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        jpeg.write(new byte[] {(byte) 0xFF, (byte) 0xD8});
        jpeg.write(new byte[] {(byte) 0xFF, (byte) 0xDA});   // start of scan
        jpeg.write(new byte[] {0, 8});                        // segment length
        jpeg.write(new byte[] {0, 0, 0, 0, 0, 0});
        jpeg.write(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        jpeg.write(new byte[] {'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 1, 18, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0});
        jpeg.write(new byte[] {(byte) 0xFF, (byte) 0xD9});
        Path file = Files.write(temp.resolve("pixels.jpg"), jpeg.toByteArray());

        assertEquals(0, ExifOrientation.degreesFor(file));
    }

    @Test
    @DisplayName("a PNG has no EXIF and is not worth opening for it")
    void ignoresFormatsWithoutExif(@TempDir Path temp) throws IOException {
        Path png = Files.write(temp.resolve("shot.png"), new byte[] {(byte) 137, 'P', 'N', 'G'});
        assertEquals(0, ExifOrientation.degreesFor(png));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*ExifOrientationTest*'`
Expected: FAIL — `cannot find symbol: ExifOrientation`.

- [ ] **Step 3: Write minimal implementation**

```java
package mediacenter.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one EXIF field worth reading: which way up the photograph was taken.
 *
 * <p>JavaFX ignores it, so a portrait photograph from any telephone is displayed
 * on its side. A library would answer this in a line, but the production image
 * resolves no dependencies, so the tag is read here — the orientation field
 * alone, not a general EXIF reader.
 *
 * <p>Opens the file. Call it from a background thread and never from the JavaFX
 * one: over a network share this is a round trip.
 */
public final class ExifOrientation {

    private static final Logger LOG = Logger.getLogger(ExifOrientation.class.getName());

    /** Enough for the APP1 segment; the pixels beyond it are of no interest. */
    private static final int HEADER_BYTES = 64 * 1024;

    private static final int ORIENTATION_TAG = 0x0112;
    private static final int TYPE_SHORT = 3;

    private ExifOrientation() {
    }

    /** Clockwise rotation in degrees that puts the photograph the right way up. */
    public static int degreesFor(Path photo) {
        if (photo == null || !isJpeg(photo)) {
            return 0;
        }
        try (InputStream in = Files.newInputStream(photo)) {
            return degreesIn(in.readNBytes(HEADER_BYTES));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not read the orientation of " + photo, e);
            return 0;
        }
    }

    private static boolean isJpeg(Path photo) {
        String extension = photo.getFileName() == null
                ? "" : VideoFiles.extensionOf(photo.getFileName().toString());
        return extension.equals("jpg") || extension.equals("jpeg");
    }

    private static int degreesIn(byte[] header) {
        int exif = exifSegmentStart(header);
        if (exif < 0) {
            return 0;
        }
        int tiff = exif + 6;
        if (tiff + 8 > header.length) {
            return 0;
        }
        ByteBuffer buffer = ByteBuffer.wrap(header);
        char byteOrder = (char) header[tiff];
        if (byteOrder != 'M' && byteOrder != 'I') {
            return 0;
        }
        buffer.order(byteOrder == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);

        int ifdOffset = buffer.getInt(tiff + 4);
        // Checked rather than caught: a corrupt offset can be negative or enormous.
        if (ifdOffset < 8 || tiff + ifdOffset + 2 > header.length) {
            return 0;
        }
        int ifd = tiff + ifdOffset;
        int entries = buffer.getShort(ifd) & 0xFFFF;
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > header.length) {
                return 0;
            }
            if ((buffer.getShort(entry) & 0xFFFF) == ORIENTATION_TAG
                    && (buffer.getShort(entry + 2) & 0xFFFF) == TYPE_SHORT) {
                return degreesForTagValue(buffer.getShort(entry + 8) & 0xFFFF);
            }
        }
        return 0;
    }

    /**
     * Walks the JPEG's segments to find APP1, rather than searching the bytes for
     * "Exif\0\0" — those six bytes can occur in the pixels, and reading whatever
     * follows them as a TIFF header produces confident nonsense.
     */
    private static int exifSegmentStart(byte[] header) {
        if (header.length < 4 || (header[0] & 0xFF) != 0xFF || (header[1] & 0xFF) != 0xD8) {
            return -1;
        }
        int position = 2;
        while (position + 4 <= header.length) {
            if ((header[position] & 0xFF) != 0xFF) {
                return -1;
            }
            int marker = header[position + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                // Start of scan or end of image: no more metadata segments.
                return -1;
            }
            int length = ((header[position + 2] & 0xFF) << 8) | (header[position + 3] & 0xFF);
            if (length < 2) {
                return -1;
            }
            int payload = position + 4;
            if (marker == 0xE1 && payload + 6 <= header.length
                    && header[payload] == 'E' && header[payload + 1] == 'x'
                    && header[payload + 2] == 'i' && header[payload + 3] == 'f'
                    && header[payload + 4] == 0 && header[payload + 5] == 0) {
                return payload;
            }
            position += 2 + length;
        }
        return -1;
    }

    /**
     * Only the three rotations are honoured. The mirrored orientations exist but
     * are vanishingly rare, and guessing wrong about a flip is worse than leaving
     * the photograph as it was taken.
     */
    private static int degreesForTagValue(int orientation) {
        return switch (orientation) {
            case 3 -> 180;
            case 6 -> 90;
            case 8 -> 270;
            default -> 0;
        };
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*ExifOrientationTest*'`
Expected: PASS, four tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mediacenter/media/ExifOrientation.java src/test/java/mediacenter/media/ExifOrientationTest.java
git commit -m "Read which way up a photograph was taken"
```

---

### Task 6: Holding three photographs and no more

**Files:**
- Create: `src/main/java/mediacenter/ui/components/PhotoCache.java`
- Test: `src/test/java/mediacenter/ui/components/PhotoCacheTest.java`

**Interfaces:**
- Produces:
  - `PhotoCache<T>` with `interface Loader<T> { T load(Path photo, double width, double height); }`
  - `PhotoCache(Loader<T> loader, Consumer<T> onEvict)`
  - `T PhotoCache.show(List<Path> photos, int index, List<Integer> neighbours, double width, double height)`
  - `void PhotoCache.clear()` — releases everything held, for leaving the viewer
  - `int PhotoCache.size()`
  - `static Loader<Image> PhotoCache.imageLoader()`, `static Consumer<Image> PhotoCache.imageDisposer()`

**A cost worth knowing before someone "simplifies" the key:** because the box
depends on the rotation of the photograph being shown, a neighbour prefetched
beside a portrait photograph is keyed to the swapped box and is evicted unused
when it is arrived at. That wastes one decode across an orientation change. It is
the price of never displaying an upscaled photograph, and keying on the path
alone would trade a visible fault for an invisible one.

**Three review findings shaped this signature.** It is **generic**, so the shipping
path never holds `Object` and eviction can call a typed method. It takes an
**`onEvict`** hook, because a background-loading `Image` that is merely dropped
from a map keeps decoding — hold an arrow key down and dozens of full-screen
decodes run at once. And it takes the **neighbour indices** rather than computing
`index ± 1`, because at either end of a looping slideshow the neighbour wraps,
and a cache that does not know that makes every wrap a cold decode.

- [ ] **Step 1: Write the failing test**

```java
package mediacenter.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PhotoCacheTest {

    private static List<Path> photos(int count) {
        List<Path> paths = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            paths.add(Path.of("/photos/" + i + ".jpg"));
        }
        return paths;
    }

    private static PhotoCache<String> cacheOf(List<String> evicted) {
        return new PhotoCache<>((photo, width, height) -> photo.toString(), evicted::add);
    }

    @Test
    @DisplayName("the picture on screen and its two neighbours are kept, nothing else")
    void keepsOnlyTheNeighbourhood() {
        PhotoCache<String> cache = cacheOf(new ArrayList<>());

        cache.show(photos(10), 5, List.of(4, 6), 1920, 1080);

        assertEquals(3, cache.size());
    }

    @Test
    @DisplayName("what is no longer next to the viewer is let go of, not merely forgotten")
    void disposesOfWhatItEvicts() {
        List<String> evicted = new ArrayList<>();
        PhotoCache<String> cache = cacheOf(evicted);
        List<Path> photos = photos(10);

        cache.show(photos, 5, List.of(4, 6), 1920, 1080);
        cache.show(photos, 8, List.of(7, 9), 1920, 1080);

        assertEquals(3, cache.size());
        assertTrue(evicted.contains(photos.get(5).toString()),
                "expected the old middle to be disposed: " + evicted);
    }

    @Test
    @DisplayName("a photograph already decoded is not decoded again")
    void reusesWhatItAlreadyHas() {
        List<Path> loaded = new ArrayList<>();
        PhotoCache<String> cache = new PhotoCache<>((photo, width, height) -> {
            loaded.add(photo);
            return photo.toString();
        }, evicted -> { });
        List<Path> photos = photos(10);

        String first = cache.show(photos, 5, List.of(4, 6), 1920, 1080);
        String again = cache.show(photos, 5, List.of(4, 6), 1920, 1080);

        assertSame(first, again);
        assertEquals(3, loaded.size(), "the neighbourhood is loaded once: " + loaded);
    }

    @Test
    @DisplayName("a wrapping run prefetches the far end, which is genuinely next")
    void prefetchesAcrossTheWrap() {
        PhotoCache<String> cache = cacheOf(new ArrayList<>());
        List<Path> photos = photos(5);

        cache.show(photos, 0, List.of(4, 1), 1920, 1080);

        assertEquals(3, cache.size());
    }

    @Test
    @DisplayName("the ends of a run that does not wrap have one neighbour, not two")
    void copesWithTheEnds() {
        PhotoCache<String> cache = cacheOf(new ArrayList<>());
        List<Path> photos = photos(3);

        // Compared against the path's own toString: a separator is not the same
        // character on the machine this suite also runs on.
        assertEquals(photos.get(0).toString(), cache.show(photos, 0, List.of(1), 1920, 1080));
        assertEquals(2, cache.size());
    }

    @Test
    void aSinglePhotographIsItsOwnNeighbourhood() {
        PhotoCache<String> cache = cacheOf(new ArrayList<>());

        cache.show(photos(1), 0, List.of(), 1920, 1080);

        assertEquals(1, cache.size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*PhotoCacheTest*'`
Expected: FAIL — `cannot find symbol: PhotoCache`.

- [ ] **Step 3: Write minimal implementation**

```java
package mediacenter.ui.components;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.scene.image.Image;

/**
 * The photograph on screen and its neighbours, and nothing else.
 *
 * <p>Deliberately not an LRU. A full-screen photograph is around eight megabytes
 * decoded, and the machine this runs on is an old laptop: a cache that grows
 * would exhaust it within a folder.
 *
 * <p>Each is requested at the size it will be shown at, so JavaFX scales during
 * decode and a twenty-four megapixel photograph never exists at full size.
 *
 * <p>Generic over what is held so that the retention policy can be tested: a
 * JavaFX {@link Image} cannot be built without a toolkit, and the tests have
 * none. The shipping path is {@code PhotoCache<Image>}, so nothing downcasts.
 */
public final class PhotoCache<T> {

    /** Builds whatever the view will display, at the size asked for. */
    @FunctionalInterface
    public interface Loader<T> {
        T load(Path photo, double width, double height);
    }

    private final Loader<T> loader;
    private final Consumer<T> onEvict;
    /**
     * Keyed on the size as well as the path, exactly as {@code ArtworkCache} is.
     * The box a photograph is decoded into depends on the rotation of whatever is
     * on screen, so a neighbour prefetched beside a portrait photograph would
     * otherwise be reused, upscaled, in a landscape box and never decoded again.
     */
    private final Map<String, T> held = new HashMap<>();

    private static String keyFor(Path photo, double width, double height) {
        return photo + "@" + Math.round(width) + "x" + Math.round(height);
    }

    public PhotoCache(Loader<T> loader, Consumer<T> onEvict) {
        this.loader = loader;
        this.onEvict = onEvict;
    }

    /** The real thing: a background-loading, downscaled JavaFX image. */
    public static Loader<Image> imageLoader() {
        return (photo, width, height) ->
                new Image(photo.toUri().toString(), width, height, true, true, true);
    }

    /**
     * A dropped image goes on decoding unless it is told not to, and holding an
     * arrow key down would otherwise put a dozen full-screen decodes in flight.
     */
    public static Consumer<Image> imageDisposer() {
        return Image::cancel;
    }

    /**
     * Returns the photograph at {@code index}, having made sure its neighbours are
     * on their way and everything else has been let go.
     *
     * @param neighbours indices to prefetch — the viewer knows whether the run
     *                   wraps, and this class does not
     */
    public T show(List<Path> photos, int index, List<Integer> neighbours, double width, double height) {
        Map<String, Path> wanted = new LinkedHashMap<>();
        wanted.put(keyFor(photos.get(index), width, height), photos.get(index));
        for (int neighbour : neighbours) {
            if (neighbour >= 0 && neighbour < photos.size()) {
                Path photo = photos.get(neighbour);
                wanted.putIfAbsent(keyFor(photo, width, height), photo);
            }
        }
        held.entrySet().removeIf(entry -> {
            if (wanted.containsKey(entry.getKey())) {
                return false;
            }
            onEvict.accept(entry.getValue());
            return true;
        });
        wanted.forEach((key, photo) -> held.computeIfAbsent(key, ignored -> loader.load(photo, width, height)));
        return held.get(keyFor(photos.get(index), width, height));
    }

    /** Everything held is released; for leaving the viewer. */
    public void clear() {
        held.values().forEach(onEvict);
        held.clear();
    }

    /** How many photographs are held; for tests. */
    public int size() {
        return held.size();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*PhotoCacheTest*'`
Expected: PASS, six tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mediacenter/ui/components/PhotoCache.java src/test/java/mediacenter/ui/components/PhotoCacheTest.java
git commit -m "Hold the photograph on screen and its neighbours"
```

---

### Task 7: The rules for moving through a run

**Files:**
- Create: `src/main/java/mediacenter/ui/PhotoRun.java`
- Test: `src/test/java/mediacenter/ui/PhotoRunTest.java`

**Interfaces:**
- Produces (all package-private, `mediacenter.ui`):
  - `PhotoRun(boolean looping)`
  - `void add(List<Path> batch)`, `void markComplete()`, `void markTruncated()`
  - `boolean complete()`, `boolean isEmpty()`, `int size()`
  - `Path get(int index)`, `List<Path> photosView()`
  - `int next(int from)`, `int previous(int from)`, `List<Integer> neighbours(int from)`
  - `String counterText(int index)`
  - `int indexOf(Path photo)` — `-1` when not collected yet

Every rule worth testing lives here, with no JavaFX in the file, so it can be
tested without a toolkit.

- [ ] **Step 1: Write the failing test**

```java
package mediacenter.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PhotoRunTest {

    private static List<Path> photos(String... names) {
        return java.util.Arrays.stream(names).map(Path::of).toList();
    }

    private static PhotoRun runOf(boolean looping, String... names) {
        PhotoRun run = new PhotoRun(looping);
        run.add(photos(names));
        return run;
    }

    @Test
    @DisplayName("an unfinished run holds on the last picture rather than looping")
    void doesNotLoopWhileStillCollecting() {
        PhotoRun run = runOf(true, "a.jpg", "b.jpg");

        assertEquals(1, run.next(1), "the end of an incomplete run stays where it is");
    }

    @Test
    @DisplayName("a finished run loops back to the beginning")
    void loopsOnceComplete() {
        PhotoRun run = runOf(true, "a.jpg", "b.jpg");
        run.markComplete();

        assertEquals(0, run.next(1));
        assertEquals(1, run.previous(0));
    }

    @Test
    @DisplayName("looking at one folder stops at either end instead of wrapping")
    void aSingleFolderRunStopsAtTheEnds() {
        PhotoRun run = runOf(false, "a.jpg", "b.jpg");
        run.markComplete();

        assertEquals(1, run.next(1));
        assertEquals(0, run.previous(0));
    }

    @Test
    @DisplayName("a truncated walk loops over what it has, without claiming that is all")
    void aTruncatedWalkLoopsButDoesNotClaimATotal() {
        PhotoRun run = runOf(true, "a.jpg", "b.jpg");
        run.markTruncated();
        run.markComplete();

        // Freezing on the last of five thousand would be worse than looping them.
        assertEquals(0, run.next(1));
        assertEquals("2 of 2+", run.counterText(1));
    }

    @Test
    @DisplayName("the counter admits when it does not yet know the total")
    void countsHonestly() {
        PhotoRun run = runOf(true, "a.jpg", "b.jpg", "c.jpg");

        assertEquals("2 of 3+", run.counterText(1));

        run.markComplete();
        assertEquals("2 of 3", run.counterText(1));
    }

    @Test
    void movesForwardAndBackInTheMiddle() {
        PhotoRun run = runOf(true, "a.jpg", "b.jpg", "c.jpg");

        assertEquals(2, run.next(1));
        assertEquals(0, run.previous(1));
    }

    @Test
    @DisplayName("neighbours wrap when the run does, so a wrap is not a cold decode")
    void reportsNeighboursForPrefetching() {
        PhotoRun looping = runOf(true, "a.jpg", "b.jpg", "c.jpg");
        looping.markComplete();
        assertEquals(List.of(2, 1), looping.neighbours(0));

        PhotoRun stopping = runOf(false, "a.jpg", "b.jpg", "c.jpg");
        stopping.markComplete();
        assertEquals(List.of(0, 1), stopping.neighbours(0).stream().distinct().sorted().toList());
    }

    @Test
    @DisplayName("an empty run answers without throwing")
    void survivesAnEmptyRun() {
        PhotoRun run = new PhotoRun(true);
        run.markComplete();

        assertTrue(run.isEmpty());
        assertEquals(0, run.next(0));
        assertEquals(0, run.previous(0));
        assertEquals("", run.counterText(0));
        assertEquals(List.of(), run.neighbours(0));
    }

    @Test
    void findsAPhotographOnceItHasBeenCollected() {
        PhotoRun run = runOf(true, "a.jpg", "b.jpg");

        assertEquals(1, run.indexOf(Path.of("b.jpg")));
        assertEquals(-1, run.indexOf(Path.of("nowhere.jpg")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*PhotoRunTest*'`
Expected: FAIL — `cannot find symbol: PhotoRun`.

- [ ] **Step 3: Write minimal implementation**

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*PhotoRunTest*'`
Expected: PASS, nine tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mediacenter/ui/PhotoRun.java src/test/java/mediacenter/ui/PhotoRunTest.java
git commit -m "Define how a viewing moves through its photographs"
```

---

### Task 8: A page that wants the whole screen, and knows when it leaves

**Files:**
- Modify: `src/main/java/mediacenter/ui/View.java`
- Modify: `src/main/java/mediacenter/ui/MediaCenterShell.java`

**Interfaces:**
- Produces: `View.fullBleed()` → `boolean`, default `false`; `View.onHidden()` → default no-op, called when a page leaves the stack.

**Why `onHidden` is here and not invented later:** the viewer runs a timer thread
and a background walk. `goBack()` merely pops the stack, so without a hook both
keep running against a detached scene, swapping images and holding three
full-screen bitmaps for every slideshow ever opened.

- [ ] **Step 1: Add both to the contract**

In `View.java`:

```java
    /**
     * Whether this page wants the screen to itself, without the header and the
     * hint bar. A photograph fills the screen; everything else is furniture
     * around it.
     */
    default boolean fullBleed() {
        return false;
    }

    /**
     * Called when this page leaves the stack for good. A page that started a
     * thread or a walk stops it here — nothing else will.
     */
    default void onHidden() {
        // Most pages hold nothing that outlives them.
    }
```

- [ ] **Step 2: Honour the flag in the shell**

In `MediaCenterShell.showCurrentView(Direction)`, after `frame.setCenter(view.node())`:

```java
        // A full-bleed page takes the header and the hint bar with it, and gives
        // them back when it leaves.
        boolean chrome = !view.fullBleed();
        frame.getTop().setVisible(chrome);
        frame.getTop().setManaged(chrome);
        frame.getBottom().setVisible(chrome);
        frame.getBottom().setManaged(chrome);
```

- [ ] **Step 3: Call `onHidden` wherever a page is popped**

In `MediaCenterShell.goBack()`, the popped view must be told:

```java
        View leaving = viewStack.pop();
        leaving.onHidden();
```

Do the same anywhere else the stack is unwound — search for `viewStack.pop()` and
for the home-screen shortcut that clears the stack, and call `onHidden()` on
every view removed.

- [ ] **Step 4: Verify nothing else changed**

Run: `./gradlew test`
Expected: PASS, unchanged count.

Run: `./gradlew run --args="--snapshot=/tmp/home-chrome.png"`
Expected: the home screen still has its title and hint bar — no view returns
`true` yet. **Look at the image.**

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mediacenter/ui/View.java src/main/java/mediacenter/ui/MediaCenterShell.java
git commit -m "Let a page take the whole screen, and know when it leaves"
```

---

### Task 9: How long each photograph stays

**Files:**
- Modify: `src/main/java/mediacenter/config/ApplicationSettings.java`
- Modify: `src/main/java/mediacenter/config/SettingsStore.java`
- Modify: `src/main/java/mediacenter/ui/SettingsView.java`
- Test: `src/test/java/mediacenter/config/ApplicationSettingsTest.java`
- Test: `src/test/java/mediacenter/config/SettingsStoreTest.java`

**Interfaces:**
- Produces: `ApplicationSettings.slideshowSeconds()` → `int`, `withSlideshowSeconds(int)`. JSON key `slideshowSeconds`.

**Adding a record component breaks every canonical-constructor call site.** They
are, exhaustively: `ApplicationSettings.defaults()`, every `withX` method in that
record, the construction in `SettingsStore`'s reader, and a direct
`new ApplicationSettings(...)` in `SettingsStoreTest`. All four must change in the
same step or the build is red.

- [ ] **Step 1: Write the failing tests**

Add to `ApplicationSettingsTest` — it has no `import org.junit.jupiter.api.DisplayName;`
yet, so add that too:

```java
    @Test
    @DisplayName("an interval nobody could watch is brought back to something usable")
    void clampsTheSlideshowInterval() {
        assertEquals(5, ApplicationSettings.defaults().slideshowSeconds());
        assertEquals(2, ApplicationSettings.defaults().withSlideshowSeconds(0).slideshowSeconds());
        assertEquals(60, ApplicationSettings.defaults().withSlideshowSeconds(3600).slideshowSeconds());
    }

    @Test
    @DisplayName("changing one setting leaves the interval alone")
    void carriesTheIntervalThroughOtherChanges() {
        ApplicationSettings settings = ApplicationSettings.defaults().withSlideshowSeconds(9);

        assertEquals(9, settings.withFullScreen(false).slideshowSeconds());
        assertEquals(9, settings.withTheme(Theme.LIGHT).slideshowSeconds());
    }
```

Add to `SettingsStoreTest`, alongside its existing round-trip test:

```java
    @Test
    @DisplayName("the interval survives being written and read back")
    void roundTripsTheSlideshowInterval(@TempDir Path temp) {
        SettingsStore store = new SettingsStore(temp);
        store.save(ApplicationSettings.defaults().withSlideshowSeconds(9));

        assertEquals(9, store.load().slideshowSeconds());
    }

    @Test
    @DisplayName("a configuration written before slideshows still loads")
    void defaultsTheIntervalWhenTheKeyIsAbsent(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("config.json"), "{\"fullScreen\": true, \"theme\": \"DARK\"}");

        assertEquals(5, new SettingsStore(temp).load().slideshowSeconds());
    }
```

Adjust the two tests to whatever `SettingsStore`'s actual constructor and
save/load method names are — read the class first — but keep both behaviours.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*ApplicationSettingsTest*' --tests '*SettingsStoreTest*'`
Expected: FAIL — `cannot find symbol: slideshowSeconds`.

- [ ] **Step 3: Implement, touching every call site**

In `ApplicationSettings`:

1. Add `int slideshowSeconds` as the **last** record component.
2. In the compact constructor, clamp it:

```java
        // Under two seconds nobody can take the picture in; over a minute the
        // screen looks stuck.
        slideshowSeconds = Math.clamp(slideshowSeconds, 2, 60);
```

3. Add:

```java
    public ApplicationSettings withSlideshowSeconds(int newSlideshowSeconds) {
        return new ApplicationSettings(vlcPath, browserPath, fullScreen, theme, mediaRoots, newSlideshowSeconds);
    }
```

4. Update `defaults()` to pass `5`, and **every existing** `withVlcPath`,
   `withBrowserPath`, `withFullScreen`, `withTheme`, `withMediaRoots` to carry
   `slideshowSeconds` through.

In `SettingsStore`: add `import mediacenter.json.JsonValue.JsonNumber;`, write the
key as `new JsonNumber(settings.slideshowSeconds())`, and read it as

```java
        int slideshowSeconds = document.longValue("slideshowSeconds").orElse(5L).intValue();
```

passing it as the sixth constructor argument. `JsonValue` has no `intValue`, hence
the `longValue(...).intValue()`.

In `SettingsStoreTest`, update the direct `new ApplicationSettings(...)` call to
pass a sixth argument.

In `SettingsView`, add a row **between the theme row and the media-roots
section**, because `themeRow()` and `mediaRootsSection()` each append to
`navigationRows` as a side effect and the arrow order follows that order. Model it
on the theme row — two `ToggleButton`s in a `ToggleGroup` offering `5s` and `10s`,
which is a 10-foot control needing no typing:

```java
    private Node slideshowRow() {
        Label name = new Label("Slideshow");
        name.getStyleClass().add("setting-name");
        name.setMinWidth(320);

        ToggleGroup group = new ToggleGroup();
        List<Node> toggles = new ArrayList<>();
        for (int seconds : List.of(5, 10)) {
            ToggleButton toggle = new ToggleButton(seconds + "s");
            toggle.setToggleGroup(group);
            toggle.setUserData(seconds);
            toggle.setOnAction(event -> {
                // A toggle group lets a second click clear the selection, which
                // would leave the row showing no interval at all.
                toggle.setSelected(true);
                update(settings().withSlideshowSeconds(seconds));
            });
            slideshowToggles.add(toggle);
            toggles.add(toggle);
        }
        navigationRows.add(List.copyOf(toggles));

        HBox controls = new HBox(12);
        controls.getChildren().addAll(toggles);
        controls.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(controls, Priority.ALWAYS);
        controls.setMaxWidth(Double.MAX_VALUE);

        HBox line = new HBox(16, name, controls);
        line.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, line);
        card.getStyleClass().add("setting-row");
        return card;
    }
```

Read `themeRow()` before writing this and follow whatever it actually does — the
sketch above is the shape, not a transcription.

Keep a `private final List<ToggleButton> slideshowToggles = new ArrayList<>();`
field. In `readSettings()`, select the matching button — note that the theme
row's `getUserData() == settings.theme()` pattern cannot be copied here, because
the user data is an `Integer` and the setting is an `int`, which will not compile
as a reference comparison:

```java
        Integer configured = settings.slideshowSeconds();
        for (ToggleButton toggle : slideshowToggles) {
            toggle.setSelected(configured.equals(toggle.getUserData()));
        }
``` A hand-edited `config.json` may hold a value the buttons do
not offer — `30` is legal, since the record clamps to `[2, 60]` — in which case
**no toggle is selected**, which is honest: the row still takes the keyboard, and
pressing either button adopts that value.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test`
Expected: PASS, whole suite.

- [ ] **Step 5: Verify the Settings screen by screenshot**

```bash
./gradlew run --args="--snapshot=/tmp/settings.png --snapshot-keys=RIGHT,RIGHT,RIGHT,ENTER,DOWN,DOWN,DOWN,DOWN"
```

Expected: the Slideshow row is present between Theme and Media folders, and four
Downs land on it. **Look at the image** — the row order determines what each Down
reaches, and this task changes it.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/mediacenter/config src/main/java/mediacenter/ui/SettingsView.java src/test/java/mediacenter/config
git commit -m "Let the viewer choose how long each photograph stays"
```

**Why this comes before the viewer:** `PhotoView` reads `slideshowSeconds()`. With
the settings in place first, every task still ends with a green build and one
commit, which is the rule this plan works to.

---

### Task 10: The photograph viewer

**Files:**
- Create: `src/main/java/mediacenter/ui/PhotoView.java`
- Modify: `src/main/java/mediacenter/ui/Navigation.java`
- Modify: `src/main/java/mediacenter/ui/MediaCenterShell.java`
- Modify: `src/main/java/mediacenter/ui/components/Motion.java`
- Modify: `src/main/resources/mediacenter/ui/mediacenter.css`

**Interfaces:**
- Consumes: `PhotoRun`, `PhotoCache<Image>`, `PhotoWalker.collect`, `ExifOrientation.degreesFor`, `FxTasks`, `View.fullBleed/onHidden`.
- Produces:
  - `Navigation.openSlideshow(Path folder)`
  - `Navigation.openPhoto(Path folder, Path photo)` — **the path, not an index.** The grid's ordering and the walk's ordering are produced by different code; handing an index across that boundary was a defect in the first draft of this plan. The view seeks the path as batches arrive.
  - `PhotoView implements View`, `fullBleed()` → `true`.

**The whole class is given below** rather than described, because the first draft
of this plan described it and review found eight defects hiding in the prose.

- [ ] **Step 1: Add the navigation entry points**

In `Navigation.java`:

```java
    /** Runs every photograph beneath a folder as a slideshow. */
    void openSlideshow(Path folder);

    /** Opens one photograph, with the arrows moving through its own folder. */
    void openPhoto(Path folder, Path photo);
```

In `MediaCenterShell`:

```java
    @Override
    public void openSlideshow(Path folder) {
        push(new PhotoView(context, folder, true, null));
    }

    @Override
    public void openPhoto(Path folder, Path photo) {
        push(new PhotoView(context, folder, false, photo));
    }
```

- [ ] **Step 2: Write the view**

```java
package mediacenter.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;

import java.util.HashMap;
import java.util.Map;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.util.Duration;

import mediacenter.media.ExifOrientation;
import mediacenter.media.MediaAccessException;
import mediacenter.media.PhotoWalker;
import mediacenter.ui.components.ActivationGate;
import mediacenter.ui.components.Motion;
import mediacenter.ui.components.PhotoCache;

/**
 * Photographs, full screen.
 *
 * <p>Two viewings share this class: a slideshow of everything beneath a folder,
 * advancing on its own and looping; and one photograph with the arrows moving
 * through its own folder. The difference is entirely in what is collected and in
 * {@link PhotoRun}'s wrapping rule.
 */
final class PhotoView implements View {

    private static final Logger LOG = Logger.getLogger(PhotoView.class.getName());

    /**
     * A ceiling on one viewing. Large enough for any real shelf of holidays,
     * small enough that a wrong turn into a system directory cannot collect for
     * ever. Reaching it leaves the run incomplete, so the counter keeps its "+".
     */
    private static final int PHOTO_LIMIT = 5_000;

    /** Where a photograph goes before the window has been laid out. */
    private static final double FALLBACK_SIZE = 1920;

    private final UiContext context;
    private final Path folder;
    private final boolean slideshow;
    private final Path startAt;

    private final StackPane root = new StackPane();
    private final ImageView imageView = new ImageView();
    private final Label overlay = new Label();
    private final PhotoRun run;
    private final PhotoCache<Image> cache =
            new PhotoCache<>(PhotoCache.imageLoader(), PhotoCache.imageDisposer());

    private final ActivationGate activationGate = new ActivationGate();
    /** Orientation is read from disk, so it is remembered rather than re-read on every repaint. */
    private final Map<Path, Integer> orientations = new HashMap<>();
    private FadeTransition captionFade;
    /** A resize is acted on once it has stopped, not while it is happening. */
    private final PauseTransition resizeSettled = new PauseTransition(Duration.millis(250));

    private int index;
    private boolean showingSomething;
    private boolean seekDone;
    private boolean showingFailure;
    private int failuresSinceLastSuccess;
    private double lastPaintedWidth;
    private double lastPaintedHeight;

    /** Set from the JavaFX thread, read by the timer thread. */
    private volatile boolean cancelled;
    private volatile boolean advancing;
    private final AtomicLong nextAdvanceAt = new AtomicLong(Long.MAX_VALUE);

    PhotoView(UiContext context, Path folder, boolean slideshow, Path startAt) {
        this.context = context;
        this.folder = folder;
        this.slideshow = slideshow;
        this.startAt = startAt;
        this.run = new PhotoRun(slideshow);

        root.getStyleClass().add("photo-view");
        root.setAlignment(Pos.CENTER);
        // Without this the arrow keys never arrive: an event filter only sees
        // events aimed at this node or below it.
        root.setFocusTraversable(true);

        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);

        overlay.getStyleClass().add("photo-overlay");
        overlay.setVisible(false);
        StackPane.setAlignment(overlay, Pos.BOTTOM_LEFT);

        root.getChildren().addAll(imageView, overlay);
        root.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);
        root.addEventFilter(KeyEvent.KEY_RELEASED, event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER
                    || event.getCode() == javafx.scene.input.KeyCode.SPACE) {
                activationGate.released();
            }
        });

        // The scene does not exist yet — this view is constructed before it is put
        // into the frame — so the first sizing waits for the pane to be laid out.
        // Debounced, not merely de-duplicated: width and height fire separately
        // and a drag changes the size on every pulse, so reacting to each one
        // would clear the cache and start three fresh decodes many times a second.
        resizeSettled.setOnFinished(event -> {
            if (cancelled) {
                return;
            }
            if (showingSomething && sizeChanged()) {
                cache.clear();
                display();
            }
        });
        InvalidationListener resize = observable -> resizeSettled.playFromStart();
        root.widthProperty().addListener(resize);
        root.heightProperty().addListener(resize);

        startWalk();
        if (slideshow) {
            startTimer();
        }
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public String title() {
        Path name = folder.getFileName();
        return name == null ? folder.toString() : name.toString();
    }

    @Override
    public boolean fullBleed() {
        return true;
    }

    @Override
    public void focusSelection() {
        root.requestFocus();
    }

    @Override
    public void onHidden() {
        // Nothing else stops these: the shell only pops the stack. The walk reads
        // this flag through the supplier it was given, so it stops between
        // directories rather than running on against a page nobody is looking at.
        cancelled = true;
        advancing = false;
        // The two asynchronous things here that no flag guards: a pending debounce
        // would settle after Esc and repaint a dead page, filling a cache that
        // will never be cleared again, and a caption fade would go on running
        // against a detached label.
        resizeSettled.stop();
        if (captionFade != null) {
            captionFade.stop();
        }
        cache.clear();
    }

    // -- collecting ---------------------------------------------------------

    private void startWalk() {
        context.backgroundExecutor().execute(() -> {
            try {
                PhotoWalker.Walk walk = PhotoWalker.collect(folder, slideshow, PHOTO_LIMIT,
                        () -> cancelled, batch -> Platform.runLater(() -> onBatch(batch)));
                Platform.runLater(() -> {
                    if (cancelled) {
                        return;
                    }
                    if (walk.truncated()) {
                        run.markTruncated();
                    }
                    run.markComplete();
                    if (run.isEmpty()) {
                        // A black screen with no explanation is the worst outcome
                        // here: nothing on it would say that Esc is the way out.
                        showCaption("No photographs here.");
                    } else {
                        if (startAt != null && !seekDone) {
                            // The grid offered a photograph the walk never found.
                            // The two are meant to agree; if they ever do not, say
                            // so in the log rather than silently showing another.
                            LOG.warning("Not among the photographs collected: " + startAt);
                        }
                        refreshOverlay();
                    }
                });
            } catch (MediaAccessException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not collect photographs in " + folder, e);
                Platform.runLater(() -> {
                    if (cancelled) {
                        return;
                    }
                    run.markComplete();
                    if (!showingSomething) {
                        showCaption("These photographs are not available.");
                    }
                });
            }
        });
    }

    /** On the JavaFX thread: the run is only ever mutated here. */
    private void onBatch(List<Path> batch) {
        if (cancelled) {
            return;
        }
        run.add(batch);
        if (!showingSomething) {
            // The photograph that was activated, if it has been collected yet.
            int wanted = startAt == null ? -1 : run.indexOf(startAt);
            seekDone = startAt == null || wanted >= 0;
            show(Math.max(wanted, 0));
        } else if (!seekDone) {
            // Seek once and once only. Re-seeking on every batch would drag the
            // viewer back to where they started after they had arrowed away.
            int wanted = run.indexOf(startAt);
            if (wanted >= 0) {
                seekDone = true;
                show(wanted);
            }
        } else {
            // Deliberately not refreshOverlay(): the caption belongs to a change
            // of photograph, and re-showing it for every directory the walk
            // finishes would leave it up for the whole walk.
            updateCounterIfShowing();
        }
    }

    /** Keeps the count honest as the walk grows, without re-showing a faded caption. */
    private void updateCounterIfShowing() {
        // Never over a failure message: that one is the only thing on a black
        // screen, and replacing it with a file name would leave no explanation.
        if (overlay.isVisible() && !showingFailure && !run.isEmpty()) {
            Path name = run.get(index).getFileName();
            overlay.setText((name == null ? run.get(index).toString() : name.toString())
                    + "        " + run.counterText(index));
        }
    }

    // -- showing ------------------------------------------------------------

    private void show(int target) {
        if (run.isEmpty()) {
            return;
        }
        index = Math.floorMod(target, run.size());
        showingSomething = true;
        showingFailure = false;
        resetTimer();
        display();
        // display() can fail synchronously — a revisited photograph whose image is
        // cached and already in error reports it from inside paint() — and the
        // caption it puts up must survive.
        if (!showingFailure) {
            refreshOverlay();
        }
    }

    /**
     * Reading the orientation opens the file, so it happens on the background
     * executor. The photograph already on screen stays until the answer arrives,
     * which reads better than showing one sideways and snapping it upright.
     */
    private void display() {
        Path photo = run.get(index);
        Integer known = orientations.get(photo);
        if (known != null) {
            paint(photo, known);
            return;
        }
        int shownIndex = index;
        FxTasks.run(
                context.backgroundExecutor(),
                () -> ExifOrientation.degreesFor(photo),
                degrees -> {
                    orientations.put(photo, degrees);
                    if (!cancelled && shownIndex == index) {
                        paint(photo, degrees);
                    }
                },
                failure -> {
                    orientations.put(photo, 0);
                    if (!cancelled && shownIndex == index) {
                        paint(photo, 0);
                    }
                });
    }

    /** Whether the page has been given a different size since the last painting. */
    private boolean sizeChanged() {
        return Math.abs(root.getWidth() - lastPaintedWidth) >= 1
                || Math.abs(root.getHeight() - lastPaintedHeight) >= 1;
    }

    private void paint(Path photo, int degrees) {
        double width = root.getWidth() > 0 ? root.getWidth() : FALLBACK_SIZE;
        double height = root.getHeight() > 0 ? root.getHeight() : FALLBACK_SIZE;
        boolean quarterTurn = degrees == 90 || degrees == 270;

        // A rotation is applied after layout and does not re-lay-out, so a portrait
        // photograph has to be fitted into the box it will occupy once turned —
        // otherwise it is fitted landscape and then hangs off both sides.
        double fitWidth = quarterTurn ? height : width;
        double fitHeight = quarterTurn ? width : height;

        lastPaintedWidth = width;
        lastPaintedHeight = height;
        Image image = cache.show(run.photosView(), index, run.neighbours(index), fitWidth, fitHeight);
        imageView.setFitWidth(fitWidth);
        imageView.setFitHeight(fitHeight);
        imageView.setRotate(degrees);
        imageView.setImage(image);
        watchForFailure(image, photo);
    }

    /**
     * A background-loading image reports failure later, not when it is handed
     * over, so the answer has to be waited for rather than asked for.
     *
     * <p>The listener watches progress as well as error and removes itself from
     * both, exactly as {@code MediaTile.revealWhenLoaded} does: a listener
     * registered on one property is never invalidated by the other, so watching
     * only the error would leave the success path dead and the listener attached
     * to a cached image for as long as it lives.
     */
    private void watchForFailure(Image image, Path photo) {
        if (image.isError()) {
            onDecodeFailed(photo);
            return;
        }
        if (image.getProgress() >= 1.0) {
            // Already decoded — a cache hit, or a repaint after a resize.
            // Registering again would stack a listener per repaint on one image.
            failuresSinceLastSuccess = 0;
            return;
        }
        InvalidationListener[] listener = new InvalidationListener[1];
        listener[0] = observable -> {
            if (image.isError()) {
                stopListening(image, listener[0]);
                // Both tests are needed. Image.cancel() reports itself as an
                // error one pulse later, so an image dropped by a resize must not
                // be read as a failure — hence the identity test. And display()
                // is asynchronous while an orientation is still being read, so
                // the image on screen can belong to the previous photograph —
                // hence the path test, without which a resize during that gap
                // skips the photograph that was never even tried.
                if (!cancelled && photo.equals(run.get(index)) && imageView.getImage() == image) {
                    onDecodeFailed(photo);
                }
            } else if (image.getProgress() >= 1.0) {
                stopListening(image, listener[0]);
                failuresSinceLastSuccess = 0;
            }
        };
        image.progressProperty().addListener(listener[0]);
        image.errorProperty().addListener(listener[0]);
    }

    private static void stopListening(Image image, InvalidationListener listener) {
        image.progressProperty().removeListener(listener);
        image.errorProperty().removeListener(listener);
    }

    /**
     * A photograph that will not decode must not stop the show. The caption is
     * left up only when there is nowhere to advance to — the last of a run, or a
     * single photograph — because advancing immediately would replace it before
     * anyone could read it. Giving up after a full pass keeps a folder of broken
     * files from spinning for ever.
     */
    private void onDecodeFailed(Path photo) {
        LOG.log(Level.FINE, () -> "Could not decode " + photo);
        failuresSinceLastSuccess++;
        // Only once the run has ended does a full pass of failures mean anything.
        // While the walk continues, size() is whatever has been collected so far,
        // and one bad photograph in the first batch would end the show.
        if (run.complete() && failuresSinceLastSuccess >= Math.max(run.size(), 1)) {
            LOG.warning("None of these photographs could be shown");
            // Stopped, not merely reported: leaving the timer running would take
            // the next interval, fail again, and arrive back here for ever, at one
            // full-screen decode a time. The arrows still work.
            advancing = false;
            imageView.setImage(null);
            showingFailure = true;
            showCaption("These photographs could not be shown.");
            return;
        }
        int from = index;
        int onwards = run.next(from);
        if (onwards != from) {
            // Posted rather than called: a cached image that is already in error
            // reports it from inside paint(), and advancing straight away would
            // nest paint() inside paint() once per broken file.
            Platform.runLater(() -> {
                // Compared against the index that failed, not against where we are
                // going: an arrow pressed in this pulse has already moved the
                // viewer, and they must not be dragged onwards from it.
                if (!cancelled && index == from) {
                    show(onwards);
                }
            });
        } else {
            imageView.setImage(null);
            showingFailure = true;
            showCaption("This photograph could not be shown.");
        }
    }

    private void refreshOverlay() {
        if (run.isEmpty()) {
            return;
        }
        Path photo = run.get(index);
        Path name = photo.getFileName();
        showCaption((name == null ? photo.toString() : name.toString())
                + "        " + run.counterText(index));
    }

    /**
     * One transition, restarted. A new one per caption would leave several running
     * on the same label during a walk, and an older one finishing would hide a
     * caption a newer one had just put up.
     */
    private void showCaption(String text) {
        overlay.setText(text);
        overlay.setVisible(true);
        overlay.setOpacity(1);
        if (captionFade == null) {
            captionFade = Motion.fadeOutAfter(overlay);
        }
        captionFade.playFromStart();
    }

    // -- input --------------------------------------------------------------

    private void handleKey(KeyEvent event) {
        switch (event.getCode()) {
            case LEFT -> {
                show(run.previous(index));
                event.consume();
            }
            case RIGHT -> {
                show(run.next(index));
                event.consume();
            }
            case ENTER, SPACE -> {
                // A thumb resting on the remote opened this page; without the gate
                // the auto-repeat would toggle the show off before it had begun.
                if (slideshow && activationGate.pressed(System.nanoTime())) {
                    advancing = !advancing;
                    resetTimer();
                }
                event.consume();
            }
            default -> { }
        }
    }

    // -- advancing ----------------------------------------------------------

    /**
     * Timed on an ordinary daemon thread rather than an animation: an animation
     * stops with the rendering pulse, and a display that goes to sleep would stop
     * the slideshow with it. The thread owns nothing but the deadline; every
     * change to what is on screen happens on the JavaFX thread.
     */
    private void startTimer() {
        advancing = true;
        resetTimer();
        Thread timer = new Thread(() -> {
            while (!cancelled) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (advancing && System.currentTimeMillis() >= nextAdvanceAt.get()) {
                    Platform.runLater(this::advance);
                }
            }
        }, "photo-slideshow");
        timer.setDaemon(true);
        timer.start();
    }

    private void advance() {
        if (cancelled) {
            return;
        }
        if (run.isEmpty()) {
            // Nothing to advance to yet; wait out another interval rather than
            // being asked again on the next tick.
            resetTimer();
            return;
        }
        // Checked again here: an arrow pressed between the timer reading the
        // deadline and this running would otherwise be skipped straight past.
        if (System.currentTimeMillis() < nextAdvanceAt.get()) {
            return;
        }
        int onwards = run.next(index);
        if (onwards != index) {
            show(onwards);
        } else {
            // Nothing to advance to yet — wait out another interval rather than
            // spinning on the deadline.
            resetTimer();
        }
    }

    /** A manual move buys a full interval on the picture arrived at. */
    private void resetTimer() {
        nextAdvanceAt.set(System.currentTimeMillis()
                + context.settings().get().slideshowSeconds() * 1000L);
    }
}
```

- [ ] **Step 3: Add the fade-out helper and the styles**

`Motion` offers `fadeIn` and `slideFadeIn`; there is no fade-out. Add one, beside
them:

```java
    /** Holds a caption long enough to be read, then takes it away. */
    public static FadeTransition fadeOutAfter(Node node) {
        FadeTransition fade = new FadeTransition(GENTLE, node);
        fade.setDelay(Duration.seconds(3));
        fade.setFromValue(1);
        fade.setToValue(0);
        fade.setInterpolator(EASE);
        fade.setOnFinished(event -> node.setVisible(false));
        return fade;
    }
```

In `mediacenter.css`, add styles for the two new classes — a black page and a
legible caption over any photograph:

```css
.photo-view {
    -fx-background-color: black;
}

.photo-overlay {
    -fx-font-size: 26px;
    -fx-text-fill: white;
    -fx-background-color: rgba(0, 0, 0, 0.55);
    -fx-background-radius: 8;
    -fx-padding: 10 18 10 18;
}
```

These colours are deliberately literal rather than `-mc-*` variables: a
photograph is not themed, and a caption over one needs the same contrast in
either theme.

- [ ] **Step 4: Compile**

Run: `./gradlew test`
Expected: PASS. `PhotoView` has no test of its own — it is JavaFX. Its rules live
in `PhotoRun` and its retention in `PhotoCache`, both tested. It is exercised by
screenshot in the next task. `slideshowSeconds()` already exists, because the
settings task comes before this one.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mediacenter/ui src/main/resources/mediacenter/ui/mediacenter.css
git commit -m "Show photographs full screen, one viewing at a time"
```

---

### Task 11: The way in

**Files:**
- Modify: `src/main/java/mediacenter/ui/BrowseView.java`
- Modify: `src/main/java/mediacenter/ui/components/TileGrid.java`

**Interfaces:**
- Consumes: `PhotoWalker.hasPhotos(Path)`, `Navigation.openSlideshow(Path)`, `Navigation.openPhoto(Path, Path)`, `ActionTile`.

- [ ] **Step 1: Offer the tile, guarded like every other late result**

`BrowseView` already guards asynchronous results with `loadGeneration`. The probe
must do the same, or an F5 — or a theme change, which refreshes every stacked
view — lets a stale answer repopulate the grid.

`showItems(List<MediaItem>)` is where tiles are set. Note that `generation` is a
**local variable of `load()`** and is not in scope there — read the field
instead. Also note `showItems` returns early when the folder is empty, so the
call has to go in both paths: a folder whose own entries are all hidden may still
have photographs several levels below it.

```java
    private void showItems(List<MediaItem> scanned) {
        items = scanned;
        long generation = loadGeneration;
        if (items.isEmpty()) {
            grid.clear();
            grid.showMessage("This folder has nothing to show.");
            offerSlideshow(List.of(), generation);
            return;
        }
        List<Tile> tiles = new ArrayList<>(items.size());
        for (MediaItem item : items) {
            tiles.add(new MediaTile(item, shape, context.artworkCache(), context.settings().get().theme()));
        }
        grid.setTiles(tiles);
        grid.focusSelection();
        offerSlideshow(tiles, generation);
    }
```

and add:

```java
    /**
     * The Slideshow tile only makes sense where there are photographs, and finding
     * that out means walking the tree — so the grid is filled first and the tile
     * appears a moment later, rather than the folder waiting on the answer.
     */
    private void offerSlideshow(List<Tile> tiles, long generation) {
        FxTasks.run(
                context.backgroundExecutor(),
                () -> PhotoWalker.hasPhotos(folder),
                hasPhotos -> {
                    if (!hasPhotos || generation != loadGeneration) {
                        return;
                    }
                    int selected = grid.selectedIndex();
                    grid.showMessage(null);
                    List<Tile> withSlideshow = new ArrayList<>();
                    withSlideshow.add(new ActionTile("▣", "Slideshow", "Every photograph, including subfolders"));
                    withSlideshow.addAll(tiles);
                    grid.setTiles(withSlideshow);
                    // Everything shifted by one; the selection must shift with it,
                    // or the highlight silently lands on a different picture.
                    grid.setSelectedIndex(selected < 0 ? 0 : selected + 1);
                    // setTiles takes the focus owner out of the scene, and
                    // setSelectedIndex deliberately does not put it back. Without
                    // this the highlight vanishes and the arrows go dead.
                    grid.focusSelection();
                },
                failure -> { });
    }
```

- [ ] **Step 2: Let the grid size itself from all its tiles, not just the first**

`TileGrid.setTiles` takes the cell size from `tiles.getFirst()`. That was safe
while every tile in a grid was the same kind. It is not any more: an `ActionTile`
is 280 × 210 and a poster `MediaTile` is 210 × 407 and pins itself with
`setMinSize`/`setMaxSize`, so a first tile of the wrong kind makes every poster
overflow its cell.

```java
        if (!tiles.isEmpty()) {
            // The largest, not the first: a grid may hold an action tile beside
            // media tiles, and a cell sized for the smaller clips the larger.
            double widest = tiles.stream().mapToDouble(Tile::getPrefWidth).max().orElse(0);
            double tallest = tiles.stream().mapToDouble(Tile::getPrefHeight).max().orElse(0);
            tilePane.setPrefTileWidth(widest);
            tilePane.setPrefTileHeight(tallest);
        }
```

- [ ] **Step 3: Handle activation**

In `BrowseView.activate(Tile tile)`, before the `instanceof MediaTile` check:

```java
        if (tile instanceof ActionTile) {
            context.navigation().openSlideshow(folder);
            return;
        }
```

and in the branch that currently sends a file to VLC:

```java
        if (item.isImage()) {
            // The path, not a position: this grid and the walk that fills the
            // viewer are ordered by different code, and an index would drift.
            context.navigation().openPhoto(folder, item.path());
            return;
        }
```

Add the imports `mediacenter.ui.components.ActionTile` and
`mediacenter.media.PhotoWalker`. `ArrayList` is already imported and `FxTasks`
needs no import — it is in this package.

- [ ] **Step 4: Verify by screenshot**

Put a few `.jpg` files into a folder under your configured media root, including
at least one in a subfolder, then:

```bash
./gradlew run --args="--snapshot=/tmp/photos-grid.png --snapshot-keys=RIGHT,ENTER,ENTER"
```

Expected: the folder's grid shows a **Slideshow** tile first, then the
photographs as their own thumbnails.

```bash
./gradlew run --args="--snapshot=/tmp/photos-show.png --snapshot-keys=RIGHT,ENTER,ENTER,ENTER"
```

Expected: a full-screen photograph on black with **no header and no hint bar**.
The caption may or may not have faded — `SceneSnapshot`'s settle and the
caption's delay are both three seconds, so the capture lands on the fade. Judge
the photograph and the missing chrome here; the caption is checked by the third
capture below, which arrives fresh after a key press.

```bash
./gradlew run --args="--snapshot=/tmp/photos-right.png --snapshot-keys=RIGHT,ENTER,ENTER,ENTER,RIGHT"
```

Expected: the *second* photograph, proving the arrow keys reach the view.

**Look at all three images.** A blank frame means the viewer failed to launch; an
unchanged frame in the third means the key filter is not receiving events.

- [ ] **Step 5: Run the whole suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/mediacenter/ui/BrowseView.java src/main/java/mediacenter/ui/components/TileGrid.java
git commit -m "Offer a slideshow on folders that have photographs beneath them"
```

---

### Task 12: Shipping it

**Files:**
- Modify: `build.gradle.kts`
- Modify: `README.md`

- [ ] **Step 1: Pin the heap, for the image and for `run`**

In the `jpackage` task's argument list:

```kotlin
                // Pinned rather than left to the default: a photograph decoded at
                // screen size is about eight megabytes, three are held at once, and
                // an unspecified heap turns a large picture into an unpredictable
                // failure on a machine with little to spare.
                "--java-options", "-Xmx512m",
```

And so that a local run exercises the same ceiling the shipped image has, add to
the `application` block:

```kotlin
    applicationDefaultJvmArgs = listOf("-Xmx512m")
```

- [ ] **Step 2: Verify the packaged image still builds and runs**

Run: `./gradlew packageZip`
Expected: BUILD SUCCESSFUL.

Run the packaged binary with `--windowed` and quit it.

- [ ] **Step 3: Write the README section**

Add a **Photographs** section covering: the Slideshow tile and what it gathers;
Enter on a photograph opening it with the arrows moving within its folder; the
interval setting; and — plainly, because it is otherwise an unanswerable support
question — that **HEIC files are not shown at all**, since JavaFX cannot decode
them, and that JPEG, PNG, GIF and BMP are.

In "Design rules the code follows", amend the **VLC does the playing** rule to
name photographs as its one exception: the application draws them itself, because
a still image is not playback and VLC has nothing to offer.

Add the slideshow interval to the `config.json` example.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts README.md
git commit -m "Ship the photo viewer with a heap it can rely on"
```

---

## Self-Review

Checked against the task numbering as it now stands: 1 PhotoFiles, 2
FileVisibility, 3 scanner and grid, 4 PhotoWalker, 5 ExifOrientation, 6
PhotoCache, 7 PhotoRun, 8 View flags, 9 settings interval, 10 PhotoView, 11
BrowseView entry, 12 shipping.

**Spec coverage.** Slideshow tile only where photographs exist beneath — Task 11.
Enter on a photograph opens its own folder without auto-advance — Task 10 (the
`slideshow` flag drives both `PhotoWalker`'s `recursive` and `PhotoRun`'s
wrapping) and Task 11. Folder order depth first — Task 4. Loops at the end — Task
7. Streaming with batches to the JavaFX thread — Tasks 4 and 10. Counter with
`+` — Task 7. `PhotoFiles`, `MediaItemType.IMAGE`, scanner — Tasks 1 and 3.
`PhotoWalker` serving both jobs — Task 4. Keys — Task 10. `View.fullBleed` — Task
8. `PhotoCache` three at screen size — Task 6. Interval — Task 9. Decode failure
shows a caption and advances — Task 10. Share disappearing: the root throws
`MediaAccessException` (Task 4) and the viewer says so (Task 10); a subdirectory
going away ends that branch only. No photographs means no tile — Task 11. HEIC
excluded — Task 1, documented Task 12. EXIF — Task 5, applied off-thread in Task
10. `-Xmx` — Task 12. README premise change — Task 12.

**Placeholders.** No open decisions. Every piece of new logic has a body: the
viewer in Task 10 Step 2, the artwork rule **and its call site** in Task 3 Step 3,
`slideshowRow` and the toggle selection in Task 9 Step 3.

Three steps do ask the implementer to read an existing class rather than trusting
a transcription of it — the `SettingsStore` constructor and save/load names in
Task 9 Step 1, `themeRow()`'s exact assembly in Task 9 Step 3, and the README
prose in Task 12 Step 3. That is deliberate: those are places where this plan
could go stale against the code, and a wrong transcription would be worse than an
instruction to look. It is not the same as leaving a decision open, but it is not
"nothing to write" either, and the distinction is worth stating plainly rather
than claiming a clean sheet.

**Type consistency.**
`PhotoWalker.collect(Path, boolean, int, BooleanSupplier, Consumer<List<Path>>)`
returns `Walk` and `throws MediaAccessException`; called that way in Task 10 and,
via `hasPhotos(Path)` → `boolean`, in Task 11.
`PhotoCache<Image>.show(List<Path>, int, List<Integer>, double, double)` matches
its call in Task 10. Every `PhotoRun` method used in Task 10 is defined in Task 7.
`Navigation.openSlideshow(Path)` and `openPhoto(Path, Path)` are declared once,
in Task 10, and called with those signatures in Task 11.
`ArtworkResolver.artworkNames(Collection<String>)` is defined in Task 3 and
consumed in Task 4.

**Known limitations, recorded rather than hidden.**

- `hasPhotos` is cheap on a folder that has photographs — the walk stops at the
  first — and costs a bounded traversal on one that does not. `BrowseView` runs it
  per folder opened. If it is slow on the target machine, cache it per folder.
- The caption, the full-bleed layout and the arrow keys are checked only by
  screenshot. There is no way to assert them without a rendered scene.
- `PhotoView` has no unit test by design: every rule it obeys lives in `PhotoRun`
  or `PhotoCache`, which do.
- `FileVisibilityTest`'s hidden-attribute case runs only on Windows, which is the
  platform the attribute exists on; elsewhere it is skipped rather than faked.

**Six rounds of adversarial review** found around eighty defects in this plan.
In every single round, the blocking defects were introduced by the previous
round's fixes — a walker restructured three times, a `void` conversion that left
two stray returns, a trim added to remove a cosmetic "+" that reinstated a full
network traversal. Treat any further edit to `PhotoWalker` or `PhotoView` as
likely to break something, and re-read the tests around it. Two are worth carrying
into implementation as warnings rather than as corrected text: `Image.cancel()`
sets `error = true` one pulse later, so anything that drops a decoding image must
not be read as a decode failure; and a `PauseTransition` stops with the rendering
pulse, so it is safe for a resize debounce and wrong for anything that must fire
while the screen is asleep.
