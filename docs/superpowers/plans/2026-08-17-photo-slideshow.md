# Photo Slideshow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Browse photographs in the media center and play a folder of them as a recursive slideshow that starts showing pictures immediately.

**Architecture:** Photographs become a third `MediaItemType` so the existing scanner and tile grid render them with no new drawing code. A `PhotoWalker` traverses depth-first on a background thread and publishes batches to the JavaFX thread, which owns the list — so the viewer indexes an ordinary `ArrayList` with no locking. A full-screen `PhotoView` holds exactly three decoded images at screen size.

**Tech Stack:** Java 25, JavaFX (`javafx.controls` only), JUnit 5, Gradle Kotlin DSL. No third-party libraries.

**Spec:** `docs/superpowers/specs/2026-08-16-photo-slideshow-design.md`

## Global Constraints

- **No new modules.** `src/main/java/module-info.java` requires exactly `javafx.controls` and `java.logging`. Do not add `java.desktop` — the runtime image is 11 modules and the README says so.
- **No runtime dependencies.** `build.gradle.kts` resolves no third-party artifacts for the production image. EXIF is parsed by hand for this reason.
- **The JavaFX thread never does I/O.** Every directory walk and every image decode happens on `context.backgroundExecutor()` or JavaFX's own background image loader, marshalled back via `FxTasks`.
- **Tests never render a scene.** There is no JavaFX toolkit in the test JVM. Anything needing one is verified by screenshot instead (see below).
- **Supported photo formats are exactly JPEG, PNG, GIF, BMP.** HEIC, WebP and TIFF are excluded — JavaFX cannot decode them.
- **Screenshot verification** uses the existing flags: `./gradlew run --args="--snapshot=/tmp/x.png --snapshot-keys=RIGHT,ENTER"`. The display must be awake or the run hangs.
- **Comments explain why, not what.** Match the surrounding style: full sentences, reasons.
- **Commit after every task.**

---

### Task 1: Recognising a photograph

**Files:**
- Create: `src/main/java/mediacenter/media/PhotoFiles.java`
- Test: `src/test/java/mediacenter/media/PhotoFilesTest.java`

