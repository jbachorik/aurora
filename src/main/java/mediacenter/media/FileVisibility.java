package mediacenter.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributes;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Whether the operating system considers an entry hidden.
 *
 * <p>Shared so that the browse grid and a slideshow reach the same verdict. They
 * must: a photograph the grid hides and the slideshow shows would make the two
 * disagree about what is in a folder, and the viewer would open a picture the
 * viewer never chose.
 */
public final class FileVisibility {

    private static final Logger LOG = Logger.getLogger(FileVisibility.class.getName());

    private FileVisibility() {
    }

    /**
     * @return true when the entry is hidden, or when it cannot be described at
     *         all — the scanner skips such an entry, and the slideshow must skip
     *         the same ones or the two disagree about what a folder contains
     */
    public static boolean isHiddenOrSystem(Path entry) {
        try {
            // On Windows this single call also yields the hidden/system flags.
            DosFileAttributes attributes = Files.readAttributes(entry, DosFileAttributes.class);
            return attributes.isHidden() || attributes.isSystem();
        } catch (IOException | RuntimeException e) {
            // UnsupportedOperationException lands here on filesystems without the
            // DOS view, where there is no hidden attribute to consult.
            if (!Files.exists(entry)) {
                // Gone, or unreadable: the scanner drops these, so this does too.
                return true;
            }
            // No DOS view on this filesystem, which simply means no hidden bit.
            LOG.log(Level.FINEST, "No DOS attributes for " + entry, e);
            return false;
        }
    }
}
