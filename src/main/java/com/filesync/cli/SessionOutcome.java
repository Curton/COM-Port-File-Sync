package com.filesync.cli;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Maps sync events onto the CLI exit code for one session.
 *
 * <p>The core reports session results only through the event bus (there is no Future), so the CLI
 * latches on the first terminal signal: {@code SYNC_COMPLETE}, {@code SYNC_CANCELLED}, a
 * connection-loss {@code CONNECTION_STATUS} event, or — once the session has actually been started
 * — an {@code ERROR} event. The first signal wins; later events from teardown cannot flip the
 * outcome.
 */
final class SessionOutcome {

    enum Result {
        SUCCESS,
        FAILURE,
        CANCELLED,
        PARTIAL
    }

    private final CountDownLatch done = new CountDownLatch(1);
    private final AtomicReference<Result> result = new AtomicReference<>();
    private final AtomicBoolean pendingWrites = new AtomicBoolean();

    /**
     * Error events only end the session once the run has entered its await phase; startup noise
     * (e.g. one failed negotiation reply that later retries) must not fail the run prematurely.
     */
    private volatile boolean begun;

    private volatile String failureMessage;

    /** Marks the point where the run starts waiting for the session result. */
    void begin() {
        begun = true;
    }

    /** An {@code ERROR} event; terminal once the session is underway. */
    void failureSignal(String message) {
        failureMessage = message;
        if (begun) {
            settle(Result.FAILURE);
        }
    }

    /** A connection-loss {@code CONNECTION_STATUS} event; always terminal for a one-shot run. */
    void connectionLost() {
        failureMessage = "Connection lost";
        settle(Result.FAILURE);
    }

    /** A {@code SYNC_CANCELLED} event (local or peer cancel). */
    void cancelled() {
        settle(Result.CANCELLED);
    }

    /** A {@code PENDING_FILE_WRITE} event: a locked file could not be written on this receiver. */
    void notePendingWrites() {
        pendingWrites.set(true);
    }

    /** A {@code SYNC_COMPLETE} event; pending locked files demote it to a partial run. */
    void complete() {
        settle(pendingWrites.get() ? Result.PARTIAL : Result.SUCCESS);
    }

    boolean isSettled() {
        return done.getCount() == 0;
    }

    String failureMessage() {
        return failureMessage;
    }

    /**
     * Waits for a terminal event and returns the mapped exit code, or {@code -1} if the timeout
     * elapsed first (the caller turns that into a failure with its own message).
     */
    int awaitExitCode(long timeoutMs) throws InterruptedException {
        boolean finished;
        if (timeoutMs == 0) {
            done.await();
            finished = true;
        } else {
            finished = done.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
        if (!finished) {
            return -1;
        }
        return exitCodeOf(result.get());
    }

    static int exitCodeOf(Result result) {
        return switch (result) {
            case SUCCESS -> CliMain.EXIT_SUCCESS;
            case FAILURE -> CliMain.EXIT_FAILURE;
            case CANCELLED -> CliMain.EXIT_CANCELLED;
            case PARTIAL -> CliMain.EXIT_PARTIAL;
        };
    }

    private void settle(Result terminal) {
        if (result.compareAndSet(null, terminal)) {
            done.countDown();
        }
    }
}
