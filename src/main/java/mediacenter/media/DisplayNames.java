package mediacenter.media;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Turning file and directory names into something readable from the sofa.
 *
 * <p>Deliberately conservative: extensions are removed, separators become
 * spaces, an "01 - " ordering prefix is dropped and whitespace is tidied.
 * Release tags, years and quality markers are left alone — guessing wrong is
 * worse than showing the original name.
 */
public final class DisplayNames {

    private DisplayNames() {
    }

    /** Display name for a video file. */
    public static String forFile(Path file) {
        return forFileName(file.getFileName().toString());
    }

    public static String forFileName(String fileName) {
        return clean(VideoFiles.withoutExtension(fileName));
    }

    /** Display name for a directory. */
    public static String forDirectory(Path directory) {
        Path name = directory.getFileName();
        if (name == null) {
            // A filesystem root such as "D:\" or "\\synology\video".
            return directory.toString();
        }
        return clean(name.toString());
    }

    /** Display name for any path, choosing the file or directory rule by extension. */
    public static String forPath(Path path) {
        String fileName = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        return VideoFiles.isVideoFileName(fileName) ? forFileName(fileName) : clean(fileName);
    }

    /**
     * A leading "01 - " style number. Digits have to be followed by a separator, so
     * the number in "12 Angry Men" is left where it belongs, and only one or two of
     * them count, so a leading year — "2001 - A Space Odyssey" — is never read as
     * ordering.
     */
    private static final Pattern ORDERING_PREFIX = Pattern.compile("^\\d{1,2}\\s*[-.)\\]]\\s*");

    private static String clean(String rawName) {
        String name = rawName.replace('_', ' ');
        // Before the dots are touched, so "01.Dead.Mans.Chest" still has its
        // separator to recognise.
        name = withoutOrderingPrefix(name);
        // Dotted names such as "Blade.Runner.2049.2017.mkv" read badly, but dots in
        // a name that already uses spaces ("S.W.A.T. 2017") are usually intentional.
        if (!name.contains(" ") && name.indexOf('.') >= 0) {
            name = name.replace('.', ' ');
        }
        name = name.replaceAll("\\s+", " ").trim();
        return name.isEmpty() ? rawName.trim() : name;
    }

    /** Numbering the grid already conveys, so showing it twice only costs room. */
    private static String withoutOrderingPrefix(String name) {
        String stripped = ORDERING_PREFIX.matcher(name).replaceFirst("");
        return stripped.isBlank() ? name : stripped;
    }
}
