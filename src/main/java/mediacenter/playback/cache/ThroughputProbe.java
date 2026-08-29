package mediacenter.playback.cache;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.OptionalLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Measures how fast a media file can actually be read from where it lives.
 *
 * <p>Reading the file itself, rather than pinging the server, measures the
 * whole path the player will use: the share, the network, the disks behind it.
 * The probe is bounded in both bytes and time so a healthy gigabit link costs a
 * fraction of a second and a struggling Wi-Fi hop can never hold the play
 * button for more than a couple of seconds.
 *
 * <p>The number is an estimate, not a promise — an operating system that has
 * the head of the file in its page cache reads it back instantly. That error
 * only ever makes a file look faster, and a file that fast needs no help.
 */
public final class ThroughputProbe {

    private static final Logger LOG = Logger.getLogger(ThroughputProbe.class.getName());

    /** Enough to spin up SMB read-ahead and average out the first-packet cost. */
    static final long DEFAULT_LIMIT_BYTES = 16L << 20;

    /** The longest a viewer is made to wait just to find out the share is slow. */
    static final long DEFAULT_LIMIT_NANOS = 2_000_000_000L;

    private static final int CHUNK_BYTES = 1 << 20;

    private ThroughputProbe() {
    }

    /** Read rate in bytes per second, or empty when the file cannot be read at all. */
    public static OptionalLong measure(Path file) {
        return measure(file, DEFAULT_LIMIT_BYTES, DEFAULT_LIMIT_NANOS);
    }

    static OptionalLong measure(Path file, long limitBytes, long limitNanos) {
        ByteBuffer buffer = ByteBuffer.allocate(CHUNK_BYTES);
        long started = System.nanoTime();
        long total = 0;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            while (total < limitBytes && System.nanoTime() - started < limitNanos) {
                buffer.clear();
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
                total += read;
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not probe read speed of " + file, e);
            return OptionalLong.empty();
        }
        if (total <= 0) {
            return OptionalLong.empty();
        }
        // A small file read entirely from cache finishes in microseconds; the
        // floor keeps the arithmetic from declaring infinite speed. Whatever
        // huge number results still just means "fast enough".
        long elapsed = Math.max(System.nanoTime() - started, 1_000_000L);
        return OptionalLong.of(total * 1_000_000_000L / elapsed);
    }
}
