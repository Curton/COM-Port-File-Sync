package com.filesync.plugin;

import com.filesync.serial.SerialPortManager;
import java.util.List;

/**
 * The loaded {@link LinkPlugin} set plus the two questions the rest of the app asks about it: "is
 * this combo-box entry a plugin endpoint?" and "give me the port manager for it".
 *
 * <p>Ownership is decided by {@link LinkPlugin#owns(String)} and never by parsing the id (no prefix
 * matching): the id format belongs to the plugin.
 */
public final class LinkPluginRegistry {

    private static final LinkPluginRegistry EMPTY = new LinkPluginRegistry(List.of());

    private final List<LinkPlugin> plugins;

    public LinkPluginRegistry(List<LinkPlugin> plugins) {
        this.plugins = List.copyOf(plugins);
    }

    /** A registry with no plugins: every entry is treated as a system COM port. */
    public static LinkPluginRegistry empty() {
        return EMPTY;
    }

    /** Discovers plugins the default way (see {@link LinkPluginLoader#loadDefault()}). */
    public static LinkPluginRegistry loadDefault() {
        return new LinkPluginRegistry(LinkPluginLoader.loadDefault());
    }

    public List<LinkPlugin> plugins() {
        return plugins;
    }

    public boolean isEmpty() {
        return plugins.isEmpty();
    }

    public int size() {
        return plugins.size();
    }

    /** True when any plugin claims {@code portName} as one of its endpoints. */
    public boolean isPluginPort(String portName) {
        if (portName == null || portName.isEmpty()) {
            return false;
        }
        for (LinkPlugin plugin : plugins) {
            if (plugin.owns(portName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates the port manager for a plugin endpoint.
     *
     * @throws IllegalArgumentException when no plugin owns {@code endpointId}
     */
    public SerialPortManager createPortManager(String endpointId) {
        for (LinkPlugin plugin : plugins) {
            if (plugin.owns(endpointId)) {
                return plugin.createPortManager(endpointId);
            }
        }
        throw new IllegalArgumentException("no plugin owns endpoint " + endpointId);
    }
}
