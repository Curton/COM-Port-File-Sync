package com.filesync.lab.scenario;

import com.filesync.lab.Trace;
import com.filesync.lab.link.TamperRule;
import com.filesync.lab.link.WireStats;
import com.filesync.lab.peer.RemotePeer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Executes a {@link Scenario} against a live peer and reports a pass/fail verdict per step. Every
 * step has a timeout, so a hung protocol path fails the run instead of stalling it.
 */
public final class ScenarioRunner {

    private final RemotePeer peer;
    private final Trace trace;
    private final List<String> failures = new ArrayList<>();
    private int passed;

    public ScenarioRunner(RemotePeer peer, Trace trace) {
        this.peer = peer;
        this.trace = trace;
    }

    public static Scenario load(File file) throws IOException {
        Gson gson = new GsonBuilder().create();
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, Scenario.class);
        }
    }

    /** Runs the scenario; returns true when every step passed. */
    public boolean run(Scenario scenario) {
        trace.log(Trace.Dir.SCENARIO, "=== scenario: " + scenario.name + " (" + scenario.steps.size() + " steps) ===");
        for (int i = 0; i < scenario.steps.size(); i++) {
            Scenario.Step step = scenario.steps.get(i);
            runStep(i + 1, step);
        }
        trace.log(
                Trace.Dir.SCENARIO,
                String.format(
                        "=== %s: %d passed, %d failed ===",
                        scenario.name, passed, failures.size()));
        for (String failure : failures) {
            trace.log(Trace.Dir.SCENARIO, "FAILED: " + failure);
        }
        return failures.isEmpty();
    }

    private void runStep(int index, Scenario.Step step) {
        String label = "step " + index + " (" + step.op + ")";
        try {
            switch (step.op) {
                case "waitMs":
                    Thread.sleep(step.ms);
                    pass(label);
                    break;
                case "push":
                    peer.push(new File(peer.workspace(), step.path));
                    pass(label + " pushed " + step.path);
                    break;
                case "waitFile":
                    wait(label, "file " + step.path + " to appear", step.timeoutMs, () -> peer.workspaceHas(step.path));
                    break;
                case "sync":
                    peer.resetMeter();
                    peer.sync();
                    wait(label, "sync to finish", step.timeoutMs, () -> !peer.isSyncing());
                    break;
                case "cancel":
                    peer.cancelSync();
                    pass(label);
                    break;
                case "text":
                    peer.sendSharedText(step.message == null ? "" : step.message);
                    pass(label);
                    break;
                case "expectText":
                    String expected = step.text != null ? step.text : step.message;
                    wait(label, "shared text '" + expected + "'", step.timeoutMs, () -> peer.receivedTexts().contains(expected));
                    break;
                case "inject":
                    if ("to-peer".equalsIgnoreCase(step.direction)) {
                        peer.injectTowardPeer(step.frame);
                    } else {
                        peer.injectTowardApp(step.frame);
                    }
                    pass(label);
                    break;
                case "fault":
                    applyFault(step);
                    pass(label + " wire=" + peer.model().summary());
                    break;
                case "tamper":
                    peer.addTamperRule(new TamperRule(step.command, step.occurrence, actionOf(step.action), step.latencyMs, step.frame),
                            !"to-peer".equalsIgnoreCase(step.direction));
                    pass(label);
                    break;
                case "resetMeter":
                    peer.resetMeter();
                    pass(label);
                    break;
                case "expectThroughput": {
                    double bps = stats(step.channel).measuredBytesPerSecond();
                    boolean ok = bps >= step.minBps && (step.maxBps <= 0 || bps <= step.maxBps);
                    report(label, ok, String.format("%s throughput %.0f B/s (want %.0f..%.0f)", step.channel, bps, step.minBps, step.maxBps));
                    break;
                }
                case "expectStats": {
                    long dropped = stats(step.channel).droppedBytes();
                    report(label, dropped >= step.minDropped, step.channel + " dropped " + dropped + " bytes (want >= " + step.minDropped + ")");
                    break;
                }
                case "waitState":
                    waitState(label, step);
                    break;
                case "folder":
                    String folder = peer.requestFolderContext();
                    report(label, folder != null && !folder.isEmpty(), "app folder: " + folder);
                    break;
                case "content":
                    byte[] content = peer.fetchContent(step.path);
                    report(label, content != null, "fetched " + step.path + ": " + (content == null ? "null" : content.length + " bytes"));
                    break;
                case "applog":
                    String log = peer.fetchAppLog();
                    report(label, log != null && !log.isEmpty(), "app log: " + (log == null ? "null" : log.length() + " chars"));
                    break;
                case "linkCycle":
                    peer.disconnect();
                    Thread.sleep(step.ms);
                    peer.reconnect(peer.connectedPort());
                    pass(label);
                    break;
                default:
                    report(label, false, "unknown op '" + step.op + "'");
                    break;
            }
        } catch (Exception e) {
            failures.add(label + " threw " + e);
            trace.log(Trace.Dir.SCENARIO, "FAILED " + label + ": " + e);
        }
    }

    private static TamperRule.Action actionOf(String action) {
        switch (action == null ? "drop" : action.toLowerCase()) {
            case "delay":
                return TamperRule.Action.DELAY;
            case "corrupt":
                return TamperRule.Action.CORRUPT;
            case "inject-after":
                return TamperRule.Action.INJECT_AFTER;
            case "inject-before":
                return TamperRule.Action.INJECT_BEFORE;
            default:
                return TamperRule.Action.DROP;
        }
    }

    private void applyFault(Scenario.Step step) {
        if (step.baud > 0) {
            peer.model().baud(step.baud);
        }
        if (step.lossPct > 0) {
            peer.model().lossPercent(step.lossPct);
        }
        if (step.corruptPct > 0) {
            peer.model().corruptPercent(step.corruptPct);
        }
        if (step.latencyMs > 0) {
            peer.model().latencyMillis(step.latencyMs);
        }
        if (step.jitterMs > 0) {
            peer.model().jitterMillis(step.jitterMs);
        }
        if (step.noisePeriodBytes > 0 && step.noiseBurstBytes > 0) {
            peer.model().noise(step.noisePeriodBytes, step.noiseBurstBytes);
        }
    }

    private void waitState(String label, Scenario.Step step) {
        BooleanSupplier condition = () -> {
            if (step.syncing != null && peer.isSyncing() != step.syncing) {
                return false;
            }
            if (step.connected != null && peer.isConnected() != step.connected) {
                return false;
            }
            if (step.sender != null && peer.isSender() != step.sender) {
                return false;
            }
            if (step.roleNegotiated != null && peer.isRoleNegotiated() != step.roleNegotiated) {
                return false;
            }
            return true;
        };
        wait(label, "state " + describeState(step), step.timeoutMs, condition);
    }

    private static String describeState(Scenario.Step step) {
        StringBuilder sb = new StringBuilder();
        if (step.syncing != null) {
            sb.append("syncing=").append(step.syncing).append(' ');
        }
        if (step.connected != null) {
            sb.append("connected=").append(step.connected).append(' ');
        }
        if (step.sender != null) {
            sb.append("sender=").append(step.sender).append(' ');
        }
        if (step.roleNegotiated != null) {
            sb.append("roleNegotiated=").append(step.roleNegotiated);
        }
        return sb.toString().trim();
    }

    private WireStats stats(String channel) {
        return "outbound".equalsIgnoreCase(channel) ? peer.outboundStats() : peer.inboundStats();
    }

    private void wait(String label, String what, long timeoutMs, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                pass(label + " " + what);
                return;
            }
            sleep(50);
        }
        report(label, condition.getAsBoolean(), "timed out after " + timeoutMs + " ms waiting for " + what);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void pass(String label) {
        passed++;
        trace.log(Trace.Dir.SCENARIO, "PASS " + label);
    }

    private void report(String label, boolean ok, String detail) {
        if (ok) {
            pass(label + " " + detail);
        } else {
            failures.add(label + ": " + detail);
            trace.log(Trace.Dir.SCENARIO, "FAIL " + label + ": " + detail);
        }
    }

    public boolean ok() {
        return failures.isEmpty();
    }

    public int passedCount() {
        return passed;
    }
}
