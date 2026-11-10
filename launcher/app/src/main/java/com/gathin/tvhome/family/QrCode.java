package com.gathin.tvhome.family;

import java.nio.charset.StandardCharsets;

/**
 * A QR code for short texts (an address with a pairing code): byte mode, error correction level M, versions 1 to 6
 * (up to 106 bytes), the mask with the lowest penalty. It follows ISO/IEC 18004; no library is needed for it.
 */
public final class QrCode {
    private static final int MAX_VERSION = 6;
    /** Error correction codewords per block and number of blocks at level M, by version (index 0 unused). */
    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16};
    private static final int[] BLOCKS = {-1, 1, 1, 1, 2, 2, 4};

    private final int size;
    private final boolean[][] dark;
    private final boolean[][] reserved;

    private QrCode(int version, byte[] codewords, int mask) {
        size = version * 4 + 17;
        dark = new boolean[size][size];
        reserved = new boolean[size][size];
        drawFunctionPatterns(version);
        drawCodewords(codewords);
        applyMask(mask);
        drawFormat(mask);
    }

    /** The modules of the code, true for dark, size x size, without the quiet zone. */
    public static boolean[][] encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int version = 0;
        for (int v = 1; v <= MAX_VERSION; v++) {
            if (4 + 8 + data.length * 8 <= dataCodewords(v) * 8) {
                version = v;
                break;
            }
        }
        if (version == 0) throw new IllegalArgumentException("text too long for a QR code: " + data.length);
        int capacityBits = dataCodewords(version) * 8;
        BitBuffer bits = new BitBuffer();
        bits.append(0x4, 4);                    // byte mode
        bits.append(data.length, 8);            // character count: 8 bits up to version 9
        for (byte b : data) bits.append(b & 0xFF, 8);
        bits.append(0, Math.min(4, capacityBits - bits.length));
        bits.append(0, (8 - bits.length % 8) % 8);
        for (int pad = 0xEC; bits.length < capacityBits; pad ^= 0xEC ^ 0x11) bits.append(pad, 8);
        byte[] dataWords = bits.toBytes();
        byte[] all = addEccAndInterleave(version, dataWords);
        int best = 0;
        long bestPenalty = Long.MAX_VALUE;
        QrCode chosen = null;
        for (int mask = 0; mask < 8; mask++) {
            QrCode code = new QrCode(version, all, mask);
            long penalty = code.penalty();
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                best = mask;
                chosen = code;
            }
        }
        if (chosen == null || best < 0) throw new IllegalStateException();
        return chosen.dark;
    }

    static int dataCodewords(int version) {
        return rawModules(version) / 8 - ECC_PER_BLOCK[version] * BLOCKS[version];
    }

    private static int rawModules(int ver) {
        int result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int numAlign = ver / 7 + 2;
            result -= (25 * numAlign - 10) * numAlign - 55;
        }
        return result;
    }

    // ---- error correction ------------------------------------------------------------------------

    private static byte[] addEccAndInterleave(int version, byte[] data) {
        int blocks = BLOCKS[version], eccLen = ECC_PER_BLOCK[version];
        int rawCodewords = rawModules(version) / 8;
        int shortBlocks = blocks - rawCodewords % blocks;
        int shortLen = rawCodewords / blocks;
        byte[][] dataBlocks = new byte[blocks][];
        byte[][] eccBlocks = new byte[blocks][];
        byte[] divisor = reedSolomonDivisor(eccLen);
        for (int i = 0, k = 0; i < blocks; i++) {
            int len = shortLen - eccLen + (i < shortBlocks ? 0 : 1);
            byte[] block = new byte[len];
            System.arraycopy(data, k, block, 0, len);
            k += len;
            dataBlocks[i] = block;
            eccBlocks[i] = reedSolomonRemainder(block, divisor);
        }
        byte[] out = new byte[rawCodewords];
        int n = 0;
        for (int i = 0; i < shortLen - eccLen + 1; i++) {
            for (int j = 0; j < blocks; j++) {
                if (i < dataBlocks[j].length) out[n++] = dataBlocks[j][i];
            }
        }
        for (int i = 0; i < eccLen; i++) {
            for (int j = 0; j < blocks; j++) out[n++] = eccBlocks[j][i];
        }
        return out;
    }

    /** The generator polynomial of degree degree, without its leading 1 coefficient. */
    static byte[] reedSolomonDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) multiply(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = multiply(root, 0x02);
        }
        return result;
    }

    static byte[] reedSolomonRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) multiply(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    /** Multiplication in GF(2^8) with the polynomial 0x11D. */
    private static int multiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    // ---- drawing ---------------------------------------------------------------------------------

    private void setFunction(int x, int y, boolean value) {
        dark[y][x] = value;
        reserved[y][x] = true;
    }

    private void drawFunctionPatterns(int version) {
        for (int i = 0; i < size; i++) {
            setFunction(6, i, i % 2 == 0);
            setFunction(i, 6, i % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);
        if (version >= 2) {
            int[] pos = alignmentPositions(version);
            for (int i = 0; i < pos.length; i++) {
                for (int j = 0; j < pos.length; j++) {
                    boolean corner = (i == 0 && j == 0) || (i == 0 && j == pos.length - 1) || (i == pos.length - 1 && j == 0);
                    if (!corner) drawAlignment(pos[i], pos[j]);
                }
            }
        }
        drawFormat(0);
    }

    private int[] alignmentPositions(int version) {
        int numAlign = version / 7 + 2;
        int step = (version * 8 + numAlign * 3 + 5) / (numAlign * 4 - 4) * 2;
        int[] result = new int[numAlign];
        result[0] = 6;
        for (int i = numAlign - 1, pos = size - 7; i >= 1; i--, pos -= step) result[i] = pos;
        return result;
    }

    private void drawFinder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int x = cx + dx, y = cy + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) setFunction(x, y, dist != 2 && dist != 4);
            }
        }
    }

    private void drawAlignment(int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) setFunction(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
    }

    /** The 15 format bits of error correction level M and the mask, BCH-coded and masked. */
    static int formatBits(int mask) {
        int data = (0 << 3) | mask;                // level M is 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        return ((data << 10) | rem) ^ 0x5412;
    }

    private void drawFormat(int mask) {
        int bits = formatBits(mask);
        for (int i = 0; i <= 5; i++) setFunction(8, i, ((bits >>> i) & 1) != 0);
        setFunction(8, 7, ((bits >>> 6) & 1) != 0);
        setFunction(8, 8, ((bits >>> 7) & 1) != 0);
        setFunction(7, 8, ((bits >>> 8) & 1) != 0);
        for (int i = 9; i < 15; i++) setFunction(14 - i, 8, ((bits >>> i) & 1) != 0);
        for (int i = 0; i < 8; i++) setFunction(size - 1 - i, 8, ((bits >>> i) & 1) != 0);
        for (int i = 8; i < 15; i++) setFunction(8, size - 15 + i, ((bits >>> i) & 1) != 0);
        setFunction(8, size - 8, true);
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!reserved[y][x] && i < data.length * 8) {
                        dark[y][x] = ((data[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (!reserved[y][x] && invert) dark[y][x] = !dark[y][x];
            }
        }
    }

    // ---- penalty (mask choice) -------------------------------------------------------------------

    private long penalty() {
        long result = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 1;
                for (int b = 1; b < size; b++) {
                    boolean same = cell(pass, a, b) == cell(pass, a, b - 1);
                    if (same) {
                        run++;
                        if (run == 5) result += 3;
                        else if (run > 5) result++;
                    } else {
                        run = 1;
                    }
                }
                // Finder-like patterns 1:1:3:1:1 with four light modules on a side.
                for (int b = 0; b + 10 < size; b++) {
                    if (matches(pass, a, b, new boolean[] {true, false, true, true, true, false, true, false, false, false, false})
                            || matches(pass, a, b, new boolean[] {false, false, false, false, true, false, true, true, true, false, true})) {
                        result += 40;
                    }
                }
            }
        }
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean c = dark[y][x];
                if (c == dark[y][x + 1] && c == dark[y + 1][x] && c == dark[y + 1][x + 1]) result += 3;
            }
        }
        int total = 0;
        for (boolean[] row : dark) for (boolean d : row) if (d) total++;
        int k = (Math.abs(total * 20 - size * size * 10) + size * size - 1) / (size * size) - 1;
        return result + k * 10L;
    }

    private boolean cell(int pass, int a, int b) {
        return pass == 0 ? dark[a][b] : dark[b][a];
    }

    private boolean matches(int pass, int a, int b, boolean[] pattern) {
        for (int i = 0; i < pattern.length; i++) if (cell(pass, a, b + i) != pattern[i]) return false;
        return true;
    }

    // ---- bits ------------------------------------------------------------------------------------

    private static final class BitBuffer {
        private final java.util.BitSet bits = new java.util.BitSet();
        int length;

        void append(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                bits.set(length++, ((value >>> i) & 1) != 0);
            }
        }

        byte[] toBytes() {
            byte[] out = new byte[(length + 7) / 8];
            for (int i = 0; i < length; i++) if (bits.get(i)) out[i >>> 3] |= (byte) (0x80 >>> (i & 7));
            return out;
        }
    }
}
