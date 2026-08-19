package com.filesync.delta;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes {@link FileSignatures} for a file by splitting it into fixed-size blocks and hashing each
 * with the rolling weak checksum plus an MD5 strong hash. The block size is chosen adaptively from
 * the file size (clamped to {@code [512, 8192]}) so signature overhead stays roughly proportional.
 */
public final class SignatureUtil {

    private SignatureUtil() {}

    /** Minimum adaptive block size. */
    public static final int MIN_BLOCK_SIZE = 512;
    /** Maximum adaptive block size. */
    public static final int MAX_BLOCK_SIZE = 8192;

    /**
     * Choose a block size for the given file length: {@code clamp(round(sqrt(size)), 512, 8192)}.
     * Small files get 512-byte blocks; large files cap at 8192 to bound per-block strong-hash cost.
     */
    public static int chooseBlockSize(long fileSize) {
        if (fileSize <= 0) {
            return MIN_BLOCK_SIZE;
        }
        long sqrt = Math.round(Math.sqrt(fileSize));
        int size = (int) Math.max(MIN_BLOCK_SIZE, Math.min(MAX_BLOCK_SIZE, sqrt));
        return size;
    }

    /**
     * Compute signatures for a file using the {@link #chooseBlockSize adaptive} block size.
     *
     * @param path the relative path to record in the result
     * @param file the existing file on the receiver
     */
    public static FileSignatures compute(String path, File file) throws IOException {
        long len = file.length();
        int blockSize = chooseBlockSize(len);
        byte[] data = Files.readAllBytes(file.toPath());
        return compute(path, data, blockSize);
    }

    /** Compute signatures for in-memory bytes with an explicit block size. */
    public static FileSignatures compute(String path, byte[] data, int blockSize) throws IOException {
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive: " + blockSize);
        }
        int blockCount = data.length / blockSize;
        List<BlockSignature> sigs = new ArrayList<>(blockCount);
        MessageDigest md5 = Md5.newDigest();
        for (int i = 0; i < blockCount; i++) {
            int off = i * blockSize;
            int weak = RollingHash.weakChecksum(data, off, blockSize);
            md5.reset();
            byte[] strong = md5.digest(java.util.Arrays.copyOfRange(data, off, off + blockSize));
            sigs.add(new BlockSignature(i, weak, strong));
        }
        return new FileSignatures(path, blockSize, blockCount, data.length, sigs);
    }
}