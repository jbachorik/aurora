package mediacenter.remote;

import java.nio.charset.StandardCharsets;

/**
 * A QR code symbol for a short piece of text — just enough of ISO/IEC 18004 to
 * put the remote-control address on the home screen.
 *
 * <p>Hand-written for the same reason as {@link mediacenter.ui.PngWriter} and
 * {@link mediacenter.json.Json}: one small, well-specified job is no reason to
 * grow the runtime image or take on a dependency. The subset is deliberate —
 * byte mode only, error-correction level M only, versions 1 to 10 (up to 213
 * characters), which is far more than a LAN address needs. Mask selection
 * follows the specification's penalty scoring, so any phone camera reads it.
 */
public final class QrCode {

    private static final int MIN_VERSION = 1;
    private static final int MAX_VERSION = 10;

    /** Error-correction codewords per block, level M, versions 1..10. */
    private static final int[] ECC_CODEWORDS_PER_BLOCK =
            {10, 16, 26, 18, 24, 16, 18, 22, 22, 26};

    /** Number of error-correction blocks, level M, versions 1..10. */
    private static final int[] NUM_ERROR_CORRECTION_BLOCKS =
            {1, 1, 1, 2, 2, 4, 4, 4, 5, 5};

    /** Format-info bits for error-correction level M. */
    private static final int FORMAT_ECC_BITS = 0;

    // Penalty weights from the specification, in scoring order.
    private static final int PENALTY_N1 = 3;
    private static final int PENALTY_N2 = 3;
    private static final int PENALTY_N3 = 40;
    private static final int PENALTY_N4 = 10;

    private final int version;
    private final int size;

    /** {@code modules[y][x]}, true where the symbol is dark. */
    private final boolean[][] modules;

    /** Marks modules that carry function patterns rather than data. */
    private final boolean[][] isFunction;

    private QrCode(int version, byte[] dataCodewords) {
        this.version = version;
        this.size = version * 4 + 17;
        this.modules = new boolean[size][size];
        this.isFunction = new boolean[size][size];

        drawFunctionPatterns();
        drawCodewords(interleaveWithEcc(dataCodewords));

        int mask = chooseMask();
        applyMask(mask);
        drawFormatBits(mask);
    }

