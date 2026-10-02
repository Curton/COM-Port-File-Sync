package com.filesync.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Shared IO helpers: full-length reads over {@link InputStream} and crash-safe string-file
 * persistence. These centralize read loops that were previously duplicated per call site, so every
 * reader agrees on what "read fully" means (loop until the buffer is filled or EOF, never a single
 * {@link InputStream#read(byte[], int, int)} call).
 */
public final class IoUtil {

    private IoUtil() {}

    /**
     * Read up to {@code len} bytes into {@code buf[off..off+len)}, returning the number actually
     * read. Unlike {@link InputStream#read(byte[], int, int)} this loops until either the requested
     * length is filled or EOF is reached. Reaching EOF early is not an error: the returned count
     * tells the caller how much of the buffer was filled.
     */
    public static int readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int read = in.read(buf, off + total, len - total);
            if (read == -1) {
                break;
            }
            total += read;
        }
        return total;
    }

    /**
     * Read exactly {@code len} bytes into {@code buf[off..off+len)} or fail: when the stream ends
     * before {@code len} bytes have been read, throws {@link IOException} with {@code eofMessage}.
     */
    public static void readFullyOrThrow(
            InputStream in, byte[] buf, int off, int len, String eofMessage) throws IOException {
        int total = readFully(in, buf, off, len);
        if (total != len) {
            throw new IOException(eofMessage);
        }
    }

    /**
     * Write {@code content} to {@code target} so that a crash mid-write can never leave a truncated
     * file behind: the content is written to a temp file created beside the target (created if the
     * parent directory does not exist yet), then moved into place with {@link
     * StandardCopyOption#ATOMIC_MOVE}, falling back to a plain replace-on-move on filesystems that
     * cannot move atomically (some network shares). The temp file is deleted afterwards, whether or
     * not the move succeeded.
     */
    public static void writeStringAtomically(Path target, String content, String tempPrefix)
            throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, tempPrefix, ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
