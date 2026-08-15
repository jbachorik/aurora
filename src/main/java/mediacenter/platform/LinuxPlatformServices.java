package mediacenter.platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

    @Override
    public Optional<Path> findVlc() {
        return firstExistingFile(List.of(
                path("/usr/bin/vlc"),
                path("/usr/local/bin/vlc"),
                path("/snap/bin/vlc"),
                path("/var/lib/flatpak/exports/bin/org.videolan.VLC")));
    }
}
