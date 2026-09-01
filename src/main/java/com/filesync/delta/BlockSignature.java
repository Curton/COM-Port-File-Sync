package com.filesync.delta;

import java.util.Arrays;

/**
 * Signature of one fixed-size block of the receiver's existing file: a weak rolling checksum for
 * fast candidate lookup plus a strong hash for confirmation. The strong hash is a truncated MD5
 * (first {@value #STRONG_HASH_LENGTH} bytes): combined with the 32-bit weak checksum a false match
 * needs a ~2^-96 coincidence, and the receiver's full-file MD5 verification guards the final write
 * anyway. Truncation keeps the signature payload small on the serial link. The {@code blockIndex}
 * records the block's position so the encoder can emit a copy reference; it is implicit
 * (positional) in the serialized form and reconstructed on load.
 */
public final class BlockSignature {

    /** Length in bytes of the truncated strong hash. */
    public static final int STRONG_HASH_LENGTH = 8;

    private final int blockIndex;
    private final int weakHash;
    private final byte[] strongHash;

    public BlockSignature(int blockIndex, int weakHash, byte[] strongHash) {
        if (strongHash == null || strongHash.length != STRONG_HASH_LENGTH) {
            throw new IllegalArgumentException(
                    "strongHash must be " + STRONG_HASH_LENGTH + " bytes (truncated MD5)");
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
