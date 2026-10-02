package com.filesync.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.filesync.config.SettingsManager;
import com.filesync.sync.TestCacheOverride;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end CLI runs over an in-memory null-modem: both sides drive the full stack (heartbeat,
 * role settle, manifest round-trip, XMODEM transfer) exactly like two processes on a real cable.
 */
class CliEndToEndTest {

    private static final long RUN_TIMEOUT_SECONDS = 120;

    @TempDir Path tempDir;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        TestCacheOverride.set(tempDir.resolve("cache").toFile());
        pool =
                Executors.newCachedThreadPool(
                        r -> {
                            Thread t = new Thread(r, "cli-e2e");
                            t.setDaemon(true);
                            return t;
                        });
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        TestCacheOverride.clear();
    }

    @Test
    void sendReceiveRoundTripExitsZero() throws Exception {
        Path senderFolder = tempDir.resolve("sender");
        Path receiverFolder = tempDir.resolve("receiver");
        Files.createDirectories(senderFolder.resolve("sub"));
        Files.createDirectories(receiverFolder);
        byte[] nested = new byte[8192];
        new Random(42).nextBytes(nested);
        Files.write(senderFolder.resolve("hello.txt"), "Hello from the CLI sender".getBytes(UTF_8));
        Files.write(senderFolder.resolve("sub").resolve("nested.bin"), nested);

        Wire wire = cross();
        Future<Integer> receiver = submit("receive", receiverFolder, wire.receiverPort);
        Future<Integer> sender = submit("send", senderFolder, wire.senderPort);

        assertEquals(CliMain.EXIT_SUCCESS, sender.get(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(CliMain.EXIT_SUCCESS, receiver.get(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertArrayEquals(
                "Hello from the CLI sender".getBytes(UTF_8),
                Files.readAllBytes(receiverFolder.resolve("hello.txt")));
        assertArrayEquals(
                nested, Files.readAllBytes(receiverFolder.resolve("sub").resolve("nested.bin")));
    }

    @Test
    void previewPrintsPlanAndTransfersNothing() throws Exception {
        Path senderFolder = tempDir.resolve("sender");
        Path receiverFolder = tempDir.resolve("receiver");
        Files.createDirectories(senderFolder);
        Files.createDirectories(receiverFolder);
        Files.write(senderFolder.resolve("hello.txt"), "preview me".getBytes(UTF_8));

        Wire wire = cross();
        Future<Integer> receiver = submit("receive", receiverFolder, wire.receiverPort);
        Future<Integer> sender = submit("preview", senderFolder, wire.senderPort);

        assertEquals(CliMain.EXIT_SUCCESS, sender.get(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        // The receiver waits for a session that never starts; when the preview side hangs up the
        // link drops, which a one-shot receive reports as a failure.
        assertEquals(CliMain.EXIT_FAILURE, receiver.get(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertFalse(Files.exists(receiverFolder.resolve("hello.txt")));
    }

    /** The crossed conduit pair plus both port managers, built once per scenario. */
    private static final class Wire {
        final PipeSerialPortManager senderPort;
        final PipeSerialPortManager receiverPort;

        Wire(PipeSerialPortManager senderPort, PipeSerialPortManager receiverPort) {
            this.senderPort = senderPort;
            this.receiverPort = receiverPort;
        }
    }

    private static Wire cross() {
        Conduit senderToReceiver = new Conduit();
        Conduit receiverToSender = new Conduit();
        return new Wire(
                new PipeSerialPortManager(receiverToSender, senderToReceiver),
                new PipeSerialPortManager(senderToReceiver, receiverToSender));
    }

    private Future<Integer> submit(String mode, Path folder, PipeSerialPortManager port) {
        return pool.submit(() -> CliMain.run(args(mode, folder), new SettingsManager(true), port));
    }

    private static String[] args(String mode, Path folder) {
        return new String[] {mode, "--port", "PIPE", "--folder", folder.toString(), "--wait", "30"};
    }
}
