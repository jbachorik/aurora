package mediacenter.media;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The places worth browsing: what the viewer configured, plus whatever happens to
 * be plugged in at the moment.
 */
public final class MediaSources {

    /**
     * Identifiers are derived from the mount point rather than generated, so the
     * same stick plugged in twice is the same root — a generated one would look
     * like a new place on every scan and lose the selection with it.
     */
    private static final String REMOVABLE_ID_PREFIX = "removable:";

    private MediaSources() {
    }

    /**
     * A mounted volume as a media root. Always {@link MediaRootType#GENERAL}: what
     * is on a stick is anybody's guess, and a poster grid over holiday photographs
     * helps nobody.
     */
    public static MediaRoot removable(String label, Path mountPoint) {
        String name = label == null || label.isBlank()
                ? String.valueOf(mountPoint.getFileName() == null ? mountPoint : mountPoint.getFileName())
                : label;
        return new MediaRoot(REMOVABLE_ID_PREFIX + mountPoint, name, mountPoint, MediaRootType.GENERAL);
    }

    /**
     * Everything the Browse screen should offer, configured roots first.
     *
     * <p>A volume already configured as a root is dropped: a drive that lives here
     * permanently has a name and a type the viewer chose, and listing it again
     * under its label would be the same place twice.
     */
    public static List<MediaRoot> browsable(List<MediaRoot> configured, List<MediaRoot> mounted) {
        Set<Path> known = new HashSet<>();
        for (MediaRoot root : configured) {
            known.add(root.path().toAbsolutePath().normalize());
        }
        List<MediaRoot> all = new ArrayList<>(configured);
        for (MediaRoot volume : mounted) {
            if (known.add(volume.path().toAbsolutePath().normalize())) {
                all.add(volume);
            }
        }
        return List.copyOf(all);
    }
}
