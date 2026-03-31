package com.filesync.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SettingsManagerFolderChangeTest {

    @Test
    void findReceiverFolderForSender_returnsNullWhenNoMapping() {
        SettingsManager settings = new SettingsManager(true);
        String port = "COM99_EMPTY_" + System.currentTimeMillis();
        String result = settings.findReceiverFolderForSender("C:/sender", port);
        assertNull(result);
    }

    @Test
    void findReceiverFolderForSender_returnsMappedReceiver() {
        SettingsManager settings = new SettingsManager(true);
        String port = "COM99_TEST_" + System.currentTimeMillis();
        settings.setRememberedFolderMapping(port, "C:/sender", "D:/receiver");

        String result = settings.findReceiverFolderForSender("C:/sender", port);
        assertEquals("D:/receiver", result);
    }

    @Test
    void findReceiverFolderForSender_normalizesPaths() {
        SettingsManager settings = new SettingsManager(true);
        String port = "COM99_NORM_" + System.currentTimeMillis();
        settings.setRememberedFolderMapping(port, "C:\\sender", "D:\\receiver");

        String result = settings.findReceiverFolderForSender("C:/sender", port);
        assertEquals("D:/receiver", result);

        result = settings.findReceiverFolderForSender("C:\\sender", port);
        assertEquals("D:/receiver", result);
    }

    @Test
    void findReceiverFolderForSender_returnsNullWhenNoMatch() {
        SettingsManager settings = new SettingsManager(true);
        String port = "COM99_NOMATCH_" + System.currentTimeMillis();
        settings.setRememberedFolderMapping(port, "C:/sender1", "D:/receiver1");
        settings.setRememberedFolderMapping(port, "C:/sender2", "D:/receiver2");

        String result = settings.findReceiverFolderForSender("C:/unknown", port);
        assertNull(result);
    }

    @Test
    void findReceiverFolderForSender_returnsMostRecentMapping() {
        SettingsManager settings = new SettingsManager(true);
        String port = "COM99_MRU_" + System.currentTimeMillis();

        settings.setRememberedFolderMapping(port, "C:/sender", "D:/receiver1");
        settings.setRememberedFolderMapping(port, "C:/sender", "D:/receiver2");

        String result = settings.findReceiverFolderForSender("C:/sender", port);
        assertEquals("D:/receiver2", result);
    }

    @Test
    void findReceiverFolderForSender_handlesEmptyPortParameter() {
        SettingsManager settings = new SettingsManager(true);
        settings.setRememberedFolderMapping("", "C:/sender", "D:/receiver");

        String result = settings.findReceiverFolderForSender("C:/sender", "");
        assertEquals("D:/receiver", result);

        result = settings.findReceiverFolderForSender("C:/sender", null);
        assertEquals("D:/receiver", result);
    }

    @Test
    void findReceiverFolderForSender_returnsNullForNullOrEmptySenderPath() {
        SettingsManager settings = new SettingsManager(true);
        String port = "COM99_EMPTY_SENDER_" + System.currentTimeMillis();

        assertNull(settings.findReceiverFolderForSender(null, port));
        assertNull(settings.findReceiverFolderForSender("", port));
    }

    @Test
    void addRecentFolder_ignoresNullAndEmpty() {
        SettingsManager settings = new SettingsManager(true);
        int initialSize = settings.getRecentFolders().size();
        settings.addRecentFolder(null);
        settings.addRecentFolder("");
        settings.addRecentFolder("   ");
        assertEquals(initialSize, settings.getRecentFolders().size());
    }

    @Test
    void addRecentFolder_addsFolderToRecentList() {
        SettingsManager settings = new SettingsManager(true);
        String uniqueFolder = "C:/UNIQUE_ADDFIRST_" + System.nanoTime();

        settings.addRecentFolder(uniqueFolder);

        List<String> recent = settings.getRecentFolders();
        boolean found = recent.stream().anyMatch(f -> f.equals(uniqueFolder));
        assertTrue(found, "Should contain the added folder");
    }

    @Test
    void addRecentFolder_deduplicatesAndReorders() {
        SettingsManager settings = new SettingsManager(true);
        String uniqueId = "_DEDUP_" + System.currentTimeMillis();

        settings.addRecentFolder("C:/folder1" + uniqueId);
        settings.addRecentFolder("C:/folder2" + uniqueId);
        settings.addRecentFolder("C:/folder1" + uniqueId);

        List<String> recent = settings.getRecentFolders();
        String first = recent.get(0);
        assertTrue(first.startsWith("C:/folder1" + uniqueId));
        assertEquals(2, recent.stream().filter(f -> f.contains(uniqueId)).count());
    }

    @Test
    void addRecentFolder_respectsMaxRecentFolders() {
        SettingsManager settings = new SettingsManager(true);
        int initialSize = settings.getRecentFolders().size();

        for (int i = 0; i < 15; i++) {
            settings.addRecentFolder("C:/maxrecent_" + i + "_" + System.currentTimeMillis());
        }

        List<String> recent = settings.getRecentFolders();
        assertTrue(recent.size() <= initialSize + 10, "Should be capped at MAX_RECENT_FOLDERS=10");
    }

    @Test
    void addRecentFolder_normalizesBackslashes() {
        SettingsManager settings = new SettingsManager(true);
        String unique = "C:\\Users\\Test_" + System.currentTimeMillis();

        settings.addRecentFolder(unique);

        List<String> recent = settings.getRecentFolders();
        boolean hasNormalized = recent.stream().anyMatch(f -> f.contains("C:/Users/Test"));
        assertTrue(hasNormalized);
    }

    @Test
    void getRecentFolders_returnsCopyOfList() {
        SettingsManager settings = new SettingsManager(true);
        int initialSize = settings.getRecentFolders().size();

        List<String> folders = settings.getRecentFolders();
        folders.add("C:/added_after");

        assertEquals(initialSize, settings.getRecentFolders().size());
    }

    @Test
    void save_and_load_portSettings() {
        SettingsManager settings = new SettingsManager(true);
        String port = "COM99_SAVE_" + System.currentTimeMillis();

        settings.setBaudRate(SettingsManager.DEFAULT_BAUD_RATE);
        settings.setDataBits(SettingsManager.DEFAULT_DATA_BITS);
        settings.setStopBits(SettingsManager.DEFAULT_STOP_BITS);
        settings.setParity(SettingsManager.DEFAULT_PARITY);
        settings.setLastPort(port);
        settings.setLastFolder("C:/last");
        settings.setStrictSync(false);
        settings.setRespectGitignore(false);
        settings.setFastMode(true);
        settings.save();

        SettingsManager loaded = new SettingsManager(true);
        assertEquals(SettingsManager.DEFAULT_BAUD_RATE, loaded.getBaudRate());
        assertEquals(SettingsManager.DEFAULT_DATA_BITS, loaded.getDataBits());
        assertEquals(SettingsManager.DEFAULT_STOP_BITS, loaded.getStopBits());
        assertEquals(SettingsManager.DEFAULT_PARITY, loaded.getParity());
        assertEquals(port, loaded.getLastPort());
        assertEquals("C:/last", loaded.getLastFolder());
        assertFalse(loaded.isStrictSync());
        assertFalse(loaded.isRespectGitignore());
        assertTrue(loaded.isFastMode());
    }

    @AfterEach
    void cleanup() {
        // Clean up test preferences only, never touch user's actual settings.
        SettingsManager settings = new SettingsManager(true);
        settings.setBaudRate(SettingsManager.DEFAULT_BAUD_RATE);
        settings.setDataBits(SettingsManager.DEFAULT_DATA_BITS);
        settings.setStopBits(SettingsManager.DEFAULT_STOP_BITS);
        settings.setParity(SettingsManager.DEFAULT_PARITY);
        settings.setLastPort("");
        settings.setLastFolder("");
        settings.setStrictSync(false);
        settings.setRespectGitignore(false);
        settings.setFastMode(true);
        settings.setDebugMode(false);
        settings.save();
    }

    @Test
    void settersAndGetters_work() {
        SettingsManager settings = new SettingsManager(true);

        settings.setBaudRate(9600);
        assertEquals(9600, settings.getBaudRate());

        settings.setDataBits(7);
        assertEquals(7, settings.getDataBits());

        settings.setStopBits(2);
        assertEquals(2, settings.getStopBits());

        settings.setParity(1);
        assertEquals(1, settings.getParity());

        settings.setLastPort("COM5");
        assertEquals("COM5", settings.getLastPort());

        settings.setLastFolder("E:/mydir");
        assertEquals("E:/mydir", settings.getLastFolder());

        settings.setStrictSync(true);
        assertTrue(settings.isStrictSync());

        settings.setRespectGitignore(false);
        assertFalse(settings.isRespectGitignore());

        settings.setFastMode(true);
        assertTrue(settings.isFastMode());
    }
}
