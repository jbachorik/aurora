package mediacenter.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QrCodeTest {

    private static final String ADDRESS = "http://192.168.1.23:8765/";

    @Test
    @DisplayName("a LAN address fits a small symbol, sized by the formula")
    void sizeFollowsTheVersion() {
        QrCode qr = QrCode.encode(ADDRESS);
        assertEquals(qr.version() * 4 + 17, qr.size());
        assertTrue(qr.version() <= 3, "a 26-character address should not need version " + qr.version());
    }

    @Test
    @DisplayName("longer text moves up through the versions")
    void versionGrowsWithTheText() {
        assertTrue(QrCode.encode("x".repeat(120)).version() >= 7);
        assertTrue(QrCode.encode("x".repeat(200)).version() >= 10);
    }

    @Test
    @DisplayName("text past the version-10 capacity is refused, not truncated")
    void refusesTextThatCannotFit() {
        assertThrows(IllegalArgumentException.class, () -> QrCode.encode("x".repeat(500)));
    }

    @Test
    @DisplayName("the three finder patterns sit in their corners")
    void drawsFinderPatterns() {
        QrCode qr = QrCode.encode(ADDRESS);
        int size = qr.size();
        assertFinderPatternAt(qr, 0, 0);
        assertFinderPatternAt(qr, size - 7, 0);
        assertFinderPatternAt(qr, 0, size - 7);
        // The fourth corner has no finder pattern to mistake for one.
        assertFalse(isFinderPatternAt(qr, size - 7, size - 7));
    }

    /** The 7x7 finder: dark ring, light ring, dark 3x3 core. */
    private static void assertFinderPatternAt(QrCode qr, int left, int top) {
        assertTrue(isFinderPatternAt(qr, left, top),
                "expected a finder pattern at (" + left + ", " + top + ")");
    }

    private static boolean isFinderPatternAt(QrCode qr, int left, int top) {
        for (int dy = 0; dy < 7; dy++) {
            for (int dx = 0; dx < 7; dx++) {
                int ring = Math.max(Math.abs(dx - 3), Math.abs(dy - 3));
                boolean expectDark = ring != 2;
                if (qr.moduleAt(left + dx, top + dy) != expectDark) {
                    return false;
                }
            }
        }
        return true;
    }

    @Test
    @DisplayName("the timing patterns alternate along row and column six")
    void drawsTimingPatterns() {
        QrCode qr = QrCode.encode(ADDRESS);
        for (int i = 8; i < qr.size() - 8; i++) {
            assertEquals(i % 2 == 0, qr.moduleAt(i, 6), "row timing at x=" + i);
            assertEquals(i % 2 == 0, qr.moduleAt(6, i), "column timing at y=" + i);
        }
    }

    @Test
    @DisplayName("the format information is a valid BCH codeword for level M")
    void formatInformationIsConsistent() {
        QrCode qr = QrCode.encode(ADDRESS);

        // Read the first format copy back from around the top-left finder,
        // in the placement order the specification gives.
        int bits = 0;
        for (int i = 0; i <= 5; i++) {
            bits |= bit(qr, 8, i) << i;
        }
        bits |= bit(qr, 8, 7) << 6;
        bits |= bit(qr, 8, 8) << 7;
        bits |= bit(qr, 7, 8) << 8;
        for (int i = 9; i < 15; i++) {
            bits |= bit(qr, 14 - i, 8) << i;
        }

        int unmasked = bits ^ 0x5412;
        // A valid codeword leaves no remainder under the generator x^10+x^8+x^5+x^4+x^2+x+1.
        int remainder = unmasked;
        for (int i = 14; i >= 10; i--) {
            if (((remainder >>> i) & 1) != 0) {
                remainder ^= 0x537 << (i - 10);
            }
        }
        assertEquals(0, remainder, "format info fails its BCH check");
        // The top two data bits are the error-correction level; M is 00.
        assertEquals(0, (unmasked >>> 13) & 0b11);
    }

    private static int bit(QrCode qr, int x, int y) {
        return qr.moduleAt(x, y) ? 1 : 0;
    }

    @Test
    @DisplayName("encoding is deterministic — the same text gives the same symbol")
    void encodesDeterministically() {
        QrCode first = QrCode.encode(ADDRESS);
        QrCode second = QrCode.encode(ADDRESS);
        assertEquals(first.size(), second.size());
        for (int y = 0; y < first.size(); y++) {
            for (int x = 0; x < first.size(); x++) {
                assertEquals(first.moduleAt(x, y), second.moduleAt(x, y));
            }
        }
    }

    @Test
    @DisplayName("dark modules stay near half of the symbol, as masking intends")
    void masksTowardBalance() {
        QrCode qr = QrCode.encode(ADDRESS);
        int dark = 0;
        for (int y = 0; y < qr.size(); y++) {
            for (int x = 0; x < qr.size(); x++) {
                if (qr.moduleAt(x, y)) {
                    dark++;
                }
            }
        }
        double ratio = dark / (double) (qr.size() * qr.size());
        assertTrue(ratio > 0.35 && ratio < 0.65, "dark ratio " + ratio + " suggests a masking fault");
    }
}
