package com.filesync.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * A plugin endpoint id must survive a settings round trip untouched: it is what the drop-down
 * stores as {@code lastPort}, and the plugin (not a COM-number allocator) decides what it means.
 */
class LastPortPersistenceTest {

    @Test
    void aPluginEndpointIdRoundTripsThroughPreferences() {
        SettingsManager settings = new SettingsManager(true);
        settings.setLastPort("hidlink:A");
        settings.save();

        SettingsManager reloaded = new SettingsManager(true);

        assertEquals("hidlink:A", reloaded.getLastPort());
    }

    @Test
    void aComPortNameRoundTripsTheSameWay() {
        SettingsManager settings = new SettingsManager(true);
        settings.setLastPort("COM7");
        settings.save();

        assertEquals("COM7", new SettingsManager(true).getLastPort());
    }
}
