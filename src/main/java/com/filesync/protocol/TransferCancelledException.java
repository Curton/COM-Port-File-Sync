package com.filesync.protocol;

import java.io.IOException;

/**
 * Signals that an in-flight transfer was deliberately cancelled — by the local user or by the peer
 * (XMODEM CAN signal) — rather than failing. This is an expected, benign outcome: callers must log
 * it as a normal event and keep the serial link up instead of treating it as a communication error
 * that tears the connection down.
 */
public class TransferCancelledException extends IOException {

    public TransferCancelledException(String message) {
        super(message);
    }
}
