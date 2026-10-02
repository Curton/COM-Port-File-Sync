package com.filesync.delta;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * MD5 helper for delta-sync verification (raw bytes to lowercase hex). Delegates to {@link Md5}.
 */
public final class HashUtil {

    private HashUtil() {}

    /** Compute the lowercase hex MD5 of the given bytes. */
    public static String md5Hex(byte[] data) {
        return Md5.hex(data);
    }

    /** Return a fresh, ready-to-use MD5 message digest. */
    public static MessageDigest newDigest() {
        return Md5.newDigest();
    }

    /** Format an already-computed digest as lowercase hex. */
    public static String toHex(byte[] digest) {
        return Md5.toHex(digest);
    }

    /**
     * Compute the lowercase hex MD5 of a file's content, streaming it in bounded chunks so files of
     * any size are hashed without being read into memory.
     */
    public static String md5Hex(File file) throws IOException {
        MessageDigest md = Md5.newDigest();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                md.update(buf, 0, read);
            }
        }
        return Md5.toHex(md.digest());
    }
}
