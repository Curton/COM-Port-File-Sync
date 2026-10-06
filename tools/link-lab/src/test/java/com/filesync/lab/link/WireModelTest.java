package com.filesync.lab.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The link parameters must translate into real serial timing, not into the raw baud number. */
class WireModelTest {

    @ParameterizedTest
    @MethodSource
    void rateFollowsBaudAndFraming(
            int baud,
            int dataBits,
            boolean parity,
            int stopBits,
            double expectedBytesPerSecond,
            double expectedNanosPerByte) {
        WireModel model = new WireModel().baud(baud).framing(dataBits, parity, stopBits);
        assertEquals(expectedBytesPerSecond, model.bytesPerSecond(), 0.01);
        assertEquals(expectedNanosPerByte, model.nanosPerByte(), 0.01);
    }

    static Stream<Arguments> rateFollowsBaudAndFraming() {
        return Stream.of(
                Arguments.of(115_200, 8, false, 1, 11_520.0, 86_805.56),
                Arguments.of(9600, 8, false, 1, 960.0, 1041666.67),
                Arguments.of(9600, 7, true, 1, 960.0, 1041666.67),
                Arguments.of(9600, 8, false, 2, 872.73, 1145833.33));
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