**Interfaces:**
- Consumes: `VideoFiles.extensionOf(String)`, `VideoFiles.isJunk(String)` — both already `public static`.
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
    @DisplayName("the artwork a folder already uses is still junk to a slideshow")
    void treatsPlatformJunkAsNotAPhotograph() {
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
Expected: FAIL — `compileTestJava` reports `cannot find symbol` for `PhotoFiles`.

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
Expected: PASS, five tests. `VideoFiles.isJunk` already rejects both the named
junk files and anything beginning with a dot, so the hidden-file assertion is
satisfied by delegating to it rather than by repeating the rule.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mediacenter/media/PhotoFiles.java src/test/java/mediacenter/media/PhotoFilesTest.java
git commit -m "Recognise the photo formats JavaFX can actually decode"
```

---

### Task 2: Photographs in the browse grid

**Files:**
- Modify: `src/main/java/mediacenter/media/MediaItemType.java`
- Modify: `src/main/java/mediacenter/media/MediaItem.java`
- Modify: `src/main/java/mediacenter/media/MediaScanner.java`
- Test: `src/test/java/mediacenter/media/MediaScannerTest.java`

**Interfaces:**
- Consumes: `PhotoFiles.isPhoto(Path)` from Task 1.
- Produces: `MediaItemType.IMAGE`, `MediaItem.isImage()`, `MediaItem.image(Path, String, Optional<Path>, long)`. `MediaScanner.scan(Path)` now returns image items alongside video items.

- [ ] **Step 1: Write the failing test**

Add to `MediaScannerTest`:

```java
    @Test
    @DisplayName("photographs are listed beside videos, and are their own thumbnails")
    void listsPhotographs(@TempDir Path temp) throws Exception {
        Files.createFile(temp.resolve("beach.jpg"));
        Files.createFile(temp.resolve("movie.mkv"));
        Files.createFile(temp.resolve("notes.txt"));

        List<MediaItem> items = scanner.scan(temp);

        assertEquals(List.of("beach", "movie"),
                items.stream().map(MediaItem::displayName).toList());
        MediaItem photo = items.getFirst();
        assertTrue(photo.isImage());
        assertEquals(Optional.of(temp.resolve("beach.jpg")), photo.artworkPath());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*MediaScannerTest*'`
Expected: FAIL — `cannot find symbol: isImage`.

- [ ] **Step 3: Write minimal implementation**

In `MediaItemType.java`, add the constant:

```java
    /** A photograph, shown full screen rather than handed to the player. */
    IMAGE
```

In `MediaItem.java`, add beside the existing factories and predicates:

```java
    /** A photograph is its own thumbnail, so the artwork is the file itself. */
    public static MediaItem image(Path path, String displayName, Optional<Path> artwork, long lastModified) {
        return new MediaItem(path, displayName, MediaItemType.IMAGE, artwork, lastModified);
    }

    public boolean isImage() {
        return type == MediaItemType.IMAGE;
    }
```

In `MediaScanner.java`, wherever the directory listing decides a file is a video
(the filter that calls `VideoFiles.isVideo`), accept photographs too and build
them with `MediaItem.image(...)`, passing `Optional.of(file)` as the artwork.
Keep them in the same sorted list as videos — `BY_FILE_NAME` already orders on
the name on disk, so an `01 -` prefix orders photographs exactly as it orders
films.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test`
Expected: PASS, all tests. `MediaTile` needs no change — it already renders any
item that has artwork.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mediacenter/media src/test/java/mediacenter/media
git commit -m "List photographs in the browse grid"
```

---

### Task 3: Walking a tree for photographs

**Files:**
- Create: `src/main/java/mediacenter/media/PhotoWalker.java`
- Test: `src/test/java/mediacenter/media/PhotoWalkerTest.java`

**Interfaces:**
- Consumes: `PhotoFiles.isPhoto(Path)`.
- Produces:
  - `PhotoWalker.collect(Path root, int limit, Consumer<List<Path>> onBatch)` — walks depth first, calling `onBatch` with each batch of photographs found, and returns the total count. Called on a background thread; `onBatch` is called on that same thread and it is the caller's job to marshal to the JavaFX thread.
  - `PhotoWalker.hasPhotos(Path root)` — `boolean`, stops at the first photograph found.

- [ ] **Step 1: Write the failing test**

```java
package mediacenter.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static List<String> namesOf(List<Path> paths, Path root) {
        return paths.stream().map(path -> root.relativize(path).toString()).toList();
    }

    @Test
    @DisplayName("each folder's photographs come before its subfolders'")
    void walksDepthFirstInFolderOrder(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("02 - b.jpg"));
        Files.createFile(root.resolve("01 - a.jpg"));
        Path crete = Files.createDirectory(root.resolve("Crete"));
        Files.createFile(crete.resolve("beach.jpg"));
        Path athens = Files.createDirectory(root.resolve("Athens"));
        Files.createFile(athens.resolve("ruins.jpg"));

        List<Path> found = new ArrayList<>();
        long total = PhotoWalker.collect(root, 1000, found::addAll);

        assertEquals(4, total);
        assertEquals(
                List.of("01 - a.jpg", "02 - b.jpg", "Athens/ruins.jpg", "Crete/beach.jpg"),
                namesOf(found, root));
    }

    @Test
    @DisplayName("the first photograph is reported before the walk has finished")
    void reportsInBatchesAsItGoes(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("first.jpg"));
        Path deep = Files.createDirectories(root.resolve("a/b/c"));
        Files.createFile(deep.resolve("last.jpg"));

        List<Integer> batchSizes = new ArrayList<>();
        PhotoWalker.collect(root, 1000, batch -> batchSizes.add(batch.size()));

        assertTrue(batchSizes.size() >= 2, "expected more than one batch, got " + batchSizes);
    }

    @Test
    void ignoresEverythingThatIsNotAPhotograph(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("movie.mkv"));
        Files.createFile(root.resolve("notes.txt"));
        Files.createFile(root.resolve("IMG_1.heic"));

        List<Path> found = new ArrayList<>();
        assertEquals(0, PhotoWalker.collect(root, 1000, found::addAll));
        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("a link pointing back up the tree does not walk forever")
    void doesNotFollowSymbolicLinks(@TempDir Path root) throws IOException {
        Files.createFile(root.resolve("a.jpg"));
        Path child = Files.createDirectory(root.resolve("child"));
        try {
            Files.createSymbolicLink(child.resolve("up"), root);
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.abort("This filesystem has no symbolic links");
        }

        List<Path> found = new ArrayList<>();
        assertEquals(1, PhotoWalker.collect(root, 1000, found::addAll));
    }

    @Test
    @DisplayName("the limit is a stop, not a silent truncation")
    void stopsAtTheLimit(@TempDir Path root) throws IOException {
        for (int i = 0; i < 10; i++) {
            Files.createFile(root.resolve("photo-" + i + ".jpg"));
        }

        List<Path> found = new ArrayList<>();
        assertEquals(4, PhotoWalker.collect(root, 4, found::addAll));
        assertEquals(4, found.size());
    }

    @Test
    void answersWhetherAFolderHasAnyPhotographsBeneathIt(@TempDir Path root) throws IOException {
        Path deep = Files.createDirectories(root.resolve("a/b"));
        assertFalse(PhotoWalker.hasPhotos(root));

        Files.createFile(deep.resolve("found.jpg"));
        assertTrue(PhotoWalker.hasPhotos(root));
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
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Finds photographs beneath a folder, depth first and in folder order.
 *
 * <p>Reports in batches as it goes rather than at the end. A slideshow can show
 * the first picture as soon as one directory has been listed, and waiting for a
 * whole shelf of holidays to be counted before anything appears is the
 * experience this is built to avoid.
 *
 * <p>Runs on a background thread and calls back on that same thread; marshalling
 * to the JavaFX thread is the caller's job.
 */
public final class PhotoWalker {

    private static final Logger LOG = Logger.getLogger(PhotoWalker.class.getName());

    private PhotoWalker() {
    }

    /**
     * @param limit  the most photographs to collect; reaching it stops the walk
     * @param onBatch called with each folder's photographs as they are found
     * @return how many were collected
     */
    public static long collect(Path root, int limit, Consumer<List<Path>> onBatch) {
        List<Path> collected = new ArrayList<>();
        walk(root, limit, onBatch, collected);
        return collected.size();
    }

    /** Whether there is any photograph at all beneath this folder. */
    public static boolean hasPhotos(Path root) {
        return collect(root, 1, batch -> { }) > 0;
    }

    private static void walk(Path directory, int limit, Consumer<List<Path>> onBatch, List<Path> collected) {
        if (collected.size() >= limit) {
            return;
        }
        List<Path> photos = new ArrayList<>();
        List<Path> subdirectories = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                // Symbolic links are not followed: one pointing back up the tree
                // would walk for ever.
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    subdirectories.add(entry);
                } else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) && PhotoFiles.isPhoto(entry)) {
                    photos.add(entry);
                }
            }
        } catch (IOException | RuntimeException e) {
            // A share that goes away mid-walk ends this branch and no more.
            LOG.log(Level.FINE, "Could not list " + directory, e);
            return;
        }

        photos.sort(null);
        subdirectories.sort(null);

        List<Path> batch = new ArrayList<>();
        for (Path photo : photos) {
            if (collected.size() >= limit) {
                break;
            }
            collected.add(photo);
            batch.add(photo);
        }
        if (!batch.isEmpty()) {
            onBatch.accept(List.copyOf(batch));
        }
        for (Path subdirectory : subdirectories) {
            walk(subdirectory, limit, onBatch, collected);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*PhotoWalkerTest*'`
Expected: PASS, six tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mediacenter/media/PhotoWalker.java src/test/java/mediacenter/media/PhotoWalkerTest.java
git commit -m "Walk a folder tree for photographs, reporting as it goes"
```

---

### Task 4: EXIF orientation

**Files:**
- Create: `src/main/java/mediacenter/media/ExifOrientation.java`
- Test: `src/test/java/mediacenter/media/ExifOrientationTest.java`

**Interfaces:**
- Produces: `ExifOrientation.degreesFor(Path)` — returns `0`, `90`, `180` or `270`. Anything unreadable, absent or unrecognised returns `0`.

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
        ifd.putShort((short) orientation);  // the value, left-aligned in four bytes
        ifd.putShort((short) 0);
        ifd.putInt(0);                      // no next IFD
        tiff.write(ifd.array());

        byte[] exifBody = tiff.toByteArray();
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        jpeg.write(new byte[] {(byte) 0xFF, (byte) 0xD8});                   // SOI
        jpeg.write(new byte[] {(byte) 0xFF, (byte) 0xE1});                   // APP1
        int segmentLength = 2 + 6 + exifBody.length;
        jpeg.write((segmentLength >> 8) & 0xFF);
        jpeg.write(segmentLength & 0xFF);
        jpeg.write(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        jpeg.write(exifBody);
        jpeg.write(new byte[] {(byte) 0xFF, (byte) 0xD9});                   // EOI

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
        Path plain = Files.write(temp.resolve("plain.jpg"), new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});
        assertEquals(0, ExifOrientation.degreesFor(plain));
        assertEquals(0, ExifOrientation.degreesFor(temp.resolve("missing.jpg")));
        assertEquals(0, ExifOrientation.degreesFor(null));
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
 */
public final class ExifOrientation {

    private static final Logger LOG = Logger.getLogger(ExifOrientation.class.getName());

    /** Enough for the APP1 segment; the pixels beyond it are of no interest. */
    private static final int HEADER_BYTES = 64 * 1024;

    private static final int ORIENTATION_TAG = 0x0112;

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
        int exif = indexOfExifHeader(header);
        if (exif < 0) {
            return 0;
        }
        int tiff = exif + 6;
        if (tiff + 8 > header.length) {
            return 0;
        }
        ByteBuffer buffer = ByteBuffer.wrap(header);
        buffer.order(header[tiff] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);

        int ifdOffset = buffer.getInt(tiff + 4);
        int ifd = tiff + ifdOffset;
        if (ifd + 2 > header.length) {
            return 0;
        }
        int entries = buffer.getShort(ifd) & 0xFFFF;
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > header.length) {
                return 0;
            }
            if ((buffer.getShort(entry) & 0xFFFF) == ORIENTATION_TAG) {
                return degreesForTagValue(buffer.getShort(entry + 8) & 0xFFFF);
            }
        }
        return 0;
    }

    /** Finds "Exif\0\0", which introduces the APP1 segment. */
    private static int indexOfExifHeader(byte[] header) {
        for (int i = 0; i + 6 <= header.length; i++) {
            if (header[i] == 'E' && header[i + 1] == 'x' && header[i + 2] == 'i'
                    && header[i + 3] == 'f' && header[i + 4] == 0 && header[i + 5] == 0) {
                return i;
            }
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
Expected: PASS, three tests. If the big-endian test fails, check that the test
writes `MM` and the reader keys byte order off `header[tiff]`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mediacenter/media/ExifOrientation.java src/test/java/mediacenter/media/ExifOrientationTest.java
git commit -m "Read which way up a photograph was taken"
```

---

### Task 5: Holding three photographs and no more

**Files:**
- Create: `src/main/java/mediacenter/ui/components/PhotoCache.java`
- Test: `src/test/java/mediacenter/ui/components/PhotoCacheTest.java`

**Interfaces:**
- Produces:
  - `PhotoCache(PhotoCache.Loader loader)` where `Loader` is `@FunctionalInterface interface Loader { Object load(Path photo, double width, double height); }`
  - `PhotoCache.showing(List<Path> photos, int index, double width, double height)` — returns the object for `index` and retains only `index - 1`, `index` and `index + 1`.
  - `PhotoCache.size()` — how many are held, for tests.
  - `PhotoCache.imageLoader()` — a static `Loader` that builds a real JavaFX `Image`.

The loader is injected **because a JavaFX `Image` cannot be constructed without
a toolkit**, and the test JVM has none. The cache's policy — which three are
held, what is evicted — is the part worth testing, and it is testable with a
fake loader that returns strings.

- [ ] **Step 1: Write the failing test**

```java
package mediacenter.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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

    @Test
    @DisplayName("the picture on screen and its two neighbours are kept, nothing else")
    void keepsOnlyTheNeighbourhood() {
        PhotoCache cache = new PhotoCache((photo, width, height) -> photo.toString());
        List<Path> photos = photos(10);

        cache.showing(photos, 5, 1920, 1080);

        assertEquals(3, cache.size());
    }

    @Test
    @DisplayName("stepping on keeps what is still next to the viewer and drops the rest")
    void evictsWhatIsNoLongerNext() {
        PhotoCache cache = new PhotoCache((photo, width, height) -> photo.toString());
        List<Path> photos = photos(10);

        cache.showing(photos, 5, 1920, 1080);
        cache.showing(photos, 6, 1920, 1080);

        assertEquals(3, cache.size());
    }

    @Test
    @DisplayName("a photograph already decoded is not decoded again")
    void reusesWhatItAlreadyHas() {
        List<Path> loaded = new ArrayList<>();
        PhotoCache cache = new PhotoCache((photo, width, height) -> {
            loaded.add(photo);
            return photo.toString();
        });
        List<Path> photos = photos(10);

        Object first = cache.showing(photos, 5, 1920, 1080);
        Object again = cache.showing(photos, 5, 1920, 1080);

        assertSame(first, again);
        assertEquals(3, loaded.size(), "the neighbourhood is loaded once: " + loaded);
    }

    @Test
    @DisplayName("the ends of the run have one neighbour, not two")
    void copesWithTheEnds() {
        PhotoCache cache = new PhotoCache((photo, width, height) -> photo.toString());
        List<Path> photos = photos(3);

        cache.showing(photos, 0, 1920, 1080);
        assertEquals(2, cache.size());

        cache.showing(photos, 2, 1920, 1080);
        assertEquals(2, cache.size());
    }

    @Test
    void aSinglePhotographIsItsOwnNeighbourhood() {
        PhotoCache cache = new PhotoCache((photo, width, height) -> photo.toString());

        cache.showing(photos(1), 0, 1920, 1080);

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.scene.image.Image;

/**
 * The photograph on screen and its two neighbours, and nothing else.
 *
 * <p>Deliberately not an LRU. A full-screen photograph is around eight megabytes
 * decoded, and the machine this runs on is an old laptop: a cache that grows
 * would exhaust it within a folder. Three is what movement needs — back, here,
 * forward — so three is what is kept.
 *
 * <p>Each is requested at the size it will be shown at, so JavaFX scales during
 * decode and a twenty-four megapixel photograph never exists at full size.
 *
 * <p>The loader is injected so the eviction policy can be tested: a JavaFX
 * {@link Image} cannot be built without a toolkit, and the tests have none.
 */
public final class PhotoCache {

    /** Builds whatever the view will display, at the size asked for. */
    @FunctionalInterface
    public interface Loader {
        Object load(Path photo, double width, double height);
    }

    private final Loader loader;
    private final Map<Path, Object> images = new HashMap<>();

    public PhotoCache(Loader loader) {
        this.loader = loader;
    }

    /** The real thing: a background-loading, downscaled JavaFX image. */
    public static Loader imageLoader() {
        return (photo, width, height) ->
                new Image(photo.toUri().toString(), width, height, true, true, true);
    }

    /**
     * Returns the photograph at {@code index}, having made sure its neighbours are
     * on their way and everything else has been let go.
     */
    public Object showing(List<Path> photos, int index, double width, double height) {
        Set<Path> wanted = new LinkedHashSet<>();
        for (int offset = -1; offset <= 1; offset++) {
            int neighbour = index + offset;
            if (neighbour >= 0 && neighbour < photos.size()) {
                wanted.add(photos.get(neighbour));
            }
        }
        images.keySet().retainAll(wanted);
        for (Path photo : wanted) {
            images.computeIfAbsent(photo, path -> loader.load(path, width, height));
        }
        return images.get(photos.get(index));
    }

    /** How many photographs are held; for tests. */
    public int size() {
        return images.size();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*PhotoCacheTest*'`
Expected: PASS, five tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mediacenter/ui/components/PhotoCache.java src/test/java/mediacenter/ui/components/PhotoCacheTest.java
git commit -m "Hold the photograph on screen and its two neighbours"
```

---

### Task 6: A view that wants the whole screen

**Files:**
- Modify: `src/main/java/mediacenter/ui/View.java`
- Modify: `src/main/java/mediacenter/ui/MediaCenterShell.java` (in `showCurrentView`)

**Interfaces:**
- Produces: `View.fullBleed()` — `default boolean fullBleed() { return false; }`. The shell hides the header and the hint bar while such a view is on top and restores them when it leaves.

There is no test for this task: it is layout, and the suite renders no scenes.
It is verified by screenshot in Task 8, once there is something to look at.

- [ ] **Step 1: Add the flag to the contract**

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
```

- [ ] **Step 2: Honour it in the shell**

In `MediaCenterShell.showCurrentView(Direction)`, after `frame.setCenter(view.node())`, add:

```java
        // A full-bleed page takes the header and the hint bar with it, and gives
        // them back when it leaves.
        boolean chrome = !view.fullBleed();
        frame.getTop().setVisible(chrome);
        frame.getTop().setManaged(chrome);
        frame.getBottom().setVisible(chrome);
        frame.getBottom().setManaged(chrome);
```

- [ ] **Step 3: Verify nothing else changed**

Run: `./gradlew test`
Expected: PASS, the whole suite, unchanged in count.

Run: `./gradlew run --args="--snapshot=/tmp/home-chrome.png"`
Expected: the home screen still has its title and its hint bar — no view returns
`true` yet, so nothing should look different.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mediacenter/ui/View.java src/main/java/mediacenter/ui/MediaCenterShell.java
git commit -m "Let a page ask for the whole screen"
```

---

### Task 7: The photograph viewer

**Files:**
- Create: `src/main/java/mediacenter/ui/PhotoView.java`
- Create: `src/test/java/mediacenter/ui/PhotoRunTest.java`
- Modify: `src/main/java/mediacenter/ui/Navigation.java`
- Modify: `src/main/java/mediacenter/ui/MediaCenterShell.java`

**Interfaces:**
- Consumes: `PhotoWalker.collect`, `PhotoCache`, `ExifOrientation.degreesFor`, `View.fullBleed`, `FxTasks`, `context.backgroundExecutor()`.
- Produces:
  - `PhotoRun` — the model, a separate class so it can be tested without a scene. `PhotoRun(boolean recursive)`, `add(List<Path>)`, `size()`, `complete()`, `markComplete()`, `next(int from)`, `previous(int from)`, `counterText(int index)`.
  - `Navigation.openSlideshow(Path folder)` and `Navigation.openPhoto(MediaItem photo, List<MediaItem> siblings)`.
  - `PhotoView implements View` with `fullBleed()` returning `true`.

Split deliberately: `PhotoRun` holds every rule worth testing — what "next" means
at the end of an incomplete run, what the counter says — and `PhotoView` is only
the JavaFX around it.

- [ ] **Step 1: Write the failing test for the model**

```java
package mediacenter.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PhotoRunTest {

    private static List<Path> photos(String... names) {
        return java.util.Arrays.stream(names).map(Path::of).toList();
    }

    @Test
    @DisplayName("an unfinished run holds on the last picture rather than looping")
    void doesNotLoopWhileStillCollecting() {
        PhotoRun run = new PhotoRun(true);
        run.add(photos("a.jpg", "b.jpg"));

        assertEquals(1, run.next(1), "the end of an incomplete run stays where it is");
    }

    @Test
    @DisplayName("a finished run loops back to the beginning")
    void loopsOnceComplete() {
        PhotoRun run = new PhotoRun(true);
        run.add(photos("a.jpg", "b.jpg"));
        run.markComplete();

        assertEquals(0, run.next(1));
        assertEquals(1, run.previous(0));
    }

    @Test
    @DisplayName("looking at one folder stops at either end instead of wrapping")
    void aSingleFolderRunStopsAtTheEnds() {
        PhotoRun run = new PhotoRun(false);
        run.add(photos("a.jpg", "b.jpg"));
        run.markComplete();

        assertEquals(1, run.next(1));
        assertEquals(0, run.previous(0));
    }

    @Test
    @DisplayName("the counter admits when it does not yet know the total")
    void countsHonestly() {
        PhotoRun run = new PhotoRun(true);
        run.add(photos("a.jpg", "b.jpg", "c.jpg"));

        assertEquals("2 of 3+", run.counterText(1));

        run.markComplete();
        assertEquals("2 of 3", run.counterText(1));
    }

    @Test
    void movesForwardAndBackInTheMiddle() {
        PhotoRun run = new PhotoRun(true);
        run.add(photos("a.jpg", "b.jpg", "c.jpg"));

        assertEquals(2, run.next(1));
        assertEquals(0, run.previous(1));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*PhotoRunTest*'`
Expected: FAIL — `cannot find symbol: PhotoRun`.

- [ ] **Step 3: Write the model**

Create `src/main/java/mediacenter/ui/PhotoRun.java`:

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
 */
final class PhotoRun {

    private final List<Path> photos = new ArrayList<>();
    private final boolean looping;
    private boolean complete;

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

    boolean complete() {
        return complete;
    }

    int size() {
        return photos.size();
    }

    Path get(int index) {
        return photos.get(index);
    }

    List<Path> photos() {
        return List.copyOf(photos);
    }

    /**
     * The next photograph. At the end of a finished slideshow this is the first
     * one again; at the end of one that is still being collected it is where you
     * already are, because the last is not yet known.
     */
    int next(int from) {
        if (from + 1 < photos.size()) {
            return from + 1;
        }
        return looping && complete ? 0 : from;
    }

    int previous(int from) {
        if (from > 0) {
            return from - 1;
        }
        return looping && complete ? photos.size() - 1 : from;
    }

    /** "2 of 3" once everything is known, "2 of 3+" while the walk continues. */
    String counterText(int index) {
        return (index + 1) + " of " + photos.size() + (complete ? "" : "+");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*PhotoRunTest*'`
Expected: PASS, five tests.

- [ ] **Step 5: Build the view around it**

Create `src/main/java/mediacenter/ui/PhotoView.java`. It:

- implements `View`, returns `true` from `fullBleed()`, and its `node()` is a
  `StackPane` holding an `ImageView` and an overlay `Label`;
- takes `(UiContext context, Path folder, boolean recursive, int startIndex)`;
- on construction, submits the walk to `context.backgroundExecutor()`:
  `PhotoWalker.collect(folder, PHOTO_LIMIT, batch -> Platform.runLater(() -> onBatch(batch)))`,
  then `Platform.runLater(run::markComplete)` when it returns;
- shows the first photograph as soon as the first batch arrives;
- binds the `ImageView`'s fit width and height to the scene, and sets
  `setRotate(ExifOrientation.degreesFor(photo))` on each change;
- asks `PhotoCache.showing(run.photos(), index, sceneWidth, sceneHeight)` for the
  image, having built the cache with `PhotoCache.imageLoader()`;
- installs a `KEY_PRESSED` filter: `LEFT` → `show(run.previous(index))`, `RIGHT` →
  `show(run.next(index))`, `ENTER`/`SPACE` → toggle auto-advance when
  `recursive`, ignored otherwise;
- auto-advances with a timer **on an ordinary daemon thread**, not a
  `PauseTransition` — an animation stops with the rendering pulse, which is how a
  sleeping display hung `SceneSnapshot` earlier;
- resets that timer on every manual move, so skipping to a photograph gives a
  full dwell on it;
- shows the file name and `run.counterText(index)` in the overlay on each change,
  fading it out with `Motion.GENTLE`;
- on a decode failure (`Image::isError`), logs and moves on with
  `show(run.next(index))` rather than stalling.

Add to `Navigation.java`:

```java
    /** Runs every photograph beneath a folder as a slideshow. */
    void openSlideshow(Path folder);

    /** Opens one photograph, with the arrows moving through its own folder. */
    void openPhoto(Path folder, int startIndex);
```

Implement both in `MediaCenterShell` by pushing a `PhotoView`:

```java
    @Override
    public void openSlideshow(Path folder) {
        push(new PhotoView(context, folder, true, 0));
    }

    @Override
    public void openPhoto(Path folder, int startIndex) {
        push(new PhotoView(context, folder, false, startIndex));
    }
```

- [ ] **Step 6: Verify the suite still passes**

Run: `./gradlew test`
Expected: PASS. `PhotoView` itself is not covered — it is verified in Task 8.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/mediacenter/ui src/test/java/mediacenter/ui
git commit -m "Show photographs full screen, one run at a time"
```

---

### Task 8: The way in

**Files:**
- Modify: `src/main/java/mediacenter/ui/BrowseView.java`

**Interfaces:**
- Consumes: `PhotoWalker.hasPhotos(Path)`, `Navigation.openSlideshow(Path)`, `Navigation.openPhoto(Path, int)`, `ActionTile`.
- Produces: nothing further.

The Slideshow tile is an `ActionTile` titled `"Slideshow"`, inserted first in the
grid. `BrowseView.activate` already ignores anything that is not a `MediaTile`,
so it gains a branch for it.

- [ ] **Step 1: Add the tile, behind a background probe**

In `BrowseView`, after the grid's items have been set, ask off-thread whether
there are photographs beneath the folder and, if so, put the tile in front:

```java
    /**
     * The Slideshow tile only makes sense where there are photographs, and finding
     * that out means walking the tree — so the grid is filled first and the tile
     * appears a moment later, rather than the folder waiting on the answer.
     */
    private void offerSlideshow(List<Tile> tiles) {
        FxTasks.run(
                context.backgroundExecutor(),
                () -> PhotoWalker.hasPhotos(folder),
                hasPhotos -> {
                    if (hasPhotos) {
                        List<Tile> withSlideshow = new ArrayList<>();
                        withSlideshow.add(new ActionTile("▶", "Slideshow", "All photos, including subfolders"));
                        withSlideshow.addAll(tiles);
                        grid.setTiles(withSlideshow);
                    }
                },
                failure -> { });
    }
```

- [ ] **Step 2: Handle activation**

In `BrowseView.activate(Tile tile)`, before the `instanceof MediaTile` check:

```java
        if ("Slideshow".equals(tile.title()) && !(tile instanceof MediaTile)) {
            context.navigation().openSlideshow(folder);
            return;
        }
```

and in the existing media branch, send a photograph to the viewer rather than to
VLC:

```java
        if (item.isImage()) {
            context.navigation().openPhoto(folder, indexOfPhoto(item));
            return;
        }
```

where `indexOfPhoto` is the item's position among the **photographs** in the
current grid, since the viewer moves through photographs only.

- [ ] **Step 3: Verify by screenshot**

Put a few `.jpg` files into a folder under your configured media root, with at
least one in a subfolder, then:

```bash
./gradlew run --args="--snapshot=/tmp/photos-grid.png --snapshot-keys=RIGHT,ENTER,ENTER"
```

Expected: the folder's grid shows a **Slideshow** tile first, then the
photographs as their own thumbnails.

```bash
./gradlew run --args="--snapshot=/tmp/photos-show.png --snapshot-keys=RIGHT,ENTER,ENTER,ENTER"
```

Expected: a full-screen photograph with **no header and no hint bar**, and an
overlay reading `1 of N+` or `1 of N`.

**Look at both images.** A blank frame means the viewer failed to launch.

- [ ] **Step 4: Run the whole suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mediacenter/ui/BrowseView.java
git commit -m "Offer a slideshow on folders that have photographs beneath them"
```

---

### Task 9: How long each photograph stays

**Files:**
- Modify: `src/main/java/mediacenter/config/ApplicationSettings.java`
- Modify: `src/main/java/mediacenter/config/SettingsStore.java`
- Modify: `src/main/java/mediacenter/ui/SettingsView.java`
- Modify: `src/main/java/mediacenter/ui/PhotoView.java`
- Test: `src/test/java/mediacenter/config/ApplicationSettingsTest.java`, `src/test/java/mediacenter/config/SettingsStoreTest.java`

**Interfaces:**
- Produces: `ApplicationSettings.slideshowSeconds()` (`int`), `withSlideshowSeconds(int)`. JSON key `slideshowSeconds`.

- [ ] **Step 1: Write the failing test**

Add to `ApplicationSettingsTest`:

```java
    @Test
    @DisplayName("an interval nobody could watch is brought back to something usable")
    void clampsTheSlideshowInterval() {
        assertEquals(5, ApplicationSettings.defaults().slideshowSeconds());
        assertEquals(2, ApplicationSettings.defaults().withSlideshowSeconds(0).slideshowSeconds());
        assertEquals(60, ApplicationSettings.defaults().withSlideshowSeconds(3600).slideshowSeconds());
    }
```

Add to `SettingsStoreTest`, mirroring whatever round-trip test already exists
there: write settings with `withSlideshowSeconds(9)`, read them back, assert
`slideshowSeconds()` is `9`, and assert that a file with no `slideshowSeconds`
key still loads with the default of `5`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*ApplicationSettingsTest*' --tests '*SettingsStoreTest*'`
Expected: FAIL — `cannot find symbol: slideshowSeconds`.

- [ ] **Step 3: Implement**

Add `int slideshowSeconds` as the last component of the `ApplicationSettings`
record. In its compact constructor clamp it: `slideshowSeconds = Math.clamp(slideshowSeconds, 2, 60)`,
with a comment saying why — under two seconds nobody can see the picture, over a
minute it looks broken. Add `withSlideshowSeconds`, and update **every existing**
`withX` method to carry the new component through. Read and write the
`slideshowSeconds` key in `SettingsStore`, defaulting to `5` when absent.

In `SettingsView`, add a row for it following the pattern of the theme row, and
**add its controls to `navigationRows`** so the arrows reach it like every other
row. In `PhotoView`, take the interval from
`context.settings().get().slideshowSeconds()`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test`
Expected: PASS, whole suite.

- [ ] **Step 5: Verify the Settings screen by screenshot**

```bash
./gradlew run --args="--snapshot=/tmp/settings.png --snapshot-keys=RIGHT,RIGHT,RIGHT,ENTER,DOWN,DOWN,DOWN,DOWN"
```

Expected: the new row is present, and four Downs still land somewhere sensible —
adding a row shifts what each Down reaches, so check the focus ring is where the
row order says it should be.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/mediacenter/config src/main/java/mediacenter/ui src/test/java/mediacenter/config
git commit -m "Let the viewer choose how long each photograph stays"
```

---

### Task 10: Shipping it

**Files:**
- Modify: `build.gradle.kts` (the `jpackage` task)
- Modify: `README.md`

**Interfaces:** none.

- [ ] **Step 1: Pin the heap**

In the `jpackage` task's argument list, beside `--app-version` and the rest, add:

```kotlin
                // Pinned rather than left to the default: a photograph decoded at
                // screen size is about eight megabytes, three are held at once, and
                // an unspecified heap turns a large picture into an unpredictable
                // failure on a machine with little to spare.
                "--java-options", "-Xmx512m",
```

- [ ] **Step 2: Verify the packaged image still builds and runs**

Run: `./gradlew packageZip`
Expected: BUILD SUCCESSFUL.

Run: `build/app-image/MediaCenter.app/Contents/MacOS/MediaCenter --windowed`
(on macOS; on Windows run `MediaCenter.exe --windowed`)
Expected: the application starts. Quit it.

- [ ] **Step 3: Write the README section**

Add a **Photographs** section covering: the Slideshow tile and what it gathers;
Enter on a photograph opening it with the arrows moving within its folder; the
interval setting; and — plainly, because it is otherwise an unanswerable support
question — that **HEIC files are not shown at all**, since JavaFX cannot decode
them, and that JPEG, PNG, GIF and BMP are.

In the "Design rules the code follows" list, amend the **VLC does the playing**
rule to say that photographs are the one exception: the application draws them
itself, because a still image is not playback and VLC has nothing to offer.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts README.md
git commit -m "Ship the photo viewer with a heap it can rely on"
```

---

## Self-Review

**Spec coverage.** Slideshow tile shown only where photographs exist beneath —
Task 8. Enter on a photograph, own folder, no auto-advance — Tasks 7 and 8.
Folder order depth first — Task 3. Loops at the end — Task 7 (`PhotoRun`).
Streaming with batches to the JavaFX thread — Tasks 3 and 7. `PhotoFiles`,
`MediaItemType.IMAGE`, scanner — Tasks 1 and 2. `PhotoWalker` both jobs — Task 3.
`PhotoView` two modes and keys — Task 7. `View.fullBleed` — Task 6. `PhotoCache`
three images at screen size — Task 5. Settings interval — Task 9. Error
handling: decode failure advances (Task 7), share disappearing ends the branch
(Task 3), no photographs means no tile (Task 8). HEIC excluded — Task 1 and
documented in Task 10. EXIF orientation — Task 4, applied in Task 7. `-Xmx` —
Task 10. README premise change — Task 10.

**Placeholders.** None: every code step carries the code, and the two steps that
describe rather than show — Task 7 Step 5 and Task 9 Step 3 — enumerate each
change with the exact names and call sites.

**Type consistency.** `PhotoFiles.isPhoto(Path)` is used in Tasks 2 and 3 as
defined in Task 1. `PhotoWalker.collect(Path, int, Consumer<List<Path>>)` returns
`long` and is used that way in Task 7; `hasPhotos(Path)` returns `boolean` and is
used that way in Task 8. `PhotoCache.showing(List<Path>, int, double, double)`
matches its call in Task 7. `PhotoRun` methods used in Task 7 are exactly those
defined in Task 7 Step 3. `Navigation.openSlideshow(Path)` and
`openPhoto(Path, int)` are declared in Task 7 and called in Task 8 with those
signatures.

**One gap accepted deliberately.** The spec's `42 of 380+` example implies the
counter is visible; `PhotoRun.counterText` is unit tested but its appearance on
screen is only ever checked by screenshot in Task 8. There is no way to assert it
without a rendered scene.
