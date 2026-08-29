package mediacenter.playback.cache;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads a video file's duration out of its container header.
 *
 * <p>The duration turns a file size into a bitrate, and the bitrate is what
 * decides whether a share can feed the player in real time. Only the two
 * containers that actually occur in a video library are understood — MP4 and
 * Matroska — recognised by their magic bytes rather than their extension.
 * Anything else simply reports unknown, which callers treat conservatively.
 *
 * <p>A hand-written parser for the same reason the JSON one is: two fixed
 * binary layouts, read-only, a few dozen lines each — far less to carry than a
 * media library kept {@code jlink}-friendly.
 */
public final class MediaDurations {

    private static final Logger LOG = Logger.getLogger(MediaDurations.class.getName());

    /**
     * What the container header gave up.
     *
     * @param duration          the running time, when the header carries one
     * @param headerBeforeMedia whether everything needed to start decoding sits
     *                          in front of the media data. Only such a file can
     *                          be played while it is still being copied — a
     *                          growing copy of an MP4 whose index trails the
     *                          media has no index yet, and the player cannot
     *                          even open it.
     */
    public record VideoHeader(Optional<Duration> duration, boolean headerBeforeMedia) {

        public static VideoHeader unknown() {
            return new VideoHeader(Optional.empty(), false);
        }
    }

    // MP4 box types.
    private static final int FTYP = 0x66747970;
    private static final int MOOV = 0x6D6F6F76;
    private static final int MDAT = 0x6D646174;
    private static final int MVHD = 0x6D766864;

    // Matroska/EBML element IDs, marker bit included as the specification writes them.
    private static final long EBML_HEADER = 0x1A45DFA3L;
    private static final long SEGMENT = 0x18538067L;
    private static final long SEGMENT_INFO = 0x1549A966L;
    private static final long CLUSTER = 0x1F43B675L;
    private static final long TIMESTAMP_SCALE = 0x2AD7B1L;
    private static final long INFO_DURATION = 0x4489L;

    /** Older Matroska files omit the scale; the specification defaults it to a millisecond. */
    private static final double DEFAULT_TIMESTAMP_SCALE_NANOS = 1_000_000d;

    /** Boxes and elements walked before giving up on a header that never ends. */
    private static final int WALK_LIMIT = 512;

    private MediaDurations() {
    }

