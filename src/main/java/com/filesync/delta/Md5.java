package com.filesync.delta;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Centralized MD5 access for the delta package. The {@code MD5} algorithm is guaranteed by the JVM
 * specification, so {@link NoSuchAlgorithmException} is impossible in practice; it is handled here
 * once (rather than at every call site) by rethrowing as an unchecked error. This isolates the only
 * untestable exception path in the delta package so the rest of the code can be fully covered.
 */
final class Md5 {

    private Md5() {}

    /** Return a fresh, ready-to-use MD5 message digest. */
    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
    }

    /** Compute the lowercase hex MD5 of the given bytes. */
    static String hex(byte[] data) {
        byte[] digest = newDigest().digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}