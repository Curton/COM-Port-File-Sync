package com.filesync.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SettingsManagerFolderMappingTest {

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
    void getRememberedFolderMapping_returnsNullWhenEmpty() {
        SettingsManager settings = new SettingsManager();
        String port = "COM99_TEST_EMPTY_" + System.currentTimeMillis();
        String[] result = settings.getRememberedFolderMapping(port);
        assertNull(result);
    }

    @Test
    void setAndGetRememberedFolderMapping_persistsCorrectly() {
        SettingsManager settings = new SettingsManager();
        String port = "COM99_TEST_" + System.currentTimeMillis();
        settings.setRememberedFolderMapping(port, "C:/sender", "D:/receiver");
        String[] result = settings.getRememberedFolderMapping(port);
        assertArrayEquals(new String[] {"C:/sender", "D:/receiver"}, result);
    }

    @Test
    void setRememberedFolderMapping_ignoresEmptyPaths() {
        SettingsManager settings = new SettingsManager();
        String port = "COM99_EMPTY_" + System.currentTimeMillis();
        settings.setRememberedFolderMapping(port, "", "D:/receiver");
        settings.setRememberedFolderMapping(port, "C:/sender", "");
        settings.setRememberedFolderMapping(port, null, "D:/receiver");
        settings.setRememberedFolderMapping(port, "C:/sender", null);
        assertNull(settings.getRememberedFolderMapping(port));
    }

    @Test
    void getRememberedFolderMappings_returnsMultipleInOrder() {
        SettingsManager settings = new SettingsManager();
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
        SettingsManager settings = new SettingsManager();
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
    void getBaudRateIndex_returnsCorrectIndex() {
        assertEquals(0, SettingsManager.getBaudRateIndex(300));
        assertEquals(9, SettingsManager.getBaudRateIndex(115200));
        assertEquals(12, SettingsManager.getBaudRateIndex(921600));
    }

    @Test
    void getBaudRateIndex_returnsDefaultForUnknownValue() {
        assertEquals(4, SettingsManager.getBaudRateIndex(9600));
        assertEquals(9, SettingsManager.getBaudRateIndex(123456));
        assertEquals(9, SettingsManager.getBaudRateIndex(0));
        assertEquals(9, SettingsManager.getBaudRateIndex(-1));
    }

    @Test
    void getDataBitsIndex_returnsCorrectIndex() {
        assertEquals(0, SettingsManager.getDataBitsIndex(5));
        assertEquals(3, SettingsManager.getDataBitsIndex(8));
    }

    @Test
    void getDataBitsIndex_returnsDefaultForUnknownValue() {
        assertEquals(3, SettingsManager.getDataBitsIndex(4));
        assertEquals(3, SettingsManager.getDataBitsIndex(0));
        assertEquals(3, SettingsManager.getDataBitsIndex(-1));
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
}
