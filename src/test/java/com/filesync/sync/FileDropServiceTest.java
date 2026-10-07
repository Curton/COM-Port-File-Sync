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
        private int transferCompleteCount;

        private RecordingBus() {
            register(
                    event -> {
                        if (event instanceof SyncEvent.LogEvent logEvent) {
                            logs.add(logEvent.getMessage());
                        } else if (event instanceof SyncEvent.ErrorEvent errorEvent) {
                            errors.add(errorEvent.getMessage());
                        } else if (event instanceof SyncEvent.TransferCompleteEvent) {
                            transferCompleteCount++;
                        }
                    });
        }
    }

    private static final class StubDropProtocol extends SyncProtocol {
        private final List<File> sent = new ArrayList<>();
        private final List<Boolean> unpackFlags = new ArrayList<>();
        private IOException sendFailure;

        private StubDropProtocol() {
            super(new SerialPortManager());
        }

        @Override
        public void sendDropFile(File file, boolean unpackAfterReceive) throws IOException {
            // Record before a simulated failure so tests can still inspect the temp archive path.
            sent.add(file);
            unpackFlags.add(unpackAfterReceive);
            if (sendFailure != null) {
                throw sendFailure;
            }
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
        assertEquals(
                1,
                bus.transferCompleteCount,
                "a successful drop posts TRANSFER_COMPLETE so the UI reverts the bar to Ready");
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
        assertEquals(
                0, bus.transferCompleteCount, "a failed drop must not report a completed transfer");
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

    @Test
    void sendDropFilesPacksTheSelectionIntoOneArchiveTransfer() throws Exception {
        StubDropProtocol protocol = new StubDropProtocol();
        RecordingBus bus = new RecordingBus();
        AtomicInteger flushes = new AtomicInteger();
        FileDropService service = service(protocol, bus, flushes);
        File folder = tempDir.resolve("photos-" + System.nanoTime()).toFile();
        File nested = new File(folder, "nested");
        assertTrue(nested.mkdirs());
        Files.writeString(new File(nested, "pic.bin").toPath(), "bytes");
        File loose = newDropFile();

        service.sendDropFiles(List.of(folder, loose));

        assertEquals(1, protocol.sent.size(), "the whole selection is one archive transfer");
        File archive = protocol.sent.get(0);
        assertEquals(List.of(true), protocol.unpackFlags, "archive drops ask the peer to unpack");
        assertTrue(
                archive.getName().startsWith("dropped-files-"),
                "a multi-item archive is timestamped, got: " + archive.getName());
        assertTrue(archive.getName().endsWith(".zip"));
        assertTrue(
                bus.logs.stream().anyMatch(msg -> msg.contains("Dropped items sent")),
                "the packed send is logged, got: " + bus.logs);
        assertEquals(1, bus.transferCompleteCount);
        assertEquals(1, flushes.get(), "a finished archive drop flushes the queued text too");
        assertFalse(archive.exists(), "the temp archive is cleaned up after the send");
        assertFalse(service.isTransferInProgress());
    }

    @Test
    void sendDropFilesNamesALoneFolderArchiveAfterTheFolder() throws Exception {
        StubDropProtocol protocol = new StubDropProtocol();
        FileDropService service = service(protocol, new RecordingBus(), new AtomicInteger());
        File folder = tempDir.resolve("holiday").toFile();
        assertTrue(folder.mkdirs());
        Files.writeString(new File(folder, "a.txt").toPath(), "a");

        service.sendDropFiles(List.of(folder));

        assertEquals("holiday.zip", protocol.sent.get(0).getName());
        assertEquals(List.of(true), protocol.unpackFlags);
    }

    @Test
    void sendDropFilesCleansUpTheTempArchiveAndFlushesWhenTheSendFails() throws Exception {
        StubDropProtocol protocol = new StubDropProtocol();
        protocol.sendFailure = new IOException("port gone");
        RecordingBus bus = new RecordingBus();
        AtomicInteger flushes = new AtomicInteger();
        FileDropService service = service(protocol, bus, flushes);
        File folder = tempDir.resolve("stuck").toFile();
        assertTrue(folder.mkdirs());
        Files.writeString(new File(folder, "a.txt").toPath(), "a");

        service.sendDropFiles(List.of(folder));

        assertEquals(1, flushes.get(), "the flush runs in the finally, success or not");
        assertTrue(
                bus.errors.stream().anyMatch(msg -> msg.contains("Failed to send dropped items")),
                "the failure surfaces as an error, got: " + bus.errors);
        assertEquals(0, bus.transferCompleteCount);
        assertFalse(protocol.sent.get(0).exists(), "the temp archive is removed on failure too");
        assertFalse(service.isTransferInProgress());
    }

    @Test
    void sendDropFilesTreatsAPeerCancelAsBenignAndStillFlushes() throws Exception {
        StubDropProtocol protocol = new StubDropProtocol();
        protocol.sendFailure = new TransferCancelledException("Transfer cancelled by receiver");
        RecordingBus bus = new RecordingBus();
        AtomicInteger flushes = new AtomicInteger();
        FileDropService service = service(protocol, bus, flushes);
        File folder = tempDir.resolve("cancelled").toFile();
        assertTrue(folder.mkdirs());

        service.sendDropFiles(List.of(folder));

        assertEquals(1, flushes.get());
        assertTrue(bus.errors.isEmpty(), "a peer cancel is expected, not an error");
        assertTrue(bus.logs.contains("Transfer cancelled by receiver"));
        assertFalse(protocol.sent.get(0).exists());
    }
}
