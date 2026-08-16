package mediacenter.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaScannerTest {

    private final MediaScanner scanner = new MediaScanner();

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
}
