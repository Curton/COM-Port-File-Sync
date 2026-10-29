package com.filesync.plugin;

/**
 * One connectable endpoint offered by a {@link LinkPlugin} (for example the A and B side of a USB
 * HID bridge).
 *
 * <p>{@link #id()} is what the port combo box shows and what {@link
 * com.filesync.serial.SerialPortManager#open(String)} is later called with, so it must be stable
 * across unplug/replug: it is also what {@code lastPort} persistence stores. It must not collide
 * with a system COM port name.
 */
public interface LinkEndpoint {

    /** Stable identifier, e.g. {@code hidlink:A}; shown verbatim in the port drop-down. */
    String id();

    /**
     * Human-readable detail for the log and for troubleshooting: resolved system port, USB serial
     * number, interface index — whatever tells two candidates apart. Never empty.
     */
    String describe();

    /** Creates an endpoint with no detail beyond its id. */
    static LinkEndpoint of(String id) {
        return of(id, id);
    }

    /** Creates an endpoint with a diagnostic description. */
    static LinkEndpoint of(String id, String describe) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("endpoint id must not be empty");
        }
        return new LinkEndpoint() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String describe() {
                return describe == null || describe.isEmpty() ? id : describe;
            }

            @Override
            public String toString() {
                return id;
            }
        };
    }
}
