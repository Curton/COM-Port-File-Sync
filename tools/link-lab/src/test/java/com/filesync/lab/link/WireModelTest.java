package com.filesync.lab.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** The link parameters must translate into real serial timing, not into the raw baud number. */
class WireModelTest {

    @Test
    void eightNoneOneCarriesOneTenthOfTheBaud() {
        WireModel model = new WireModel().baud(115_200);
        assertEquals(11_520.0, model.bytesPerSecond(), 0.001);
        // One bit takes 1e9/115200 ns, one byte takes 10 of those.
        assertEquals(86_805.56, model.nanosPerByte(), 0.01);
    }

    @Test
    void slowBaudIsHonoured() {
        WireModel model = new WireModel().baud(9600);
        assertEquals(960.0, model.bytesPerSecond(), 0.001);
        // A 1 KiB block at 9600 baud needs about 1.07 s on the wire.
        assertEquals(1066.67, 1024 / model.bytesPerSecond() * 1000, 1);
    }

    @Test
    void parityAndStopBitsChangeTheBytePeriod() {
        WireModel sevenEvenOne = new WireModel().baud(9600).framing(7, true, 1);
        assertEquals(1 + 7 + 1 + 1, 10);
        assertEquals(960.0, sevenEvenOne.bytesPerSecond(), 0.001);

        WireModel eightNoneTwo = new WireModel().baud(9600).framing(8, false, 2);
        assertEquals(9600 / 11.0, eightNoneTwo.bytesPerSecond(), 0.001);
    }

    @Test
    void settingsAreClampedAndValidated() {
        WireModel model = new WireModel();
        model.lossPercent(-5);
        model.lossPercent(500);
        assertEquals(100.0, model.lossPercent(), 0.001);
        model.lossPercent(0);
        assertEquals(0.0, model.lossPercent(), 0.001);
        model.jitterMillis(-1);
        assertEquals(0, model.jitterMillis());
        model.highWaterBytes(10);
        assertEquals(4096, model.highWaterBytes());
        assertThrows(IllegalArgumentException.class, () -> model.baud(0));
    }

    @Test
    void summaryMentionsTheEffectiveRate() {
        String summary = new WireModel().baud(115_200).summary();
        assertEquals(
                "baud=115200 (11520 B/s @ 10 bits/byte), latency=2ms jitter=1ms loss=0.000% corrupt=0.000% noise=0/0",
                summary);
    }
}
