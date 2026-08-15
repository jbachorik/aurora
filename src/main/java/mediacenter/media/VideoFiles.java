package mediacenter.media;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Recognizing playable files and skipping obvious junk. */
public final class VideoFiles {

    /** Extensions (without the dot) that are offered for playback. */
    public static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mkv", "mp4", "avi", "mov", "m4v", "mpg", "mpeg", "ts", "m2ts", "webm",
            "wmv", "flv", "divx", "vob", "iso", "ogv", "mts");

    /** Files that are never interesting to a viewer. */
    private static final Set<String> JUNK_FILE_NAMES = Set.of(
            "desktop.ini", "thumbs.db", ".ds_store", "@eadir", ".directory", "albumart.ini");

    private VideoFiles() {
    }

    public static boolean isVideo(Path path) {
        return path != null && VIDEO_EXTENSIONS.contains(extensionOf(path.getFileName().toString()));
    }

    public static boolean isVideoFileName(String fileName) {
        return fileName != null && VIDEO_EXTENSIONS.contains(extensionOf(fileName));
    }

    /** Lower-case extension without the dot, or an empty string when there is none. */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static String withoutExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    /** Junk and dot-files that should never be shown. */
    public static boolean isJunk(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return true;
        }
        String normalized = fileName.toLowerCase(Locale.ROOT);
        return JUNK_FILE_NAMES.contains(normalized) || normalized.startsWith(".");
    }
}
