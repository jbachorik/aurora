package mediacenter.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PngWriterTest {

    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    @Test
    @DisplayName("the file opens with the PNG signature and a header naming its size")
    void writesTheSignatureAndHeader() throws IOException {
        byte[] png = write(2, 3, new int[] {
            0xFF000000, 0xFFFFFFFF,
            0xFFFF0000, 0xFF00FF00,
            0xFF0000FF, 0x80123456,
        });

        assertArrayEquals(SIGNATURE, java.util.Arrays.copyOf(png, 8));

        ByteBuffer header = ByteBuffer.wrap(chunk(png, "IHDR"));
        assertEquals(2, header.getInt());
        assertEquals(3, header.getInt());
        assertEquals(8, header.get());   // bit depth
        assertEquals(6, header.get());   // colour type: RGBA
    }

    @Test
    @DisplayName("pixels survive the round trip, alpha included")
    void roundTripsEveryPixel() throws IOException, DataFormatException {
        int[] argb = {
            0xFF000000, 0xFFFFFFFF,
            0xFFFF0000, 0x8000FF00,
        };

        byte[] scanlines = inflate(chunk(write(2, 2, argb), "IDAT"));

        // Each row is a filter byte followed by RGBA quadruples.
        assertEquals(2 * (1 + 2 * 4), scanlines.length);
        assertEquals(0, scanlines[0]);
        assertArrayEquals(
                new byte[] {0, 0, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
                java.util.Arrays.copyOfRange(scanlines, 1, 9));
        assertEquals(0, scanlines[9]);
        assertArrayEquals(
                new byte[] {(byte) 0xFF, 0, 0, (byte) 0xFF, 0, (byte) 0xFF, 0, (byte) 0x80},
                java.util.Arrays.copyOfRange(scanlines, 10, 18));
    }

    @Test
    @DisplayName("each chunk carries a checksum a reader can verify")
    void checksumsEveryChunk() throws IOException {
        byte[] png = write(1, 1, new int[] {0xFF808080});

        int offset = SIGNATURE.length;
        while (offset < png.length) {
            int length = ByteBuffer.wrap(png, offset, 4).getInt();
            CRC32 crc = new CRC32();
            crc.update(png, offset + 4, 4 + length);
            int stored = ByteBuffer.wrap(png, offset + 8 + length, 4).getInt();
            assertEquals((int) crc.getValue(), stored);
            offset += 12 + length;
        }
        assertEquals(png.length, offset);
    }

    private static byte[] write(int width, int height, int[] argb) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PngWriter.write(width, height, (x, y) -> argb[y * width + x], out);
        return out.toByteArray();
    }

    /** Contents of the first chunk of the given type. */
    private static byte[] chunk(byte[] png, String type) {
        int offset = SIGNATURE.length;
        while (offset < png.length) {
            int length = ByteBuffer.wrap(png, offset, 4).getInt();
            String name = new String(png, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (name.equals(type)) {
                return java.util.Arrays.copyOfRange(png, offset + 8, offset + 8 + length);
            }
            offset += 12 + length;
        }
        throw new AssertionError("No " + type + " chunk");
    }

    private static byte[] inflate(byte[] data) throws DataFormatException {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (!inflater.finished()) {
            int read = inflater.inflate(buffer);
            if (read == 0) {
                break;
            }
            out.write(buffer, 0, read);
        }
        inflater.end();
        return out.toByteArray();
    }
}
