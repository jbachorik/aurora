package mediacenter.playback.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mediacenter.playback.cache.MediaDurations.VideoHeader;

class MediaDurationsTest {

    @Test
    @DisplayName("an MP4 with its index up front reveals its duration and streams while growing")
    void readsAnMp4WithTheMoovFirst(@TempDir Path temp) throws IOException {
        Path file = temp.resolve("film.mp4");
        Files.write(file, mp4(true, 1000, 60_000));

        VideoHeader header = MediaDurations.read(file);

        assertEquals(Optional.of(Duration.ofSeconds(60)), header.duration());
        assertTrue(header.headerBeforeMedia());
    }

    @Test
    @DisplayName("an MP4 whose index trails the media still gives a duration, but cannot grow-play")
    void readsAnMp4WithTheMoovLast(@TempDir Path temp) throws IOException {
        Path file = temp.resolve("film.mp4");
        Files.write(file, mp4(false, 600, 45_000));

        VideoHeader header = MediaDurations.read(file);

        assertEquals(Optional.of(Duration.ofSeconds(75)), header.duration());
        assertFalse(header.headerBeforeMedia());
    }

    @Test
    @DisplayName("a Matroska file's Info element yields the duration")
    void readsAMatroskaDuration(@TempDir Path temp) throws IOException {
        Path file = temp.resolve("episode.mkv");
        // 2_700_000 ticks at the default millisecond scale: 45 minutes.
        Files.write(file, matroska(Optional.of(1_000_000L), 2_700_000f));

        VideoHeader header = MediaDurations.read(file);

        assertEquals(Optional.of(Duration.ofMinutes(45)), header.duration());
        assertTrue(header.headerBeforeMedia());
    }

    @Test
    @DisplayName("a Matroska file without a timestamp scale uses the specification default")
    void matroskaScaleDefaultsToAMillisecond(@TempDir Path temp) throws IOException {
        Path file = temp.resolve("episode.mkv");
        Files.write(file, matroska(Optional.empty(), 90_000f));

        assertEquals(Optional.of(Duration.ofSeconds(90)), MediaDurations.read(file).duration());
    }

    @Test
    void anythingElseIsUnknown(@TempDir Path temp) throws IOException {
        Path file = temp.resolve("mystery.avi");
        Files.write(file, new byte[] {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'A', 'V', 'I', ' '});

        VideoHeader header = MediaDurations.read(file);

        assertTrue(header.duration().isEmpty());
        assertFalse(header.headerBeforeMedia());
    }

    @Test
    void aMissingFileIsUnknownRatherThanAnError(@TempDir Path temp) {
        assertEquals(VideoHeader.unknown(), MediaDurations.read(temp.resolve("gone.mkv")));
    }

    // -- synthesised containers ----------------------------------------------

    /** The smallest MP4 the parser accepts: ftyp, then moov/mvhd and mdat in either order. */
    private static byte[] mp4(boolean moovFirst, int timescale, long duration) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(box("ftyp", new byte[8]));

        ByteBuffer mvhdPayload = ByteBuffer.allocate(20);
        mvhdPayload.putInt(0); // version 0, no flags
        mvhdPayload.putInt(0); // creation
        mvhdPayload.putInt(0); // modification
        mvhdPayload.putInt(timescale);
        mvhdPayload.putInt((int) duration);
        byte[] moov = box("moov", box("mvhd", mvhdPayload.array()));
        byte[] mdat = box("mdat", new byte[32]);

        out.write(moovFirst ? moov : mdat);
        out.write(moovFirst ? mdat : moov);
        return out.toByteArray();
    }

    private static byte[] box(String type, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + payload.length);
        buffer.putInt(8 + payload.length);
        buffer.put(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.put(payload);
        return buffer.array();
    }

    /** EBML header, then a Segment whose first child is Info with scale and duration. */
    private static byte[] matroska(Optional<Long> timestampScale, float durationTicks)
            throws IOException {
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        if (timestampScale.isPresent()) {
            info.write(new byte[] {0x2A, (byte) 0xD7, (byte) 0xB1, (byte) 0x83});
            long scale = timestampScale.get();
            info.write(new byte[] {
                    (byte) (scale >> 16), (byte) (scale >> 8), (byte) scale});
        }
        info.write(new byte[] {0x44, (byte) 0x89, (byte) 0x84});
        info.write(ByteBuffer.allocate(4).putFloat(durationTicks).array());

        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        segment.write(new byte[] {0x15, 0x49, (byte) 0xA9, 0x66});
        segment.write(0x80 | info.size());
        info.writeTo(segment);

        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0x84, 1, 2, 3, 4});
        file.write(new byte[] {0x18, 0x53, (byte) 0x80, 0x67});
        file.write(0x80 | segment.size());
        segment.writeTo(file);
        return file.toByteArray();
    }
}
