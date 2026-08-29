package mediacenter.playback.cache;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import mediacenter.json.JsonException;
import mediacenter.json.JsonFiles;
import mediacenter.json.JsonValue;
import mediacenter.json.JsonValue.JsonArray;
import mediacenter.json.JsonValue.JsonBoolean;
import mediacenter.json.JsonValue.JsonNumber;
import mediacenter.json.JsonValue.JsonObject;
import mediacenter.json.JsonValue.JsonString;

/**
 * Local copies of media that lives on the network.
 *
 * <p>The mirror serves two masters. Before a playback it receives files a
 * throughput check found too slow to stream, copied far enough ahead that the
 * player never catches the copy front. Between playbacks it collects the files
 * a viewer keeps coming back to, so a favourite plays from the local disk even
 * when the share is off or the network is having a bad evening.
 *
 * <p>One copy runs at a time, on purpose: two copies from the same share split
 * the very bandwidth that was already too small, and the order the copies were
 * asked in — this episode before the next one — is exactly the order they are
 * needed in.
 *
 * <p>A copy is validated against the size and modification time the source had
 * when it was taken. A source that cannot be reached at all still serves its
 * copy — an unreachable share is precisely the moment the mirror exists for.
 */
public final class MediaMirror {

    private static final Logger LOG = Logger.getLogger(MediaMirror.class.getName());

    /** The second viewing is the signal: nobody re-opens a file by accident. */
    public static final int FREQUENT_PLAYS = 2;

    private static final String INDEX_FILE = "mirror-index.json";
    private static final int COPY_CHUNK_BYTES = 1 << 20;

    /** Play counts kept; beyond this the oldest are forgotten with no harm done. */
    private static final int PLAY_RECORD_LIMIT = 500;

    private final Path directory;
    private final LongSupplier capacityBytes;
    private final ExecutorService copier;

    private final Object lock = new Object();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, PlayRecord> plays = new LinkedHashMap<>();
    private final Map<String, MirrorTask> running = new HashMap<>();

    /** A mirrored (or mirroring) file. {@code complete} flips only after the last byte. */
    private record Entry(
            String source,
            String fileName,
            long sourceSize,
            long sourceModifiedMillis,
            boolean complete,
            long lastUsedMillis) {

        Entry used(long now) {
            return new Entry(source, fileName, sourceSize, sourceModifiedMillis, complete, now);
        }

        Entry completed(long now) {
            return new Entry(source, fileName, sourceSize, sourceModifiedMillis, true, now);
        }
    }

    private record PlayRecord(String source, long count, long lastPlayedMillis, boolean network) {
    }

