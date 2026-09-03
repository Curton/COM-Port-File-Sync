package com.filesync.bench;

import com.filesync.delta.DeltaEncoder;
import com.filesync.delta.FileSignatures;
import com.filesync.delta.SignatureSet;
import com.filesync.delta.SignatureUtil;
import com.filesync.protocol.SyncProtocol;
import com.filesync.serial.SerialPortManager;
import com.filesync.serial.XModemTransfer;
import com.filesync.sync.CompressionUtil;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Empirical measurement of the per-session fixed overhead that the delta-vs-batch routing threshold
 * ({@code SyncCoordinator.MIN_DELTA_SAVINGS_BYTES}) has to cover, at the real 115200 baud 8N1 line
 * rate.
 *
 * <p>Method: the unmodified protocol stack ({@link SyncProtocol}, XModemTransfer, batch encoding,
 * gzip, MD5 verification) runs on both ends; the only simulated part is the wire, which releases
 * each written byte array to the peer after its true transmission time (86.81 us per byte). Every
 * sleep, poll interval, ACK round trip and CPU cost in the protocol code elapses in real time. Not
 * modeled: USB-serial driver latency (a few ms per round trip on real Prolific/CH340 hardware) —
 * small next to the block-size wire times measured here.
 *
 * <p>Run: mvn test-compile exec:java -Dexec.mainClass=com.filesync.bench.DeltaThresholdBenchmark
 * -Dexec.classpathScope=test
 */
public class DeltaThresholdBenchmark {

    /** 115200 baud, 8N1 -> 10 bits per byte -> 11520 bytes/s. */
    static final double NANOS_PER_BYTE = 1_000_000_000.0 / 11520.0;

    static final int WARMUP_ITERATIONS = Integer.getInteger("bench.warmup", 2);
    static final int MEASURED_ITERATIONS = Integer.getInteger("bench.iters", 5);

    /** Byte-level wire logging for debugging; enabled with -Dbench.debug=true. */
    static final boolean DEBUG = Boolean.getBoolean("bench.debug");

    static final long T0 = System.nanoTime();

    static void log(String format, Object... args) {
        if (DEBUG) {
            System.err.printf(
                    "[%8.1fms] %s%n", (System.nanoTime() - T0) / 1e6, String.format(format, args));
        }
    }

    /** One measured point: wire bytes against a list of elapsed-ms samples. */
    private static final class Sample {
        final double wireBytes;
        final List<Long> elapsedMs = new ArrayList<>();

        Sample(double wireBytes) {
            this.wireBytes = wireBytes;
        }

        double mean() {
            return elapsedMs.stream().mapToLong(Long::longValue).average().orElse(0);
        }

        double min() {
            return elapsedMs.stream().mapToLong(Long::longValue).min().orElse(0);
        }

        double median() {
            List<Long> sorted = new ArrayList<>(elapsedMs);
            sorted.sort(Long::compare);
            return sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.printf(
                "Delta threshold benchmark — wire: 115200 baud 8N1 (%.2f us/byte),%n",
                NANOS_PER_BYTE / 1000.0);
        System.out.printf(
                "warmup=%d measured=%d per point, Java %s on %s%n%n",
                WARMUP_ITERATIONS,
                MEASURED_ITERATIONS,
                System.getProperty("java.version"),
                System.getProperty("os.name"));

        Path root = Files.createTempDirectory("delta-bench");
        java.util.Set<String> only = java.util.Set.of(args.length > 0 ? args : new String[0]);
        boolean runAll = only.isEmpty();
        try {
            Sample[] deltaSweep = null;
            Sample[] batchSweep = null;
            if (runAll || only.contains("delta")) {
                deltaSweep = measurePerFileDeltaSessionOverhead(root);
            }
            if (runAll || only.contains("batch")) {
                batchSweep = measureBatchSessionOverhead(root);
            }
            if (runAll || only.contains("sig")) {
                measureSignatureExchange(root);
            }
            if (runAll || only.contains("replay")) {
                replayLogScenario(root);
            }

            System.out.println("== Threshold derivation ==");
            if (deltaSweep != null) {
                double[] deltaFit = linearFit(deltaSweep);
                System.out.printf(
                        "per-file delta session: %.4f ms/byte + %.1f ms fixed  ->  fixed ≈ %.0f B%n",
                        deltaFit[1], deltaFit[0], deltaFit[0] * 11.52);
            }
            if (batchSweep != null) {
                double[] batchFit = linearFit(batchSweep);
                System.out.printf(
                        "batch session:          %.4f ms/byte + %.1f ms fixed  ->  fixed ≈ %.0f B%n",
                        batchFit[1], batchFit[0], batchFit[0] * 11.52);
            }
        } finally {
            deleteRecursively(root);
        }
    }

