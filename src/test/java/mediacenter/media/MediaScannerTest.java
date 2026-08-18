package mediacenter.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaScannerTest {

    private final MediaScanner scanner = new MediaScanner();

    @Test
    @DisplayName("an ordering prefix still orders the grid even though it is not shown")
    void sortsByTheOrderingPrefixItDoesNotDisplay(@TempDir Path temp) throws Exception {
        Files.createFile(temp.resolve("03 - At Worlds End.mkv"));
        Files.createFile(temp.resolve("01 - Curse of the Black Pearl.mkv"));
        Files.createFile(temp.resolve("02 - Dead Mans Chest.mkv"));

        List<MediaItem> items = scanner.scan(temp);

        assertEquals(
                List.of("Curse of the Black Pearl", "Dead Mans Chest", "At Worlds End"),
                items.stream().map(MediaItem::displayName).toList());
    }

    @Test
    @DisplayName("directories come before files and both are sorted by name")
    void ordersDirectoriesBeforeFiles(@TempDir Path temp) throws Exception {
        Files.createDirectory(temp.resolve("Zulu Collection"));
        Files.createDirectory(temp.resolve("Alpha Collection"));
        Files.createFile(temp.resolve("zeta.mkv"));
        Files.createFile(temp.resolve("alpha.mkv"));

        List<MediaItem> items = scanner.scan(temp);

        assertEquals(
                List.of("Alpha Collection", "Zulu Collection", "alpha", "zeta"),
                items.stream().map(MediaItem::displayName).toList());
        assertTrue(items.get(0).isDirectory());
        assertTrue(items.get(3).isVideo());
    }

    @Test
    void skipsJunkAndNonVideoFiles(@TempDir Path temp) throws Exception {
        Files.createFile(temp.resolve("movie.mkv"));
        Files.createFile(temp.resolve("desktop.ini"));
        Files.createFile(temp.resolve("Thumbs.db"));
        Files.createFile(temp.resolve(".hidden.mkv"));
        Files.createFile(temp.resolve("readme.txt"));

        List<MediaItem> items = scanner.scan(temp, true);

        assertEquals(List.of("movie"), items.stream().map(MediaItem::displayName).toList());
    }

    @Test
    @DisplayName("a media root's own name is never borrowed for a file inside it")
    void doesNotBorrowTheNameOfAMediaRoot(@TempDir Path temp) throws Exception {
        Path moviesRoot = Files.createDirectory(temp.resolve("Movies"));
        Files.createFile(moviesRoot.resolve("Dune.2021.mkv"));

        assertEquals(List.of("Dune 2021"),
                scanner.scan(moviesRoot, true).stream().map(MediaItem::displayName).toList());
        assertEquals(List.of("Movies"),
                scanner.scan(moviesRoot, false).stream().map(MediaItem::displayName).toList());
    }

    @Test
    @DisplayName("a folder holding one movie file is displayed with the folder's name")
    void prefersTheDirectoryNameForASingleVideo(@TempDir Path temp) throws Exception {
        Path movieFolder = Files.createDirectory(temp.resolve("Blade Runner 2049 (2017)"));
        Files.createFile(movieFolder.resolve("Blade.Runner.2049.2017.mkv"));

        List<MediaItem> items = scanner.scan(movieFolder);

        assertEquals(1, items.size());
        assertEquals("Blade Runner 2049 (2017)", items.getFirst().displayName());
    }

    @Test
    void keepsFileNamesWhenAFolderHoldsSeveralVideos(@TempDir Path temp) throws Exception {
        Files.createFile(temp.resolve("Episode.One.mkv"));
        Files.createFile(temp.resolve("Episode.Two.mkv"));

        List<MediaItem> items = scanner.scan(temp);

        assertEquals(List.of("Episode One", "Episode Two"),
                items.stream().map(MediaItem::displayName).toList());
    }

    @Test
    void attachesArtworkFoundInsideASubdirectory(@TempDir Path temp) throws Exception {
        Path movie = Files.createDirectory(temp.resolve("Alien (1979)"));
        Files.createFile(movie.resolve("alien.mkv"));
        Path poster = Files.createFile(movie.resolve("poster.jpg"));

        List<MediaItem> items = scanner.scan(temp);

        assertEquals(1, items.size());
        assertEquals(java.util.Optional.of(poster), items.getFirst().artworkPath());
    }

    @Test
    @DisplayName("the quick scan leaves folder artwork out, and names it separately on request")
    void skipsDirectoryArtworkUntilItIsAskedFor(@TempDir Path temp) throws Exception {
        // Each of these lookups is a directory listing, and over a share they are
        // the whole wait before anything can be drawn. The quick scan skips them so
        // the folder appears at once; the poster is fetched per folder afterwards.
        Path movie = Files.createDirectory(temp.resolve("Alien (1979)"));
        Files.createFile(movie.resolve("alien.mkv"));
        Path poster = Files.createFile(movie.resolve("poster.jpg"));

        List<MediaItem> items = scanner.scanWithoutDirectoryArtwork(temp, false);

        assertEquals(1, items.size());
        assertEquals(java.util.Optional.empty(), items.getFirst().artworkPath());
        assertEquals(java.util.Optional.of(poster), scanner.directoryArtwork(movie));
    }

    @Test
    void emptyDirectoryScansToAnEmptyList(@TempDir Path temp) throws Exception {
        assertTrue(scanner.scan(temp).isEmpty());
    }

    @Test
    @DisplayName("a missing folder produces a friendly message, not a stack trace")
    void reportsMissingDirectoryWithAUsefulMessage(@TempDir Path temp) {
        Path missing = temp.resolve("gone");

        MediaAccessException failure = assertThrows(
                MediaAccessException.class, () -> scanner.scan(missing));

        assertTrue(failure.userMessage().startsWith("Cannot access "));
        assertFalse(failure.userMessage().contains("Exception"));
        assertEquals(missing, failure.path());
    }

    @Test
    void reportsAFileUsedAsAFolder(@TempDir Path temp) throws IOException {
        Path file = Files.createFile(temp.resolve("not-a-folder.mkv"));

        MediaAccessException failure = assertThrows(
                MediaAccessException.class, () -> scanner.scan(file));

        assertEquals("This location is not a folder.", failure.userMessage());
    }

    @Test
    @DisplayName("the UNC hint from the specification is used for network paths")
    void mentionsSmbCredentialsForUncPaths() {
        String message = MediaScanner.cannotAccessMessage(Path.of("\\\\synology\\video\\Movies"));

        assertTrue(message.contains("Cannot access"));
        assertTrue(message.contains("Check network connectivity or Windows SMB credentials."));
        assertTrue(MediaScanner.isNetworkPath(Path.of("\\\\synology\\video")));
        assertFalse(MediaScanner.isNetworkPath(Path.of("/media/movies")));
    }

    @Test
    void verifyAccessibleAcceptsARealDirectory(@TempDir Path temp) {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> scanner.verifyAccessible(temp));
    }

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

    // -- the single-medium collapse ------------------------------------------

    @Test
    @DisplayName("a folder holding one film — plus artwork and junk — is just the film")
    void collapsesAFolderHoldingOneFilm(@TempDir Path temp) throws Exception {
        Path folder = Files.createDirectory(temp.resolve("Blade Runner 2049 (2017)"));
        Files.createFile(folder.resolve("Blade.Runner.2049.mkv"));
        Files.createFile(folder.resolve("poster.jpg"));
        Files.createFile(folder.resolve("Blade.Runner.2049.srt"));
        Files.createFile(folder.resolve("Thumbs.db"));

        MediaItem sole = scanner.soleMedia(folder).orElseThrow();

        assertTrue(sole.isVideo());
        assertEquals(folder.resolve("Blade.Runner.2049.mkv"), sole.path());
        // The lone film borrows its folder's name, so the collapse plays under it.
        assertEquals("Blade Runner 2049 (2017)", sole.displayName());
    }

    @Test
    @DisplayName("a folder holding one photograph is just the photograph")
    void collapsesAFolderHoldingOnePhotograph(@TempDir Path temp) throws Exception {
        Files.createFile(temp.resolve("holiday.jpg"));

        MediaItem sole = scanner.soleMedia(temp).orElseThrow();

        assertTrue(sole.isImage());
    }

    @Test
    @DisplayName("a sub-folder means there is somewhere to go, so nothing collapses")
    void aSubFolderPreventsTheCollapse(@TempDir Path temp) throws Exception {
        Files.createFile(temp.resolve("movie.mkv"));
        Files.createDirectory(temp.resolve("Extras"));

        assertEquals(Optional.empty(), scanner.soleMedia(temp));
    }

    @Test
    @DisplayName("two films are a listing, not a medium")
    void twoFilmsPreventTheCollapse(@TempDir Path temp) throws Exception {
        Files.createFile(temp.resolve("part one.mkv"));
        Files.createFile(temp.resolve("part two.mkv"));

        assertEquals(Optional.empty(), scanner.soleMedia(temp));
    }

    @Test
    @DisplayName("artwork alone is an empty folder, not a medium")
    void artworkAloneIsNotAMedium(@TempDir Path temp) throws Exception {
        Files.createFile(temp.resolve("poster.jpg"));

        assertEquals(Optional.empty(), scanner.soleMedia(temp));
    }
}
