package com.gathin.tvhome.family;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Ed25519 signature verification (RFC 8032), written out because the platform has no provider below Android 13 and the
 * launcher keeps minSdk 26. Verification only, BigInteger arithmetic: one check takes a few milliseconds, no keys or
 * secrets are handled here. Checked against the RFC's test vectors.
 */
public final class Ed25519 {
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger I = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
    private static final BigInteger[] B;

    static {
        BigInteger by = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
        BigInteger bx = recoverX(by, 0);
        B = new BigInteger[] {bx, by};
    }

    private Ed25519() {
    }

    /** @return true when {@code signature} (64 bytes) is a valid Ed25519 signature of {@code message} under {@code publicKey} (32 bytes) */
    public static boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
        if (publicKey == null || signature == null || publicKey.length != 32 || signature.length != 64) return false;
        try {
            BigInteger[] a = decodePoint(publicKey);
            BigInteger[] r = decodePoint(Arrays.copyOfRange(signature, 0, 32));
            BigInteger s = littleEndian(Arrays.copyOfRange(signature, 32, 64));
            if (a == null || r == null || s.compareTo(L) >= 0) return false;
            MessageDigest sha = MessageDigest.getInstance("SHA-512");
            sha.update(Arrays.copyOfRange(signature, 0, 32));
            sha.update(publicKey);
            sha.update(message);
            BigInteger h = littleEndian(sha.digest()).mod(L);
            BigInteger[] left = scalarMult(B, s);
            BigInteger[] right = add(r, scalarMult(a, h));
            return left[0].equals(right[0]) && left[1].equals(right[1]);
        } catch (NoSuchAlgorithmException e) {
            return false;
        }
    }

    private static BigInteger recoverX(BigInteger y, int sign) {
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger xx = y2.subtract(BigInteger.ONE).multiply(D.multiply(y2).add(BigInteger.ONE).modInverse(P)).mod(P);
        BigInteger x = xx.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (!x.multiply(x).subtract(xx).mod(P).equals(BigInteger.ZERO)) x = x.multiply(I).mod(P);
        if (!x.multiply(x).subtract(xx).mod(P).equals(BigInteger.ZERO)) return null;
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x);
        return x;
    }

    private static BigInteger[] decodePoint(byte[] enc) {
        byte[] copy = enc.clone();
        int sign = (copy[31] >> 7) & 1;
        copy[31] &= 0x7f;
        BigInteger y = littleEndian(copy);
        if (y.compareTo(P) >= 0) return null;
        BigInteger x = recoverX(y, sign);
        if (x == null || (x.equals(BigInteger.ZERO) && sign == 1)) return null;
        return new BigInteger[] {x, y};
    }

    /** Edwards addition on affine points (a = -1). */
    private static BigInteger[] add(BigInteger[] p, BigInteger[] q) {
        BigInteger dxy = D.multiply(p[0]).multiply(q[0]).mod(P).multiply(p[1]).mod(P).multiply(q[1]).mod(P);
        BigInteger x = p[0].multiply(q[1]).add(q[0].multiply(p[1])).mod(P).multiply(BigInteger.ONE.add(dxy).modInverse(P)).mod(P);
        BigInteger y = p[1].multiply(q[1]).add(p[0].multiply(q[0])).mod(P).multiply(BigInteger.ONE.subtract(dxy).mod(P).modInverse(P)).mod(P);
        return new BigInteger[] {x, y};
    }

    private static BigInteger[] scalarMult(BigInteger[] p, BigInteger e) {
        BigInteger[] result = {BigInteger.ZERO, BigInteger.ONE};
        BigInteger[] base = p;
        for (int i = 0; i < e.bitLength(); i++) {
            if (e.testBit(i)) result = add(result, base);
            base = add(base, base);
        }
        return result;
    }

    private static BigInteger littleEndian(byte[] b) {
        byte[] be = new byte[b.length];
        for (int i = 0; i < b.length; i++) be[i] = b[b.length - 1 - i];
        return new BigInteger(1, be);
    }
}
