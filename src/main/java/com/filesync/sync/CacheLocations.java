package com.filesync.sync;

import java.io.File;

/**
 * Shared location of the on-disk cache directory used by the persisted-manifest and block-signature
 * caches. Production resolves to {@code <user.home>/.filesync} (outside any sync folder so the
 * manifest scan never sees the cache files). Tests redirect it to a temp directory via {@link
 * #setOverrideForTest(File)} so suite runs never litter the real one.
 */
final class CacheLocations {

    static final String CACHE_DIR_NAME = ".filesync";

    private static volatile File override;

    private CacheLocations() {}

    /** The cache directory: the test override when set, otherwise {@code ~/.filesync}. */
    static File cacheDir() {
        File dir = override;
        return dir != null ? dir : new File(System.getProperty("user.home"), CACHE_DIR_NAME);
    }

    /** Redirect all cache writes to {@code dir} until {@link #clearOverrideForTest()}. */
    static void setOverrideForTest(File dir) {
        override = dir;
    }

    /** Restore the default {@code ~/.filesync} location. */
    static void clearOverrideForTest() {
        override = null;
    }
}