    /** Never throws: an unreadable or unrecognised file is simply unknown. */
    public static VideoHeader read(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer magic = readAt(channel, 0, 8);
            if (magic.remaining() < 8) {
                return VideoHeader.unknown();
            }
            if ((magic.getInt(0) & 0xFFFFFFFFL) == EBML_HEADER) {
                return readMatroska(channel);
            }
            if (magic.getInt(4) == FTYP) {
                return readMp4(channel);
            }
            return VideoHeader.unknown();
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not read the container header of " + file, e);
            return VideoHeader.unknown();
        }
    }

    // -- MP4 -----------------------------------------------------------------

    private static VideoHeader readMp4(FileChannel channel) throws IOException {
        long fileSize = channel.size();
        long position = 0;
        boolean sawMediaData = false;
        for (int walked = 0; walked < WALK_LIMIT && position + 8 <= fileSize; walked++) {
            ByteBuffer header = readAt(channel, position, 16);
            if (header.remaining() < 8) {
                break;
            }
            long size = Integer.toUnsignedLong(header.getInt(0));
            int type = header.getInt(4);
            long headerLength = 8;
            if (size == 1) {
                if (header.remaining() < 16) {
                    break;
                }
                size = header.getLong(8);
                headerLength = 16;
            } else if (size == 0) {
                // "To the end of the file" — legal only for the last box.
                size = fileSize - position;
            }
            if (size < headerLength) {
                break;
            }
            if (type == MOOV) {
                return new VideoHeader(
                        readMovieHeaderBox(channel, position + headerLength, size - headerLength),
                        !sawMediaData);
            }
            if (type == MDAT) {
                sawMediaData = true;
            }
            position += size;
        }
        return VideoHeader.unknown();
    }

    private static Optional<Duration> readMovieHeaderBox(FileChannel channel, long start, long length)
            throws IOException {
        long position = start;
        long end = start + length;
        for (int walked = 0; walked < WALK_LIMIT && position + 8 <= end; walked++) {
            ByteBuffer header = readAt(channel, position, 8);
            if (header.remaining() < 8) {
                break;
            }
            long size = Integer.toUnsignedLong(header.getInt(0));
            int type = header.getInt(4);
            if (size < 8) {
                break;
            }
            if (type == MVHD) {
                return readMovieHeaderPayload(channel, position + 8);
            }
            position += size;
        }
        return Optional.empty();
    }

    private static Optional<Duration> readMovieHeaderPayload(FileChannel channel, long payloadStart)
            throws IOException {
        ByteBuffer payload = readAt(channel, payloadStart, 32);
        if (payload.remaining() < 20) {
            return Optional.empty();
        }
        int version = payload.get(0) & 0xFF;
        long timescale;
        long duration;
        if (version == 1) {
            if (payload.remaining() < 32) {
                return Optional.empty();
            }
            timescale = Integer.toUnsignedLong(payload.getInt(20));
            duration = payload.getLong(24);
        } else {
            timescale = Integer.toUnsignedLong(payload.getInt(12));
            duration = Integer.toUnsignedLong(payload.getInt(16));
            if (duration == 0xFFFFFFFFL) {
                // The all-ones value is the specification's "unknown".
                return Optional.empty();
            }
        }
        if (timescale <= 0 || duration <= 0) {
            return Optional.empty();
        }
        // Millisecond precision: the number feeds a bitrate estimate, nothing finer.
        return Optional.of(Duration.ofMillis((long) ((double) duration / timescale * 1000)));
    }

    // -- Matroska ------------------------------------------------------------

    private static VideoHeader readMatroska(FileChannel channel) throws IOException {
        EbmlElement ebmlHeader = readElement(channel, 0);
        if (ebmlHeader == null || ebmlHeader.id() != EBML_HEADER || !ebmlHeader.sizeKnown()) {
            return VideoHeader.unknown();
        }
        EbmlElement segment = readElement(channel, ebmlHeader.dataEnd());
        if (segment == null || segment.id() != SEGMENT) {
            return VideoHeader.unknown();
        }
        // A live-captured Segment has no size; walking then ends at the file.
        long limit = segment.sizeKnown()
                ? Math.min(segment.dataEnd(), channel.size())
                : channel.size();
        long position = segment.dataStart();
        for (int walked = 0; walked < WALK_LIMIT && position < limit; walked++) {
            EbmlElement child = readElement(channel, position);
            if (child == null || !child.sizeKnown()) {
                break;
            }
            if (child.id() == SEGMENT_INFO) {
                // The Info element in front of the first Cluster is precisely
                // what makes a Matroska file playable while still growing.
                return new VideoHeader(readSegmentInfo(channel, child), true);
            }
            if (child.id() == CLUSTER) {
                // Media before Info: a shape no muxer writes; give up rather
                // than seek through gigabytes of clusters looking for it.
                break;
            }
            position = child.dataEnd();
        }
        return VideoHeader.unknown();
    }

    private static Optional<Duration> readSegmentInfo(FileChannel channel, EbmlElement info)
            throws IOException {
        double scaleNanos = DEFAULT_TIMESTAMP_SCALE_NANOS;
        Double durationTicks = null;
        long position = info.dataStart();
        for (int walked = 0; walked < WALK_LIMIT && position < info.dataEnd(); walked++) {
            EbmlElement child = readElement(channel, position);
            if (child == null || !child.sizeKnown()) {
                break;
            }
            if (child.id() == TIMESTAMP_SCALE) {
                scaleNanos = readUnsigned(channel, child);
            } else if (child.id() == INFO_DURATION) {
                durationTicks = readFloat(channel, child);
            }
            position = child.dataEnd();
        }
        if (durationTicks == null || durationTicks <= 0 || scaleNanos <= 0) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofNanos((long) (durationTicks * scaleNanos)));
    }

    private record EbmlElement(long id, long dataStart, long size, boolean sizeKnown) {

        long dataEnd() {
            return dataStart + size;
        }
    }

    private static EbmlElement readElement(FileChannel channel, long position) throws IOException {
        ByteBuffer bytes = readAt(channel, position, 12);
        if (bytes.remaining() < 2) {
            return null;
        }
        int idLength = vintLength(bytes.get(0) & 0xFF);
        if (idLength == 0 || idLength > 4 || bytes.remaining() < idLength + 1) {
            return null;
        }
        long id = 0;
        for (int i = 0; i < idLength; i++) {
            // The marker bit stays: element IDs are quoted with it everywhere.
            id = id << 8 | (bytes.get(i) & 0xFF);
        }
        int sizeFirst = bytes.get(idLength) & 0xFF;
        int sizeLength = vintLength(sizeFirst);
        if (sizeLength == 0 || sizeLength > 8 || bytes.remaining() < idLength + sizeLength) {
            return null;
        }
        long size = sizeFirst & (0xFF >>> sizeLength);
        boolean allOnes = size == (0xFF >>> sizeLength);
        for (int i = 1; i < sizeLength; i++) {
            int value = bytes.get(idLength + i) & 0xFF;
            size = size << 8 | value;
            allOnes &= value == 0xFF;
        }
        // All size bits set means "unknown size", not a number.
        return new EbmlElement(id, position + idLength + sizeLength, size, !allOnes);
    }

    /** How many bytes a variable-length integer starting with this byte occupies. */
    private static int vintLength(int firstByte) {
        if (firstByte == 0) {
            return 0;
        }
        return Integer.numberOfLeadingZeros(firstByte) - 23;
    }

    private static long readUnsigned(FileChannel channel, EbmlElement element) throws IOException {
        if (element.size() > 8) {
            return 0;
        }
        ByteBuffer bytes = readAt(channel, element.dataStart(), (int) element.size());
        long value = 0;
        while (bytes.hasRemaining()) {
            value = value << 8 | (bytes.get() & 0xFF);
        }
        return value;
    }

    private static Double readFloat(FileChannel channel, EbmlElement element) throws IOException {
        ByteBuffer bytes = readAt(channel, element.dataStart(), (int) Math.min(element.size(), 8));
        if (element.size() == 4 && bytes.remaining() >= 4) {
            return (double) bytes.getFloat(0);
        }
        if (element.size() == 8 && bytes.remaining() >= 8) {
            return bytes.getDouble(0);
        }
        return null;
    }

    /** Reads up to {@code length} bytes at an absolute position, flipped for reading. */
    private static ByteBuffer readAt(FileChannel channel, long position, int length)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        long at = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, at);
            if (read < 0) {
                break;
            }
            at += read;
        }
        buffer.flip();
        return buffer;
    }
}
