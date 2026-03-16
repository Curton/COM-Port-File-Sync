package com.filesync.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertArrayEquals(new String[]{"C:/sender", "D:/receiver"}, result);
    }
}
