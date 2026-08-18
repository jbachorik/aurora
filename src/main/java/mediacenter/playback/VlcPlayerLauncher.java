package mediacenter.playback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plays media by starting VLC and waiting for it to exit.
 *
 * <p>Arguments are always passed as a list — never as a composed shell command —
 * so spaces, Unicode characters and UNC paths need no quoting rules.
 */
public final class VlcPlayerLauncher implements PlayerLauncher {

    private static final Logger LOG = Logger.getLogger(VlcPlayerLauncher.class.getName());

    static final String VLC_NOT_CONFIGURED =
            "VLC was not found. Choose it in Settings.";
    static final String VLC_MISSING =
            "The configured VLC program no longer exists. Check the path in Settings.";
    static final String VLC_NOT_A_PROGRAM =
            "The configured VLC path is not a program that can be started. "
                    + "Choose VLC again in Settings.";
    static final String MEDIA_MISSING =
            "This file is no longer available. The folder or network share may have disconnected.";
    static final String VLC_NOT_STARTED =
            "VLC could not be started.";

    private final Supplier<Optional<Path>> vlcExecutableSupplier;
    private final List<String> platformOptions;

    /** @param vlcExecutableSupplier read on every playback so Settings changes take effect at once */
    public VlcPlayerLauncher(Supplier<Optional<Path>> vlcExecutableSupplier) {
        this(vlcExecutableSupplier, List.of());
    }

    /**
     * @param platformOptions extra options the running platform needs, from
     *                        {@code PlatformServices.playerOptions()}; they are not
     *                        hard-coded here because an option that one build of
     *                        VLC needs is one another build refuses to start with
     */
    public VlcPlayerLauncher(Supplier<Optional<Path>> vlcExecutableSupplier, List<String> platformOptions) {
        this.vlcExecutableSupplier = vlcExecutableSupplier;
        this.platformOptions = List.copyOf(platformOptions);
    }

    /** Command line VLC is started with, exposed for logging and tests. */
    public static List<String> commandFor(Path vlcExecutable, Path mediaFile) {
        return commandFor(vlcExecutable, mediaFile, List.of());
    }

    /** @see #commandFor(Path, Path) */
    public static List<String> commandFor(Path vlcExecutable, Path mediaFile, List<String> platformOptions) {
        List<String> command = new ArrayList<>(4 + platformOptions.size());
        command.add(vlcExecutable.toString());
        command.add("--fullscreen");
        command.add("--play-and-exit");
        command.addAll(platformOptions);
        // Last, so that a file whose name begins with a dash is still read as the
        // file: everything after it is one.
        command.add(mediaFile.toString());
        return List.copyOf(command);
    }

    @Override
    public PlaybackResult play(Path mediaFile) {
        Optional<Path> configured = vlcExecutableSupplier.get();
        if (configured.isEmpty()) {
            LOG.warning("Playback requested but no VLC executable is configured");
            return PlaybackResult.Failed.of(VLC_NOT_CONFIGURED);
        }
        Path vlcExecutable = configured.get();
        if (!Files.isRegularFile(vlcExecutable)) {
            // A path that exists but is not a file is a directory — on macOS, an
            // application bundle. Saying it "no longer exists" would send the user
            // looking for something that is plainly still there.
            if (Files.exists(vlcExecutable)) {
                LOG.log(Level.WARNING,
                        () -> "Configured VLC path is not a runnable program: " + vlcExecutable);
                return PlaybackResult.Failed.of(VLC_NOT_A_PROGRAM);
            }
            LOG.log(Level.WARNING, () -> "Configured VLC executable is missing: " + vlcExecutable);
            return PlaybackResult.Failed.of(VLC_MISSING);
        }
        if (!isPlayable(mediaFile)) {
            LOG.log(Level.WARNING, () -> "Media file is not available: " + mediaFile);
            return PlaybackResult.Failed.of(MEDIA_MISSING);
        }

        List<String> command = commandFor(vlcExecutable, mediaFile, platformOptions);
        LOG.log(Level.INFO, () -> "Starting playback: " + String.join(" ", command));

        Instant startedAt = Instant.now();
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            int exitCode = process.waitFor();
            Duration duration = Duration.between(startedAt, Instant.now());
            LOG.log(Level.INFO, () -> "Playback finished after " + duration.toSeconds()
                    + "s with exit code " + exitCode);
            return new PlaybackResult.Completed(exitCode, duration);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "VLC could not be started", e);
            return PlaybackResult.Failed.of(VLC_NOT_STARTED, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, "Interrupted while waiting for VLC", e);
            return new PlaybackResult.Completed(-1, Duration.between(startedAt, Instant.now()));
        }
    }

    /**
     * Checks the file is still there. A disconnected share makes this fail rather
     * than hang forever, and it is always called from a background thread.
     */
    private static boolean isPlayable(Path mediaFile) {
        try {
            return Files.isRegularFile(mediaFile);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Could not stat media file " + mediaFile, e);
            return false;
        }
    }
}