    /** XMODEM-level diagnostics on both ends: errors and cancels surface on stderr. */
    private static final XModemListener LISTENER = new XModemListener("xmodem");

    private static final class XModemListener implements XModemTransfer.TransferProgressListener {
        private final String tag;

        XModemListener(String tag) {
            this.tag = tag;
        }

        @Override
        public void onProgress(
                int currentBlock,
                int totalBlocks,
                long bytesTransferred,
                double speedBytesPerSec) {}

        @Override
        public void onError(String message) {
            System.err.println("  [" + tag + "] ERROR: " + message);
        }

        @Override
        public void onCancelled(String message) {
            System.err.println("  [" + tag + "] CANCELLED: " + message);
        }
    }

    // ---- scenario 1: per-file CMD_FILE_DELTA session, payload size sweep
    // -------------------------

    /**
     * Sends real signature-based deltas of a 48 KB text source for a sweep of modification sizes,
     * then fits elapsed = a + wire/rate. The intercept a is the fixed cost of one per-file delta
     * session (command round trip, handshake, per-block ACKs, EOT) — the number the routing
     * threshold must be derived from.
     */
    private static Sample[] measurePerFileDeltaSessionOverhead(Path root) throws Exception {
        System.out.println("== Per-file delta session (CMD_FILE_DELTA), 48 KB text source ==");
        String path = "sweep.txt";
        byte[] base = repeatedText(48 * 1024);
        File recvDir = Files.createDirectory(root.resolve("sweep-receiver")).toFile();
        write(recvDir, path, base);
        FileSignatures sigs = SignatureUtil.compute(path, new File(recvDir, path));

        // Random tails keep the delta payload close to incompressible, so the wire length tracks
        // the tail size 1:1 and the sweep points are directly comparable.
        Random random = new Random(42);
        int[] tailSizes =
                System.getProperty("bench.tail") != null
                        ? new int[] {Integer.getInteger("bench.tail", 8000)}
                        : new int[] {0, 200, 1000, 2000, 4000, 8000, 16000, 32000};
        Sample[] points = new Sample[tailSizes.length];
        for (int p = 0; p < tailSizes.length; p++) {
            byte[] modified = concat(base, randomBytes(random, tailSizes[p]));
            byte[] delta = DeltaEncoder.encode(modified, sigs);
            int wire = CompressionUtil.compressIfBeneficial(path, delta).getData().length;
            String sourceMd5 = md5Hex(modified);
            points[p] = new Sample(wire);

            Pair pair = new Pair();
            ReceiverPeer receiver = new ReceiverPeer(pair.bSide(), recvDir);
            receiver.start();
            try {
                for (int i = 0; i < WARMUP_ITERATIONS + MEASURED_ITERATIONS; i++) {
                    receiver.expectOps(1);
                    restore(recvDir, path, base);
                    long t0 = System.nanoTime();
                    pair.sender()
                            .sendFileDelta(
                                    path,
                                    delta,
                                    System.currentTimeMillis(),
                                    modified.length,
                                    sourceMd5);
                    long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                    receiver.awaitOps(60_000);
                    if (i >= WARMUP_ITERATIONS) {
                        points[p].elapsedMs.add(elapsedMs);
                    }
                }
            } catch (Exception e) {
                System.out.printf("  POINT FAILED at tail=%d: %s%n", tailSizes[p], e.getMessage());
            } finally {
                receiver.stopAndJoin();
            }
            System.out.printf(
                    "  delta wire=%6d B (padded %6d B)  median %6.0f ms  min %6.0f ms%n",
                    wire, paddedWireBytes(wire), points[p].median(), points[p].min());
        }
        double[] fit = linearFit(points);
        System.out.printf(
                "  fit: %.4f ms/byte + %.1f ms fixed  ->  fixed ≈ %.0f wire-byte equivalents%n%n",
                fit[1], fit[0], fit[0] * 11.52);
        return points;
    }

