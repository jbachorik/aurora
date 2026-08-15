package mediacenter.media;

import java.nio.file.Path;

/**
 * Turning file and directory names into something readable from the sofa.
 *
 * <p>Deliberately conservative: extensions are removed, separators become
 * spaces and whitespace is tidied. Release tags, years and quality markers are
 * left alone — guessing wrong is worse than showing the original name.
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

    private static String clean(String rawName) {
        String name = rawName.replace('_', ' ');
        // Dotted names such as "Blade.Runner.2049.2017.mkv" read badly, but dots in
        // a name that already uses spaces ("S.W.A.T. 2017") are usually intentional.
        if (!name.contains(" ") && name.indexOf('.') >= 0) {
            name = name.replace('.', ' ');
        }
        name = name.replaceAll("\\s+", " ").trim();
        return name.isEmpty() ? rawName.trim() : name;
    }
}
