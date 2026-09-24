package com.filesync.lab;

import com.filesync.lab.bridge.BridgeMode;
import com.filesync.lab.link.WireModel;
import com.filesync.lab.peer.PeerConsole;
import com.filesync.lab.peer.RemotePeer;
import com.filesync.lab.scenario.Scenario;
import com.filesync.lab.scenario.ScenarioRunner;
import com.filesync.lab.selftest.LinkSelfTest;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Command line entry point for the link lab: serial link emulator plus protocol peer for regression
 * testing COM Port File Sync without a second machine.
 *
 * <p>Modes:
 *
 * <ul>
 *   <li>{@code peer} - simulates the other machine on one end of a virtual COM pair, with an
 *       interactive console and/or a JSON scenario;
 *   <li>{@code bridge} - relays two real COM ports through the emulated wire so two real app
 *       instances talk over a slow/lossy link;
 *   <li>{@code selftest} - measures the emulated rate with no hardware at all;
 *   <li>{@code ports} - lists the COM ports the tool can see.
 * </ul>
 */
public final class LinkLab {

    private static final String USAGE =
            "Usage:\n"
                    + "  java -jar link-lab.jar peer --port COM11 [--workspace DIR] [--roles peer-sender|peer-receiver|auto]\n"
                    + "         [--baud N] [--latency MS] [--jitter MS] [--loss PCT] [--corrupt PCT]\n"
                    + "         [--noise PERIOD BURST] [--frames] [--trace FILE] [--script scenario.json]\n"
                    + "  java -jar link-lab.jar bridge --ports COM11,COM13 [wire options] [--trace FILE]\n"
                    + "  java -jar link-lab.jar selftest [wire options]\n"
                    + "  java -jar link-lab.jar ports\n"
                    + "\n"
                    + "Wire options model a real serial line: at 8N1 the link carries baud/10\n"
                    + "bytes per second (115200 -> 11520 B/s, 9600 -> 960 B/s), plus latency,\n"
                    + "jitter, byte loss, bit corruption and noise bursts. The simulated peer runs\n"
                    + "the same build as the app under test, on the same wire model.";

