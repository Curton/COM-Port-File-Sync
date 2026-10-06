package com.filesync.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** Event-to-exit-code mapping of {@link SessionOutcome}. */
class SessionOutcomeTest {

    @Test
    void completeMapsToSuccess() throws Exception {
        SessionOutcome outcome = new SessionOutcome();
        outcome.begin();
        outcome.complete();
        assertEquals(CliMain.EXIT_SUCCESS, outcome.awaitExitCode(100));
    }

    @Test
    void cancelledMapsToThree() throws Exception {
        SessionOutcome outcome = new SessionOutcome();
        outcome.begin();
        outcome.cancelled();
        assertEquals(CliMain.EXIT_CANCELLED, outcome.awaitExitCode(100));
    }

    @Test
    void pendingWritesDemoteCompleteToPartial() throws Exception {
        SessionOutcome outcome = new SessionOutcome();
        outcome.begin();
        outcome.notePendingWrites();
        outcome.complete();
        assertEquals(CliMain.EXIT_PARTIAL, outcome.awaitExitCode(100));
    }

    @Test
    void failureAfterBeginMapsToOne() throws Exception {
        SessionOutcome outcome = new SessionOutcome();
        outcome.begin();
        outcome.failureSignal("boom");
        assertEquals(CliMain.EXIT_FAILURE, outcome.awaitExitCode(100));
        assertEquals("boom", outcome.failureMessage());
    }

    @Test
    void errorBeforeBeginDoesNotSettle() throws Exception {
        SessionOutcome outcome = new SessionOutcome();
        outcome.failureSignal("startup noise");
        assertFalse(outcome.isSettled());
        assertEquals(-1, outcome.awaitExitCode(50));
    }

    @Test
    void connectionLostSettlesEvenBeforeBegin() throws Exception {
        SessionOutcome outcome = new SessionOutcome();
        outcome.connectionLost();
        assertEquals(CliMain.EXIT_FAILURE, outcome.awaitExitCode(100));
    }

    @Test
    void firstTerminalSignalWins() throws Exception {
        SessionOutcome outcome = new SessionOutcome();
        outcome.begin();
        outcome.complete();
        outcome.failureSignal("late teardown error");
        assertEquals(CliMain.EXIT_SUCCESS, outcome.awaitExitCode(100));
    }
}
