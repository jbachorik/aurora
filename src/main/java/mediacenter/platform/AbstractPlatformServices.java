package mediacenter.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Shared helpers for the concrete {@link PlatformServices} implementations. */
abstract class AbstractPlatformServices implements PlatformServices {

    private static final Logger LOG = Logger.getLogger(AbstractPlatformServices.class.getName());

    @Override
    public void launchExternal(Path executable) throws IOException {
        if (executable == null) {
            throw new IOException("No program configured");
        }
        if (!Files.isRegularFile(executable)) {
            throw new IOException("Program not found: " + executable);
        }
        LOG.log(Level.INFO, () -> "Launching external program " + executable);
        new ProcessBuilder(executable.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    @Override
    public void showDesktop() {
        // Nothing reliable to do by default; the caller minimizes its own window.
    }

    /** First candidate that exists as a regular file. */
    protected static Optional<Path> firstExistingFile(List<Path> candidates) {
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Reads a non-empty environment variable as a path. */
    protected static Optional<Path> environmentPath(String variable) {
        String value = System.getenv(variable);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(value));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Creates the application data directory, falling back to a temporary directory. */
    protected static Path ensureDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            return directory;
        } catch (IOException e) {
            Path fallback = Path.of(System.getProperty("java.io.tmpdir"), APPLICATION_DIRECTORY_NAME);
            LOG.log(Level.WARNING, e, () -> "Could not create " + directory + ", falling back to " + fallback);
            try {
                Files.createDirectories(fallback);
            } catch (IOException nested) {
                LOG.log(Level.SEVERE, "Could not create a data directory at all", nested);
            }
            return fallback;
        }
    }

    /**
     * Runs a short-lived helper command and returns its standard output, or empty
     * when it fails or takes too long. Used only for optional discovery.
     */
    protected static Optional<String> readCommandOutput(List<String> command, long timeoutMillis) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .start();
            String output;
            try (var input = process.getInputStream()) {
                output = new String(input.readAllBytes());
            }
            if (!process.waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            return process.exitValue() == 0 ? Optional.of(output) : Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Helper command failed: " + String.join(" ", command), e);
            return Optional.empty();
        }
    }

    /** Convenience for building candidate paths without worrying about separators. */
    protected static Path path(String first, String... more) {
        return Path.of(first, more);
    }
}