    public static void main(String[] args) {
        try {
            List<String> arguments = new ArrayList<>(Arrays.asList(args));
            if (arguments.isEmpty()) {
                System.out.println(USAGE);
                System.exit(2);
            }
            String mode = arguments.remove(0).toLowerCase();
            switch (mode) {
                case "peer":
                    runPeer(arguments);
                    break;
                case "bridge":
                    runBridge(arguments);
                    break;
                case "selftest":
                    runSelfTest(arguments);
                    break;
                case "ports":
                    listPorts();
                    break;
                default:
                    System.out.println(USAGE);
                    System.exit(2);
            }
        } catch (Exception e) {
            System.err.println("link-lab failed: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- peer mode

    private static void runPeer(List<String> args) throws Exception {
        Args parsed = Args.parse(args);
        File workspace = new File(parsed.get("workspace", "link-lab-workspace"));
        Trace trace = new Trace(parsed.has("frames"), parsed.fileOrNull("trace"));
        WireModel model = parsed.wireModel();
        String role = parsed.get("roles", "peer-sender");
        String portName = parsed.require("port");

        RemotePeer peer = RemotePeer.connect(trace, model, workspace, role, portName);
        trace.log(Trace.Dir.PEER, peer.state());

        String script = parsed.get("script", null);
        int exitCode = 0;
        if (script != null) {
            Scenario scenario = ScenarioRunner.load(new File(script));
            ScenarioRunner runner = new ScenarioRunner(peer, trace);
            boolean ok = runner.run(scenario);
            exitCode = ok ? 0 : 1;
        }
        if (script == null) {
            PeerConsole console = new PeerConsole(peer, trace);
            console.start();
            awaitSessionEnd(peer, trace);
        }
        trace.log(Trace.Dir.PEER, peer.stats());
        peer.close();
        trace.close();
        System.exit(exitCode);
    }

    /** Keeps the process alive until the peer stops or the operator presses Ctrl+C. */
    private static void awaitSessionEnd(RemotePeer peer, Trace trace) {
        AtomicBoolean stopping = new AtomicBoolean(false);
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    stopping.set(true);
                                    trace.log(Trace.Dir.PEER, "shutdown requested");
                                },
                                "link-lab-shutdown"));
        while (!stopping.get() && peer.isRunning()) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // ---------------------------------------------------------------- bridge mode

    private static void runBridge(List<String> args) throws Exception {
        Args parsed = Args.parse(args);
        String ports = parsed.require("ports");
        String[] ends = ports.split(",");
        if (ends.length != 2) {
            throw new IllegalArgumentException("--ports expects exactly two port names, e.g. COM11,COM13");
        }
        Trace trace = new Trace(parsed.has("frames"), parsed.fileOrNull("trace"));
        BridgeMode bridge = BridgeMode.start(trace, parsed.wireModel(), ends[0].trim(), ends[1].trim());
        AtomicBoolean stopping = new AtomicBoolean(false);
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(() -> stopping.set(true), "link-lab-shutdown"));
        while (!stopping.get()) {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        trace.log(Trace.Dir.WIRE, bridge.stats());
        bridge.close();
        trace.close();
    }

    // ---------------------------------------------------------------- selftest

    private static void runSelfTest(List<String> args) throws Exception {
        Args parsed = Args.parse(args);
        Trace trace = new Trace(false, parsed.fileOrNull("trace"));
        int ver = LinkSelfTest.measure(parsed.wireModel(), trace);
        trace.close();
        System.exit(ver);
    }

    private static void listPorts() {
        List<String> ports = com.filesync.serial.SerialPortManager.getAvailablePorts();
        if (ports.isEmpty()) {
            System.out.println("no COM ports found (install com0com for virtual ports)");
            return;
        }
        ports.forEach(System.out::println);
    }

    /** Minimal named-argument parser shared by all modes. */
    private static final class Args {
        private final java.util.Map<String, String> values = new java.util.HashMap<>();
        private final java.util.Set<String> flags = new java.util.HashSet<>();

        static Args parse(List<String> args) {
            Args parsed = new Args();
            for (int i = 0; i < args.size(); i++) {
                String arg = args.get(i);
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException("unexpected argument: " + arg);
                }
                String name = arg.substring(2);
                if (name.equals("frames")) {
                    parsed.flags.add(name);
                    continue;
                }
                if (i + 1 >= args.size()) {
                    throw new IllegalArgumentException("missing value for --" + name);
                }
                int consumed = 1;
                parsed.values.put(name, args.get(i + 1));
                if (name.equals("noise")) {
                    if (i + 2 >= args.size()) {
                        throw new IllegalArgumentException("--noise expects PERIOD and BURST");
                    }
                    parsed.values.put("noiseBurst", args.get(i + 2));
                    consumed = 2;
                }
                i += consumed;
            }
            return parsed;
        }

        boolean has(String name) {
            return flags.contains(name);
        }

        String get(String name, String fallback) {
            String value = values.get(name);
            return value == null || value.isEmpty() ? fallback : value;
        }

        String require(String name) {
            String value = values.get(name);
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException("missing required --" + name);
            }
            return value;
        }

        File fileOrNull(String name) {
            String value = values.get(name);
            return value == null || value.isEmpty() ? null : new File(value);
        }

        WireModel wireModel() {
            WireModel model = new WireModel();
            String baud = values.get("baud");
            if (baud != null) {
                model.baud(Integer.parseInt(baud));
            }
            String latency = values.get("latency");
            if (latency != null) {
                model.latencyMillis(Long.parseLong(latency));
            }
            String jitter = values.get("jitter");
            if (jitter != null) {
                model.jitterMillis(Long.parseLong(jitter));
            }
            String loss = values.get("loss");
            if (loss != null) {
                model.lossPercent(Double.parseDouble(loss));
            }
            String corrupt = values.get("corrupt");
            if (corrupt != null) {
                model.corruptPercent(Double.parseDouble(corrupt));
            }
            String noisePeriod = values.get("noise");
            if (noisePeriod != null) {
                int period = Integer.parseInt(noisePeriod);
                int burst = Integer.parseInt(values.getOrDefault("noiseBurst", "1"));
                model.noise(period, burst);
            }
            return model;
        }
    }
}