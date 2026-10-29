package com.filesync.plugin;

import com.filesync.serial.SerialPortManager;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Test plugin: a fixed endpoint list, a fixed hidden set, and one canned port manager. */
public final class FakeLinkPlugin implements LinkPlugin {

    private final String name;
    private final List<LinkEndpoint> endpoints = new ArrayList<>();
    private final Set<String> hidden = new LinkedHashSet<>();
    private final List<String> createRequests = new ArrayList<>();

    private SerialPortManager manager;
    private java.util.function.Supplier<SerialPortManager> managerFactory;

    public FakeLinkPlugin(String name) {
        this.name = name;
    }

    public FakeLinkPlugin endpoint(String id) {
        return endpoint(id, id);
    }

    public FakeLinkPlugin endpoint(String id, String describe) {
        endpoints.add(LinkEndpoint.of(id, describe));
        return this;
    }

    public FakeLinkPlugin hidden(String... portNames) {
        for (String portName : portNames) {
            hidden.add(portName);
        }
        return this;
    }

    /** The manager {@link #createPortManager(String)} hands out. */
    public FakeLinkPlugin manager(SerialPortManager manager) {
        this.manager = manager;
        this.managerFactory = () -> this.manager;
        return this;
    }

    /** A fresh manager per {@link #createPortManager(String)} call (reconnect scenarios). */
    public FakeLinkPlugin managerFactory(
            java.util.function.Supplier<SerialPortManager> managerFactory) {
        this.managerFactory = managerFactory;
        return this;
    }

    /** Endpoint ids {@link #createPortManager(String)} was called with, in order. */
    public List<String> createRequests() {
        return createRequests;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<LinkEndpoint> discoverEndpoints() {
        return List.copyOf(endpoints);
    }

    @Override
    public Set<String> hiddenSystemPorts() {
        return Set.copyOf(hidden);
    }

    @Override
    public boolean owns(String endpointId) {
        for (LinkEndpoint endpoint : endpoints) {
            if (endpoint.id().equals(endpointId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public SerialPortManager createPortManager(String endpointId) {
        if (!owns(endpointId)) {
            throw new IllegalArgumentException("not mine: " + endpointId);
        }
        createRequests.add(endpointId);
        return managerFactory != null ? managerFactory.get() : manager;
    }
}
