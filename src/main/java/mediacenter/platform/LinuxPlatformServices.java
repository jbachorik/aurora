package mediacenter.platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.Optional;

import mediacenter.media.MediaRoot;

/** Linux — supported so the code stays portable, per the specification. */
public final class LinuxPlatformServices extends AbstractPlatformServices {

    @Override
    public String name() {
        return "Linux";
    }

    @Override
    public Path applicationDataDirectory() {
        Path base = environmentPath("XDG_CONFIG_HOME")
                .orElseGet(() -> path(System.getProperty("user.home"), ".config"));
        return ensureDirectory(base.resolve(APPLICATION_DIRECTORY_NAME));
    }

    /**
     * The three places a Linux desktop mounts a stick: per-user directories first,
     * since that is what udisks has used for years, then the traditional
     * {@code /mnt} for anything mounted by hand.
     */
    @Override
    public List<MediaRoot> removableVolumes() {
        String user = System.getProperty("user.name", "");
        return volumesUnder(
                List.of(path("/media", user), path("/run/media", user), path("/media"), path("/mnt")),
                Set.of());
    }

    @Override
    public Optional<Path> findVlc() {
        return firstExistingFile(List.of(
                path("/usr/bin/vlc"),
                path("/usr/local/bin/vlc"),
                path("/snap/bin/vlc"),
                path("/var/lib/flatpak/exports/bin/org.videolan.VLC")));
    }
}
