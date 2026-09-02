package com.filesync;

import java.io.InputStream;
import java.util.Properties;

/**
 * Application version stamped into {@code version.properties} by Maven resource filtering at build
 * time.
 */
public final class AppVersion {

    /** Reported when the version resource is missing or unreadable (e.g. unpackaged IDE runs). */
    public static final String UNKNOWN = "unknown";

    private AppVersion() {}

    /** The application version, e.g. {@code "1.6.33"}, or {@link #UNKNOWN} when unavailable. */
    public static String get() {
        try (InputStream is =
                AppVersion.class.getClassLoader().getResourceAsStream("version.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                String version = props.getProperty("application.version");
                if (version != null && !version.trim().isEmpty()) {
                    return version.trim();
                }
            }
        } catch (Exception e) {
            // Fall through to UNKNOWN
        }
        return UNKNOWN;
    }
}