    // ---- scenario 2: batch session, entry count sweep
    // --------------------------------------------

    /** Times CMD_BATCH_DATA for 1..16 small text entries; slope = per-entry marginal cost. */
    private static Sample[] measureBatchSessionOverhead(Path root) throws Exception {
        System.out.println("== Batch session (CMD_BATCH_DATA), ~3 KB text entries ==");
        File sendDir = Files.createDirectory(root.resolve("batch-sender")).toFile();
        File recvDir = Files.createDirectory(root.resolve("batch-receiver")).toFile();
        int[] counts = {1, 2, 4, 8, 16};
        List<byte[]> entryContents = new ArrayList<>();
        for (int i = 0; i < counts[counts.length - 1]; i++) {
            entryContents.add(repeatedText(3 * 1024 + i));
        }
        Sample[] points = new Sample[counts.length];
        for (int c = 0; c < counts.length; c++) {
            int k = counts[c];
            List<Object[]> entries = new ArrayList<>();
            int totalWire = 0;
            for (int i = 0; i < k; i++) {
                String name = "entry-" + i + ".txt";
                File f = write(sendDir, name, entryContents.get(i));
                entries.add(new Object[] {f, name});
                totalWire +=
                        CompressionUtil.compressIfBeneficial(name, entryContents.get(i))
                                .getData()
                                .length;
            }
            points[c] = new Sample(totalWire);

            Pair pair = new Pair();
            ReceiverPeer receiver = new ReceiverPeer(pair.bSide(), recvDir);
            receiver.start();
            try {
                for (int i = 0; i < WARMUP_ITERATIONS + MEASURED_ITERATIONS; i++) {
                    receiver.expectOps(1);
                    cleanDirectory(recvDir);
                    long t0 = System.nanoTime();
                    if (!pair.sender().sendBatch(entries, 0, null, null)) {
                        throw new IllegalStateException("sendBatch failed for k=" + k);
                    }
                    long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                    receiver.awaitOps(60_000);
                    if (i >= WARMUP_ITERATIONS) {
                        points[c].elapsedMs.add(elapsedMs);
                    }
                }
            } finally {
                receiver.stopAndJoin();
            }
            System.out.printf(
                    "  k=%2d  compressed total=%6d B  median %6.0f ms  min %6.0f ms%n",
                    k, totalWire, points[c].median(), points[c].min());
        }
        double[] fit = linearFit(points);
        System.out.printf(
                "  fit: %.4f ms/byte + %.1f ms fixed  ->  per-entry marginal ≈ %.0f B, session"
                        + " fixed ≈ %.0f B%n%n",
                fit[1], fit[0], fit[1] * 11520.0, fit[0] * 11.52);
        return points;
    }

    // ---- scenario 3: grouped signature exchange
    // --------------------------------------------------

    /** One CMD_DELTA_SIG_REQ round trip for 3 text files; cost is shared by all candidates. */
    private static void measureSignatureExchange(Path root) throws Exception {
        System.out.println("== Grouped signature exchange (CMD_DELTA_SIG_REQ/DATA), 3 files ==");
        File recvDir = Files.createDirectory(root.resolve("sig-receiver")).toFile();
        List<String> paths = List.of("a-10kb.txt", "b-48kb.txt", "c-101kb.txt");
        write(recvDir, paths.get(0), repeatedText(10 * 1024));
        write(recvDir, paths.get(1), repeatedText(48 * 1024));
        write(recvDir, paths.get(2), repeatedText(101 * 1024));

        Pair pair = new Pair();
        ReceiverPeer receiver = new ReceiverPeer(pair.bSide(), recvDir);
        receiver.start();
        List<Long> times = new ArrayList<>();
        try {
            for (int i = 0; i < WARMUP_ITERATIONS + MEASURED_ITERATIONS; i++) {
                long t0 = System.nanoTime();
                pair.sender().requestDeltaSignatures(paths);
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                if (i >= WARMUP_ITERATIONS) {
                    times.add(elapsedMs);
                }
            }
        } finally {
            receiver.stopAndJoin();
        }
        double medianMs = medianOf(times);
        System.out.printf(
                "  median %6.0f ms total, %.0f ms per candidate at 3 candidates%n%n",
                medianMs, medianMs / 3.0);
    }

