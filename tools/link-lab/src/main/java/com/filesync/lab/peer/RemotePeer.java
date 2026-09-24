package com.filesync.lab.peer;

import com.filesync.config.SettingsManager;
import com.filesync.lab.Trace;
import com.filesync.lab.link.TamperRule;
import com.filesync.lab.link.WireChannel;
import com.filesync.lab.link.WireModel;
import com.filesync.lab.link.WireStats;
import com.filesync.lab.port.LinkSerialPortManager;
import com.filesync.sync.FileSyncManager;
import com.filesync.sync.SyncEvent;
import com.filesync.sync.SyncEventType;
import com.filesync.sync.SyncPreviewPlan;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The simulated other machine.
 *
 * <p>The peer is not a re-implementation of the protocol: it is the application itself, run
 * headless. A real {@link FileSyncManager} owns a {@link LinkSerialPortManager} that sits on the
 * emulated wire, so everything the listener loop does - heartbeats, role negotiation, manifest
 * exchange, batched transfers, delta signatures, conflict content, shared text, drop files - is the
 * production code path. What the tool adds on top is the wire (speed, latency, faults, frame
 * tampering), a workspace directory standing in for the peer's sync folder, an operator surface
 * (console + scenario steps) and a session trace.
 *
 * <p>Disk caches are redirected into the workspace so repeated regression runs are independent and
 * never touch the real {@code ~/.filesync}.
 */
public final class RemotePeer implements AutoCloseable {

    private final Trace trace;
    private final WireModel model;
    private final File workspace;
    private final LinkSerialPortManager port;
    private final FileSyncManager manager;
    private final List<String> receivedTexts = new CopyOnWriteArrayList<>();
    private final List<String> receivedFiles = new CopyOnWriteArrayList<>();
    private final List<String> logLines = new CopyOnWriteArrayList<>();
    private volatile String connectedPort;

    private RemotePeer(Trace trace, WireModel model, File workspace, LinkSerialPortManager port) {
        this.trace = trace;
        this.model = model;
        this.workspace = workspace;
        this.port = port;
        this.manager = new FileSyncManager(port, new SettingsManager(true));
    }

    /**
     * Builds and connects the peer over a real COM port (the peer end of a com0com pair). Role
     * forcing is deterministic: {@code peer-sender} and {@code peer-receiver} inject a
     * DIRECTION_CHANGE pair that settles both sides, so a regression never depends on the random
     * role-negotiation priorities. {@code auto} leaves the real negotiation in place.
     */
    public static RemotePeer connect(
            Trace trace, WireModel model, File workspace, String role, String portName)
            throws IOException {
        RemotePeer peer =
                new RemotePeer(
                        trace,
                        model,
                        workspace,
                        new LinkSerialPortManager(
                                trace,
                                model,
                                roleRules(role, true),
                                roleRules(role, false),
                                0x5EEDL));
        peer.start(portName, true);
        return peer;
    }

    /**
     * Builds and connects a peer over an already-open emulated port - the hardware-free path used
     * by regression tests that run two application instances on one {@link
     * com.filesync.lab.port.DuplexLink}. Roles are left to the real negotiation. Pass {@code
     * waitForPeer=false} to start listening without blocking on the other side attaching first.
     */
    public static RemotePeer attach(
            Trace trace,
            WireModel model,
            File workspace,
            LinkSerialPortManager port,
            boolean waitForPeer)
            throws IOException {
        RemotePeer peer = new RemotePeer(trace, model, workspace, port);
        peer.start(port.getPortName(), waitForPeer);
        return peer;
    }

