package com.filesync.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SyncProtocolPathEncodingTest {

    @Test
    void encodePathForProtocolReturnsEmptyStringForNullAndEmptyString() {
        assertEquals("", SyncProtocol.encodePathForProtocol(null));
        assertEquals("", SyncProtocol.encodePathForProtocol(""));
    }

    @Test
    void decodePathFromProtocolReturnsEmptyStringForBadInput() {
        assertEquals("", SyncProtocol.decodePathFromProtocol(null));
        assertEquals("", SyncProtocol.decodePathFromProtocol(""));
        assertEquals("", SyncProtocol.decodePathFromProtocol("!!!invalid!!!"));
    }

    @Test
    void encodeDecodeRoundTripsArbitraryPaths() {
        for (String path :
                new String[] {
                    "C:/Users/test",
                    "C:/Users/test:folder",
                    "C:/用户/文档/测试.txt",
                    "C:/folder with spaces/file-name_v2.0.txt",
                    "C:\\Users\\foo\\Documents",
                    "/home/user/project",
                    "C:\\path\\with\\backslashes",
                    "C:\\Program Files\\App (v2.0)\\data:cache"
                }) {
            String encoded = SyncProtocol.encodePathForProtocol(path);
            String decoded = SyncProtocol.decodePathFromProtocol(encoded);
            assertEquals(path, decoded, "round-trip must preserve: " + path);
        }
    }
}
