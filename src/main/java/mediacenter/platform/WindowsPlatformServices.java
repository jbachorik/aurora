package mediacenter.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import mediacenter.media.MediaRoot;
import mediacenter.media.MediaSources;

/** Windows 7 and later. */
public final class WindowsPlatformServices extends AbstractPlatformServices {

    private static final Logger LOG = Logger.getLogger(WindowsPlatformServices.class.getName());

    private static final String VLC_EXECUTABLE = "vlc.exe";
    private static final long REGISTRY_TIMEOUT_MILLIS = 2_000;

    /** Long enough for a spinning optical drive, short enough not to be felt. */
    private static final long DRIVE_QUERY_TIMEOUT_MILLIS = 4_000;

    private static final List<String> REGISTRY_KEYS = List.of(
            "HKLM\\SOFTWARE\\VideoLAN\\VLC",
            "HKLM\\SOFTWARE\\WOW6432Node\\VideoLAN\\VLC");

    @Override
    public List<String> playerOptions() {
        return List.of("--no-one-instance");
    }

    @Override
    public String name() {
        return "Windows";
    }

    @Override
    public Path applicationDataDirectory() {
        Path base = environmentPath("APPDATA")
                .orElseGet(() -> path(System.getProperty("user.home"), "AppData", "Roaming"));
        return ensureDirectory(base.resolve(APPLICATION_DIRECTORY_NAME));
    }

    @Override
    public Optional<Path> findVlc() {
        Optional<Path> fromWellKnownLocation = firstExistingFile(wellKnownVlcLocations());
        if (fromWellKnownLocation.isPresent()) {
            return fromWellKnownLocation;
        }
        return findVlcInRegistry();
    }

    /**
     * Asks Windows which drives are removable. Drive type 2 is a stick or a card
     * reader and type 5 an optical drive; a fixed disk is deliberately not offered,
     * since a second internal disk is a place to configure as a root, not something
     * that comes and goes.
     */
    @Override
    public List<MediaRoot> removableVolumes() {
        return readCommandOutput(
                List.of("wmic", "logicaldisk", "where", "drivetype=2 or drivetype=5",
                        "get", "deviceid,volumename"),
                DRIVE_QUERY_TIMEOUT_MILLIS)
                .map(WindowsPlatformServices::parseRemovableDrives)
                .orElseGet(() -> {
                    LOG.fine("Could not list removable drives; offering none");
                    return List.of();
                });
    }

    /**
     * Reads the two-column listing the drive query produces: the drive letter, then
     * the volume label, which is blank on a stick nobody has named.
     *
     * <pre>
     * DeviceID  VolumeName
     * E:        KINGSTON
     * F:
     * </pre>
     */
    static List<MediaRoot> parseRemovableDrives(String output) {
        List<MediaRoot> drives = new ArrayList<>();
        boolean headerSeen = false;
        for (String line : output.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!headerSeen) {
                // The first non-blank line names the columns, whatever their order.
                headerSeen = true;
                continue;
            }
            int space = trimmed.indexOf(' ');
            String deviceId = space < 0 ? trimmed : trimmed.substring(0, space);
            String label = space < 0 ? "" : trimmed.substring(space).strip();
            if (!deviceId.endsWith(":")) {
                continue;
            }
            // A drive letter is not a directory until it has a separator.
            drives.add(MediaSources.removable(
                    label.isEmpty() ? deviceId : label, Path.of(deviceId + "\\")));
        }
        return List.copyOf(drives);
    }

    @Override
    public Optional<Path> programsDirectory() {
        return environmentPath("ProgramFiles").filter(java.nio.file.Files::isDirectory);
    }

    private static List<Path> wellKnownVlcLocations() {
        List<Path> candidates = new ArrayList<>();
        environmentPath("ProgramFiles")
                .ifPresent(base -> candidates.add(base.resolve("VideoLAN").resolve("VLC").resolve(VLC_EXECUTABLE)));
        environmentPath("ProgramFiles(x86)")
                .ifPresent(base -> candidates.add(base.resolve("VideoLAN").resolve("VLC").resolve(VLC_EXECUTABLE)));
        candidates.add(path("C:\\Program Files\\VideoLAN\\VLC\\" + VLC_EXECUTABLE));
        candidates.add(path("C:\\Program Files (x86)\\VideoLAN\\VLC\\" + VLC_EXECUTABLE));
        return candidates;
    }

    /** Optional registry lookup for a non-standard installation directory. */
    private Optional<Path> findVlcInRegistry() {
        for (String key : REGISTRY_KEYS) {
            Optional<Path> installDirectory = readCommandOutput(
                    List.of("reg", "query", key, "/v", "InstallDir"), REGISTRY_TIMEOUT_MILLIS)
                    .flatMap(WindowsPlatformServices::parseRegistryStringValue);
            if (installDirectory.isPresent()) {
                Path executable = installDirectory.get().resolve(VLC_EXECUTABLE);
                LOG.log(Level.FINE, () -> "Registry key " + key + " points at " + executable);
                Optional<Path> existing = firstExistingFile(List.of(executable));
                if (existing.isPresent()) {
                    return existing;
                }
            }
        }
        return Optional.empty();
    }

    /** Extracts the value from a line such as {@code InstallDir REG_SZ C:\Program Files\...}. */
    static Optional<Path> parseRegistryStringValue(String registryOutput) {
        for (String line : registryOutput.split("\\R")) {
            int marker = line.indexOf("REG_SZ");
            if (marker < 0) {
                continue;
            }
            String value = line.substring(marker + "REG_SZ".length()).trim();
            if (!value.isEmpty()) {
                try {
                    return Optional.of(Path.of(value));
                } catch (RuntimeException e) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public void showDesktop() {
        // The Shell COM object is the documented way to minimize every window and
        // is available on Windows 7. Failure is not worth bothering the user with:
        // the media center window is minimized by the caller regardless.
        try {
            new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "(New-Object -ComObject Shell.Application).MinimizeAll()")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not minimize all windows", e);
        }
    }
}