    /** Encodes the text as UTF-8 in byte mode at error-correction level M. */
    public static QrCode encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int version = smallestVersionFor(data.length);
        return new QrCode(version, buildCodewords(data, version));
    }

    public int version() {
        return version;
    }

    /** Width and height in modules; the quiet zone is the renderer's to add. */
    public int size() {
        return size;
    }

    /** True where the module at ({@code x}, {@code y}) is dark. */
    public boolean moduleAt(int x, int y) {
        return modules[y][x];
    }

    // -- capacity and the data bit stream ------------------------------------

    private static int smallestVersionFor(int dataLength) {
        for (int version = MIN_VERSION; version <= MAX_VERSION; version++) {
            if (dataLength <= dataCapacityBytes(version)) {
                return version;
            }
        }
        throw new IllegalArgumentException(
                "Text is too long for a version-" + MAX_VERSION + " QR code: " + dataLength + " bytes");
    }

    /** How many content bytes fit once the mode and length headers are paid for. */
    private static int dataCapacityBytes(int version) {
        int headerBits = 4 + charCountBits(version);
        return numDataCodewords(version) - (headerBits + 7) / 8;
    }

    /** Byte-mode character count field width: 8 bits up to version 9, 16 after. */
    private static int charCountBits(int version) {
        return version <= 9 ? 8 : 16;
    }

    private static int numDataCodewords(int version) {
        return numRawDataModules(version) / 8 - eccCodewordsTotal(version);
    }

    private static int eccCodewordsTotal(int version) {
        return ECC_CODEWORDS_PER_BLOCK[version - 1] * NUM_ERROR_CORRECTION_BLOCKS[version - 1];
    }

    /** Modules left for codewords once every function pattern has its place. */
    private static int numRawDataModules(int version) {
        int result = (16 * version + 128) * version + 64;
        if (version >= 2) {
            int numAlign = version / 7 + 2;
            result -= (25 * numAlign - 10) * numAlign - 55;
            if (version >= 7) {
                result -= 36;
            }
        }
        return result;
    }

    /** Mode header, length, content, terminator and the specified pad bytes. */
    private static byte[] buildCodewords(byte[] data, int version) {
        BitBuffer bits = new BitBuffer();
        bits.append(0b0100, 4); // byte mode
        bits.append(data.length, charCountBits(version));
        for (byte b : data) {
            bits.append(b & 0xFF, 8);
        }

        int capacityBits = numDataCodewords(version) * 8;
        bits.append(0, Math.min(4, capacityBits - bits.length())); // terminator
        bits.append(0, (8 - bits.length() % 8) % 8); // to a byte boundary
        for (int pad = 0xEC; bits.length() < capacityBits; pad ^= 0xEC ^ 0x11) {
            bits.append(pad, 8);
        }
        return bits.toBytes();
    }

    // -- error correction ----------------------------------------------------

    /**
     * Splits the data into the version's blocks, appends Reed-Solomon codewords
     * to each and interleaves the lot in the order the symbol is read.
     */
    private byte[] interleaveWithEcc(byte[] data) {
        int numBlocks = NUM_ERROR_CORRECTION_BLOCKS[version - 1];
        int eccLen = ECC_CODEWORDS_PER_BLOCK[version - 1];
        int rawCodewords = numRawDataModules(version) / 8;
        int numShortBlocks = numBlocks - rawCodewords % numBlocks;
        int shortBlockLen = rawCodewords / numBlocks;

        byte[][] blocks = new byte[numBlocks][];
        byte[] rsDivisor = reedSolomonDivisor(eccLen);
        for (int i = 0, offset = 0; i < numBlocks; i++) {
            int dataLen = shortBlockLen - eccLen + (i < numShortBlocks ? 0 : 1);
            byte[] block = new byte[shortBlockLen + 1];
            System.arraycopy(data, offset, block, 0, dataLen);
            byte[] ecc = reedSolomonRemainder(data, offset, dataLen, rsDivisor);
            System.arraycopy(ecc, 0, block, block.length - eccLen, eccLen);
            blocks[i] = block;
            offset += dataLen;
        }

        byte[] result = new byte[rawCodewords];
        for (int i = 0, k = 0; i < blocks[0].length; i++) {
            for (int j = 0; j < numBlocks; j++) {
                // Short blocks have no codeword at the padding index.
                if (i != shortBlockLen - eccLen || j >= numShortBlocks) {
                    result[k++] = blocks[j][i];
                }
            }
        }
        return result;
    }

    /** The generator polynomial (x - r^0)(x - r^1)...(x - r^{degree-1}). */
    private static byte[] reedSolomonDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) gfMultiply(result[j] & 0xFF, root);
                if (j + 1 < degree) {
                    result[j] ^= result[j + 1];
                }
            }
            root = gfMultiply(root, 0x02);
        }
        return result;
    }

    private static byte[] reedSolomonRemainder(byte[] data, int offset, int length, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (int i = 0; i < length; i++) {
            int factor = (data[offset + i] ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int j = 0; j < result.length; j++) {
                result[j] ^= (byte) gfMultiply(divisor[j] & 0xFF, factor);
            }
        }
        return result;
    }

    /** Multiplication in GF(2^8) with the QR polynomial x^8+x^4+x^3+x^2+1. */
    private static int gfMultiply(int a, int b) {
        int result = 0;
        for (int i = 7; i >= 0; i--) {
            result = (result << 1) ^ ((result >>> 7) * 0x11D);
            result ^= ((b >>> i) & 1) * a;
        }
        return result;
    }

    // -- function patterns ---------------------------------------------------

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            setFunctionModule(6, i, i % 2 == 0); // timing patterns
            setFunctionModule(i, 6, i % 2 == 0);
        }

        drawFinderPattern(3, 3);
        drawFinderPattern(size - 4, 3);
        drawFinderPattern(3, size - 4);

        int[] alignment = alignmentPositions();
        for (int i = 0; i < alignment.length; i++) {
            for (int j = 0; j < alignment.length; j++) {
                // Skip the three corners occupied by finder patterns.
                boolean corner = (i == 0 && j == 0)
                        || (i == 0 && j == alignment.length - 1)
                        || (i == alignment.length - 1 && j == 0);
                if (!corner) {
                    drawAlignmentPattern(alignment[i], alignment[j]);
                }
            }
        }

        // Reserve the format areas so data placement skips them; the real bits
        // are written once the mask is chosen.
        drawFormatBits(0);
        drawVersionInfo();
    }

    private void drawFinderPattern(int centreX, int centreY) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = centreX + dx;
                int y = centreY + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) {
                    int distance = Math.max(Math.abs(dx), Math.abs(dy));
                    setFunctionModule(x, y, distance != 2 && distance != 4);
                }
            }
        }
    }

    private void drawAlignmentPattern(int centreX, int centreY) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                setFunctionModule(centreX + dx, centreY + dy,
                        Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    /** Centre coordinates of the alignment patterns for this version. */
    private int[] alignmentPositions() {
        if (version == 1) {
            return new int[0];
        }
        int numAlign = version / 7 + 2;
        int step = (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2;
        int[] result = new int[numAlign];
        result[0] = 6;
        for (int i = result.length - 1, pos = size - 7; i >= 1; i--, pos -= step) {
            result[i] = pos;
        }
        return result;
    }

    /** The 15 format bits — error-correction level and mask, BCH-protected. */
    private void drawFormatBits(int mask) {
        int data = FORMAT_ECC_BITS << 3 | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        }
        int bits = (data << 10 | rem) ^ 0x5412;

        // First copy, around the top-left finder pattern.
        for (int i = 0; i <= 5; i++) {
            setFunctionModule(8, i, getBit(bits, i));
        }
        setFunctionModule(8, 7, getBit(bits, 6));
        setFunctionModule(8, 8, getBit(bits, 7));
        setFunctionModule(7, 8, getBit(bits, 8));
        for (int i = 9; i < 15; i++) {
            setFunctionModule(14 - i, 8, getBit(bits, i));
        }

        // Second copy, split between the other two finder patterns.
        for (int i = 0; i <= 7; i++) {
            setFunctionModule(size - 1 - i, 8, getBit(bits, i));
        }
        for (int i = 8; i < 15; i++) {
            setFunctionModule(8, size - 15 + i, getBit(bits, i));
        }
        setFunctionModule(8, size - 8, true); // the always-dark module
    }

    /** The 18 version bits, present from version 7 up. */
    private void drawVersionInfo() {
        if (version < 7) {
            return;
        }
        int rem = version;
        for (int i = 0; i < 12; i++) {
            rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        }
        int bits = version << 12 | rem;
        for (int i = 0; i < 18; i++) {
            boolean bit = getBit(bits, i);
            int a = size - 11 + i % 3;
            int b = i / 3;
            setFunctionModule(a, b, bit);
            setFunctionModule(b, a, bit);
        }
    }

    private void setFunctionModule(int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    // -- data placement and masking ------------------------------------------

    /** Zigzag placement: two-module columns, right to left, snaking vertically. */
    private void drawCodewords(byte[] codewords) {
        int bitIndex = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5; // the vertical timing pattern is stepped over
            }
            for (int vertical = 0; vertical < size; vertical++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vertical : vertical;
                    if (!isFunction[y][x] && bitIndex < codewords.length * 8) {
                        modules[y][x] = getBit(codewords[bitIndex >>> 3], 7 - (bitIndex & 7));
                        bitIndex++;
                    }
                    // Any module left over is a remainder bit, already light.
                }
            }
        }
    }

    /** Tries all eight masks and keeps the one the penalty score likes best. */
    private int chooseMask() {
        int bestMask = 0;
        int bestPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            applyMask(mask);
            drawFormatBits(mask);
            int penalty = penaltyScore();
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                bestMask = mask;
            }
            applyMask(mask); // XOR is its own inverse
        }
        return bestMask;
    }

    /** XORs the mask pattern over every data module; applying twice removes it. */
    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean invert = switch (mask) {
                    case 0 -> (x + y) % 2 == 0;
                    case 1 -> y % 2 == 0;
                    case 2 -> x % 3 == 0;
                    case 3 -> (x + y) % 3 == 0;
                    case 4 -> (x / 3 + y / 2) % 2 == 0;
                    case 5 -> x * y % 2 + x * y % 3 == 0;
                    case 6 -> (x * y % 2 + x * y % 3) % 2 == 0;
                    case 7 -> ((x + y) % 2 + x * y % 3) % 2 == 0;
                    default -> throw new AssertionError();
                };
                modules[y][x] ^= invert && !isFunction[y][x];
            }
        }
    }

    /** The specification's four penalty rules, summed. */
    private int penaltyScore() {
        int result = 0;

        // Rules 1 and 3 along rows: runs of one colour, and finder-lookalikes.
        for (int y = 0; y < size; y++) {
            boolean runColour = false;
            int runLength = 0;
            int[] history = new int[7];
            for (int x = 0; x < size; x++) {
                if (modules[y][x] == runColour) {
                    runLength++;
                    if (runLength == 5) {
                        result += PENALTY_N1;
                    } else if (runLength > 5) {
                        result++;
                    }
                } else {
                    addRunToHistory(runLength, history);
                    if (!runColour) {
                        result += countFinderPatterns(history) * PENALTY_N3;
                    }
                    runColour = modules[y][x];
                    runLength = 1;
                }
            }
            result += terminateAndCount(runColour, runLength, history) * PENALTY_N3;
        }
        // The same along columns.
        for (int x = 0; x < size; x++) {
            boolean runColour = false;
            int runLength = 0;
            int[] history = new int[7];
            for (int y = 0; y < size; y++) {
                if (modules[y][x] == runColour) {
                    runLength++;
                    if (runLength == 5) {
                        result += PENALTY_N1;
                    } else if (runLength > 5) {
                        result++;
                    }
                } else {
                    addRunToHistory(runLength, history);
                    if (!runColour) {
                        result += countFinderPatterns(history) * PENALTY_N3;
                    }
                    runColour = modules[y][x];
                    runLength = 1;
                }
            }
            result += terminateAndCount(runColour, runLength, history) * PENALTY_N3;
        }

        // Rule 2: 2x2 blocks of one colour.
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean colour = modules[y][x];
                if (colour == modules[y][x + 1] && colour == modules[y + 1][x]
                        && colour == modules[y + 1][x + 1]) {
                    result += PENALTY_N2;
                }
            }
        }

        // Rule 4: dark-module balance, in 5% steps away from half and half.
        int dark = 0;
        for (boolean[] row : modules) {
            for (boolean module : row) {
                if (module) {
                    dark++;
                }
            }
        }
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        return result + k * PENALTY_N4;
    }

    // Penalty rule 3 works on a sliding window of the last seven run lengths:
    // a 1:1:3:1:1 dark-light pattern flanked by four light modules reads as a
    // finder pattern to a scanner and is penalised. The newest run sits at
    // index 0, and the symbol's border counts as light.

    private void addRunToHistory(int runLength, int[] history) {
        if (history[0] == 0) {
            runLength += size; // light border before the row's first run
        }
        System.arraycopy(history, 0, history, 1, history.length - 1);
        history[0] = runLength;
    }

    private static int countFinderPatterns(int[] history) {
        int n = history[1];
        boolean core = n > 0 && history[2] == n && history[3] == n * 3
                && history[4] == n && history[5] == n;
        return (core && history[0] >= n * 4 && history[6] >= n ? 1 : 0)
                + (core && history[6] >= n * 4 && history[0] >= n ? 1 : 0);
    }

    /** Closes the line as if bordered by light modules and counts matches. */
    private int terminateAndCount(boolean runColour, int runLength, int[] history) {
        if (runColour) {
            addRunToHistory(runLength, history);
            runLength = 0;
        }
        addRunToHistory(runLength + size, history);
        return countFinderPatterns(history);
    }

    private static boolean getBit(int value, int index) {
        return ((value >>> index) & 1) != 0;
    }

    /** Append-only bit stream, most significant bit first. */
    private static final class BitBuffer {
        private byte[] bytes = new byte[64];
        private int length;

        void append(int value, int bitCount) {
            for (int i = bitCount - 1; i >= 0; i--) {
                if (length / 8 >= bytes.length) {
                    bytes = java.util.Arrays.copyOf(bytes, bytes.length * 2);
                }
                bytes[length / 8] |= (byte) (((value >>> i) & 1) << (7 - length % 8));
                length++;
            }
        }

        int length() {
            return length;
        }

        byte[] toBytes() {
            return java.util.Arrays.copyOf(bytes, (length + 7) / 8);
        }
    }
}
