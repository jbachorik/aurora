package mediacenter.media;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
