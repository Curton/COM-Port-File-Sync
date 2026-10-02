package com.filesync.sync;

import java.io.File;

/**
 * Public bridge so tests outside {@code com.filesync.sync} (e.g. the CLI end-to-end tests) can
 * redirect the on-disk cache directory; {@link CacheLocations} itself stays package-private.
 */
public final class TestCacheOverride {

    private TestCacheOverride() {}

    public static void set(File dir) {
        CacheLocations.setOverrideForTest(dir);
    }

    public static void clear() {
        CacheLocations.clearOverrideForTest();
    }
}
