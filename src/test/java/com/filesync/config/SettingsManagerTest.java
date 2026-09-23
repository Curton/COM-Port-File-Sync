package com.filesync.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SettingsManagerTest {

    @Test
    void normalizeFolderPath_trimsAndReplacesBackslash() {
        assertEquals("C:/foo/bar", SettingsManager.normalizeFolderPath("C:\\foo\\bar"));
        assertEquals("/home/user", SettingsManager.normalizeFolderPath("  /home/user  "));
        assertEquals("", SettingsManager.normalizeFolderPath(null));
        assertEquals("", SettingsManager.normalizeFolderPath(""));
    }

    @Test
    void isMappingMatch_returnsTrueWhenNoRememberedMapping() {
        assertTrue(SettingsManager.isMappingMatch("/local", "/remote", "", ""));
    }

    @Test
    void isMappingMatch_returnsTrueWhenPathsMatch() {
        assertTrue(SettingsManager.isMappingMatch("C:/foo", "D:/bar", "C:/foo", "D:/bar"));
        assertTrue(SettingsManager.isMappingMatch("C:/foo", "D:/bar", "C:\\foo", "D:\\bar"));
    }

    @Test
    void isMappingMatch_returnsFalseWhenPathsDiffer() {
        assertFalse(SettingsManager.isMappingMatch("C:/foo", "D:/bar", "C:/other", "D:/bar"));
        assertFalse(SettingsManager.isMappingMatch("C:/foo", "D:/bar", "C:/foo", "D:/other"));
    }

    @Test
    void isBothSidesChangedFromRemembered_returnsTrueOnlyWhenBothDiffer() {
        assertTrue(
                SettingsManager.isBothSidesChangedFromRemembered(
                        "C:/newLocal", "D:/newRemote", "C:/oldLocal", "D:/oldRemote"));
        assertFalse(
                SettingsManager.isBothSidesChangedFromRemembered(
                        "C:/newLocal", "D:/oldRemote", "C:/oldLocal", "D:/oldRemote"));
        assertFalse(
                SettingsManager.isBothSidesChangedFromRemembered(
                        "C:/oldLocal", "D:/newRemote", "C:/oldLocal", "D:/oldRemote"));
        assertFalse(
                SettingsManager.isBothSidesChangedFromRemembered(
                        "C:/newLocal", "", "C:/oldLocal", "D:/oldRemote"));
    }

    @Test
    void getRememberedFolderMappings_returnsEmptyWhenNothingStored() {
        SettingsManager settings = new SettingsManager(true);
        String port = "COM99_TEST_EMPTY_" + System.currentTimeMillis();
        assertTrue(settings.getRememberedFolderMappings(port).isEmpty());
    }

    @Test
    void setAndGetRememberedFolderMapping_persistsCorrectly() {
        SettingsManager settings = new SettingsManager(true);
        String port = "COM99_TEST_" + System.currentTimeMillis();
        settings.setRememberedFolderMapping(port, "C:/sender", "D:/receiver");
        List<String[]> result = settings.getRememberedFolderMappings(port);
        assertEquals(1, result.size());
        assertArrayEquals(new String[] {"C:/sender", "D:/receiver"}, result.get(0));
    }

    @Test
    void setRememberedFolderMapping_ignoresEmptyPaths() {
        SettingsManager settings = new SettingsManager(true);
        String port = "COM99_EMPTY_" + System.currentTimeMillis();
        settings.setRememberedFolderMapping(port, "", "D:/receiver");
        settings.setRememberedFolderMapping(port, "C:/sender", "");
        settings.setRememberedFolderMapping(port, null, "D:/receiver");
        settings.setRememberedFolderMapping(port, "C:/sender", null);
        assertTrue(settings.getRememberedFolderMappings(port).isEmpty());
    }

    @Test
    void getRememberedFolderMappings_returnsMultipleInOrder() {
        SettingsManager settings = new SettingsManager(true);
        String port = "COM99_MULTI_" + System.currentTimeMillis();
        settings.setRememberedFolderMapping(port, "C:/sender1", "D:/receiver1");
        settings.setRememberedFolderMapping(port, "C:/sender2", "D:/receiver2");
        settings.setRememberedFolderMapping(port, "C:/sender3", "D:/receiver3");

        List<String[]> mappings = settings.getRememberedFolderMappings(port);
        assertEquals(3, mappings.size());
        assertArrayEquals(new String[] {"C:/sender3", "D:/receiver3"}, mappings.get(0));
        assertArrayEquals(new String[] {"C:/sender2", "D:/receiver2"}, mappings.get(1));
        assertArrayEquals(new String[] {"C:/sender1", "D:/receiver1"}, mappings.get(2));
    }

    @Test
    void setRememberedFolderMapping_removesExactDuplicateWhenSamePairAddedTwice() {
        SettingsManager settings = new SettingsManager(true);
        String port = "COM99_DUP_" + System.currentTimeMillis();
        String senderPath = "C:/sender_" + port;
        String receiverPath = "D:/receiver1";
        settings.setRememberedFolderMapping(port, senderPath, receiverPath);
        settings.setRememberedFolderMapping(port, senderPath, receiverPath);

        List<String[]> mappings = settings.getRememberedFolderMappings(port);
        assertEquals(1, mappings.size());
        assertArrayEquals(new String[] {senderPath, receiverPath}, mappings.get(0));
    }

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
    void getParityIndex_returnsCorrectIndex() {
        assertEquals(0, SettingsManager.getParityIndex(0));
        assertEquals(1, SettingsManager.getParityIndex(1));
        assertEquals(2, SettingsManager.getParityIndex(2));
        assertEquals(3, SettingsManager.getParityIndex(3));
        assertEquals(4, SettingsManager.getParityIndex(4));
    }

    @Test
    void getParityIndex_returnsDefaultForUnknownValue() {
        assertEquals(0, SettingsManager.getParityIndex(5));
        assertEquals(0, SettingsManager.getParityIndex(-1));
        assertEquals(0, SettingsManager.getParityIndex(100));
    }

    @Test
    void save_and_load_portSettings() {
        SettingsManager settings = new SettingsManager(true);
        String port = "COM99_SAVE_" + System.currentTimeMillis();

        settings.setBaudRate(SettingsManager.DEFAULT_BAUD_RATE);
        settings.setDataBits(SettingsManager.DEFAULT_DATA_BITS);
        settings.setStopBits(2);
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
        assertEquals(2, loaded.getStopBits());
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

    /**
     * {@code saveRememberedFolderMappings} flushes the preferences node but {@code save} never did,
     * so on a file-backed backend (Linux/macOS) the last port, folder, recent list and every flag
     * could be lost on an abnormal exit. Both paths must go through the same persist step.
     */
    @Test
    void save_flushesPreferences() {
        CountingSettingsManager settings = new CountingSettingsManager(true);

        settings.setLastPort("COM7");
        settings.save();

        assertTrue(settings.persistCalls > 0, "save() must flush the preferences node");
    }

    @Test
    void setRememberedFolderMapping_flushesPreferences() {
        CountingSettingsManager settings = new CountingSettingsManager(true);

        settings.setRememberedFolderMapping("COM7", "C:/local", "D:/remote");

        assertTrue(
                settings.persistCalls > 0,
                "the mapping writer must still flush the preferences node");
    }

    /** Records how often the persist step runs, without touching the real backing store. */
    private static final class CountingSettingsManager extends SettingsManager {
        int persistCalls;

        CountingSettingsManager(boolean testMode) {
            super(testMode);
        }

        @Override
        void persistPreferences() {
            persistCalls++;
        }
    }
}
