package mediacenter.playback.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import mediacenter.playback.cache.MediaDurations.VideoHeader;
import mediacenter.playback.cache.MediaMirror.MirrorTask;

/**
 * Decides, just before the player starts, whether a file can be streamed from
 * where it is — and buffers ahead into the {@link MediaMirror} when it cannot.
 *
 * <p>The decision rests on two numbers: how fast the file's home can be read
 * (measured, not assumed) and how fast the file must be read (its size over
 * its duration). When the first comfortably exceeds the second, the player
 * gets the original path and nothing was lost but a moment. When it does not,
 * the file is copied locally and playback starts only once the head start is
 * large enough that the player can never catch the copy front — the next
 * title in the queue included, so an episode run survives a slow share.
 *
 * <p>When even that head start would take unreasonably long to build, the
 * viewer gets the film now, over the slow share, with a warning — and the
 * mirror quietly takes a full copy in the background so the next viewing is
 * smooth. Waiting half an hour in front of a black screen is never the right
 * answer to a play button.
 */
public final class PlaybackPreparer {

    private static final Logger LOG = Logger.getLogger(PlaybackPreparer.class.getName());

    /** Reads the share; replaceable so tests can dictate the weather. */
    public interface Throughput {
        OptionalLong bytesPerSecond(Path file);
    }

    /** Reads container headers; replaceable for the same reason. */
    public interface Headers {
        VideoHeader read(Path file);
    }

    /** What the player should actually be handed. */
    public record Prepared(Path mediaFile, List<Path> playOnwards, Optional<String> notice) {

        static Prepared passthrough(Path mediaFile, List<Path> playOnwards) {
            return new Prepared(mediaFile, playOnwards, Optional.empty());
        }
    }

    static final String SLOW_SHARE_NOTICE =
            "The network share is slow; playback may stutter. A local copy is being "
                    + "made for next time.";

    /**
     * The measured rate is discounted by this much before it is trusted: a
     * share that keeps up with no margin stalls on the first busy moment.
     */
    static final double HEADROOM = 1.25;

    /** Jitter cover even when the arithmetic says less would do. */
    static final long MINIMUM_LEAD_BYTES = 16L << 20;

    /**
     * Rate a file of unknown duration is assumed to need — generous, because
     * being wrong in the fast direction merely buffers more.
     */
    static final long ASSUMED_RATE_UNKNOWN_DURATION = 3L << 20;

    /** Longest acceptable buffering before choosing to play over the slow share instead. */
    static final int MAX_BUFFER_WAIT_SECONDS = 120;

    /** Absolute cap on the buffering loop, whatever the estimates said. */
    private static final long HARD_WAIT_LIMIT_NANOS = Duration.ofMinutes(10).toNanos();

    private final MediaMirror mirror;
    private final Throughput throughput;
    private final Headers headers;
    private final long pollMillis;

    public PlaybackPreparer(MediaMirror mirror) {
        this(mirror, ThroughputProbe::measure, MediaDurations::read, 250);
    }

    PlaybackPreparer(MediaMirror mirror, Throughput throughput, Headers headers, long pollMillis) {
        this.mirror = mirror;
        this.throughput = throughput;
        this.headers = headers;
        this.pollMillis = pollMillis;
    }

    /**
     * Blocks until the given file is ready to play smoothly, or until waiting
     * longer stops being worth it. Called on a background thread; the progress
     * consumer hears about anything the viewer should see, in their words.
     */
    public Prepared prepare(Path mediaFile, List<Path> playOnwards, Consumer<String> progress) {
        try {
            return decide(mediaFile, playOnwards, progress);
        } catch (RuntimeException e) {
            // Preparation is an optimisation; failing at it must never cost the film.
            LOG.log(Level.WARNING, "Playback preparation failed; playing directly", e);
            return Prepared.passthrough(mediaFile, playOnwards);
        }
    }

    /** The completed local copies for these files — originals map to themselves. */
    public Map<Path, Path> completedCopies(List<Path> files) {
        Map<Path, Path> resolved = new HashMap<>();
        for (Path file : files) {
            mirror.completedCopy(file).ifPresent(copy -> resolved.put(file, copy));
        }
        return resolved;
    }

