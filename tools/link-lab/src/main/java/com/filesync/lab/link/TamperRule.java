package com.filesync.lab.link;

import java.nio.charset.StandardCharsets;

/**
 * One tamper rule for control frames crossing the emulated link. Rules match a command name
 * (optionally only its n-th occurrence) and then drop, delay, corrupt or inject frames around it.
 * Injection is how the emulator forces deterministic behaviour such as role assignment.
 */
public final class TamperRule {

    public enum Action {
        /** The frame never reaches the other side. */
        DROP,
        /** The frame is delivered late (the wire is slow for this frame). */
        DELAY,
        /** One bit of the frame payload is flipped. */
        CORRUPT,
        /** An extra frame is emitted immediately before the matched frame. */
        INJECT_BEFORE,
        /** An extra frame is emitted immediately after the matched frame. */
        INJECT_AFTER
    }

    private final String command;
    private final int occurrence;
    private final Action action;
    private final long delayMillis;
    private final byte[] injectFrame;
    private int seen;

    public TamperRule(String command, int occurrence, Action action, long delayMillis, String injectFrame) {
        this.command = command;
        this.occurrence = occurrence;
        this.action = action;
        this.delayMillis = delayMillis;
        this.injectFrame =
                injectFrame == null || injectFrame.isEmpty()
                        ? null
                        : (injectFrame.endsWith("\n")
                                ? injectFrame
                                : injectFrame + "\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * Records this frame against the rule and returns the action to take, or {@code null} when the
     * rule does not fire.
     */
    synchronized Action claim(String frameCommand) {
        if (!command.equals(frameCommand)) {
            return null;
        }
        seen++;
        if (occurrence <= 0 || seen == occurrence) {
            return action;
        }
        return null;
    }

    long delayMillis() {
        return delayMillis;
    }

    byte[] injectFrameBytes() {
        return injectFrame;
    }

    synchronized int seen() {
        return seen;
    }

    public String command() {
        return command;
    }

    public Action action() {
        return action;
    }

    public int occurrence() {
        return occurrence;
    }

    public String describe() {
        switch (action) {
            case INJECT_BEFORE:
            case INJECT_AFTER:
                return String.format(
                        "%s %s#%d -> inject %s",
                        action,
                        command,
                        occurrence <= 0 ? seen : occurrence,
                        new String(injectFrame, StandardCharsets.ISO_8859_1).trim());
            default:
                return String.format("%s %s#%d", action, command, occurrence <= 0 ? seen : occurrence);
        }
    }
}
