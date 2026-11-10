package org.espressh.core;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Host-owned state machine. A stop request is NOT evidence that execution has stopped. */
public final class ProcessRecord {
    private final long pid, parentPid;
    private final LaunchRequest request;
    private ProcessState state = ProcessState.NEW;
    private ProcessOutcome outcome;
    private final CompletableFuture<ProcessOutcome> completion = new CompletableFuture<>();
    ProcessRecord(long pid, long parentPid, LaunchRequest request) { this.pid=pid; this.parentPid=parentPid; this.request=request; }
    public long pid() { return pid; }
    public long parentPid() { return parentPid; }
    public LaunchRequest request() { return request; }
    public synchronized ProcessState state() { return state; }
    public synchronized Optional<ProcessOutcome> outcome() { return Optional.ofNullable(outcome); }
    public synchronized void starting() { move(ProcessState.STARTING); }
    public synchronized void running() { move(ProcessState.RUNNING); }
    public synchronized boolean requestStop() {
        if (state.terminal() || state == ProcessState.STOPPING) return false;
        move(ProcessState.STOPPING); return true;
    }
    private void move(ProcessState next) {
        if (!state.permits(next)) throw new KernelException(ErrorCode.INVALID_STATE, state + " -> " + next);
        state = next;
    }
    /** Called only after the provider confirms termination; first terminal result wins. */
    public boolean confirm(ProcessOutcome result) {
        synchronized (this) {
            if (state.terminal()) return false;
            move(result.state()); outcome = result;
        }
        // Completing a future can run callbacks; never do that under the record lock.
        completion.complete(result); return true;
    }
    public ProcessOutcome await() throws InterruptedException {
        try { return completion.get(); } catch (ExecutionException impossible) { throw new AssertionError(impossible); }
    }
    public Optional<ProcessOutcome> await(Duration timeout) throws InterruptedException {
        if (timeout.isNegative()) throw new IllegalArgumentException("Negative timeout");
        try { return Optional.of(completion.get(timeout.toNanos(), TimeUnit.NANOSECONDS)); }
        catch (TimeoutException expected) { return Optional.empty(); }
        catch (ExecutionException impossible) { throw new AssertionError(impossible); }
    }
}