    /**
     * Counts a finished viewing and, once a network file has proven popular,
     * takes a full local copy in the background — the "local mirroring" that
     * frees a favourite from the network altogether. Called after the player
     * exits, when the bandwidth is free again.
     */
    public void recordPlayed(Path mediaFile) {
        mirror.recordPlayed(mediaFile, isNetworkPath(mediaFile));
        if (mirror.enabled()
                && mirror.isFrequentlyPlayed(mediaFile)
                && mirror.completedCopy(mediaFile).isEmpty()) {
            LOG.log(Level.INFO, () -> "Frequently played, mirroring for keeps: " + mediaFile);
            mirror.copy(mediaFile);
        }
    }

    /** A path served by a machine that can go away: a UNC share. */
    static boolean isNetworkPath(Path path) {
        String text = path.toString();
        return text.startsWith("\\\\") || text.startsWith("//");
    }

    // -- the decision ---------------------------------------------------------

    private Prepared decide(Path mediaFile, List<Path> playOnwards, Consumer<String> progress) {
        // A finished mirror copy answers every question at once.
        Optional<Path> copy = mirror.completedCopy(mediaFile);
        if (copy.isPresent()) {
            LOG.log(Level.INFO, () -> "Playing the mirror copy of " + mediaFile);
            mirrorNextInQueue(playOnwards);
            return new Prepared(copy.get(), substitute(playOnwards), Optional.empty());
        }

        OptionalLong measured = throughput.bytesPerSecond(mediaFile);
        if (measured.isEmpty()) {
            // Unreadable now is the player's message to deliver, not ours to guess at.
            return Prepared.passthrough(mediaFile, substitute(playOnwards));
        }
        long rate = measured.getAsLong();
        long size;
        try {
            size = Files.size(mediaFile);
        } catch (IOException e) {
            return Prepared.passthrough(mediaFile, substitute(playOnwards));
        }
        VideoHeader header = headers.read(mediaFile);
        long requiredRate = header.duration()
                .map(duration -> (long) (bitrate(size, duration) * HEADROOM))
                .orElse(ASSUMED_RATE_UNKNOWN_DURATION);
        LOG.log(Level.INFO, () -> "Throughput of " + mediaFile + ": " + (rate >> 10)
                + " KiB/s, needs " + (requiredRate >> 10) + " KiB/s"
                + header.duration().map(d -> " over " + d.toMinutes() + "min").orElse(" (duration unknown)"));
        if (rate >= requiredRate) {
            return Prepared.passthrough(mediaFile, substitute(playOnwards));
        }
        if (!mirror.enabled()) {
            return new Prepared(mediaFile, substitute(playOnwards),
                    Optional.of("The network share is slow; playback may stutter."));
        }
        return bufferAhead(mediaFile, size, header, rate, playOnwards, progress);
    }

    private Prepared bufferAhead(
            Path mediaFile,
            long size,
            VideoHeader header,
            long probedRate,
            List<Path> playOnwards,
            Consumer<String> progress) {

        // How much of the next title must also be down before starting, so the
        // player rolls into it without a pause. Only a title whose header is up
        // front can be handed over while its copy still grows.
        Optional<NextTitle> plannedNext = playOnwards.isEmpty() ? Optional.empty()
                : planNextTitle(playOnwards.getFirst());

        long mainLead = leadBytes(size, header, probedRate);
        long nextLead = plannedNext.map(next -> next.leadBytes(probedRate)).orElse(0L);
        long estimatedWaitSeconds = (mainLead + nextLead) / Math.max(probedRate, 1);
        if (estimatedWaitSeconds > MAX_BUFFER_WAIT_SECONDS) {
            // Play now over the slow share; mirror the whole file meanwhile so
            // the *next* viewing comes off the local disk.
            LOG.log(Level.INFO, () -> "Buffering would take ~" + estimatedWaitSeconds
                    + "s; playing " + mediaFile + " directly and mirroring in the background");
            mirror.copy(mediaFile);
            return new Prepared(mediaFile, substitute(playOnwards), Optional.of(SLOW_SHARE_NOTICE));
        }

        Optional<MirrorTask> started = mirror.copy(mediaFile);
        if (started.isEmpty()) {
            return new Prepared(mediaFile, substitute(playOnwards),
                    Optional.of("The network share is slow; playback may stutter."));
        }
        MirrorTask mainTask = started.get();
        Optional<MirrorTask> nextTask = plannedNext.flatMap(next -> mirror.copy(next.path()));

        long deadline = System.nanoTime() + HARD_WAIT_LIMIT_NANOS;
        int lastReported = -1;
        boolean buffered = false;
        while (System.nanoTime() < deadline) {
            if (mainTask.isFailed()) {
                break;
            }
            // Re-planned every pass with the rate the copy is actually getting,
            // so a share that speeds up releases the viewer sooner and one that
            // slows down keeps them safe. Once the main copy has finished, its
            // frozen average only decays; the successor's live rate takes over.
            long liveRate = mainTask.isDone() && nextTask.isPresent()
                    ? nextTask.get().observedBytesPerSecond()
                    : mainTask.observedBytesPerSecond();
            if (liveRate <= 0) {
                liveRate = probedRate;
            }
            boolean nextRides = nextTask.isPresent() && !nextTask.get().isFailed();
            long effectiveRate = liveRate;
            long needed = leadBytes(size, header, liveRate)
                    + (nextRides ? plannedNext.map(next -> next.leadBytes(effectiveRate)).orElse(0L) : 0);
            long copied = mainTask.copiedBytes()
                    + (nextRides ? nextTask.get().copiedBytes() : 0);
            if (copied >= needed) {
                buffered = true;
                break;
            }
            int percent = (int) Math.min(99, copied * 100 / Math.max(needed, 1));
            if (percent != lastReported) {
                lastReported = percent;
                progress.accept("Buffering from the network… " + percent + "%");
            }
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Prepared.passthrough(mediaFile, substitute(playOnwards));
            }
        }
        if (!buffered) {
            // The copy failed or the share degraded past saving: a partial
            // mirror must never reach the player, it would end mid-scene.
            return new Prepared(mediaFile, substitute(playOnwards),
                    Optional.of("The network share is slow; playback may stutter."));
        }