    // ---- scenario 4: replay of the 3-file sync from the user's log
    // -------------------------------

    /**
     * Uses the actual files from the reported sync (pom.xml, SyncProtocol.java,
     * SyncProtocolPartialDiskWriteTest.java) with a small appended edit each, computes the routing
     * arithmetic exactly as SyncCoordinator does, and times the three routing plans end to end:
     * all-delta, mixed (batch the two small-saving files + delta the large one), and all-batch.
     */
    private static void replayLogScenario(Path root) throws Exception {
        System.out.println("== Replay: pom.xml + SyncProtocol.java + PartialDiskWriteTest.java ==");
        Path repo = Path.of(".").toAbsolutePath().normalize();
        String[] repoPaths = {
            "pom.xml",
            "src/main/java/com/filesync/protocol/SyncProtocol.java",
            "src/test/java/com/filesync/sync/SyncProtocolPartialDiskWriteTest.java"
        };
        String[] names = {"pom.xml", "SyncProtocol.java", "PartialDiskWriteTest.java"};
        String[] edits = {
            "\n<!-- benchmark edit: threshold validation pass -->\n",
            "\n/** Benchmark edit appended for delta threshold validation. */\n",
            "\n// Benchmark edit appended for delta threshold validation.\n"
        };

        File sendDir = Files.createDirectory(root.resolve("replay-sender")).toFile();
        File recvDir = Files.createDirectory(root.resolve("replay-receiver")).toFile();
        File stashDir = Files.createDirectory(root.resolve("replay-stash")).toFile();

        byte[][] baseBytes = new byte[repoPaths.length][];
        byte[][] modifiedBytes = new byte[repoPaths.length][];
        for (int i = 0; i < repoPaths.length; i++) {
            baseBytes[i] = Files.readAllBytes(repo.resolve(repoPaths[i]));
            modifiedBytes[i] = concat(baseBytes[i], edits[i].getBytes(StandardCharsets.UTF_8));
            write(stashDir, names[i], baseBytes[i]);
        }

        // Routing arithmetic exactly as SyncCoordinator performs it (full vs delta compressed).
        for (int i = 0; i < names.length; i++) {
            FileSignatures sigs = SignatureUtil.compute(names[i], new File(stashDir, names[i]));
            byte[] delta = DeltaEncoder.encode(modifiedBytes[i], sigs);
            int deltaWire = CompressionUtil.compressIfBeneficial(names[i], delta).getData().length;
            int fullWire =
                    CompressionUtil.compressIfBeneficial(names[i], modifiedBytes[i])
                            .getData()
                            .length;
            System.out.printf(
                    "  %-24s full=%7d B  delta=%7d B  saved=%7d B  (padded full=%7d delta=%7d)%n",
                    names[i],
                    fullWire,
                    deltaWire,
                    fullWire - deltaWire,
                    paddedWireBytes(fullWire),
                    paddedWireBytes(deltaWire));
        }
        System.out.println();

        String[] planLabels = {
            "all-delta (3 sessions)", "mixed (1 batch + 1 delta)", "all-batch (1 session)"
        };
        int[] planExpectedOps = {3, 2, 1};
        for (int plan = 0; plan < planLabels.length; plan++) {
            List<Long> times = new ArrayList<>();
            for (int i = 0; i < WARMUP_ITERATIONS + MEASURED_ITERATIONS; i++) {
                cleanDirectory(recvDir);
                for (int f = 0; f < names.length; f++) {
                    Files.copy(
                            stashDir.toPath().resolve(names[f]),
                            recvDir.toPath().resolve(names[f]),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                Pair pair = new Pair();
                ReceiverPeer receiver = new ReceiverPeer(pair.bSide(), recvDir);
                receiver.start();
                long t0 = System.nanoTime();
                try {
                    receiver.expectOps(planExpectedOps[plan]);
                    if (plan == 0) {
                        for (int idx = 0; idx < names.length; idx++) {
                            sendOneDelta(pair, recvDir, names, modifiedBytes, idx);
                        }
                    } else if (plan == 1) {
                        sendOneDelta(pair, recvDir, names, modifiedBytes, 1);
                    }
                    if (plan == 1 || plan == 2) {
                        List<Object[]> entries = new ArrayList<>();
                        int[] batchIndices = plan == 1 ? new int[] {0, 2} : new int[] {0, 1, 2};
                        for (int idx : batchIndices) {
                            File f = write(sendDir, names[idx], modifiedBytes[idx]);
                            entries.add(new Object[] {f, names[idx]});
                        }
                        if (!pair.sender().sendBatch(entries, 0, null, null)) {
                            throw new IllegalStateException("sendBatch failed");
                        }
                    }
                    receiver.awaitOps(120_000);
                } finally {
                    receiver.stopAndJoin();
                }
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                if (i >= WARMUP_ITERATIONS) {
                    times.add(elapsedMs);
                }
            }
            System.out.printf(
                    "  %-24s median %6.0f ms  min %6.0f ms%n",
                    planLabels[plan], medianOf(times), minOf(times));
        }
        System.out.println();
    }

    private static void sendOneDelta(
            Pair pair, File recvDir, String[] names, byte[][] modifiedBytes, int idx)
            throws Exception {
        String path = names[idx];
        FileSignatures sigs = SignatureUtil.compute(path, new File(recvDir, path));
        byte[] delta = DeltaEncoder.encode(modifiedBytes[idx], sigs);
        pair.sender()
                .sendFileDelta(
                        path,
                        delta,
                        System.currentTimeMillis(),
                        modifiedBytes[idx].length,
                        md5Hex(modifiedBytes[idx]));
    }

    // ---- wire endpoints
    // --------------------------------------------------------------------------

    /** A connected endpoint pair plus the sender-side SyncProtocol. */
    private static final class Pair {
        private final Endpoint a;
        private final Endpoint b;
        private final SyncProtocol sender;

        Pair() {
            a = new Endpoint();
            b = new Endpoint();
            a.tag("A-sender");
            b.tag("B-receiver");
            a.connect(b);
            b.connect(a);
            sender = new SyncProtocol(a);
            sender.setProgressListener(LISTENER);
        }

        SyncProtocol sender() {
            return sender;
        }

        Endpoint bSide() {
            return b;
        }
    }

    /**
     * One end of a virtual null-modem cable. Writes are paced at the true 115200-baud byte time and
     * released to the peer atomically after transmission completes; reads see delivered byte arrays
     * immediately (full duplex, independent directions).
     */
    static final class Endpoint extends SerialPortManager {
        private final ArrayDeque<byte[]> rx = new ArrayDeque<>();
        private int rxCount;
        private int headIndex;
        private volatile boolean open;
        private Endpoint peer;
        private String tag = "?";

        void connect(Endpoint peer) {
            this.peer = peer;
            this.open = true;
        }

        void tag(String tag) {
            this.tag = tag;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void write(byte[] data) throws IOException {
            requireOpen();
            log("%s write n=%d head=%02X", tag, data.length, data.length > 0 ? data[0] : 0);
            // Deliver progressively like a real UART FIFO: the peer sees bytes as they are
            // "transmitted", chunk by chunk. Atomic whole-array delivery would let the peer's
            // handshake windows expire during long block writes, which a real link never does.
            int chunkSize = 16;
            long startNanos = System.nanoTime();
            for (int offset = 0; offset < data.length; offset += chunkSize) {
                int chunkEnd = Math.min(offset + chunkSize, data.length);
                long dueNanos = startNanos + (long) (chunkEnd * NANOS_PER_BYTE);
                while (true) {
                    long remaining = dueNanos - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    LockSupport.parkNanos(remaining);
                }
                peer.deliver(Arrays.copyOfRange(data, offset, chunkEnd));
            }
            log("%s -> %s write fully delivered n=%d", tag, peer.tag, data.length);
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b});
        }

        private synchronized void deliver(byte[] data) {
            rx.addLast(data);
            rxCount += data.length;
            notifyAll();
        }

        @Override
        public synchronized int available() {
            return rxCount;
        }

        @Override
        public synchronized int read() throws IOException {
            awaitData(0);
            return pop();
        }

        @Override
        public synchronized int read(byte[] buffer) throws IOException {
            awaitData(0);
            int n = Math.min(buffer.length, rxCount);
            for (int i = 0; i < n; i++) {
                buffer[i] = (byte) pop();
            }
            return n;
        }

        @Override
        public synchronized byte[] readExact(int length, int timeoutMs) throws IOException {
            byte[] out = new byte[length];
            int filled = 0;
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (filled < length) {
                if (rxCount > 0) {
                    int n = Math.min(rxCount, length - filled);
                    for (int i = 0; i < n; i++) {
                        out[filled + i] = (byte) pop();
                    }
                    filled += n;
                    deadline = System.currentTimeMillis() + timeoutMs;
                    continue;
                }
                long now = System.currentTimeMillis();
                if (now >= deadline) {
                    throw new IOException(
                            "Read timeout: expected " + length + " bytes, got " + filled);
                }
                awaitData(Math.min(5, deadline - now));
            }
            return out;
        }

        @Override
        public synchronized String readLine(int timeoutMs) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                if (rxCount > 0) {
                    int b = pop();
                    if (b == '\n') {
                        String result = line.toString(StandardCharsets.UTF_8.name());
                        log("%s readLine -> %s", tag, result);
                        return result;
                    }
                    if (b != '\r') {
                        line.write(b);
                    }
                    continue;
                }
                long now = System.currentTimeMillis();
                if (now >= deadline) {
                    log("%s readLine TIMEOUT (partial=%s)", tag, line);
                    throw new IOException("Read timeout");
                }
                awaitData(Math.min(5, deadline - now));
            }
        }

