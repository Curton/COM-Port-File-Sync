package com.filesync.ui;

import com.filesync.plugin.LinkEndpoint;
import com.filesync.plugin.LinkPlugin;
import com.filesync.plugin.LinkPluginRegistry;
import com.filesync.serial.SerialPortManager;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Builds the port drop-down contents: the real COM ports, minus the ones a plugin declares as its
 * own implementation detail, followed by every plugin endpoint.
 *
 * <p>Hiding before adding is what keeps a bridge from showing up as "two COM ports and a mystery
 * entry": the debug console is not a selectable link, so it must not be offered at all. The
 * composer is a plain function of enumerations and plugin answers (no Swing, no I/O of its own),
 * which is what lets the periodic refresh run off the event dispatch thread.
 */
public final class PortListComposer {

    private final LinkPluginRegistry registry;
    private final Supplier<List<String>> systemPortSupplier;

    public PortListComposer(LinkPluginRegistry registry) {
        this(registry, SerialPortManager::getAvailablePorts);
    }

    /** Test seam: replaces the jSerialComm enumeration with a fixed list. */
    PortListComposer(LinkPluginRegistry registry, Supplier<List<String>> systemPortSupplier) {
        this.registry = registry;
        this.systemPortSupplier = systemPortSupplier;
    }

    /** Enumerates the system ports and composes the drop-down list. */
    public Composed compose() {
        return compose(systemPortSupplier.get());
    }

    /**
     * Composes the list from an already-enumerated set of system ports.
     *
     * <p>A plugin that throws while answering is reported on stderr and left out; the remaining
     * ports still make it into the list, because a broken plugin must not cost the user their COM
     * ports.
     */
    public Composed compose(List<String> enumeratedSystemPorts) {
        Set<String> hidden = new LinkedHashSet<>();
        List<LinkEndpoint> discovered = new ArrayList<>();
        for (LinkPlugin plugin : registry.plugins()) {
            try {
                hidden.addAll(plugin.hiddenSystemPorts());
            } catch (RuntimeException e) {
                report(plugin, "hiddenSystemPorts", e);
            }
            try {
                discovered.addAll(plugin.discoverEndpoints());
            } catch (RuntimeException e) {
                report(plugin, "discoverEndpoints", e);
            }
        }

        List<String> systemPorts = new ArrayList<>();
        int hiddenCount = 0;
        for (String port : enumeratedSystemPorts) {
            if (port == null) {
                continue;
            }
            if (hidden.contains(port)) {
                hiddenCount++;
                continue;
            }
            if (!systemPorts.contains(port)) {
                systemPorts.add(port);
            }
        }

        Set<String> ids = new LinkedHashSet<>(systemPorts);
        List<LinkEndpoint> endpoints = new ArrayList<>();
        for (LinkEndpoint endpoint : discovered) {
            if (endpoint == null || endpoint.id() == null || endpoint.id().isEmpty()) {
                continue;
            }
            if (ids.add(endpoint.id())) {
                endpoints.add(endpoint);
            }
        }
        return new Composed(systemPorts, endpoints, List.copyOf(ids), hiddenCount);
    }

    /** Whether the entry is a plugin endpoint (and must not be auto-connected). */
    public boolean isPluginPort(String portName) {
        return registry.isPluginPort(portName);
    }

    private static void report(LinkPlugin plugin, String method, RuntimeException e) {
        System.err.println("[plugins] " + plugin.name() + "." + method + "() failed: " + e);
    }

    /** One drop-down snapshot: the visible system ports, the endpoints and the combined items. */
    public static final class Composed {

        private final List<String> systemPorts;
        private final List<LinkEndpoint> endpoints;
        private final List<String> ports;
        private final int hiddenSystemPortCount;

        Composed(
                List<String> systemPorts,
                List<LinkEndpoint> endpoints,
                List<String> ports,
                int hiddenSystemPortCount) {
            this.systemPorts = List.copyOf(systemPorts);
            this.endpoints = List.copyOf(endpoints);
            this.ports = List.copyOf(ports);
            this.hiddenSystemPortCount = hiddenSystemPortCount;
        }

        /** System COM ports that remain visible. */
        public List<String> systemPorts() {
            return systemPorts;
        }

        /** Plugin endpoints, in plugin order. */
        public List<LinkEndpoint> endpoints() {
            return endpoints;
        }

        /** Combined drop-down items: system ports first, then endpoint ids. */
        public List<String> ports() {
            return ports;
        }

        /** How many enumerated ports a plugin asked to hide (for the log). */
        public int hiddenSystemPortCount() {
            return hiddenSystemPortCount;
        }
    }
}
