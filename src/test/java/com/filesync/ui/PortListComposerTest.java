package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.plugin.FakeLinkPlugin;
import com.filesync.plugin.LinkEndpoint;
import com.filesync.plugin.LinkPlugin;
import com.filesync.plugin.LinkPluginRegistry;
import com.filesync.serial.SerialPortManager;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The drop-down composition: real COM ports in enumeration order, plugin-internal ports hidden,
 * plugin endpoints appended.
 */
class PortListComposerTest {

    @Test
    void withoutPluginsTheSystemPortsPassThroughUnchanged() {
        PortListComposer composer = composer(LinkPluginRegistry.empty());

        PortListComposer.Composed composed = composer.compose(List.of("COM1", "COM3"));

        assertEquals(List.of("COM1", "COM3"), composed.ports());
        assertEquals(List.of("COM1", "COM3"), composed.systemPorts());
        assertTrue(composed.endpoints().isEmpty());
        assertEquals(0, composed.hiddenSystemPortCount());
    }

    @Test
    void hiddenPortsDisappearAndEndpointsAreAppended() {
        FakeLinkPlugin bridge =
                new FakeLinkPlugin("hidlink")
                        .endpoint("hidlink:A", "COM5 MI_00")
                        .endpoint("hidlink:B", "COM4 MI_02")
                        .hidden("COM4", "COM5");
        PortListComposer composer = composer(new LinkPluginRegistry(List.of(bridge)));

        PortListComposer.Composed composed = composer.compose(List.of("COM1", "COM4", "COM5"));

        assertEquals(List.of("COM1"), composed.systemPorts());
        assertEquals(List.of("hidlink:A", "hidlink:B"), ids(composed.endpoints()));
        assertEquals(List.of("COM1", "hidlink:A", "hidlink:B"), composed.ports());
        assertEquals(2, composed.hiddenSystemPortCount());
    }

    @Test
    void onlyPluginEndpointsStillProduceAList() {
        FakeLinkPlugin bridge = new FakeLinkPlugin("hidlink").endpoint("hidlink:A");
        PortListComposer composer = composer(new LinkPluginRegistry(List.of(bridge)));

        PortListComposer.Composed composed = composer.compose(List.of());

        assertEquals(List.of("hidlink:A"), composed.ports());
        assertTrue(composed.systemPorts().isEmpty());
    }

    @Test
    void endpointIdsAreDeduplicatedAcrossPlugins() {
        FakeLinkPlugin first = new FakeLinkPlugin("first").endpoint("dup:A");
        FakeLinkPlugin second = new FakeLinkPlugin("second").endpoint("dup:A").endpoint("dup:B");
        PortListComposer composer = composer(new LinkPluginRegistry(List.of(first, second)));

        PortListComposer.Composed composed = composer.compose(List.of());

        assertEquals(List.of("dup:A", "dup:B"), composed.ports());
        assertEquals(2, composed.endpoints().size());
    }

    @Test
    void oneBrokenPluginDoesNotCostTheOthersTheirPorts() {
        FakeLinkPlugin good = new FakeLinkPlugin("good").endpoint("good:A");
        LinkPlugin broken =
                new LinkPlugin() {
                    @Override
                    public String name() {
                        return "broken";
                    }

                    @Override
                    public List<LinkEndpoint> discoverEndpoints() {
                        throw new IllegalStateException("device enumeration blew up");
                    }

                    @Override
                    public Set<String> hiddenSystemPorts() {
                        throw new IllegalStateException("also broken");
                    }

                    @Override
                    public boolean owns(String endpointId) {
                        return false;
                    }

                    @Override
                    public SerialPortManager createPortManager(String endpointId) {
                        throw new IllegalStateException("unreachable");
                    }
                };
        PortListComposer composer = composer(new LinkPluginRegistry(List.of(broken, good)));

        PortListComposer.Composed composed = composer.compose(List.of("COM2"));

        assertEquals(List.of("COM2", "good:A"), composed.ports());
    }

    @Test
    void pluginPortCheckFollowsOwnership() {
        FakeLinkPlugin bridge = new FakeLinkPlugin("hidlink").endpoint("hidlink:A");
        PortListComposer composer = composer(new LinkPluginRegistry(List.of(bridge)));

        assertTrue(composer.isPluginPort("hidlink:A"));
        assertFalse(composer.isPluginPort("COM1"));
    }

    private static PortListComposer composer(LinkPluginRegistry registry) {
        // The supplier is irrelevant for these tests: every case passes its own enumeration.
        return new PortListComposer(registry, List::of);
    }

    private static List<String> ids(List<LinkEndpoint> endpoints) {
        return endpoints.stream().map(LinkEndpoint::id).toList();
    }
}
