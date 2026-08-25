package com.filesync.delta;

import java.util.Arrays;

/**
 * Signature of one fixed-size block of the receiver's existing file: a weak rolling checksum for
 * fast candidate lookup plus a strong MD5 hash for confirmation. The {@code blockIndex} records the
 * block's position so the encoder can emit a copy reference; it is implicit (positional) in the
 * serialized form and reconstructed on load.
 */
public final class BlockSignature {

    private final int blockIndex;
    private final int weakHash;
    private final byte[] strongHash;

    public BlockSignature(int blockIndex, int weakHash, byte[] strongHash) {
        if (strongHash == null || strongHash.length != 16) {
            throw new IllegalArgumentException("strongHash must be 16 bytes (MD5)");
        }
        this.blockIndex = blockIndex;
        this.weakHash = weakHash;
        this.strongHash = strongHash.clone();
    }

    public int getBlockIndex() {
        return blockIndex;
    }

    public int getWeakHash() {
        return weakHash;
    }

    public byte[] getStrongHash() {
        return strongHash.clone();
    }

    /** Strong hash without the defensive copy, for internal comparison only. */
    byte[] strongHashInternal() {
        return strongHash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockSignature)) return false;
        BlockSignature that = (BlockSignature) o;
        return blockIndex == that.blockIndex
                && weakHash == that.weakHash
                && Arrays.equals(strongHash, that.strongHash);
    }

    @Override
    public int hashCode() {
        int result = blockIndex;
        result = 31 * result + weakHash;
        result = 31 * result + Arrays.hashCode(strongHash);
        return result;
    }
}
