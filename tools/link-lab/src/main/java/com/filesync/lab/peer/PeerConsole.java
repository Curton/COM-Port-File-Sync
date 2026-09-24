package com.filesync.lab.peer;

import com.filesync.lab.Trace;
import com.filesync.lab.link.TamperRule;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Interactive operator console for a running {@link RemotePeer}. Reads lines from stdin on a daemon
 * thread so an operator can drive a regression session by hand (push files, start syncs, inject
 * frames, degrade the link) and watch the trace at the same time.
 */
public final class PeerConsole {

    private final RemotePeer peer;
    private final Trace trace;
    private final BufferedReader in =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    public PeerConsole(RemotePeer peer, Trace trace) {
        this.peer = peer;
        this.trace = trace;
    }

    public void start() {
        Thread thread = new Thread(this::loop, "peer-console");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        printHelp();
        String line;
        try {
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    if (!dispatch(line)) {
                        return;
                    }
                } catch (Exception e) {
                    trace.log(Trace.Dir.PEER, "command failed: " + e);
                }
            }
        } catch (IOException e) {
            trace.log(Trace.Dir.PEER, "console closed: " + e.getMessage());
        }
    }

    /** Returns false when the console should exit (quit / EOF). */
    private boolean dispatch(String line) throws IOException {
        String[] parts = line.split("\\s+");
        String cmd = parts[0].toLowerCase();
        switch (cmd) {
            case "help":
                printHelp();
                return true;
            case "state":
                trace.log(Trace.Dir.PEER, peer.state());
                return true;
            case "ls":
                trace.log(Trace.Dir.PEER, "workspace " + peer.workspace() + ":");
                for (String file : peer.workspaceFiles()) {
                    trace.log(Trace.Dir.PEER, "  " + file);
                }
                return true;
            case "push":
                if (parts.length < 2) {
                    trace.log(Trace.Dir.PEER, "usage: push <file-in-workspace>");
                    return true;
                }
                peer.push(new File(peer.workspace(), parts[1]));
                return true;
            case "sync":
                peer.sync();
                return true;
            case "cancel":
                peer.cancelSync();
                return true;
            case "text":
                if (parts.length < 2) {
                    trace.log(Trace.Dir.PEER, "usage: text <message>");
                    return true;
                }
                peer.sendSharedText(line.substring(cmd.length()).trim());
                return true;
            case "texts":
                trace.log(Trace.Dir.PEER, "received from app: " + peer.receivedTexts());
                return true;
            case "folder":
                peer.requestFolderContext();
                return true;
            case "content":
                if (parts.length < 2) {
                    trace.log(Trace.Dir.PEER, "usage: content <relative-path>");
                    return true;
                }
                peer.fetchContent(parts[1]);
                return true;
            case "applog":
                peer.fetchAppLog();
                return true;
            case "inject":
                if (parts.length < 3) {
                    trace.log(Trace.Dir.PEER, "usage: inject <to-app|to-peer> <frame>");
                    return true;
                }
                String frame = line.substring(line.indexOf(parts[1]) + parts[1].length()).trim();
                if (parts[1].equalsIgnoreCase("to-app")) {
                    peer.injectTowardApp(frame);
                } else {
                    peer.injectTowardPeer(frame);
                }
                return true;
            case "fault":
                applyFault(parts);
                return true;
            case "tamper":
                applyTamper(parts);
                return true;
            case "stats":
                trace.log(Trace.Dir.PEER, peer.stats());
                return true;
            case "disconnect":
                peer.disconnect();
                return true;
            case "connect":
                if (parts.length < 2) {
                    trace.log(Trace.Dir.PEER, "usage: connect <COMx>");
                    return true;
                }
                peer.reconnect(parts[1]);
                return true;
            case "quit":
            case "exit":
                return false;
            default:
                trace.log(Trace.Dir.PEER, "unknown command: " + cmd + " (try 'help')");
                return true;
        }
    }

    private void applyFault(String[] parts) {
        if (parts.length < 3) {
            trace.log(Trace.Dir.PEER, "usage: fault <loss|corrupt|baud|latency|jitter|noise|clean> <value...>");
            return;
        }
        String what = parts[1].toLowerCase();
        switch (what) {
            case "loss":
                peer.model().lossPercent(Double.parseDouble(parts[2]));
                break;
            case "corrupt":
                peer.model().corruptPercent(Double.parseDouble(parts[2]));
                break;
            case "baud":
                peer.model().baud(Integer.parseInt(parts[2]));
                break;
            case "latency":
                peer.model().latencyMillis(Long.parseLong(parts[2]));
                break;
            case "jitter":
                peer.model().jitterMillis(Long.parseLong(parts[2]));
                break;
            case "noise":
                peer.model().noise(Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                break;
            case "clean":
                peer.model().lossPercent(0).corruptPercent(0).noise(0, 0).latencyMillis(2).jitterMillis(1);
                break;
            default:
                trace.log(Trace.Dir.PEER, "unknown fault: " + what);
                return;
        }
        trace.log(Trace.Dir.PEER, "wire now: " + peer.model().summary());
    }

    private void applyTamper(String[] parts) {
        // tamper <to-app|to-peer> <drop|delay|inject-after|inject-before|corrupt> <command>[#n] [arg]
        if (parts.length < 4) {
            trace.log(
                    Trace.Dir.PEER,
                    "usage: tamper <to-app|to-peer> <drop|delay|corrupt|inject-after|inject-before> <CMD>[#n] [frame|ms]");
            return;
        }
        boolean towardApp = parts[1].equalsIgnoreCase("to-app");
        String action = parts[2].toLowerCase();
        String[] target = parts[3].split("#");
        String command = target[0];
        int occurrence = target.length > 1 ? Integer.parseInt(target[1]) : 1;
        String arg = parts.length > 4 ? parts[4] : "";
        TamperRule rule;
        switch (action) {
            case "drop":
                rule = new TamperRule(command, occurrence, TamperRule.Action.DROP, 0, null);
                break;
            case "delay":
                rule = new TamperRule(command, occurrence, TamperRule.Action.DELAY, Long.parseLong(arg), null);
                break;
            case "corrupt":
                rule = new TamperRule(command, occurrence, TamperRule.Action.CORRUPT, 0, null);
                break;
            case "inject-after":
                rule = new TamperRule(command, occurrence, TamperRule.Action.INJECT_AFTER, 0, arg);
                break;
            case "inject-before":
                rule = new TamperRule(command, occurrence, TamperRule.Action.INJECT_BEFORE, 0, arg);
                break;
            default:
                trace.log(Trace.Dir.PEER, "unknown tamper action: " + action);
                return;
        }
        peer.addTamperRule(rule, towardApp);
        trace.log(Trace.Dir.PEER, "rule: " + rule.describe());
    }

    private void printHelp() {
        List<String> lines =
                List.of(
                        "peer commands:",
                        "  state                              connection/role/sync state",
                        "  ls                                 workspace files",
                        "  push <file>                        send a workspace file to the app",
                        "  sync                               start a sync as sender",
                        "  cancel                             cancel the running sync",
                        "  text <message>                     send shared text",
                        "  texts                              shared texts received from the app",
                        "  folder                             ask the app for its sync folder",
                        "  content <path>                     fetch the app's copy of a file",
                        "  applog                             fetch the app's log text",
                        "  inject <to-app|to-peer> <frame>    put a raw frame on the wire",
                        "  fault <loss|corrupt|baud|latency|jitter|noise|clean> <value...>",
                        "  tamper <to-app|to-peer> <drop|delay|corrupt|inject-after|inject-before> <CMD>[#n] [arg]",
                        "  stats                              wire statistics",
                        "  disconnect / connect <COMx>        link cycle",
                        "  quit                               stop the session");
        for (String l : lines) {
            trace.log(Trace.Dir.PEER, l);
        }
    }
}
