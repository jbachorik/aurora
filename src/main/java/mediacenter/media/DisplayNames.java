package mediacenter.media;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Turning file and directory names into something readable from the sofa.
 *
 * <p>Deliberately conservative: extensions are removed, separators become
 * spaces, an "01 - " ordering prefix is dropped, anything in square brackets
 * goes, a trailing "S01E01" style episode tag goes, and whitespace is tidied.
 * Years and unbracketed quality markers are left alone — guessing wrong is
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

    /**
     * Anything in square brackets: "[DVDRip-XviD]", "[1080p]", "[EN]", and the
     * "[Group]" a fansub writes at the front. Square brackets in a file name are
     * all but never part of what the thing is called — they are what the tool
     * that produced the file wrote about itself — and on a tile they crowd out
     * the half of the title that matters.
     *
     * <p>Round brackets are deliberately not touched: those carry the year, and
     * "Blade Runner 2049 (2017)" wants it.
     */
    private static final Pattern BRACKETED_TAG = Pattern.compile("\\[[^\\[\\]]*\\]");

    /**
     * A "-S01E01" style episode tag stuck on the end of a name — the shape a
     * viewer's own rename leaves behind: "bad parents-S07E03". Only a
     * <em>trailing</em> tag is stripped, requiring a separator before it, so a
     * tag sitting in the middle of a longer release name — "My Name Is Earl -
     * S01.E01 - Pilot" — is left alone, since something meaningful follows it
     * there. The grid already lays episodes out in order, so a tag that is the
     * last word of the name adds nothing a viewer needs to read.
     *
     * @see SeriesFolders the same tag shape, used there to detect a series folder
     */
    private static final Pattern TRAILING_EPISODE_TAG =
            Pattern.compile("(?i)[\\s._-]+(?:" + SeriesFolders.EPISODE_TAG_CORE + ")$");

    private static String clean(String rawName) {
        String name = rawName.replace('_', ' ');
        // Before the ordering prefix is looked for, so a tag in front of the
        // number does not hide it, and before the dots are touched, so that a
        // stripped name is judged on the separators it actually has left.
        name = BRACKETED_TAG.matcher(name).replaceAll(" ").trim();
        name = TRAILING_EPISODE_TAG.matcher(name).replaceFirst("");
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
