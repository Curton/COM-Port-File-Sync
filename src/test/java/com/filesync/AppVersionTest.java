package com.filesync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class AppVersionTest {

    @Test
    void versionIsNeverBlank() {
        String version = AppVersion.get();
        assertNotNull(version, "Version must never be null");
        assertFalse(version.isBlank(), "Version must never be blank");
    }

    @Test
    void versionIsNotUnfilteredPlaceholder() {
        // A raw "${project.version}" means Maven resource filtering broke and the startup log
        // line would report a placeholder instead of the real build.
        assertFalse(AppVersion.get().contains("${"));
    }
}
