package mediacenter.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import mediacenter.media.MediaRoot;
import mediacenter.media.MediaSources;

/** Shared helpers for the concrete {@link PlatformServices} implementations. */
abstract class AbstractPlatformServices implements PlatformServices {

    private static final Logger LOG = Logger.getLogger(AbstractPlatformServices.class.getName());

    /**
     * How long a sleep helper is given to fail in. Long enough for one that is
     * going to refuse — a missing assembly, a policy that forbids suspending —
     * to have said so, because past this point the next candidate is never
     * tried and a refusal would go unnoticed.
     */
    private static final long SLEEP_SETTLE_MS = 5_000;

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

    /**
     * Tries each known mechanism until one reports success.
     *
     * <p>The last failure is the one raised: on a machine where the first
     * candidate is simply absent, the message that matters is the one from the
     * mechanism that was actually expected to work.
     */
    @Override
    public void sleepComputer() throws IOException {
        List<List<String>> candidates = sleepCommands();
        if (candidates.isEmpty()) {
            throw new IOException("This platform has no known way to sleep");
        }
        IOException lastFailure = null;
        for (List<String> command : candidates) {
            try {
                LOG.log(Level.INFO, () -> "Sleeping with " + String.join(" ", command));
                startAndSettle(command, SLEEP_SETTLE_MS);
                return;
            } catch (IOException failure) {
                LOG.log(Level.FINE, failure, () -> command.getFirst() + " could not sleep the computer");
                lastFailure = failure;
            }
        }
        throw lastFailure;
    }

    /**
     * Starts a sleep helper and waits only long enough to see it fail.
     *
     * @throws IOException when it cannot be started, or exits non-zero early
     */
    private static void startAndSettle(List<String> command, long settleMillis) throws IOException {
        // Nothing reads the streams — the helper may outlive this call by a whole
        // night — so they are discarded rather than piped into a buffer that
        // fills up while the machine is asleep.
        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        settle(process, command.getFirst(), settleMillis);
    }

    /**
     * A sleep helper fails fast or not at all.
     *
     * <p>Windows suspends inside the call and does not return until the machine
     * wakes again, so waiting for the helper to finish would report a failure
     * for a sleep that worked — and then, on the way back, send the machine
     * straight down again through the next candidate. A helper still running
     * when the settling time is up is one whose machine is going down under it.
     *
     * @throws IOException when it exits non-zero before the settling time is up
     */
    static void settle(Process process, String name, long settleMillis) throws IOException {
        try {
            if (!process.waitFor(settleMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for " + name, e);
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException(name + " failed with exit code " + exitCode);
        }
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

    /**
     * Runs a helper that is expected to finish promptly and fails loudly when it
     * does not succeed.
     *
     * <p>For launchers that hand a program to the desktop and exit: whether the
     * program actually started is carried only by the exit status, so discarding it
     * would report success for a bundle that is missing, damaged or not an
     * application at all.
     *
     * @throws IOException when the helper exits non-zero, does not finish in time,
     *                     or cannot be started
     */
    static void runToCompletion(List<String> command, long timeoutMillis) throws IOException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .start();
        String output;
        try (var input = process.getInputStream()) {
            output = new String(input.readAllBytes()).trim();
        }
        try {
            if (!process.waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException(command.getFirst() + " did not finish within "
                        + timeoutMillis + "ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for " + command.getFirst(), e);
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException(command.getFirst() + " failed with exit code " + exitCode
                    + (output.isEmpty() ? "" : ": " + output));
        }
    }

    /**
     * Mounted volumes found by listing the directories a desktop mounts them under.
     *
     * <p>Anything unreadable is skipped rather than reported: a volume being pulled
     * out mid-scan is ordinary, not an error worth showing anybody.
     *
     * @param mountDirectories where this desktop puts them, most likely first
     * @param excluded         mount points that are not removable — the boot volume
     */
    protected static List<MediaRoot> volumesUnder(List<Path> mountDirectories, Set<Path> excluded) {
        List<MediaRoot> volumes = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (Path directory : mountDirectories) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> entries = Files.list(directory)) {
                entries.filter(Files::isDirectory)
                        .filter(Files::isReadable)
                        .sorted()
                        .forEach(mount -> {
                            Path normalized = mount.toAbsolutePath().normalize();
                            if (!excluded.contains(normalized) && seen.add(normalized)) {
                                volumes.add(MediaSources.removable(
                                        String.valueOf(mount.getFileName()), normalized));
                            }
                        });
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.FINE, "Could not list " + directory, e);
            }
        }
        return List.copyOf(volumes);
    }

    /** Convenience for building candidate paths without worrying about separators. */
    protected static Path path(String first, String... more) {
        return Path.of(first, more);
    }
}
