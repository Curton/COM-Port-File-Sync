package com.filesync.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.serial.SerialPortManager;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ownership and port-manager lookup over a plugin set. */
class LinkPluginRegistryTest {

    @Test
    void emptyRegistryOwnsNothing() {
        LinkPluginRegistry registry = LinkPluginRegistry.empty();

        assertTrue(registry.isEmpty());
        assertEquals(0, registry.size());
        assertFalse(registry.isPluginPort("COM3"));
        assertFalse(registry.isPluginPort("hidlink:A"));
        assertFalse(registry.isPluginPort(null));
        assertFalse(registry.isPluginPort(""));
        assertThrows(IllegalArgumentException.class, () -> registry.createPortManager("hidlink:A"));
    }

    @Test
    void ownershipIsDelegatedToThePluginThatClaimsTheId() {
        SerialPortManager manager = new SerialPortManager();
        FakeLinkPlugin bridge =
                new FakeLinkPlugin("hidlink").endpoint("hidlink:A").manager(manager);
        FakeLinkPlugin other = new FakeLinkPlugin("other").endpoint("other:X");
        LinkPluginRegistry registry = new LinkPluginRegistry(List.of(bridge, other));

        assertEquals(2, registry.size());
        assertFalse(registry.isEmpty());
        assertTrue(registry.isPluginPort("hidlink:A"));
        assertTrue(registry.isPluginPort("other:X"));
        assertFalse(registry.isPluginPort("COM7"));
        assertFalse(registry.isPluginPort("hidlink:C"));
        assertSame(manager, registry.createPortManager("hidlink:A"));
        assertEquals(List.of("hidlink:A"), bridge.createRequests());
        assertTrue(other.createRequests().isEmpty());
    }

    @Test
    void ownershipDoesNotParseIdPrefixes() {
        FakeLinkPlugin bridge = new FakeLinkPlugin("hidlink").endpoint("hidlink:A");
        LinkPluginRegistry registry = new LinkPluginRegistry(List.of(bridge));

        assertFalse(
                registry.isPluginPort("hidlink:B"),
                "an id the plugin never announced must not be claimed");
    }

    @Test
    void createForAnUnownedIdIsAProgrammingError() {
        FakeLinkPlugin bridge = new FakeLinkPlugin("hidlink").endpoint("hidlink:A");
        LinkPluginRegistry registry = new LinkPluginRegistry(List.of(bridge));

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class, () -> registry.createPortManager("COM4"));
        assertTrue(error.getMessage().contains("COM4"));
    }

    @Test
    void pluginListIsAnImmutableCopy() {
        FakeLinkPlugin bridge = new FakeLinkPlugin("hidlink").endpoint("hidlink:A");
        java.util.List<LinkPlugin> source = new java.util.ArrayList<>(java.util.List.of(bridge));
        LinkPluginRegistry registry = new LinkPluginRegistry(source);

        source.clear();

        assertEquals(1, registry.plugins().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> registry.plugins().add(new FakeLinkPlugin("late")));
    }
}