    /**
     * @param capacityBytes read on every decision so a settings change applies at
     *                      once; zero disables the mirror and existing copies are
     *                      trimmed away the next time space is asked for
     */
    public MediaMirror(Path directory, LongSupplier capacityBytes) {
        this.directory = directory;
        this.capacityBytes = capacityBytes;
        this.copier = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "media-mirror-copy");
            // The mirror must never keep the JVM alive; a copy cut short is
            // incomplete in the index and cleaned up on the next start.
            thread.setDaemon(true);
            return thread;
        });
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            // Copies will fail loudly enough; the mirror just stays empty.
            LOG.log(Level.WARNING, "Could not create the mirror directory " + directory, e);
        }
        load();
    }

    public Path directory() {
        return directory;
    }

    /** Whether the mirror may take new copies at all. */
    public boolean enabled() {
        return capacityBytes.getAsLong() > 0;
    }

    /**
     * The finished local copy of a file, when there is one and it is still
     * current. Serving it counts as a use for eviction purposes.
     */
    public Optional<Path> completedCopy(Path source) {
        String key = key(source);
        synchronized (lock) {
            Entry entry = entries.get(key);
            if (entry == null || !entry.complete()) {
                return Optional.empty();
            }
            Path copy = directory.resolve(entry.fileName());
            if (!Files.isRegularFile(copy)) {
                LOG.log(Level.INFO, () -> "Mirror copy disappeared, forgetting it: " + copy);
                entries.remove(key);
                save();
                return Optional.empty();
            }
            try {
                BasicFileAttributes current =
                        Files.readAttributes(source, BasicFileAttributes.class);
                if (current.size() != entry.sourceSize()
                        || current.lastModifiedTime().toMillis() != entry.sourceModifiedMillis()) {
                    LOG.log(Level.INFO, () -> "Source changed since it was mirrored: " + source);
                    entries.remove(key);
                    save();
                    deleteQuietly(copy);
                    return Optional.empty();
                }
            } catch (IOException e) {
                // The share is unreachable — the copy is the whole point now.
                LOG.log(Level.INFO, () -> "Source unreachable, serving the mirror copy: " + source);
            }
            entries.put(key, entry.used(System.currentTimeMillis()));
            save();
            return Optional.of(copy);
        }
    }

    /**
     * Starts copying a file into the mirror, or joins the copy already running
     * for it. Empty when the mirror is off, the source cannot be read, or the
     * file does not fit even after evicting everything evictable.
     */
    public Optional<MirrorTask> copy(Path source) {
        String key = key(source);
        synchronized (lock) {
            MirrorTask existing = running.get(key);
            if (existing != null) {
                return Optional.of(existing);
            }
            if (!enabled()) {
                return Optional.empty();
            }
            long size;
            long modifiedMillis;
            try {
                BasicFileAttributes attributes =
                        Files.readAttributes(source, BasicFileAttributes.class);
                size = attributes.size();
                modifiedMillis = attributes.lastModifiedTime().toMillis();
            } catch (IOException e) {
                LOG.log(Level.FINE, "Cannot mirror an unreadable source " + source, e);
                return Optional.empty();
            }
            // A stale copy of the same source is replaced, not kept alongside.
            Entry stale = entries.remove(key);
            if (stale != null) {
                deleteQuietly(directory.resolve(stale.fileName()));
            }
            if (!makeRoomFor(size)) {
                LOG.log(Level.INFO, () -> "No mirror space for " + source
                        + " (" + size + " bytes within " + capacityBytes.getAsLong() + ")");
                save();
                return Optional.empty();
            }
            String fileName = fileNameFor(source);
            entries.put(key, new Entry(
                    key, fileName, size, modifiedMillis, false, System.currentTimeMillis()));
            save();
            MirrorTask task = new MirrorTask(source, directory.resolve(fileName), size);
            running.put(key, task);
            copier.execute(() -> runCopy(key, task));
            return Optional.of(task);
        }
    }

    /**
     * Counts a viewing. {@code network} marks files that live somewhere a cable
     * can take away; only those are worth mirroring for keeps.
     */
    public void recordPlayed(Path source, boolean network) {
        String key = key(source);
        synchronized (lock) {
            PlayRecord record = plays.get(key);
            long count = record == null ? 1 : record.count() + 1;
            plays.put(key, new PlayRecord(key, count, System.currentTimeMillis(), network));
            while (plays.size() > PLAY_RECORD_LIMIT) {
                String oldest = plays.values().stream()
                        .min(Comparator.comparingLong(PlayRecord::lastPlayedMillis))
                        .map(PlayRecord::source)
                        .orElseThrow();
                plays.remove(oldest);
            }
            save();
        }
    }

    /** Whether this file has earned a permanent local copy. */
    public boolean isFrequentlyPlayed(Path source) {
        synchronized (lock) {
            PlayRecord record = plays.get(key(source));
            return record != null && record.network() && record.count() >= FREQUENT_PLAYS;
        }
    }

    /** Everything the mirror is holding or reserving, in bytes. Exposed for tests. */
    public long usedBytes() {
        synchronized (lock) {
            return entries.values().stream().mapToLong(Entry::sourceSize).sum();
        }
    }

    // -- the copy ------------------------------------------------------------

    /** A copy in flight. Progress is safe to read from any thread. */
    public static final class MirrorTask {

        private final Path source;
        private final Path target;
        private final long totalBytes;
        private final long startedNanos = System.nanoTime();
        private final CountDownLatch finished = new CountDownLatch(1);
        private volatile long copiedBytes;
        private volatile boolean failed;

        private MirrorTask(Path source, Path target, long totalBytes) {
            this.source = source;
            this.target = target;
            this.totalBytes = totalBytes;
        }

        public Path source() {
            return source;
        }

        /** Where the copy is growing. Exists from the first chunk onwards. */
        public Path target() {
            return target;
        }

        public long totalBytes() {
            return totalBytes;
        }

        public long copiedBytes() {
            return copiedBytes;
        }

        public boolean isDone() {
            return finished.getCount() == 0 && !failed;
        }

        public boolean isFailed() {
            return failed;
        }

        /**
         * The copy rate actually being achieved, or zero while there is too
         * little history to say. This is the honest version of the probe's
         * estimate — measured over the real copy, not a two-second sample.
         */
        public long observedBytesPerSecond() {
            long elapsed = System.nanoTime() - startedNanos;
            if (elapsed < 200_000_000L) {
                return 0;
            }
            return copiedBytes * 1_000_000_000L / elapsed;
        }

        /** Waits for the copy to end one way or the other. */
        public boolean await(long timeoutMillis) throws InterruptedException {
            return finished.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void runCopy(String key, MirrorTask task) {
        LOG.log(Level.INFO, () -> "Mirroring " + task.source() + " -> " + task.target());
        try (FileChannel in = FileChannel.open(task.source(), StandardOpenOption.READ);
                FileChannel out = FileChannel.open(task.target(),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.allocate(COPY_CHUNK_BYTES);
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Copy interrupted");
                }
                buffer.clear();
                int read = in.read(buffer);
                if (read < 0) {
                    break;
                }
                buffer.flip();
                while (buffer.hasRemaining()) {
                    out.write(buffer);
                }
                task.copiedBytes += read;
            }
            synchronized (lock) {
                Entry entry = entries.get(key);
                if (entry != null) {
                    entries.put(key, entry.completed(System.currentTimeMillis()));
                    save();
                }
            }
            LOG.log(Level.INFO, () -> "Mirrored " + task.source()
                    + " (" + task.copiedBytes() + " bytes)");
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Mirroring failed for " + task.source(), e);
            task.failed = true;
            synchronized (lock) {
                entries.remove(key);
                save();
            }
            deleteQuietly(task.target());
        } finally {
            synchronized (lock) {
                running.remove(key);
            }
            task.finished.countDown();
        }
    }

    // -- housekeeping ---------------------------------------------------------

    /** Evicts least-recently-used finished copies until {@code incoming} fits. */
    private boolean makeRoomFor(long incoming) {
        long capacity = capacityBytes.getAsLong();
        if (incoming > capacity) {
            return false;
        }
        while (usedBytesLocked() + incoming > capacity) {
            Optional<Entry> victim = entries.values().stream()
                    .filter(Entry::complete)
                    .filter(entry -> !running.containsKey(entry.source()))
                    .min(Comparator.comparingLong(Entry::lastUsedMillis));
            if (victim.isEmpty()) {
                return false;
            }
            Entry evicted = victim.get();
            LOG.log(Level.INFO, () -> "Evicting mirror copy of " + evicted.source());
            entries.remove(evicted.source());
            deleteQuietly(directory.resolve(evicted.fileName()));
        }
        return true;
    }

    private long usedBytesLocked() {
        return entries.values().stream().mapToLong(Entry::sourceSize).sum();
    }

    private void load() {
        try {
            Optional<JsonObject> document = JsonFiles.readObject(directory.resolve(INDEX_FILE));
            if (document.isEmpty()) {
                return;
            }
            synchronized (lock) {
                for (JsonObject object : document.get().objectArray("entries")) {
                    readEntry(object).ifPresent(entry -> entries.put(entry.source(), entry));
                }
                for (JsonObject object : document.get().objectArray("plays")) {
                    readPlay(object).ifPresent(record -> plays.put(record.source(), record));
                }
                // A copy that was still running when the application died is a
                // torso with no owner; it goes, and so does an entry whose file
                // someone deleted by hand.
                List<String> broken = new ArrayList<>();
                for (Entry entry : entries.values()) {
                    Path copy = directory.resolve(entry.fileName());
                    if (!entry.complete() || !Files.isRegularFile(copy)) {
                        broken.add(entry.source());
                        deleteQuietly(copy);
                    }
                }
                broken.forEach(entries::remove);
                if (!broken.isEmpty()) {
                    save();
                }
            }
        } catch (JsonException e) {
            LOG.log(Level.WARNING, "Mirror index is not valid JSON, starting over", e);
            JsonFiles.quarantine(directory.resolve(INDEX_FILE));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read the mirror index", e);
        }
    }

    /** Callers hold {@code lock}. Losing the index loses copies, not media. */
    private void save() {
        Map<String, JsonValue> document = new LinkedHashMap<>();
        List<JsonValue> entryValues = new ArrayList<>();
        for (Entry entry : entries.values()) {
            Map<String, JsonValue> members = new LinkedHashMap<>();
            members.put("source", new JsonString(entry.source()));
            members.put("file", new JsonString(entry.fileName()));
            members.put("sourceSize", new JsonNumber(entry.sourceSize()));
            members.put("sourceModifiedMillis", new JsonNumber(entry.sourceModifiedMillis()));
            members.put("complete", new JsonBoolean(entry.complete()));
            members.put("lastUsedMillis", new JsonNumber(entry.lastUsedMillis()));
            entryValues.add(new JsonObject(members));
        }
        document.put("entries", new JsonArray(entryValues));
        List<JsonValue> playValues = new ArrayList<>();
        for (PlayRecord record : plays.values()) {
            Map<String, JsonValue> members = new LinkedHashMap<>();
            members.put("source", new JsonString(record.source()));
            members.put("count", new JsonNumber(record.count()));
            members.put("lastPlayedMillis", new JsonNumber(record.lastPlayedMillis()));
            members.put("network", new JsonBoolean(record.network()));
            playValues.add(new JsonObject(members));
        }
        document.put("plays", new JsonArray(playValues));
        try {
            JsonFiles.write(directory.resolve(INDEX_FILE), new JsonObject(document));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not write the mirror index", e);
        }
    }

    private static Optional<Entry> readEntry(JsonObject object) {
        Optional<String> source = object.nonBlankString("source");
        Optional<String> file = object.nonBlankString("file");
        if (source.isEmpty() || file.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Entry(
                source.get(),
                file.get(),
                object.longValue("sourceSize").orElse(0L),
                object.longValue("sourceModifiedMillis").orElse(0L),
                object.booleanValue("complete", false),
                object.longValue("lastUsedMillis").orElse(0L)));
    }

    private static Optional<PlayRecord> readPlay(JsonObject object) {
        return object.nonBlankString("source").map(source -> new PlayRecord(
                source,
                object.longValue("count").orElse(0L),
                object.longValue("lastPlayedMillis").orElse(0L),
                object.booleanValue("network", false)));
    }

    private static String key(Path source) {
        return source.toString();
    }

    /**
     * Content-free name derived from the source path, keeping the extension:
     * the player sniffs formats, but a familiar suffix costs nothing and helps
     * anyone looking at the cache directory by hand.
     */
    private static String fileNameFor(Path source) {
        String name = String.valueOf(source.getFileName());
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 && name.length() - dot <= 6 ? name.substring(dot) : "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(source.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex + extension;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform specification.
            throw new AssertionError(e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not delete " + file, e);
        }
    }
}
