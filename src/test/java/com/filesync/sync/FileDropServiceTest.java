package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.protocol.SyncProtocol;
import com.filesync.protocol.TransferCancelledException;
import com.filesync.serial.SerialPortManager;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the send path of {@link FileDropService}: how the transfer outcome maps to events and,
 * crucially, the shared-text flush callback that runs once a transfer is over. A drop transfer has
 * no sync boundary to flush at, so this callback is the only thing that releases a shared text
 * queued while the drop was running.
 *
 * <p>The receive path is deliberately untested here: it resolves the real Downloads folder and
 * writes the received file into it.
 */
class FileDropServiceTest {

    @TempDir Path tempDir;

    private static final class RecordingBus extends SimpleSyncEventBus {
        private final List<String> logs = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();

        private RecordingBus() {
            register(
                    event -> {
                        if (event instanceof SyncEvent.LogEvent logEvent) {
                            logs.add(logEvent.getMessage());
                        } else if (event instanceof SyncEvent.ErrorEvent errorEvent) {
                            errors.add(errorEvent.getMessage());
                        }
                    });
        }
    }

    private static final class StubDropProtocol extends SyncProtocol {
        private final List<File> sent = new ArrayList<>();
        private IOException sendFailure;

        private StubDropProtocol() {
            super(new SerialPortManager());
        }

        @Override
        public void sendDropFile(File file) throws IOException {
            if (sendFailure != null) {
                throw sendFailure;
            }
            sent.add(file);
        }
    }

    private static FileDropService service(
            StubDropProtocol protocol, RecordingBus bus, AtomicInteger flushes) {
        return new FileDropService(
                protocol,
                bus,
                () -> true,
                () -> true,
                () -> false,
                () -> false,
                flushes::incrementAndGet);
    }

    private File newDropFile() throws IOException {
        File file = tempDir.resolve("drop-" + System.nanoTime() + ".txt").toFile();
        Files.writeString(file.toPath(), "payload");
        return file;
    }

    @Test
    void sendDropFileFlushesSharedTextAfterSuccess() throws Exception {
        StubDropProtocol protocol = new StubDropProtocol();
        RecordingBus bus = new RecordingBus();
        AtomicInteger flushes = new AtomicInteger();
        FileDropService service = service(protocol, bus, flushes);
        File file = newDropFile();

        service.sendDropFile(file);

        assertEquals(1, flushes.get(), "a finished drop transfer must flush the queued text");
        assertEquals(List.of(file), protocol.sent, "the dropped file reached the protocol");
        assertTrue(
                bus.logs.contains("Dropped file sent: " + file.getName()),
                "the success is logged, got: " + bus.logs);
        assertFalse(service.isTransferInProgress(), "the slot is free for the next drop");
    }

    @Test
    void sendDropFileFlushesSharedTextEvenWhenTheSendFails() throws Exception {
        StubDropProtocol protocol = new StubDropProtocol();
        protocol.sendFailure = new IOException("port gone");
        RecordingBus bus = new RecordingBus();
        AtomicInteger flushes = new AtomicInteger();
        FileDropService service = service(protocol, bus, flushes);
        File file = newDropFile();

        service.sendDropFile(file);

        assertEquals(
                1, flushes.get(), "the flush runs in the finally, success or not: a queued text");
        assertTrue(
                bus.errors.stream().anyMatch(msg -> msg.contains("Failed to send dropped file")),
                "the failure surfaces as an error, got: " + bus.errors);
        assertFalse(service.isTransferInProgress());
    }

    @Test
    void sendDropFileTreatsAPeerCancelAsBenignAndStillFlushes() throws Exception {
        StubDropProtocol protocol = new StubDropProtocol();
        protocol.sendFailure = new TransferCancelledException("Transfer cancelled by receiver");
        RecordingBus bus = new RecordingBus();
        AtomicInteger flushes = new AtomicInteger();
        FileDropService service = service(protocol, bus, flushes);
        File file = newDropFile();

        service.sendDropFile(file);

        assertEquals(1, flushes.get(), "a cancelled drop still ends the transfer, so flush");
        assertTrue(bus.errors.isEmpty(), "a peer cancel is expected, not an error");
        assertTrue(bus.logs.contains("Transfer cancelled by receiver"), "logged benignly");
    }

    @Test
    void sendDropFileDoesNotFlushWhenNoTransferHappened() throws Exception {
        StubDropProtocol protocol = new StubDropProtocol();
        RecordingBus bus = new RecordingBus();
        AtomicInteger flushes = new AtomicInteger();
        FileDropService service =
                new FileDropService(
                        protocol,
                        bus,
                        () -> true,
                        () -> false, // not connected
                        () -> false,
                        () -> false,
                        flushes::incrementAndGet);
        File file = newDropFile();

        service.sendDropFile(file);

        assertEquals(0, flushes.get(), "nothing was transferred, so there is nothing to flush");
        assertTrue(protocol.sent.isEmpty(), "the protocol was never called");
        assertTrue(
                bus.errors.contains("Dropped file transfer failed: not connected"),
                "the rejection is reported, got: " + bus.errors);
    }

    @Test
    void sendDropFileWorksWithoutAFlushCallback() throws Exception {
        StubDropProtocol protocol = new StubDropProtocol();
        RecordingBus bus = new RecordingBus();
        // A null callback is accepted so callers that do not care about shared text stay simple.
        FileDropService service =
                new FileDropService(
                        protocol, bus, () -> true, () -> true, () -> false, () -> false, null);
        File file = newDropFile();

        service.sendDropFile(file);

        assertEquals(1, protocol.sent.size(), "the drop transfer itself is unaffected");
    }
}
