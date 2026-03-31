package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UiFormattingTest {

    @Test
    void formatBytesReturnsBytesForValuesUnder1024() {
        assertEquals("0 B", UiFormatting.formatBytes(0));
        assertEquals("512 B", UiFormatting.formatBytes(512));
        assertEquals("1023 B", UiFormatting.formatBytes(1023));
    }

    @Test
    void formatBytesReturnsKilobytesForValuesUnder1MB() {
        assertEquals("1.0 KB", UiFormatting.formatBytes(1024));
        assertEquals("1.5 KB", UiFormatting.formatBytes(1536));
        assertEquals("1024.0 KB", UiFormatting.formatBytes(1024 * 1024 - 1));
    }

    @Test
    void formatBytesReturnsMegabytesForValuesUnder1GB() {
        assertEquals("1.00 MB", UiFormatting.formatBytes(1024 * 1024));
        assertEquals("1.50 MB", UiFormatting.formatBytes(1024 * 1024 + 512 * 1024));
        assertEquals("1024.00 MB", UiFormatting.formatBytes(1024L * 1024L * 1024L - 1));
    }

    @Test
    void formatBytesReturnsGigabytesForValues1GBAndAbove() {
        assertEquals("1.00 GB", UiFormatting.formatBytes(1024L * 1024L * 1024L));
        assertEquals(
                "1.50 GB", UiFormatting.formatBytes(1024L * 1024L * 1024L + 512L * 1024L * 1024L));
        assertEquals("10.00 GB", UiFormatting.formatBytes(10L * 1024L * 1024L * 1024L));
    }

    @Test
    void formatSpeedReturnsBytesPerSecondForValuesUnder1024() {
        assertEquals("0 B/s", UiFormatting.formatSpeed(0));
        assertEquals("512 B/s", UiFormatting.formatSpeed(512));
        assertEquals("1023 B/s", UiFormatting.formatSpeed(1023));
    }

    @Test
    void formatSpeedReturnsKilobytesPerSecondForValuesUnder1MB() {
        assertEquals("1.0 KB/s", UiFormatting.formatSpeed(1024));
        assertEquals("1.5 KB/s", UiFormatting.formatSpeed(1536));
        assertEquals("1024.0 KB/s", UiFormatting.formatSpeed(1024 * 1024 - 1));
    }

    @Test
    void formatSpeedReturnsMegabytesPerSecondForValues1MBAndAbove() {
        assertEquals("1.00 MB/s", UiFormatting.formatSpeed(1024 * 1024));
        assertEquals("1.50 MB/s", UiFormatting.formatSpeed(1024 * 1024 + 512 * 1024));
        assertEquals("10.00 MB/s", UiFormatting.formatSpeed(10L * 1024L * 1024L));
    }
}