    private static List<TamperRule> roleRules(String role, boolean towardApp) {
        if ("auto".equalsIgnoreCase(role)) {
            return List.of();
        }
        boolean peerSender = "peer-sender".equalsIgnoreCase(role);
        // A DIRECTION_CHANGE frame takes the complementary role on the receiving side.
        // peerSender: the peer must hear "remote is receiver" and the app must hear
        // "remote is sender".
        String frameValue = towardApp == peerSender ? "false" : "true";
        String command = towardApp ? "HEARTBEAT_ACK" : "HEARTBEAT";
        return List.of(
                new TamperRule(
                        command,
                        1,
                        TamperRule.Action.INJECT_AFTER,
                        0,
                        "[[SYNC:DIRECTION_CHANGE:" + frameValue + "]]"));
    }

    private void start(String portName, boolean waitForPeer) throws IOException {
        workspace.mkdirs();
        redirectDiskCaches(workspace);
        manager.setSyncFolder(workspace);
        manager.setLogTextProvider(() -> String.join("\n", logLines) + "\n");
        manager.setLogMarkerSink(
                line -> {
                    logLines.add(line);
                    trace.log(Trace.Dir.PEER, "log-marker " + line);
                });
        manager.getEventBus().register(this::onEvent);

        trace.log(Trace.Dir.PEER, "workspace " + workspace);
        if (!port.isOpen() && !port.open(portName)) {
            throw new IOException("cannot open " + portName);
        }
        connectedPort = port.getPortName();
        manager.startListening(connectedPort);
        if (!waitForPeer) {
            return;
        }
        boolean connected = manager.waitForConnection(FileSyncManager.getInitialConnectTimeoutMs());
        trace.log(
                Trace.Dir.PEER,
                connected
                        ? "connection alive (sender=" + manager.isSender() + ")"
                        : "connection timeout after 60s");
    }

    /**
     * Redirects the manifest/signature caches out of the synced workspace into the temp directory.
     * Production keeps them in {@code ~/.filesync} for the same reason: on Windows the hidden
     * attribute, not the dot prefix, decides what a manifest walk skips, so a cache directory
     * inside a sync folder would itself be synced to the peer.
     */
    private static void redirectDiskCaches(File workspace) {
        String key = Integer.toHexString(workspace.getAbsolutePath().hashCode());
        File dir = new File(System.getProperty("java.io.tmpdir"), "link-lab-cache-" + key);
        try {
            Class<?> cacheLocations = Class.forName("com.filesync.sync.CacheLocations");
            Field override = cacheLocations.getDeclaredField("override");
            override.setAccessible(true);
            override.set(null, dir.getAbsoluteFile());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot redirect disk caches: " + e);
        }
    }

    private void onEvent(SyncEvent event) {
        String text = describe(event);
        switch (event.getType()) {
            case SHARED_TEXT_RECEIVED:
                receivedTexts.add(text);
                break;
            case DROP_FILE_RECEIVED:
                receivedFiles.add(stringOf(event, "getFilePath"));
                break;
            case LOG:
            case ERROR:
                if (logLines.size() < 5000) {
                    logLines.add(text);
                }
                break;
            default:
                break;
        }
        if (!event.getType().equals(SyncEventType.FILE_PROGRESS)
                && !event.getType().equals(SyncEventType.TRANSFER_PROGRESS)) {
            trace.log(Trace.Dir.PEER, event.getType().name() + (text.isEmpty() ? "" : ": " + text));
        }
    }

    /** Reads the payload accessors of the app's package-private event classes. */
    private static String describe(SyncEvent event) {
        for (String accessor :
                List.of("getMessage", "getText", "getFileName", "getFilePath", "getFolderPath")) {
            try {
                Method m = event.getClass().getMethod(accessor);
                m.setAccessible(true);
                Object value = m.invoke(event);
                if (value != null) {
                    String s = String.valueOf(value);
                    return s.length() > 200 ? s.substring(0, 200) + "..." : s;
                }
            } catch (ReflectiveOperationException ignored) {
                // Not every event carries that accessor.
            }
        }
        return "";
    }

