package com.filesync.plugin;

import com.filesync.serial.SerialPortManager;
import java.util.List;
import java.util.Set;

/**
 * Provider used by {@link LinkPluginLoaderTest}: discovered through {@code META-INF/services} on
 * the test class path and, from a generated jar, through the {@code plugins} directory scan.
 */
public class ClasspathTestPlugin implements LinkPlugin {

    /** Endpoint the provider announces. */
    public static final String ENDPOINT_ID = "classpath:test";

    @Override
    public String name() {
        return "classpath-test";
    }

    @Override
    public List<LinkEndpoint> discoverEndpoints() {
        return List.of(LinkEndpoint.of(ENDPOINT_ID, "classpath test endpoint"));
    }

    @Override
    public Set<String> hiddenSystemPorts() {
        return Set.of();
    }

    @Override
    public boolean owns(String endpointId) {
        return ENDPOINT_ID.equals(endpointId);
    }

    @Override
    public SerialPortManager createPortManager(String endpointId) {
        if (!owns(endpointId)) {
            throw new IllegalArgumentException("not mine: " + endpointId);
        }
        return new SerialPortManager();
    }
}
