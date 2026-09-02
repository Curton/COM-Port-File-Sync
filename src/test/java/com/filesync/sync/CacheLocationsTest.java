package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CacheLocationsTest {

    @AfterEach
    void restoreDefault() {
        CacheLocations.clearOverrideForTest();
    }

    @Test
    void defaultsToUserHomeFilesyncDir() {
        assertEquals(
                new File(System.getProperty("user.home"), ".filesync"),
                CacheLocations.cacheDir(),
                "Without an override, caches live under ~/.filesync");
    }

    @Test
    void overrideRedirectsUntilCleared() {
        File tempDir = new File("temp-cache-dir");

        CacheLocations.setOverrideForTest(tempDir);
        assertEquals(tempDir, CacheLocations.cacheDir(), "Override must redirect the location");

        CacheLocations.clearOverrideForTest();
        assertEquals(
                new File(System.getProperty("user.home"), ".filesync"),
                CacheLocations.cacheDir(),
                "Clearing must restore the default location");
    }
}