    /** One string accessor of an event, or an empty string when it is absent. */
    private static String stringOf(SyncEvent event, String accessor) {
        try {
            Method m = event.getClass().getMethod(accessor);
            m.setAccessible(true);
            Object value = m.invoke(event);
            return value == null ? "" : String.valueOf(value);
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ operator surface

    public WireModel model() {
        return model;
    }

    public String state() {
        return String.format(
                "peer: %s, connected=%s, sender=%s, roleNegotiated=%s, syncing=%s, running=%s",
                connectedPort,
                manager.isConnectionAlive(),
                manager.isSender(),
                manager.isRoleNegotiated(),
                manager.isSyncing(),
                manager.isRunning());
    }

    public boolean isConnected() {
        return manager.isConnectionAlive();
    }

    public boolean isRunning() {
        return manager.isRunning();
    }

    public boolean isSender() {
        return manager.isSender();
    }

    public boolean isSyncing() {
        return manager.isSyncing();
    }

    public boolean isRoleNegotiated() {
        return manager.isRoleNegotiated();
    }

    public File workspace() {
        return workspace;
    }

    /** The live headless manager, for tests that need the app's own API surface. */
    public FileSyncManager manager() {
        return manager;
    }

    /** The COM port the peer is currently attached to. */
    public String connectedPort() {
        return connectedPort;
    }

    /** Runs a full sync as sender (the UI's preview + apply flow, minus the dialog). */
    public SyncPreviewPlan sync() {
        if (!manager.isSender()) {
            throw new IllegalStateException("peer is the receiver; it cannot start a sync");
        }
        SyncPreviewPlan plan = manager.previewSync();
        trace.log(
                Trace.Dir.PEER,
                String.format(
                        "sync plan: %d operations, %d files, %d bytes, %d conflicts",
                        plan.getTotalOperations(),
                        plan.getFilesToTransfer().size(),
                        plan.getTotalBytesToTransfer(),
                        plan.getConflicts().size()));
        manager.initiateSync(plan);
        return plan;
    }

    /** Sends a workspace file to the app through the drop-file path. */
    public void push(File file) {
        if (!file.isAbsolute()) {
            file = new File(workspace, file.getPath());
        }
        if (!file.isFile()) {
            throw new IllegalArgumentException("not a file: " + file);
        }
        trace.log(Trace.Dir.PEER, "push " + file.getName());
        manager.sendDropFile(file);
    }

    public void sendSharedText(String text) {
        trace.log(Trace.Dir.PEER, "shared text -> app: " + abbreviate(text));
        manager.sendSharedText(text);
    }

    public List<String> receivedTexts() {
        return receivedTexts;
    }

    /** Absolute paths of files saved by the drop-file receive path, in arrival order. */
    public List<String> receivedFiles() {
        return receivedFiles;
    }

    public String requestFolderContext() {
        String folder = manager.requestRemoteFolderContext();
        trace.log(Trace.Dir.PEER, "app folder: " + folder);
        return folder;
    }

    /** Fetches the app's copy of a file (the conflict-comparison path). */
    public byte[] fetchContent(String relativePath) throws IOException {
        byte[] content = manager.fetchRemoteFileContent(relativePath);
        trace.log(
                Trace.Dir.PEER,
                "fetched "
                        + relativePath
                        + ": "
                        + (content == null ? "unavailable" : content.length + " bytes"));
        return content;
    }

    public String fetchAppLog() {
        String log = manager.fetchRemoteLogText();
        trace.log(Trace.Dir.PEER, "app log: " + (log == null ? "null" : log.length() + " chars"));
        return log;
    }

    public void cancelSync() {
        trace.log(Trace.Dir.PEER, "cancel sync");
        manager.cancelSync();
    }

    public void disconnect() {
        trace.log(Trace.Dir.PEER, "disconnect");
        manager.disconnect(true);
    }

    public void reconnect(String portName) throws IOException {
        if (!port.open(portName)) {
            throw new IOException("cannot open " + portName);
        }
        connectedPort = port.getPortName();
        manager.startListening(connectedPort);
        trace.log(
                Trace.Dir.PEER,
                "reconnecting on " + connectedPort + " -> " + manager.waitForConnection(30_000));
    }

    /**
     * Stops the peer's listener and closes its port - the hardware-free equivalent of the user
     * pressing Disconnect. Interrupts any exchange the listen loop is parked in, so a session
     * wedged by a lost control frame is cleared instead of merely waited out.
     */
    public void halt() {
        trace.log(Trace.Dir.PEER, "halt on " + connectedPort);
        manager.stopListening();
    }

    /**
     * Starts the peer's listener again after {@link #halt()} - the hardware-free equivalent of
     * pressing Connect. Unlike a user it never waits blind: the caller decides how long to wait for
     * the session, because the other end may not have started listening yet.
     */
    public void restart() {
        if (!manager.isRunning()) {
            manager.startListening(connectedPort);
        }
        trace.log(Trace.Dir.PEER, "restart on " + connectedPort);
    }

    /** Writes a frame onto the wire toward the app (bypassing the peer's protocol layer). */
    public void injectTowardApp(String frame) {
        String line = frame.endsWith("\n") ? frame : frame + "\n";
        trace.log(Trace.Dir.PEER, "inject toward app: " + line.trim());
        try {
            port.writeLine(line);
        } catch (IOException e) {
            trace.log(Trace.Dir.PEER, "inject failed: " + e.getMessage());
        }
    }

    /** Makes the peer's protocol layer receive a frame as if the app had sent it. */
    public void injectTowardPeer(String frame) {
        String line = frame.endsWith("\n") ? frame : frame + "\n";
        trace.log(Trace.Dir.PEER, "inject toward peer: " + line.trim());
        WireChannel inbound = port.inboundChannel();
        if (inbound != null) {
            byte[] data = line.getBytes(StandardCharsets.ISO_8859_1);
            inbound.acceptAsInput(data, data.length);
        }
    }

    public void addTamperRule(TamperRule rule, boolean towardApp) {
        port.addTamperRule(rule, towardApp);
    }

    public WireStats inboundStats() {
        return statsOf(port.inboundChannel());
    }

    public WireStats outboundStats() {
        return statsOf(port.outboundChannel());
    }

    private static WireStats statsOf(WireChannel channel) {
        return channel == null ? new WireStats() : channel.stats();
    }

    public String stats() {
        return "inbound  " + inboundStats().summary() + "\noutbound " + outboundStats().summary();
    }

    public void resetMeter() {
        inboundStats().resetMeter();
        outboundStats().resetMeter();
    }

    /** Reads a workspace file, for assertions. */
    public Optional<byte[]> workspaceBytes(String relativePath) {
        try {
            return Optional.of(Files.readAllBytes(new File(workspace, relativePath).toPath()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public boolean workspaceHas(String relativePath) {
        return new File(workspace, relativePath).isFile();
    }

    /** Recursive listing of the workspace (files only, relative paths). */
    public List<String> workspaceFiles() {
        List<String> out = new ArrayList<>();
        collect(workspace, "", out);
        return out;
    }

    private static void collect(File dir, String prefix, List<String> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String rel = prefix.isEmpty() ? child.getName() : prefix + "/" + child.getName();
            if (child.isDirectory()) {
                collect(child, rel, out);
            } else {
                out.add(rel);
            }
        }
    }

    private static String abbreviate(String text) {
        String flat = text.replace("\n", "\\n");
        return flat.length() > 80 ? flat.substring(0, 80) + "..." : flat;
    }

    @Override
    public void close() {
        try {
            manager.stopListening();
        } catch (RuntimeException e) {
            trace.log(Trace.Dir.PEER, "stopListening failed: " + e.getMessage());
        }
        port.close();
        trace.log(Trace.Dir.PEER, "peer stopped");
    }
}
