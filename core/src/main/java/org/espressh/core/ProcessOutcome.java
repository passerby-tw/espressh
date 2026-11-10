package org.espressh.core;

import java.time.Instant;
import java.util.Objects;

/** Immutable result values. Original causes belong to Host diagnostics, not this projection. */
public record ProcessOutcome(ProcessState state, Integer exitCode, ErrorCode error,
                             String message, Instant finishedAt) {
    public ProcessOutcome {
        Objects.requireNonNull(state); Objects.requireNonNull(message); Objects.requireNonNull(finishedAt);
        if (!state.terminal()) throw new IllegalArgumentException("Outcome must be terminal");
        if (state == ProcessState.EXITED && (exitCode == null || error != null))
            throw new IllegalArgumentException("Exit requires a code and no error");
        if (state == ProcessState.FAILED && error == null) throw new IllegalArgumentException("Failure requires an error");
        if (state == ProcessState.CANCELLED && !Objects.equals(exitCode, 130) && !Objects.equals(exitCode, 137))
            throw new IllegalArgumentException("Confirmed cancellation code must be 130 or 137");
    }
    public static ProcessOutcome exited(int code) { return new ProcessOutcome(ProcessState.EXITED, code, null, "", Instant.now()); }
    public static ProcessOutcome failed(ErrorCode code, String message) { return new ProcessOutcome(ProcessState.FAILED, null, code, message, Instant.now()); }
    public static ProcessOutcome cancelled(boolean interrupt) { return new ProcessOutcome(ProcessState.CANCELLED, interrupt ? 130 : 137, null, "", Instant.now()); }
}
