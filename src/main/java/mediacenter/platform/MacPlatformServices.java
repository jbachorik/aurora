package mediacenter.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/** macOS — the usual development machine for this project. */
public final class MacPlatformServices extends AbstractPlatformServices {

    private static final Logger LOG = Logger.getLogger(MacPlatformServices.class.getName());

    private static final String BUNDLE_SUFFIX = ".app";

    /** open only asks the desktop to launch something; it has no reason to linger. */
    private static final long OPEN_TIMEOUT_MS = 10_000;

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

    @Override
    public String executableNoun() {
        return "application";
    }

    @Override
    public Optional<Path> programsDirectory() {
        Path applications = path("/Applications");
        return Files.isDirectory(applications) ? Optional.of(applications) : Optional.empty();
    }

    /**
     * A Mac application is a directory, so the path the picker returns cannot be
     * started as-is. The runnable file lives at
     * {@code Something.app/Contents/MacOS/Something}.
     */
    @Override
    public Path resolveProgram(Path chosen) {
        if (chosen == null) {
            return null;
        }
        return bundleExecutable(chosen).orElse(chosen);
    }

    /**
     * Walks back out of {@code Something.app/Contents/MacOS/Something}. Purely a
     * question of names: the path may point at a program that is not installed on
     * this machine any more, and a picker still has to open somewhere sensible.
     */
    @Override
    public Path presentableProgram(Path stored) {
        if (stored == null) {
            return null;
        }
        Path macOsDirectory = stored.getParent();
        Path contents = macOsDirectory == null ? null : macOsDirectory.getParent();
        Path bundle = contents == null ? null : contents.getParent();
        if (bundle == null
                || !isNamed(macOsDirectory, "MacOS")
                || !isNamed(contents, "Contents")
                || bundle.getFileName() == null
                || !bundle.getFileName().toString().endsWith(BUNDLE_SUFFIX)) {
            return stored;
        }
        return bundle;
    }

    /** An application is known by its bundle name; the ".app" is packaging, not a name. */
    @Override
    public String programLabel(Path stored) {
        if (stored == null) {
            return "";
        }
        Path presentable = presentableProgram(stored);
        Path name = presentable.getFileName();
        String label = name == null ? presentable.toString() : name.toString();
        return label.endsWith(BUNDLE_SUFFIX)
                ? label.substring(0, label.length() - BUNDLE_SUFFIX.length())
                : label;
    }

    private static boolean isNamed(Path path, String name) {
        return path.getFileName() != null && name.equals(path.getFileName().toString());
    }

    @Override
    public void launchExternal(Path executable) throws IOException {
        Path program = resolveProgram(executable);
        if (isApplicationBundle(program)) {
            // The executable inside the bundle could not be identified; macOS
            // itself always knows how to open one.
            LOG.log(Level.INFO, () -> "Opening application bundle " + program);
            // open exits as soon as the desktop has been asked to launch the bundle,
            // and its exit status is the only word on whether that worked — a moved
            // or damaged bundle fails here and nowhere else.
            runToCompletion(List.of("/usr/bin/open", "-a", program.toString()), OPEN_TIMEOUT_MS);
            return;
        }
        super.launchExternal(program);
    }

    static boolean isApplicationBundle(Path candidate) {
        return candidate != null
                && candidate.getFileName() != null
                && candidate.getFileName().toString().endsWith(BUNDLE_SUFFIX)
                && Files.isDirectory(candidate);
    }

    /**
     * The runnable file inside an application bundle: the one named after the
     * bundle when it exists, otherwise the only executable in {@code Contents/MacOS}.
     * Bundles that hold several helper binaries are left to {@code open}.
     */
    static Optional<Path> bundleExecutable(Path bundle) {
        if (!isApplicationBundle(bundle)) {
            return Optional.empty();
        }
        Path macOsDirectory = bundle.resolve("Contents").resolve("MacOS");
        if (!Files.isDirectory(macOsDirectory)) {
            return Optional.empty();
        }
        String bundleName = bundle.getFileName().toString();
        Path namedAfterBundle = macOsDirectory.resolve(
                bundleName.substring(0, bundleName.length() - BUNDLE_SUFFIX.length()));
        if (Files.isRegularFile(namedAfterBundle) && Files.isExecutable(namedAfterBundle)) {
            return Optional.of(namedAfterBundle);
        }
        try (Stream<Path> entries = Files.list(macOsDirectory)) {
            List<Path> executables = entries
                    .filter(Files::isRegularFile)
                    .filter(Files::isExecutable)
                    .limit(2)
                    .toList();
            return executables.size() == 1 ? Optional.of(executables.getFirst()) : Optional.empty();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not read " + macOsDirectory, e);
            return Optional.empty();
        }
    }
}