        List<Path> queue = new ArrayList<>(substitute(playOnwards));
        if (nextTask.isPresent() && !nextTask.get().isFailed() && !queue.isEmpty()) {
            // Covered by the lead above: by the time the player reaches it, the
            // copy is far enough ahead to carry it to the end.
            queue.set(0, nextTask.get().target());
        }
        LOG.log(Level.INFO, () -> "Buffered enough of " + mediaFile + "; starting from the mirror");
        return new Prepared(mainTask.target(), List.copyOf(queue), Optional.empty());
    }

    /**
     * Bytes that must be local before playback starts so the player, consuming
     * at the file's bitrate, never overtakes a copy arriving at {@code rate}.
     * A file that cannot be played while growing must be complete first.
     */
    private static long leadBytes(long size, VideoHeader header, long rate) {
        if (header.duration().isEmpty() || !header.headerBeforeMedia()) {
            return size;
        }
        double seconds = header.duration().get().toMillis() / 1000d;
        long lead = (long) (size - rate / HEADROOM * seconds);
        return Math.clamp(Math.max(lead, MINIMUM_LEAD_BYTES), 0, size);
    }

    /** The next queued title, sized up once so the wait loop never re-reads its header. */
    private record NextTitle(Path path, long size, VideoHeader header) {

        long leadBytes(long rate) {
            return PlaybackPreparer.leadBytes(size, header, rate);
        }
    }

    /** The next title's own head start, when it can safely ride the same copy queue. */
    private Optional<NextTitle> planNextTitle(Path path) {
        if (mirror.completedCopy(path).isPresent()) {
            return Optional.empty();
        }
        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            return Optional.empty();
        }
        VideoHeader header = headers.read(path);
        if (header.duration().isEmpty() || !header.headerBeforeMedia()) {
            // Without a duration there is no safe handover point; leave the
            // original path in the queue rather than gamble on a torso.
            return Optional.empty();
        }
        return Optional.of(new NextTitle(path, size, header));
    }

    /** Kicks off a background copy of the first queued title still living remotely. */
    private void mirrorNextInQueue(List<Path> playOnwards) {
        if (!mirror.enabled()) {
            return;
        }
        for (Path following : playOnwards) {
            if (isNetworkPath(following) && mirror.completedCopy(following).isEmpty()) {
                mirror.copy(following);
                return;
            }
        }
    }

    /** Queue entries with finished mirror copies play from those copies. */
    private List<Path> substitute(List<Path> playOnwards) {
        if (playOnwards.isEmpty()) {
            return playOnwards;
        }
        List<Path> substituted = new ArrayList<>(playOnwards.size());
        for (Path following : playOnwards) {
            substituted.add(mirror.completedCopy(following).orElse(following));
        }
        return List.copyOf(substituted);
    }

    private static long bitrate(long sizeBytes, Duration duration) {
        double seconds = Math.max(duration.toMillis() / 1000d, 1);
        return (long) (sizeBytes / seconds);
    }
}
