package com.filesync.lab.e2e;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.lab.Trace;
import com.filesync.lab.link.WireModel;
import com.filesync.lab.port.DuplexLink;
import com.filesync.serial.XModemTransfer;
import java.io.File;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Isolation probe: two bare {@link XModemTransfer} instances, one on each end of the emulated link.
 * No FileSyncManager, no frames - just the handshake and the block exchange the manifest transfer
 * depends on. Used to tell an emulator defect apart from an application-flow defect.
 */
class XmodemOverDuplexLinkTest {

    @Test
    @Timeout(120)
    void rawXmodemTransferCrossesTheEmulatedLink() throws Exception {
        Trace trace = new Trace(false, (File) null);
        WireModel model = new WireModel().baud(460_800).latencyMillis(1).jitterMillis(1);
        DuplexLink link = new DuplexLink(trace, model);
        try {
            XModemTransfer sender = new XModemTransfer(link.sideA());
            XModemTransfer receiver = new XModemTransfer(link.sideB());
            byte[] payload = new byte[5_000];
            new Random(1).nextBytes(payload);
            byte[] received = new byte[payload.length];
            Throwable[] failure = new Throwable[1];
            Thread reader =
                    new Thread(
                            () -> {
                                try {
                                    byte[] got = receiver.receive(payload.length);
                                    System.arraycopy(
                                            got,
                                            0,
                                            received,
                                            0,
                                            Math.min(got.length, received.length));
                                } catch (Throwable e) {
                                    failure[0] = e;
                                }
                            });
            reader.start();

            boolean sent = sender.send(payload);
            reader.join(90_000);
            int nonZero = 0;
            for (byte b : received) {
                if (b != 0) {
                    nonZero++;
                }
            }
            System.out.println(
                    "[probe] send ok="
                            + sent
                            + " nonZeroBytes="
                            + nonZero
                            + " failure="
                            + failure[0]);
            System.out.println(
                    "[probe] A outbound " + link.sideA().outboundChannel().stats().summary());
            System.out.println(
                    "[probe] B inbound  " + link.sideB().inboundChannel().stats().summary());
            System.out.println(
                    "[probe] B outbound " + link.sideB().outboundChannel().stats().summary());
            System.out.println(
                    "[probe] A inbound  " + link.sideA().inboundChannel().stats().summary());
            if (failure[0] != null) {
                throw new AssertionError("receiver failed", failure[0]);
            }
            assertTrue(sent, "sender reported failure");
            assertArrayEquals(payload, received);
        } finally {
            link.close();
        }
    }
}
