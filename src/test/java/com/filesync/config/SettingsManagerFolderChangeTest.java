package com.filesync.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SettingsManagerFolderChangeTest {

    @Test
    void findReceiverFolderForSender_returnsNullWhenNoMapping() {
        SettingsManager settings = new SettingsManager();
        String port = "COM99_EMPTY_" + System.currentTimeMillis();
        String result = settings.findReceiverFolderForSender("C:/sender", port);
        assertNull(result);
    }

    @Test
    void findReceiverFolderForSender_returnsMappedReceiver() {
        SettingsManager settings = new SettingsManager();
        String port = "COM99_TEST_" + System.currentTimeMillis();
        settings.setRememberedFolderMapping(port, "C:/sender", "D:/receiver");

        String result = settings.findReceiverFolderForSender("C:/sender", port);
        assertEquals("D:/receiver", result);
    }

    @Test
    void findReceiverFolderForSender_normalizesPaths() {
        SettingsManager settings = new SettingsManager();
        String port = "COM99_NORM_" + System.currentTimeMillis();
        settings.setRememberedFolderMapping(port, "C:\\sender", "D:\\receiver");

        // Query with normalized forward slashes
        String result = settings.findReceiverFolderForSender("C:/sender", port);
        assertEquals("D:/receiver", result);

        // Query with Windows backslashes
        result = settings.findReceiverFolderForSender("C:\\sender", port);
        assertEquals("D:/receiver", result);
    }

    @Test
    void findReceiverFolderForSender_returnsNullWhenNoMatch() {
        SettingsManager settings = new SettingsManager();
        String port = "COM99_NOMATCH_" + System.currentTimeMillis();
        settings.setRememberedFolderMapping(port, "C:/sender1", "D:/receiver1");
        settings.setRememberedFolderMapping(port, "C:/sender2", "D:/receiver2");

        String result = settings.findReceiverFolderForSender("C:/unknown", port);
        assertNull(result);
    }

    @Test
    void findReceiverFolderForSender_returnsMostRecentMapping() {
        SettingsManager settings = new SettingsManager();
        String port = "COM99_MRU_" + System.currentTimeMillis();

        // Add multiple mappings for same sender (shouldn't happen, but test logic)
        settings.setRememberedFolderMapping(port, "C:/sender", "D:/receiver1");
        settings.setRememberedFolderMapping(port, "C:/sender", "D:/receiver2");

        String result = settings.findReceiverFolderForSender("C:/sender", port);
        // MRU first, so receiver2 should be returned
        assertEquals("D:/receiver2", result);
    }

    @Test
    void findReceiverFolderForSender_handlesEmptyPortParameter() {
        SettingsManager settings = new SettingsManager();
        // Use default port mapping
        settings.setRememberedFolderMapping("", "C:/sender", "D:/receiver");

        String result = settings.findReceiverFolderForSender("C:/sender", "");
        assertEquals("D:/receiver", result);

        result = settings.findReceiverFolderForSender("C:/sender", null);
        assertEquals("D:/receiver", result);
    }

    @Test
    void findReceiverFolderForSender_returnsNullForNullOrEmptySenderPath() {
        SettingsManager settings = new SettingsManager();
        String port = "COM99_EMPTY_SENDER_" + System.currentTimeMillis();

        assertNull(settings.findReceiverFolderForSender(null, port));
        assertNull(settings.findReceiverFolderForSender("", port));
    }
}
