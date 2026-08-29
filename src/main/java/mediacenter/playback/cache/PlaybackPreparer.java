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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import mediacenter.playback.PlayablePaths;
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
 * <p>The wait is the viewer's, not the machine's: progress goes to a listener
 * the shell shows as an overlay, and a {@link BufferingControl} lets the
 * viewer cancel the playback or start it at once, stutters accepted. When
 * even the head start would take unreasonably long to build, the film starts
 * immediately over the slow share with a warning while the mirror quietly
 * takes a full copy for next time — waiting half an hour in front of a black
 * screen is never the right answer to a play button.
 *
 * <p>Whatever way the first title plays, the titles queued behind it are
 * copied into the mirror <em>while it plays</em>: at full speed when the
 * picture comes off the local disk, or throttled to the share's measured
 * surplus when the picture is streaming — a greedy copy would cause the very
 * stutter it exists to prevent. A queued title whose copy is guaranteed to
 * finish before the player reaches it is handed over as its local path.
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

    /** What the buffering overlay shows. {@code etaSeconds} is -1 while unknown. */
    public record BufferingProgress(int percent, long etaSeconds) {
    }

    /**
     * The viewer's two ways out of a buffering wait, safe to poke from any
     * thread. One control belongs to one playback; the shell wires its keys to
     * it while the overlay is up.
     */
    public static final class BufferingControl {

        private volatile boolean cancelled;
        private volatile boolean playNow;

        /** Never mind: no playback, the browse page carries on. */
        public void cancel() {
            cancelled = true;
        }

        /** Start over the slow share right away, stutters accepted. */
        public void playNow() {
            playNow = true;
        }

        boolean isCancelled() {
            return cancelled;
        }

        boolean isPlayNow() {
            return playNow;
        }
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
    static final String SLOW_SHARE_NO_MIRROR_NOTICE =
            "The network share is slow; playback may stutter.";

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

    /** How many upcoming titles a playback tries to have copied behind it. */
    static final int PREFETCH_LOOKAHEAD = 3;

    /**
     * Bandwidth the stream must leave over before a copy may run beside it.
     * Below this a prefetch would take hours and endanger the picture for
     * nearly nothing.
     */
    static final long MINIMUM_PREFETCH_SURPLUS = 256L << 10;

    private final MediaMirror mirror;
    private final Throughput throughput;
    private final Headers headers;
    private final Predicate<Path> network;
    private final long pollMillis;

    public PlaybackPreparer(MediaMirror mirror) {
        this(mirror, ThroughputProbe::measure, MediaDurations::read,
                PlaybackPreparer::isNetworkPath, 250);
    }

    PlaybackPreparer(
            MediaMirror mirror,
            Throughput throughput,
            Headers headers,
            Predicate<Path> network,
            long pollMillis) {
        this.mirror = mirror;
        this.throughput = throughput;
        this.headers = headers;
        this.network = network;
        this.pollMillis = pollMillis;
    }

    /**
     * Blocks until the given file is ready to play smoothly, or until waiting
     * longer stops being worth it. Called on a background thread; the progress
     * consumer hears about the buffering as it happens, on the same thread.
     *
     * @return empty when the viewer cancelled through the control — nothing
     *         should play; any copies already running finish in the background
     */
    public Optional<Prepared> prepare(
            Path mediaFile,
            List<Path> playOnwards,
            Consumer<BufferingProgress> progress,
            BufferingControl control) {
        try {
            return decide(mediaFile, playOnwards, progress, control);
        } catch (RuntimeException e) {
            // Preparation is an optimisation; failing at it must never cost the film.
            LOG.log(Level.WARNING, "Playback preparation failed; playing directly", e);
            return Optional.of(Prepared.passthrough(mediaFile, playOnwards));
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
     * A session for the built-in player, which decides per episode at the
     * moment each one starts. Paths answer from a map that starts with today's
     * finished copies and grows as copies land — so an episode whose copy
     * finished during the previous one plays locally, with no lookup (and no
     * stat against a possibly dead share) on the UI thread.
     *
     * <p>Prefetching of the following titles happens only when the first entry
     * itself plays from the mirror: then the bandwidth is free. A run that
     * starts by <em>streaming</em> keeps the network to itself — until the
     * stream proves too slow, at which point the session starts a rescue copy
     * behind it and offers the player a takeover the moment the maths allow
     * one (see {@link PlayablePaths}). Call on a background thread.
     */
    public PlayablePaths embeddedSession(List<Path> files) {
        return new EmbeddedSession(files);
    }

    private final class EmbeddedSession implements PlayablePaths {

        private final Map<Path, Path> resolved;
        private final Map<Path, Rescue> rescues = new ConcurrentHashMap<>();

        /** A copy running behind an entry that is playing from its original path. */
        private record Rescue(MirrorTask task, long size, VideoHeader header, long fallbackRate) {
        }

        private EmbeddedSession(List<Path> files) {
            resolved = new ConcurrentHashMap<>(completedCopies(files));
            boolean firstPlaysLocally = !files.isEmpty() && resolved.containsKey(files.getFirst());
            if (firstPlaysLocally && mirror.enabled()) {
                int planned = 0;
                for (Path file : files.subList(1, files.size())) {
                    if (planned >= PREFETCH_LOOKAHEAD) {
                        break;
                    }
                    if (resolved.containsKey(file) || !network.test(file)) {
                        continue;
                    }
                    if (!copyIntoResolved(file)) {
                        break;
                    }
                    planned++;
                }
            }
        }

        @Override
        public Path playablePath(Path entry) {
            return resolved.getOrDefault(entry, entry);
        }

        @Override
        public void startedFromOriginal(Path entry) {
            if (rescues.containsKey(entry) || !worthRescuing(entry)) {
                return;
            }
            OptionalLong measured = throughput.bytesPerSecond(entry);
            if (measured.isEmpty()) {
                return;
            }
            long size;
            try {
                size = Files.size(entry);
            } catch (IOException e) {
                return;
            }
            VideoHeader header = headers.read(entry);
            long requiredRate = header.duration()
                    .map(duration -> (long) (bitrate(size, duration) * HEADROOM))
                    .orElse(ASSUMED_RATE_UNKNOWN_DURATION);
            // The probe ran beside the playback, so it reads low; a share that
            // still clears the bar under that handicap needs no rescue.
            if (measured.getAsLong() >= requiredRate) {
                return;
            }
            beginRescue(entry, size, header, measured.getAsLong());
        }

        @Override
        public void pausedOnOriginal(Path entry) {
            // Pausing is the viewer's own verdict on the stream; a share the
            // start-time probe passed can still have degraded since. No speed
            // test here — the pause is the evidence.
            if (rescues.containsKey(entry) || !worthRescuing(entry)) {
                return;
            }
            long size;
            try {
                size = Files.size(entry);
            } catch (IOException e) {
                return;
            }
            beginRescue(entry, size, headers.read(entry),
                    throughput.bytesPerSecond(entry).orElse(0));
        }

        @Override
        public Optional<Path> takeoverAt(Path entry, long positionMillis) {
            Rescue rescue = rescues.get(entry);
            if (rescue == null || rescue.task().isFailed()) {
                return Optional.empty();
            }
            MirrorTask task = rescue.task();
            if (task.isDone()) {
                return Optional.of(task.target());
            }
            VideoHeader header = rescue.header();
            if (header.duration().isEmpty() || !header.headerBeforeMedia()) {
                // Without both, only a finished copy is safe to open.
                return Optional.empty();
            }
            double durationMillis = header.duration().get().toMillis();
            if (durationMillis <= 0 || positionMillis >= durationMillis) {
                return Optional.empty();
            }
            long rate = task.observedBytesPerSecond();
            if (rate <= 0) {
                rate = rescue.fallbackRate();
            }
            if (rate <= 0) {
                return Optional.empty();
            }
            // The pre-play lead formula, started mid-film: the copy must be
            // past the current position by a margin, and far enough ahead that
            // the remaining play time out-lasts the remaining copy time.
            long positionBytes = (long) (rescue.size() * (positionMillis / durationMillis));
            double remainingSeconds = (durationMillis - positionMillis) / 1000d;
            long needed = Math.min(rescue.size(), Math.max(
                    positionBytes + MINIMUM_LEAD_BYTES,
                    (long) (rescue.size() - rate / HEADROOM * remainingSeconds)));
            return task.copiedBytes() >= needed
                    ? Optional.of(task.target())
                    : Optional.empty();
        }

        @Override
        public Optional<String> adviceFor(Path entry) {
            Rescue rescue = rescues.get(entry);
            if (rescue == null || rescue.task().isFailed()) {
                return Optional.empty();
            }
            return Optional.of(
                    "Downloading a local copy — pause a while, then resume to switch over.");
        }

        private boolean worthRescuing(Path entry) {
            return mirror.enabled() && network.test(entry) && !resolved.containsKey(entry);
        }

        private void beginRescue(Path entry, long size, VideoHeader header, long fallbackRate) {
            mirror.copy(entry).ifPresent(task -> {
                LOG.log(Level.INFO, () -> "Rescue copy behind the stream of " + entry);
                rescues.put(entry, new Rescue(task, size, header, fallbackRate));
                task.whenDone(() -> {
                    if (!task.isFailed()) {
                        resolved.put(task.source(), task.target());
                    }
                });
            });
        }

        /** Starts a copy that lands in the resolved map when it completes. */
        private boolean copyIntoResolved(Path file) {
            Optional<MirrorTask> task = mirror.copy(file);
            task.ifPresent(started -> started.whenDone(() -> {
                if (!started.isFailed()) {
                    resolved.put(started.source(), started.target());
                }
            }));
            return task.isPresent();
        }
    }

    /**
     * Counts a finished viewing and, once a network file has proven popular,
     * takes a full local copy in the background — the "local mirroring" that
     * frees a favourite from the network altogether. Called after the player
     * exits, when the bandwidth is free again.
     */
    public void recordPlayed(Path mediaFile) {
        mirror.recordPlayed(mediaFile, network.test(mediaFile));
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

    private Optional<Prepared> decide(
            Path mediaFile,
            List<Path> playOnwards,
            Consumer<BufferingProgress> progress,
            BufferingControl control) {
        // A finished mirror copy answers every question at once, and playing
        // from it leaves the whole network to the prefetch behind it.
        Optional<Path> copy = mirror.completedCopy(mediaFile);
        if (copy.isPresent()) {
            LOG.log(Level.INFO, () -> "Playing the mirror copy of " + mediaFile);
            List<Path> queue = prefetchOnwards(
                    playOnwards, prefetchRateBehindLocalPlayback(playOnwards),
                    headers.read(copy.get()).duration(), 0);
            return Optional.of(new Prepared(copy.get(), queue, Optional.empty()));
        }

        OptionalLong measured = throughput.bytesPerSecond(mediaFile);
        if (measured.isEmpty()) {
            // Unreadable now is the player's message to deliver, not ours to guess at.
            return Optional.of(Prepared.passthrough(mediaFile, substitute(playOnwards)));
        }
        long rate = measured.getAsLong();
        long size;
        try {
            size = Files.size(mediaFile);
        } catch (IOException e) {
            return Optional.of(Prepared.passthrough(mediaFile, substitute(playOnwards)));
        }
        VideoHeader header = headers.read(mediaFile);
        long requiredRate = header.duration()
                .map(duration -> (long) (bitrate(size, duration) * HEADROOM))
                .orElse(ASSUMED_RATE_UNKNOWN_DURATION);
        LOG.log(Level.INFO, () -> "Throughput of " + mediaFile + ": " + (rate >> 10)
                + " KiB/s, needs " + (requiredRate >> 10) + " KiB/s"
                + header.duration().map(d -> " over " + d.toMinutes() + "min").orElse(" (duration unknown)"));
        if (rate >= requiredRate) {
            // Fast enough to stream — and while it streams, the surplus quietly
            // carries the queued titles into the mirror behind it.
            long surplus = rate - requiredRate;
            List<Path> queue = network.test(mediaFile) && surplus >= MINIMUM_PREFETCH_SURPLUS
                    ? prefetchOnwards(playOnwards, surplus, header.duration(), surplus)
                    : substitute(playOnwards);
            return Optional.of(Prepared.passthrough(mediaFile, queue));
        }
        if (!mirror.enabled()) {
            return Optional.of(new Prepared(mediaFile, substitute(playOnwards),
                    Optional.of(SLOW_SHARE_NO_MIRROR_NOTICE)));
        }
        return bufferAhead(mediaFile, size, header, rate, playOnwards, progress, control);
    }

    private Optional<Prepared> bufferAhead(
            Path mediaFile,
            long size,
            VideoHeader header,
            long probedRate,
            List<Path> playOnwards,
            Consumer<BufferingProgress> progress,
            BufferingControl control) {

        // How much of the next title must also be down before starting, so the
        // player rolls into it without a pause. Only a title whose header is up
        // front can be handed over while its copy still grows.
        Optional<NextTitle> plannedNext = playOnwards.isEmpty() ? Optional.empty()
                : planNextTitle(playOnwards.getFirst());

        Optional<MirrorTask> started = mirror.copy(mediaFile);
        if (started.isEmpty()) {
            return Optional.of(new Prepared(mediaFile, substitute(playOnwards),
                    Optional.of(SLOW_SHARE_NO_MIRROR_NOTICE)));
        }
        MirrorTask mainTask = started.get();

        long mainLead = leadBytes(size, header, probedRate);
        long nextLead = plannedNext.map(next -> next.leadBytes(probedRate)).orElse(0L);
        // A copy already part-done — running since an attempt the viewer gave
        // up on, or since a play-now — counts toward the head start, so coming
        // back to a film that has been downloading means a short wait, not the
        // full one all over again.
        long stillNeeded = Math.max(0, mainLead + nextLead - mainTask.copiedBytes());
        long estimatedWaitSeconds = stillNeeded / Math.max(probedRate, 1);
        if (estimatedWaitSeconds > MAX_BUFFER_WAIT_SECONDS) {
            // Play now over the slow share; the copy keeps running so the
            // *next* viewing comes off the local disk.
            LOG.log(Level.INFO, () -> "Buffering would take ~" + estimatedWaitSeconds
                    + "s; playing " + mediaFile + " directly and mirroring in the background");
            return Optional.of(new Prepared(
                    mediaFile, substitute(playOnwards), Optional.of(SLOW_SHARE_NOTICE)));
        }
        Optional<MirrorTask> nextTask = plannedNext.flatMap(next -> mirror.copy(next.path()));

        long deadline = System.nanoTime() + HARD_WAIT_LIMIT_NANOS;
        int lastReported = -1;
        boolean buffered = false;
        while (System.nanoTime() < deadline) {
            if (control.isCancelled()) {
                // The copies keep running: the viewer said "not now", and the
                // finished copy makes the next "now" instant.
                LOG.log(Level.INFO, () -> "Buffering cancelled by the viewer for " + mediaFile);
                return Optional.empty();
            }
            if (control.isPlayNow()) {
                LOG.log(Level.INFO, () -> "Viewer chose to play " + mediaFile + " unbuffered");
                return Optional.of(new Prepared(
                        mediaFile, substitute(playOnwards), Optional.of(SLOW_SHARE_NOTICE)));
            }
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
                progress.accept(new BufferingProgress(
                        percent, (needed - copied) / Math.max(liveRate, 1)));
            }
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.of(Prepared.passthrough(mediaFile, substitute(playOnwards)));
            }
        }
        if (!buffered) {
            // The copy failed or the share degraded past saving: a partial
            // mirror must never reach the player, it would end mid-scene.
            return Optional.of(new Prepared(mediaFile, substitute(playOnwards),
                    Optional.of(SLOW_SHARE_NO_MIRROR_NOTICE)));
        }

        List<Path> queue = new ArrayList<>(substitute(playOnwards));
        if (nextTask.isPresent() && !nextTask.get().isFailed() && !queue.isEmpty()) {
            // Covered by the lead above: by the time the player reaches it, the
            // copy is far enough ahead to carry it to the end.
            queue.set(0, nextTask.get().target());
        }
        LOG.log(Level.INFO, () -> "Buffered enough of " + mediaFile + "; starting from the mirror");
        return Optional.of(new Prepared(mainTask.target(), List.copyOf(queue), Optional.empty()));
    }

    // -- prefetching the queue ------------------------------------------------

    /**
     * Starts copying the next titles into the mirror to land while the current
     * one plays, and swaps a queued path for its (possibly still growing) copy
     * when the arithmetic guarantees the copy is whole before the player gets
     * there. Where the guarantee cannot be given the original path stays — the
     * copy still runs, for the next session — and nothing further is queued,
     * because bandwidth that cannot reach one title cannot reach the ones
     * after it either.
     *
     * @param copyRate          bytes per second the copies are expected to get
     * @param currentDuration   playing time of the title now starting — the
     *                          clock the guarantees are measured against
     * @param throttleBytesPerSecond ceiling passed to each copy, {@code 0} for
     *                          none; the surplus rate when the current title
     *                          streams from the same share
     */
    private List<Path> prefetchOnwards(
            List<Path> playOnwards,
            long copyRate,
            Optional<Duration> currentDuration,
            long throttleBytesPerSecond) {
        List<Path> queue = new ArrayList<>(substitute(playOnwards));
        if (playOnwards.isEmpty() || !mirror.enabled() || copyRate <= 0) {
            return List.copyOf(queue);
        }
        // Seconds of playing time before the player reaches the title at hand;
        // grows with each title whose duration is known, dies with one whose
        // is not — guarantees cannot be built over an unknown gap.
        double secondsAvailable = currentDuration.map(d -> d.toMillis() / 1000d).orElse(0d);
        boolean chainAlive = currentDuration.isPresent();
        double copySecondsCommitted = 0;
        int planned = 0;
        for (int i = 0; i < playOnwards.size() && planned < PREFETCH_LOOKAHEAD; i++) {
            Path title = playOnwards.get(i);
            if (!queue.get(i).equals(title) || !network.test(title)) {
                // Already substituted with a finished copy, or local anyway:
                // nothing to fetch, but its length keeps the guarantee clock
                // going for the titles behind it — if it has one.
                Optional<Double> seconds = durationSeconds(queue.get(i));
                chainAlive &= seconds.isPresent();
                secondsAvailable += seconds.orElse(0d);
                continue;
            }
            long titleSize;
            try {
                titleSize = Files.size(title);
            } catch (IOException e) {
                break;
            }
            Optional<Double> titleSeconds = durationSeconds(title);
            Optional<MirrorTask> task = mirror.copy(title, throttleBytesPerSecond);
            if (task.isEmpty()) {
                break;
            }
            planned++;
            copySecondsCommitted += (double) titleSize / copyRate * HEADROOM;
            if (chainAlive && titleSeconds.isPresent() && copySecondsCommitted <= secondsAvailable) {
                LOG.log(Level.INFO, () -> "Prefetching " + title + "; the player will get the copy");
                queue.set(i, task.get().target());
                secondsAvailable += titleSeconds.get();
            } else {
                // This copy may still be running when the player arrives; the
                // original path plays, the copy serves the next session.
                LOG.log(Level.INFO, () -> "Prefetching " + title + " for a later viewing");
                break;
            }
        }
        return List.copyOf(queue);
    }

    /**
     * The copy rate to expect when the current title plays from the local disk:
     * a probe of the first title the mirror would fetch. Zero when there is
     * nothing to fetch or the share cannot be measured.
     */
    private long prefetchRateBehindLocalPlayback(List<Path> playOnwards) {
        for (Path title : playOnwards) {
            if (network.test(title) && mirror.completedCopy(title).isEmpty()) {
                return throughput.bytesPerSecond(title).orElse(0);
            }
        }
        return 0;
    }

    private Optional<Double> durationSeconds(Path file) {
        return headers.read(file).duration().map(d -> d.toMillis() / 1000d);
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
