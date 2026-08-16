package mediacenter.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtworkResolverTest {

    private final ArtworkResolver resolver = new ArtworkResolver();

    @Test
    @DisplayName("poster beats folder, folder beats cover")
    void prefersTheMostSpecificCoverName() {
        assertEquals(Optional.of("poster.jpg"),
                ArtworkResolver.selectCover(List.of("cover.png", "folder.jpg", "poster.jpg")));
        assertEquals(Optional.of("folder.jpg"),
                ArtworkResolver.selectCover(List.of("cover.png", "folder.jpg")));
        assertEquals(Optional.of("cover.png"),
                ArtworkResolver.selectCover(List.of("cover.png", "movie.mkv")));
    }

    @Test
    void jpgBeatsPngForTheSameName() {
        assertEquals(Optional.of("poster.jpg"),
                ArtworkResolver.selectCover(List.of("poster.png", "poster.jpg")));
    }

    @Test
    void coverLookupIsCaseInsensitive() {
        assertEquals(Optional.of("Folder.JPG"), ArtworkResolver.selectCover(List.of("Folder.JPG")));
    }

    @Test
    void noCoverWhenNothingMatches() {
        assertEquals(Optional.empty(), ArtworkResolver.selectCover(List.of("movie.mkv", "notes.txt")));
        assertEquals(Optional.empty(), ArtworkResolver.selectCover(List.of()));
    }

    @Test
    void resolvesArtworkInsideAMovieDirectory(@TempDir Path temp) throws IOException {
        Path movie = Files.createDirectory(temp.resolve("Alien (1979)"));
        Files.createFile(movie.resolve("alien.mkv"));
        Path poster = Files.createFile(movie.resolve("poster.jpg"));

        assertEquals(Optional.of(poster), resolver.resolveForDirectory(movie));
    }

    @Test
    void missingDirectoryYieldsNoArtworkInsteadOfAnError(@TempDir Path temp) {
        assertEquals(Optional.empty(), resolver.resolveForDirectory(temp.resolve("does-not-exist")));
    }

    @Test
    @DisplayName("a sidecar image next to the file wins over the folder cover")
    void prefersSidecarOverFolderCover(@TempDir Path temp) throws IOException {
        Path video = Files.createFile(temp.resolve("Dune.mkv"));
        Files.createFile(temp.resolve("Dune.jpg"));
        Files.createFile(temp.resolve("poster.jpg"));

        Optional<Path> artwork = resolver.resolveForFile(video, List.of("Dune.mkv", "Dune.jpg", "poster.jpg"));

        assertEquals(Optional.of(temp.resolve("Dune.jpg")), artwork);
    }

    @Test
    void fallsBackToTheFolderCoverForALooseFile(@TempDir Path temp) throws IOException {
        Path video = Files.createFile(temp.resolve("Dune.mkv"));
        Files.createFile(temp.resolve("folder.png"));

        Optional<Path> artwork = resolver.resolveForFile(video, List.of("Dune.mkv", "folder.png"));

        assertTrue(artwork.isPresent());
        assertEquals("folder.png", artwork.get().getFileName().toString());
    }
}
