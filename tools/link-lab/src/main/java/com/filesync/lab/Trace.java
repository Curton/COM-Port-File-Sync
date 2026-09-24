package com.filesync.lab;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Timestamped trace for the emulator. Every wire event (frame, fault, injection) is written to the
 * console and, when requested, to a file, so a regression run can be inspected afterwards the same
 * way a {@code combined_log} is inspected after a real two-machine session.
 *
 * <p>Line format: {@code [hh:mm:ss.SSS][CHANNEL] message}, with timestamps relative to the start
 * of the run.
 */
public final class Trace {

    /** A direction of traffic through the emulator, as it appears in the trace. */
    public enum Dir {
        APP_TO_PEER("APP->LAB"),
        PEER_TO_APP("LAB->APP"),
        BRIDGE_A_TO_B("A->B"),
        BRIDGE_B_TO_A("B->A"),
        WIRE("WIRE"),
        PEER("PEER"),
        SCENARIO("SCENARIO");

        private final String label;

        Dir(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final PrintWriter console;
    private final PrintWriter file;
    private final java.util.function.Consumer<String> mirror;
    private final boolean logFrames;
    private final long t0 = System.nanoTime();
    private boolean closed;

    public Trace(boolean logFrames, File traceFile) {
        this(logFrames, traceFile, null);
    }

    public Trace(boolean logFrames, File traceFile, java.util.function.Consumer<String> mirror) {
        this.logFrames = logFrames;
        this.mirror = mirror;
        this.console = new PrintWriter(new java.io.OutputStreamWriter(System.out, java.nio.charset.StandardCharsets.UTF_8), true);
        if (traceFile != null) {
            PrintWriter f = null;
            try {
                f = new PrintWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(traceFile), java.nio.charset.StandardCharsets.UTF_8), true);
            } catch (IOException e) {
                System.err.println("Cannot open trace file " + traceFile + ": " + e.getMessage());
            }
            this.file = f;
        } else {
            this.file = null;
        }
    }

    public void log(Dir dir, String message) {
        emit(String.format("[%s][%-8s] %s", LocalTime.now().format(CLOCK), dir.label(), message));
    }

    private void emit(String line) {
        console.println(line);
        if (mirror != null) {
            mirror.accept(line);
        }
        if (file != null) {
            file.println(line);
        }
    }

    /** Logs a control frame, replacing raw newlines so one frame stays one line. */
    public void frame(Dir dir, String frameText) {
        if (!logFrames) {
            return;
        }
        String flat = frameText.replace("\r", "\\r").replace("\n", "\\n");
        if (flat.length() > 400) {
            flat = flat.substring(0, 400) + "...(" + frameText.length() + " chars)";
        }
        log(dir, "FRAME " + flat);
    }

    /** Logs a fault applied by the emulator itself. */
    public void wire(Dir dir, String message) {
        log(dir, "WIRE  " + message);
    }

    /** Seconds since the trace was created. */
    public double elapsedSeconds() {
        return (System.nanoTime() - t0) / 1_000_000_000.0;
    }

    public void close() {
        if (!closed) {
            closed = true;
            console.flush();
            if (file != null) {
                file.flush();
                file.close();
            }
        }
    }
}
