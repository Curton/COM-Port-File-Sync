package com.filesync.delta;

/**
 * MD5 helper for delta-sync verification (raw bytes to lowercase hex). Delegates to {@link Md5}.
 */
public final class HashUtil {

    private HashUtil() {}

    /** Compute the lowercase hex MD5 of the given bytes. */
    public static String md5Hex(byte[] data) {
        return Md5.hex(data);
    }
}
