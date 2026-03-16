package com.filesync.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SyncProtocolFolderContextTest {

    @Test
    void encodePathForProtocol_handlesWindowsPaths() {
        String path = "C:\\Users\\foo\\Documents";
        String encoded = SyncProtocol.encodePathForProtocol(path);
        String decoded = SyncProtocol.decodePathFromProtocol(encoded);
        assertEquals(path, decoded);
    }

    @Test
    void encodePathForProtocol_handlesUnixPaths() {
        String path = "/home/user/project";
        String encoded = SyncProtocol.encodePathForProtocol(path);
        String decoded = SyncProtocol.decodePathFromProtocol(encoded);
        assertEquals(path, decoded);
    }

    @Test
    void encodePathForProtocol_handlesNullAndEmpty() {
        assertEquals("", SyncProtocol.encodePathForProtocol(null));
        assertEquals("", SyncProtocol.encodePathForProtocol(""));
        assertEquals("", SyncProtocol.decodePathFromProtocol(null));
        assertEquals("", SyncProtocol.decodePathFromProtocol(""));
    }

    @Test
    void encodePathForProtocol_producesSafeOutputForColons() {
        String path = "C:\\foo:bar";
        String encoded = SyncProtocol.encodePathForProtocol(path);
        assertEquals(path, SyncProtocol.decodePathFromProtocol(encoded));
    }
}
