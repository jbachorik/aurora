package mediacenter.platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** macOS — the usual development machine for this project. */
public final class MacPlatformServices extends AbstractPlatformServices {

    @Override
    public String name() {
        return "macOS";
    }

    @Override
    public Path applicationDataDirectory() {
        Path base = path(System.getProperty("user.home"), "Library", "Application Support");
        return ensureDirectory(base.resolve(APPLICATION_DIRECTORY_NAME));
    }

    @Override
    public Optional<Path> findVlc() {
        String home = System.getProperty("user.home");
        return firstExistingFile(List.of(
                path("/Applications/VLC.app/Contents/MacOS/VLC"),
                path(home, "Applications", "VLC.app", "Contents", "MacOS", "VLC"),
                path("/opt/homebrew/bin/vlc"),
                path("/usr/local/bin/vlc")));
    }
}