        @Override
        public synchronized void clearInputBuffer() {
            log("%s clearInputBuffer (dropping %d)", tag, rxCount);
            rx.clear();
            rxCount = 0;
            headIndex = 0;
        }

        private int pop() {
            byte[] head = rx.peekFirst();
            int b = head[headIndex] & 0xFF;
            if (++headIndex == head.length) {
                rx.pollFirst();
                headIndex = 0;
            }
            rxCount--;
            return b;
        }

        private void awaitData(long waitMs) throws IOException {
            while (rxCount == 0) {
                if (!open) {
                    throw new IOException("port closed");
                }
                try {
                    wait(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
                if (waitMs > 0 && rxCount == 0) {
                    return; // caller re-checks its deadline
                }
            }
        }

        private void requireOpen() throws IOException {
            if (!open) {
                throw new IOException("port closed");
            }
        }
    }

    /** Receiver-side command loop mirroring the real FileSyncManager dispatch order. */
    private static final class ReceiverPeer extends Thread {
        private final SyncProtocol protocol;
        private final File baseDir;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private volatile Exception error;

        private int pendingOps;

        ReceiverPeer(Endpoint endpoint, File baseDir) {
            this.protocol = new SyncProtocol(endpoint);
            // Idle polling timeout only; transfers use their own XMODEM timeouts.
            this.protocol.setTimeout(500);
            this.protocol.setProgressListener(LISTENER);
            this.baseDir = baseDir;
            setName("bench-receiver");
        }

        /** Announce how many operations the next phase will run; awaits re-arm every iteration. */
        synchronized void expectOps(int n) {
            pendingOps = n;
        }

        private synchronized void operationDone() {
            pendingOps--;
            notifyAll();
        }

        synchronized void awaitOps(long timeoutMs) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (pendingOps > 0) {
                if (error != null) {
                    throw new IllegalStateException("receiver failed", error);
                }
                long now = System.currentTimeMillis();
                if (now >= deadline) {
                    throw new IllegalStateException("receiver timed out");
                }
                wait(Math.min(100, deadline - now));
            }
            if (error != null) {
                throw new IllegalStateException("receiver failed", error);
            }
        }

        void stopAndJoin() throws InterruptedException {
            running.set(false);
            join(2000);
        }

        @Override
        public void run() {
            try {
                while (running.get()) {
                    SyncProtocol.Message msg;
                    try {
                        msg = protocol.receiveCommand();
                    } catch (IOException idle) {
                        continue; // idle poll timeout, no command pending
                    }
                    if (msg == null) {
                        continue;
                    }
                    switch (msg.getCommand()) {
                        case SyncProtocol.CMD_FILE_DELTA -> {
                            protocol.sendAck();
                            protocol.receiveFileDelta(
                                    baseDir,
                                    msg.getParam(0),
                                    msg.getParamAsInt(1),
                                    msg.getParamAsBoolean(2),
                                    msg.getParamAsLong(3),
                                    msg.getParamAsLong(4),
                                    msg.getParam(5));
                            operationDone();
                        }
                        case SyncProtocol.CMD_BATCH_DATA -> {
                            protocol.sendAck();
                            protocol.receiveBatch(msg.getParamAsInt(0), 0, null, baseDir, null);
                            operationDone();
                        }
                        case SyncProtocol.CMD_DELTA_SIG_REQ -> {
                            List<FileSignatures> entries = new ArrayList<>();
                            for (String p : msg.getParams()) {
                                File f = new File(baseDir, p);
                                if (f.isFile()) {
                                    entries.add(SignatureUtil.compute(p, f));
                                }
                            }
                            protocol.sendDeltaSignatures(new SignatureSet(entries));
                        }
                        default -> {
                            // not exercised by the benchmark scenarios
                        }
                    }
                }
            } catch (Exception e) {
                error = e;
                System.err.println("  [receiver] fatal: " + e);
                e.printStackTrace();
            }
        }
    }

