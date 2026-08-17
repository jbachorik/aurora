package mediacenter.media;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExifOrientationTest {

    /** A JPEG whose APP1 segment carries nothing but an orientation tag. */
    private static Path jpegWithOrientation(Path directory, String name, int orientation) throws IOException {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        header.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
        tiff.write(header.array());

        ByteBuffer ifd = ByteBuffer.allocate(2 + 12 + 4).order(ByteOrder.BIG_ENDIAN);
        ifd.putShort((short) 1);            // one entry
        ifd.putShort((short) 0x0112);       // Orientation
        ifd.putShort((short) 3);            // SHORT
        ifd.putInt(1);                      // one value
        ifd.putShort((short) orientation);  // value, left-aligned in four bytes
        ifd.putShort((short) 0);
        ifd.putInt(0);                      // no next IFD
        tiff.write(ifd.array());

        byte[] exifBody = tiff.toByteArray();
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        jpeg.write(new byte[] {(byte) 0xFF, (byte) 0xD8});
        jpeg.write(new byte[] {(byte) 0xFF, (byte) 0xE1});
        int segmentLength = 2 + 6 + exifBody.length;
        jpeg.write((segmentLength >> 8) & 0xFF);
        jpeg.write(segmentLength & 0xFF);
        jpeg.write(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        jpeg.write(exifBody);
        jpeg.write(new byte[] {(byte) 0xFF, (byte) 0xD9});

        Path file = directory.resolve(name);
        Files.write(file, jpeg.toByteArray());
        return file;
    }

    @Test
    @DisplayName("a photograph taken sideways reports the rotation that puts it right")
    void readsTheOrientationTag(@TempDir Path temp) throws IOException {
        assertEquals(0, ExifOrientation.degreesFor(jpegWithOrientation(temp, "up.jpg", 1)));
        assertEquals(180, ExifOrientation.degreesFor(jpegWithOrientation(temp, "down.jpg", 3)));
        assertEquals(90, ExifOrientation.degreesFor(jpegWithOrientation(temp, "left.jpg", 6)));
        assertEquals(270, ExifOrientation.degreesFor(jpegWithOrientation(temp, "right.jpg", 8)));
    }

    @Test
    @DisplayName("a photograph with nothing to say about orientation is left alone")
    void defaultsToNoRotation(@TempDir Path temp) throws IOException {
        Path plain = Files.write(temp.resolve("plain.jpg"),
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});
        assertEquals(0, ExifOrientation.degreesFor(plain));
        assertEquals(0, ExifOrientation.degreesFor(temp.resolve("missing.jpg")));
        assertEquals(0, ExifOrientation.degreesFor(null));
    }

    @Test
    @DisplayName("the six bytes that spell Exif are not EXIF when they are in the pixels")
    void doesNotMistakePixelsForAnExifSegment(@TempDir Path temp) throws IOException {
        // A JPEG with no APP1 at all, whose scan data happens to contain the
        // marker bytes. Scanning for the pattern rather than parsing segments
        // would read the following bytes as a TIFF header.
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        jpeg.write(new byte[] {(byte) 0xFF, (byte) 0xD8});
        jpeg.write(new byte[] {(byte) 0xFF, (byte) 0xDA});   // start of scan
        jpeg.write(new byte[] {0, 8});                        // segment length
        jpeg.write(new byte[] {0, 0, 0, 0, 0, 0});
        jpeg.write(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        jpeg.write(new byte[] {'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 1, 18, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0});
        jpeg.write(new byte[] {(byte) 0xFF, (byte) 0xD9});
        Path file = Files.write(temp.resolve("pixels.jpg"), jpeg.toByteArray());

        assertEquals(0, ExifOrientation.degreesFor(file));
    }

    @Test
    @DisplayName("a PNG has no EXIF and is not worth opening for it")
    void ignoresFormatsWithoutExif(@TempDir Path temp) throws IOException {
        Path png = Files.write(temp.resolve("shot.png"), new byte[] {(byte) 137, 'P', 'N', 'G'});
        assertEquals(0, ExifOrientation.degreesFor(png));
    }
}
