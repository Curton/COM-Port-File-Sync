package com.filesync.delta;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Block signatures for a single file: the relative path, the fixed block size, the number of full
 * blocks, the total source byte length, and one {@link BlockSignature} per full block (index 0..
 * blockCount-1). The trailing partial block (shorter than {@code blockSize}) is intentionally
 * excluded from the matchable set; the encoder will simply send those bytes as literals, which keeps
 * the rolling-hash logic single-window-length.
 *
 * <p>Serialized form (big-endian):
 *
 * <pre>
 *   PATH_LEN(2) | PATH(utf8) | BLOCK_SIZE(4) | BLOCK_COUNT(4) | SOURCE_SIZE(8)
 *   | BLOCK_COUNT * ( WEAK_HASH(4) | STRONG_HASH(16) )
 * </pre>
 *
 * The block index is implicit (positional) and reconstructed on load.
 */
public final class FileSignatures {

    /** Magic header for the multi-file container, not used here but kept for symmetry. */
    private final String path;
    private final int blockSize;
    private final int blockCount;
    private final long sourceSize;
    private final List<BlockSignature> signatures;

    public FileSignatures(
            String path, int blockSize, int blockCount, long sourceSize, List<BlockSignature> signatures) {
        this.path = path;
        this.blockSize = blockSize;
        this.blockCount = blockCount;
        this.sourceSize = sourceSize;
        this.signatures = Collections.unmodifiableList(new ArrayList<>(signatures));
    }

    public String getPath() {
        return path;
    }

    public int getBlockSize() {
        return blockSize;
    }

    public int getBlockCount() {
        return blockCount;
    }

    public long getSourceSize() {
        return sourceSize;
    }

    public List<BlockSignature> getSignatures() {
        return signatures;
    }

    /** Serialize to a self-delimiting byte array. */
    public byte[] toBytes() throws IOException {
        byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
        if (pathBytes.length > 0xFFFF) {
            throw new IOException("Path too long for FileSignatures: " + path);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeShort(pathBytes.length);
            out.write(pathBytes);
            out.writeInt(blockSize);
            out.writeInt(blockCount);
            out.writeLong(sourceSize);
            for (BlockSignature sig : signatures) {
                out.writeInt(sig.getWeakHash());
                out.write(sig.strongHashInternal());
            }
        }
        return baos.toByteArray();
    }

    /** Deserialize from the given bytes (inverse of {@link #toBytes()}). */
    public static FileSignatures fromBytes(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int pathLen = in.readUnsignedShort();
            byte[] pathBytes = new byte[pathLen];
            in.readFully(pathBytes);
            String path = new String(pathBytes, StandardCharsets.UTF_8);
            int blockSize = in.readInt();
            int blockCount = in.readInt();
            long sourceSize = in.readLong();
            List<BlockSignature> sigs = new ArrayList<>(blockCount);
            for (int i = 0; i < blockCount; i++) {
                int weak = in.readInt();
                byte[] strong = new byte[16];
                in.readFully(strong);
                sigs.add(new BlockSignature(i, weak, strong));
            }
            return new FileSignatures(path, blockSize, blockCount, sourceSize, sigs);
        }
    }

    /** Length in bytes of this file's serialized form (for the container's length prefix). */
    public int encodedLength() throws IOException {
        byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
        return 2 + pathBytes.length + 4 + 4 + 8 + (blockCount * 20);
    }
}