package com.filesync.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class SyncProtocolPathEncodingTest {

    @Test
    void encodePathForProtocolReturnsEmptyStringForNull() {
        assertEquals("", SyncProtocol.encodePathForProtocol(null));
    }

    @Test
    void encodePathForProtocolReturnsEmptyStringForEmptyString() {
        assertEquals("", SyncProtocol.encodePathForProtocol(""));
    }

    @Test
    void encodePathForProtocolEncodesSimplePath() {
        String encoded = SyncProtocol.encodePathForProtocol("C:/Users/test");
        assertNotNull(encoded);
        assertFalse(encoded.isEmpty());
    }

    @Test
    void encodePathForProtocolEncodesPathWithColons() {
        String path = "C:/Users/test:folder";
        String encoded = SyncProtocol.encodePathForProtocol(path);
        String decoded = SyncProtocol.decodePathFromProtocol(encoded);
        assertEquals(path, decoded);
    }

    @Test
    void decodePathFromProtocolReturnsEmptyStringForNull() {
        assertEquals("", SyncProtocol.decodePathFromProtocol(null));
    }

    @Test
    void decodePathFromProtocolReturnsEmptyStringForEmptyString() {
        assertEquals("", SyncProtocol.decodePathFromProtocol(""));
    }

    @Test
    void decodePathFromProtocolReturnsEmptyStringForInvalidBase64() {
        assertEquals("", SyncProtocol.decodePathFromProtocol("!!!invalid!!!"));
    }

    @Test
    void encodeDecodeRoundTripForUnicodePath() {
        String path = "C:/用户/文档/测试.txt";
        String encoded = SyncProtocol.encodePathForProtocol(path);
        String decoded = SyncProtocol.decodePathFromProtocol(encoded);
        assertEquals(path, decoded);
    }

    @Test
    void encodeDecodeRoundTripForPathWithSpecialChars() {
        String path = "C:/folder with spaces/file-name_v2.0.txt";
        String encoded = SyncProtocol.encodePathForProtocol(path);
        String decoded = SyncProtocol.decodePathFromProtocol(encoded);
        assertEquals(path, decoded);
    }
}