    // ---- helpers
    // ---------------------------------------------------------------------------------

    /** Least squares fit elapsed = intercept + slope * wireBytes over the recorded points. */
    private static double[] linearFit(Sample[] points) {
        int n = 0;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (Sample p : points) {
            if (p == null) {
                continue;
            }
            double x = p.wireBytes;
            double y = p.mean();
            n++;
            sx += x;
            sy += y;
            sxx += x * x;
            sxy += x * y;
        }
        double slope = (n * sxy - sx * sy) / (n * sxx - sx * sx);
        double intercept = (sy - slope * sx) / n;
        return new double[] {intercept, slope};
    }

    private static double medianOf(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compare);
        return sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
    }

    private static double minOf(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).min().orElse(0);
    }

    /**
     * Wire bytes an XMODEM session actually puts on the line for a payload of {@code len} bytes.
     */
    private static long paddedWireBytes(int len) {
        long total = 0;
        int remaining = len;
        while (remaining > 0) {
            int block = remaining >= 4096 ? 4096 : remaining > 128 ? 1024 : 128;
            total += block + 5;
            remaining -= Math.min(remaining, block);
        }
        return total;
    }

    private static byte[] repeatedText(int length) {
        StringBuilder sb = new StringBuilder(length + 64);
        String[] lines = {
            "public void handleSample(int value) throws IOException {\n",
            "    if (value <= 0) { return; }\n",
            "    byte[] buffer = new byte[1024];\n",
            "    for (int i = 0; i < value; i++) { buffer[i % 1024] = (byte) i; }\n",
            "    // sample line to give the gzip model realistic text\n",
        };
        while (sb.length() < length) {
            sb.append(lines[(sb.length() / 57) % lines.length]);
        }
        return sb.substring(0, length).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] randomBytes(Random random, int length) {
        byte[] data = new byte[length];
        random.nextBytes(data);
        return data;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static File write(File dir, String name, byte[] data) throws IOException {
        File f = new File(dir, name);
        Files.write(f.toPath(), data);
        return f;
    }

    private static void restore(File dir, String name, byte[] data) throws IOException {
        Files.write(new File(dir, name).toPath(), data);
    }

    private static void cleanDirectory(File dir) throws IOException {
        File[] entries = dir.listFiles();
        if (entries != null) {
            for (File f : entries) {
                Files.walk(f.toPath())
                        .sorted(Comparator.reverseOrder())
                        .forEach(
                                p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException ignored) {
                                        // best effort cleanup
                                    }
                                });
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                    // best effort cleanup
                                }
                            });
        }
    }

    private static String md5Hex(byte[] data) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
