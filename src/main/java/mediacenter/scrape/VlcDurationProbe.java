package mediacenter.scrape;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import mediacenter.playback.vlc.LibVlc;
import mediacenter.playback.vlc.VlcEngine;

/**
 * Reads a video's running time through libVLC, without playing a frame of it.
 *
 * <p>libVLC's local parse opens the file, reads the container header, and
 * answers the duration — the same library the built-in player binds, borrowed
 * for one question. The engine is {@link VlcEngine}'s process-wide singleton,
 * loaded lazily on the first probe and shared with playback; where libVLC
 * cannot be loaded at all, that failure is remembered and every probe answers
 * empty at the cost of nothing.
 *
 * <p>Blocking — the header may live at the far end of a share — so it runs
 * where the scrapes do, never on the JavaFX thread.
 */
public final class VlcDurationProbe implements MediaDurationProbe {

    private static final Logger LOG = Logger.getLogger(VlcDurationProbe.class.getName());

    /**
     * How long a parse may take before the answer stops being worth waiting
     * for. Generous, because over a busy share even a header read can crawl —
     * and one scrape at a time means nobody queues behind a slow one for long.
     */
    private static final int PARSE_TIMEOUT_MILLIS = 20_000;

    /** How often the asynchronous parse is asked whether it is done. */
    private static final long POLL_INTERVAL_MILLIS = 50;

    private final Supplier<Optional<Path>> vlcExecutable;

    /** @param vlcExecutable the VLC program from Settings, read per probe */
    public VlcDurationProbe(Supplier<Optional<Path>> vlcExecutable) {
        this.vlcExecutable = vlcExecutable;
    }

    @Override
    public Optional<Duration> durationOf(Path video) {
        Optional<VlcEngine> engine = VlcEngine.load(vlcExecutable.get());
        if (engine.isEmpty()) {
            return Optional.empty();
        }
        LibVlc lib = engine.get().lib();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment media = lib.mediaNewPath(engine.get().instance(), video, arena);
            if (media == null || media.equals(MemorySegment.NULL)) {
                return Optional.empty();
            }
            try {
                if (!lib.mediaParseLocal(media, PARSE_TIMEOUT_MILLIS)) {
                    return Optional.empty();
                }
                if (!awaitParsed(lib, media)) {
                    return Optional.empty();
                }
                long millis = lib.mediaDurationMillis(media);
                return millis > 0 ? Optional.of(Duration.ofMillis(millis)) : Optional.empty();
            } finally {
                lib.mediaRelease(media);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.INFO, e, () -> "Could not read the duration of " + video);
            return Optional.empty();
        }
    }

    /**
     * Waits the parse out. The status stays 0 while libVLC works and settles
     * on skipped/failed/timeout/done; libVLC's own timeout bounds the wait,
     * with a slightly longer deadline here as the backstop against a build
     * that never settles.
     */
    private static boolean awaitParsed(LibVlc lib, MemorySegment media) {
        long deadline = System.nanoTime() + (PARSE_TIMEOUT_MILLIS + 5_000) * 1_000_000L;
        while (System.nanoTime() < deadline) {
            int status = lib.mediaParsedStatus(media);
            if (status != 0) {
                return status == LibVlc.MEDIA_PARSED_STATUS_DONE;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
