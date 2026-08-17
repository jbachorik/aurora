package mediacenter.media;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Purely local artwork lookup: no online metadata, no scraping.
 *
 * <p>For a movie directory the well-known cover names are used; for a loose
 * video file a sidecar image next to it wins, otherwise the artwork of the
 * containing directory is reused.
 */
public final class ArtworkResolver {

    /** Cover file base names, most specific first. */
    static final List<String> COVER_BASE_NAMES = List.of("poster", "folder", "cover");

    /** Image extensions that JavaFX can display. */
    static final List<String> IMAGE_EXTENSIONS = List.of("jpg", "jpeg", "png");

    /** Finds artwork for a directory, listing it once. Returns empty if it cannot be read. */
    public Optional<Path> resolveForDirectory(Path directory) {
        List<String> names = listFileNames(directory);
        return selectCover(names).map(directory::resolve);
    }

    /**
     * Finds artwork for a video file.
     *
     * @param siblingFileNames names of the files in the containing directory, which the
     *                         caller already has from its own listing
     */
    public Optional<Path> resolveForFile(Path file, Collection<String> siblingFileNames) {
        Path directory = file.getParent();
        if (directory == null) {
            return Optional.empty();
        }
        String baseName = VideoFiles.withoutExtension(file.getFileName().toString());
        return selectSidecar(baseName, siblingFileNames)
                .or(() -> selectCover(siblingFileNames))
                .map(directory::resolve);
    }

    /**
     * Picks the best cover image from a set of file names, case-insensitively.
     *
     * <p>Pure function so the ordering rules can be tested without touching a disk.
     */
    public static Optional<String> selectCover(Collection<String> fileNames) {
        for (String baseName : COVER_BASE_NAMES) {
            Optional<String> match = selectSidecar(baseName, fileNames);
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    /**
     * The names in this folder that belong to something else — a film's poster or
     * its sidecar — rather than being photographs in their own right.
     *
     * <p>Unconditional: a cover name is claimed whether or not a video is present,
     * because {@code MediaScanner} already uses it as the folder tile's artwork.
     *
     * <p>Every match is claimed, not just the best one. {@link #selectCover} and
     * {@link #selectSidecar} stop at their first hit because a tile needs exactly
     * one image, but a Kodi- or Plex-organised folder routinely holds
     * {@code poster.jpg} and {@code folder.jpg} side by side, and a film can have
     * both a {@code .jpg} and a {@code .png} sidecar. Claiming only the winner
     * leaves the others to appear as browsable tiles captioned "folder" or named
     * after the film they belong to.
     *
     * <p>Computed once per directory. The sidecar lookup walks every sibling, and
     * asking it per photograph makes a folder of five hundred films and five
     * hundred photographs quadratic — on a thread that runs for every folder the
     * viewer opens.
     */
    public static Set<String> artworkNames(Collection<String> fileNames) {
        Set<String> artwork = new HashSet<>();
        if (fileNames == null) {
            return artwork;
        }
        for (String baseName : COVER_BASE_NAMES) {
            collectSidecars(baseName, fileNames, artwork);
        }
        for (String name : fileNames) {
            if (VideoFiles.isVideoFileName(name)) {
                collectSidecars(VideoFiles.withoutExtension(name), fileNames, artwork);
            }
        }
        return artwork;
    }

    /** Adds every {@code <baseName>.jpg|jpeg|png} sibling, matched case-insensitively. */
    private static void collectSidecars(String baseName, Collection<String> fileNames, Set<String> into) {
        if (baseName == null || baseName.isBlank()) {
            return;
        }
        String normalizedBase = baseName.toLowerCase(Locale.ROOT);
        for (String extension : IMAGE_EXTENSIONS) {
            String wanted = normalizedBase + "." + extension;
            for (String candidate : fileNames) {
                if (candidate != null && candidate.toLowerCase(Locale.ROOT).equals(wanted)) {
                    // The sibling's own spelling, not the normalised one: the caller
                    // compares these against real file names off a listing.
                    into.add(candidate);
                }
            }
        }
    }

    /** Picks {@code <baseName>.jpg|jpeg|png} from a set of file names, case-insensitively. */
    public static Optional<String> selectSidecar(String baseName, Collection<String> fileNames) {
        if (baseName == null || baseName.isBlank() || fileNames == null) {
            return Optional.empty();
        }
        String normalizedBase = baseName.toLowerCase(Locale.ROOT);
        for (String extension : IMAGE_EXTENSIONS) {
            String wanted = normalizedBase + "." + extension;
            for (String candidate : fileNames) {
                if (candidate != null && candidate.toLowerCase(Locale.ROOT).equals(wanted)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static List<String> listFileNames(Path directory) {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                Path fileName = entry.getFileName();
                if (fileName != null) {
                    names.add(fileName.toString());
                }
            }
        } catch (IOException | RuntimeException e) {
            // An unreachable share or a permission problem simply means "no artwork";
            // the caller already reports access failures for the directory it is showing.
            return List.of();
        }
        return names;
    }
}
