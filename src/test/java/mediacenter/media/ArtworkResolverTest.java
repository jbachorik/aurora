package mediacenter.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    @Test
    @DisplayName("every cover name is artwork, not just the one a tile would show")
    void claimsAllCoverNames() {
        assertEquals(
                Set.of("poster.jpg", "folder.jpg", "cover.png"),
                ArtworkResolver.artworkNames(
                        List.of("Film.mkv", "poster.jpg", "folder.jpg", "cover.png", "holiday.jpg")));
    }

    @Test
    @DisplayName("a film claims every sidecar extension it has, not only the best one")
    void claimsEverySidecarOfAFilm() {
        assertEquals(
                Set.of("movie.jpg", "movie.png"),
                ArtworkResolver.artworkNames(List.of("movie.mkv", "movie.jpg", "movie.png")));
    }

    @Test
    @DisplayName("names come back spelled as the listing spelled them")
    void keepsTheOriginalCaseOfClaimedNames() {
        // The caller matches these against real file names, so a lower-cased or
        // reconstructed spelling would silently match nothing.
        assertEquals(
                Set.of("POSTER.JPG", "Film.JPEG"),
                ArtworkResolver.artworkNames(List.of("Film.mkv", "POSTER.JPG", "Film.JPEG")));
    }

    @Test
    void claimsNothingInAFolderOfPlainPhotographs() {
        assertEquals(Set.of(), ArtworkResolver.artworkNames(List.of("holiday.jpg", "beach.png", "notes.txt")));
        assertEquals(Set.of(), ArtworkResolver.artworkNames(List.of()));
    }

    @Test
    void toleratesANullListingLikeTheOtherLookups() {
        assertEquals(Set.of(), ArtworkResolver.artworkNames(null));
    }
}
