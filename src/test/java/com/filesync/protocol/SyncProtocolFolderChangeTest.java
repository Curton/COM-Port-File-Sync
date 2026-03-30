package com.filesync.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SyncProtocolFolderChangeTest {

    @Test
    void encodePathForProtocol_usedByFolderChange_preservesWindowsPaths() {
        String path = "C:\\Users\\foo\\Documents";
        String encoded = SyncProtocol.encodePathForProtocol(path);
        String decoded = SyncProtocol.decodePathFromProtocol(encoded);
        assertEquals(path, decoded);
    }

    @Test
    void encodePathForProtocol_usedByFolderChange_preservesUnixPaths() {
        String path = "/home/user/project";
        String encoded = SyncProtocol.encodePathForProtocol(path);
        String decoded = SyncProtocol.decodePathFromProtocol(encoded);
        assertEquals(path, decoded);
    }

    @Test
    void encodePathForProtocol_usedByFolderChange_producesSafeOutputForColons() {
        String path = "C:\\foo:bar";
        String encoded = SyncProtocol.encodePathForProtocol(path);
        String decoded = SyncProtocol.decodePathFromProtocol(encoded);
        assertEquals(path, decoded);
    }

    @Test
    void encodePathForProtocol_usedByFolderChange_producesSafeOutputForSlashes() {
        String path = "C:\\path\\with\\backslashes";
        String encoded = SyncProtocol.encodePathForProtocol(path);
        String decoded = SyncProtocol.decodePathFromProtocol(encoded);
        assertEquals(path, decoded);
    }

    @Test
    void encodePathForProtocol_usedByFolderChange_handlesEmptyAndNull() {
        assertEquals("", SyncProtocol.encodePathForProtocol(null));
        assertEquals("", SyncProtocol.encodePathForProtocol(""));
        assertEquals("", SyncProtocol.decodePathFromProtocol(null));
        assertEquals("", SyncProtocol.decodePathFromProtocol(""));
    }

    @Test
    void encodePathForProtocol_usedByFolderChange_handlesComplexPaths() {
        String path = "C:\\Program Files\\App (v2.0)\\data:cache";
        String encoded = SyncProtocol.encodePathForProtocol(path);
        String decoded = SyncProtocol.decodePathFromProtocol(encoded);
        assertEquals(path, decoded);
    }
}
