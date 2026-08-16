package mediacenter.ui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * The smallest PNG encoder that serves {@link SceneSnapshot}: 8-bit RGBA, one
 * uncompressed filter type, no interlacing.
 *
 * <p>Hand-written because {@code ImageIO} lives in {@code java.desktop}, which the
 * module graph deliberately leaves out — the runtime image is 11 modules and an
 * encoder used only by a debugging flag is no reason to grow it. Everything used
 * here ({@link Deflater}, {@link CRC32}) is in {@code java.base}.
 *
 * <p>Deliberately takes pixels rather than an {@code Image}, so the encoding can be
 * tested without a JavaFX toolkit.
 */
public final class PngWriter {

    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    private static final byte BIT_DEPTH = 8;
    private static final byte COLOUR_TYPE_RGBA = 6;
    private static final byte FILTER_NONE = 0;

    /** Supplies a pixel as packed ARGB, the layout JavaFX reads out. */
    @FunctionalInterface
    public interface Pixels {
        int argbAt(int x, int y);
    }

    private PngWriter() {
    }

    public static void write(int width, int height, Pixels pixels, OutputStream out) throws IOException {
        out.write(SIGNATURE);

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        writeInt(header, width);
        writeInt(header, height);
        header.write(BIT_DEPTH);
        header.write(COLOUR_TYPE_RGBA);
        header.write(0); // deflate, the only compression PNG defines
        header.write(0); // adaptive filtering
        header.write(0); // no interlacing
        chunk(out, "IHDR", header.toByteArray());

        chunk(out, "IDAT", deflate(scanlines(width, height, pixels)));
        chunk(out, "IEND", new byte[0]);
    }

    /** Rows of RGBA, each behind the filter byte PNG requires. */
    private static byte[] scanlines(int width, int height, Pixels pixels) {
        byte[] raw = new byte[height * (width * 4 + 1)];
        int offset = 0;
        for (int y = 0; y < height; y++) {
            raw[offset++] = FILTER_NONE;
            for (int x = 0; x < width; x++) {
                int argb = pixels.argbAt(x, y);
                raw[offset++] = (byte) (argb >> 16);
                raw[offset++] = (byte) (argb >> 8);
                raw[offset++] = (byte) argb;
                raw[offset++] = (byte) (argb >> 24);
            }
        }
        return raw;
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        while (!deflater.finished()) {
            compressed.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return compressed.toByteArray();
    }

    private static void chunk(OutputStream out, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        writeInt(out, data.length);
        out.write(typeBytes);
        out.write(data);

        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }
}
