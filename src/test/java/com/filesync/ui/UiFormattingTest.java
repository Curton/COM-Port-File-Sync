package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class UiFormattingTest {

    @ParameterizedTest
    @MethodSource("formatBytesSamples")
    void formatBytesBucketsItsInput(long input, String expected) {
        assertEquals(expected, UiFormatting.formatBytes(input));
    }

    private static Stream<Arguments> formatBytesSamples() {
        return Stream.of(
                arguments(0L, "0 B"),
                arguments(512L, "512 B"),
                arguments(1023L, "1023 B"),
                arguments(1024L, "1.0 KB"),
                arguments(1536L, "1.5 KB"),
                arguments(1048575L, "1024.0 KB"),
                arguments(1048576L, "1.00 MB"),
                arguments(1572864L, "1.50 MB"),
                arguments(1073741823L, "1024.00 MB"),
                arguments(1073741824L, "1.00 GB"),
                arguments(1610612736L, "1.50 GB"),
                arguments(10737418240L, "10.00 GB"));
    }

    @ParameterizedTest
    @MethodSource("formatSpeedSamples")
    void formatSpeedBucketsItsInput(double input, String expected) {
        assertEquals(expected, UiFormatting.formatSpeed(input));
    }

    private static Stream<Arguments> formatSpeedSamples() {
        return Stream.of(
                arguments(0.0, "0 B/s"),
                arguments(512.0, "512 B/s"),
                arguments(1023.0, "1023 B/s"),
                arguments(1024.0, "1.0 KB/s"),
                arguments(1536.0, "1.5 KB/s"),
                arguments(1048575.0, "1024.0 KB/s"),
                arguments(1048576.0, "1.00 MB/s"),
                arguments(1572864.0, "1.50 MB/s"),
                arguments(10485760.0, "10.00 MB/s"));
    }
}
