package mediacenter.media;

import java.nio.file.Path;
import java.util.Set;

/**
 * Which files are photographs, for browsing and for slideshows.
 *
 * <p>Deliberately only the formats JavaFX itself can decode. HEIC in particular
 * is what every recent iPhone produces and JavaFX cannot read one at all, so it
 * is not recognised here — a photograph that would only ever appear as a broken
 * frame is better left out of the set entirely.
 */
public final class PhotoFiles {

    public static final Set<String> PHOTO_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "bmp");

    private PhotoFiles() {
    }

    public static boolean isPhoto(Path path) {
        return path != null && path.getFileName() != null
                && isPhotoFileName(path.getFileName().toString());
    }

    public static boolean isPhotoFileName(String fileName) {
        return fileName != null
                && !VideoFiles.isJunk(fileName)
                && PHOTO_EXTENSIONS.contains(VideoFiles.extensionOf(fileName));
    }
}
