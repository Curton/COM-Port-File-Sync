package com.filesync.plugin;

import com.filesync.serial.SerialPortManager;
import java.util.List;
import java.util.Set;

/**
 * A link backend that is not a plain COM port, loaded from {@code plugins/*.jar} at startup (see
 * {@link LinkPluginLoader}). The host only talks to this interface: everything behind {@link
 * #createPortManager(String)} stays inside the plugin's own class loader.
 *
 * <p><b>Threading contract.</b> {@link #discoverEndpoints()} and {@link #hiddenSystemPorts()} are
 * called from a background thread roughly every two seconds while the GUI is disconnected, and from
 * the event dispatch thread on a manual refresh. Implementations must not touch Swing, must return
 * quickly (device enumerations that take longer than the poll interval degrade the drop-down, not
 * the app), and must tolerate concurrent calls.
 *
 * <p>Instances are shared for the lifetime of the process; one plugin jar yields one instance per
 * {@code ServiceLoader} load.
 */
public interface LinkPlugin {

    /** Short plugin name for log lines, e.g. {@code hidlink}. */
    String name();

    /** Endpoints currently attached, in a deterministic order. Never {@code null}. */
    List<LinkEndpoint> discoverEndpoints();

    /**
     * System COM port names that must be hidden from the raw list because they are an
     * implementation detail of this plugin (e.g. both CDC interfaces of a bridge whose data port is
     * exposed as a plugin endpoint). Hiding both keeps a half-configured bridge from offering a
     * choice the user cannot make correctly. Never {@code null}.
     */
    Set<String> hiddenSystemPorts();

    /** Whether {@code endpointId} was produced by this plugin. */
    boolean owns(String endpointId);

    /**
     * Creates a fresh port manager for {@code endpointId}, not yet opened. Called once per
     * connection attempt; the returned instance is installed as the app's active port.
     *
     * @throws IllegalArgumentException when this plugin does not own {@code endpointId}
     */
    SerialPortManager createPortManager(String endpointId);
}
