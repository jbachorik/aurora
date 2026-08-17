package mediacenter.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one EXIF field worth reading: which way up the photograph was taken.
 *
 * <p>JavaFX ignores it, so a portrait photograph from any telephone is displayed
 * on its side. A library would answer this in a line, but the production image
 * resolves no dependencies, so the tag is read here — the orientation field
 * alone, not a general EXIF reader.
 *
 * <p>Opens the file. Call it from a background thread and never from the JavaFX
 * one: over a network share this is a round trip.
 */
public final class ExifOrientation {

    private static final Logger LOG = Logger.getLogger(ExifOrientation.class.getName());

    /** Enough for the APP1 segment; the pixels beyond it are of no interest. */
    private static final int HEADER_BYTES = 64 * 1024;

    private static final int ORIENTATION_TAG = 0x0112;
    private static final int TYPE_SHORT = 3;

    private ExifOrientation() {
    }

    /** Clockwise rotation in degrees that puts the photograph the right way up. */
    public static int degreesFor(Path photo) {
        if (photo == null || !isJpeg(photo)) {
            return 0;
        }
        try (InputStream in = Files.newInputStream(photo)) {
            return degreesIn(in.readNBytes(HEADER_BYTES));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not read the orientation of " + photo, e);
            return 0;
        }
    }

    private static boolean isJpeg(Path photo) {
        String extension = photo.getFileName() == null
                ? "" : VideoFiles.extensionOf(photo.getFileName().toString());
        return extension.equals("jpg") || extension.equals("jpeg");
    }

    private static int degreesIn(byte[] header) {
        int exif = exifSegmentStart(header);
        if (exif < 0) {
            return 0;
        }
        int tiff = exif + 6;
        if (tiff + 8 > header.length) {
            return 0;
        }
        ByteBuffer buffer = ByteBuffer.wrap(header);
        char byteOrder = (char) header[tiff];
        if (byteOrder != 'M' && byteOrder != 'I') {
            return 0;
        }
        buffer.order(byteOrder == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);

        int ifdOffset = buffer.getInt(tiff + 4);
        // Checked rather than caught: a corrupt offset can be negative or enormous.
        // The sum is taken in long because an offset near Integer.MAX_VALUE would
        // otherwise wrap negative and slip past the very check meant to stop it.
        if (ifdOffset < 8 || (long) tiff + ifdOffset + 2 > header.length) {
            return 0;
        }
        int ifd = tiff + ifdOffset;
        int entries = buffer.getShort(ifd) & 0xFFFF;
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > header.length) {
                return 0;
            }
            if ((buffer.getShort(entry) & 0xFFFF) == ORIENTATION_TAG
                    && (buffer.getShort(entry + 2) & 0xFFFF) == TYPE_SHORT) {
                return degreesForTagValue(buffer.getShort(entry + 8) & 0xFFFF);
            }
        }
        return 0;
    }

    /**
     * Walks the JPEG's segments to find APP1, rather than searching the bytes for
     * "Exif\0\0" — those six bytes can occur in the pixels, and reading whatever
     * follows them as a TIFF header produces confident nonsense.
     */
    private static int exifSegmentStart(byte[] header) {
        if (header.length < 4 || (header[0] & 0xFF) != 0xFF || (header[1] & 0xFF) != 0xD8) {
            return -1;
        }
        int position = 2;
        while (position + 4 <= header.length) {
            if ((header[position] & 0xFF) != 0xFF) {
                return -1;
            }
            int marker = header[position + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                // Start of scan or end of image: no more metadata segments.
                return -1;
            }
            int length = ((header[position + 2] & 0xFF) << 8) | (header[position + 3] & 0xFF);
            if (length < 2) {
                return -1;
            }
            int payload = position + 4;
            if (marker == 0xE1 && payload + 6 <= header.length
                    && header[payload] == 'E' && header[payload + 1] == 'x'
                    && header[payload + 2] == 'i' && header[payload + 3] == 'f'
                    && header[payload + 4] == 0 && header[payload + 5] == 0) {
                return payload;
            }
            position += 2 + length;
        }
        return -1;
    }

    /**
     * Only the three rotations are honoured. The mirrored orientations exist but
     * are vanishingly rare, and guessing wrong about a flip is worse than leaving
     * the photograph as it was taken.
     */
    private static int degreesForTagValue(int orientation) {
        return switch (orientation) {
            case 3 -> 180;
            case 6 -> 90;
            case 8 -> 270;
            default -> 0;
        };
    }
}
