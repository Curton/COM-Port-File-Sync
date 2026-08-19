package com.filesync.delta;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Container for the block signatures of multiple files, exchanged in a single XMODEM transfer.
 *
 * <p>Serialized form (big-endian):
 *
 * <pre>
 *   MAGIC(4) "SGS\0" | VERSION(1) | FILE_COUNT(4)
 *   | FILE_COUNT * ( ENTRY_LEN(4) | FileSignatures bytes )
 * </pre>
 *
 * Each entry is length-prefixed so a reader can skip malformed entries defensively.
 */
public final class SignatureSet {

    private static final byte[] MAGIC = new byte[] {0x53, 0x47, 0x53, 0x00}; // "SGS\0"
    private static final int VERSION = 1;

    private final Map<String, FileSignatures> byPath;

    public SignatureSet(Collection<FileSignatures> entries) {
        Map<String, FileSignatures> map = new LinkedHashMap<>();
        for (FileSignatures fs : entries) {
            map.put(fs.getPath(), fs);
        }
        this.byPath = Collections.unmodifiableMap(map);
    }

    /** Empty set (no delta candidates). */
    public static SignatureSet empty() {
        return new SignatureSet(List.of());
    }

    public FileSignatures get(String path) {
        return byPath.get(path);
    }

    public int size() {
        return byPath.size();
    }

    public boolean isEmpty() {
        return byPath.isEmpty();
    }

    public Collection<FileSignatures> entries() {
        return byPath.values();
    }

    public byte[] toBytes() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.write(MAGIC);
            out.writeByte(VERSION);
            out.writeInt(byPath.size());
            for (FileSignatures fs : byPath.values()) {
                byte[] payload = fs.toBytes();
                out.writeInt(payload.length);
                out.write(payload);
            }
        }
        return baos.toByteArray();
    }

    public static SignatureSet fromBytes(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IOException("Invalid SignatureSet magic");
            }
            int version = in.readUnsignedByte();
            if (version != VERSION) {
                throw new IOException("Unsupported SignatureSet version: " + version);
            }
            int count = in.readInt();
            if (count < 0) {
                throw new IOException("Negative SignatureSet count: " + count);
            }
            List<FileSignatures> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int entryLen = in.readInt();
                if (entryLen < 0) {
                    throw new IOException("Negative entry length: " + entryLen);
                }
                byte[] payload = new byte[entryLen];
                in.readFully(payload);
                entries.add(FileSignatures.fromBytes(payload));
            }
            return new SignatureSet(entries);
        }
    }
}