package org.espressh.runtime;

import java.io.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.espressh.core.*;
import sun.misc.Signal;
import sun.misc.SignalHandler;

/** Borrowed Host streams; only frontend identities cross into VTS. */
public final class HostConsole implements AutoCloseable {
  private static HostConsole active;
  private final VirtualTerminalService vts;
  private final Supplier<VirtualTerminalService.Terminal> terminal;
  private final InputStream input;
  private final PrintStream diagnostics;
  private final ExecutorService events =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "host-console-control");
            t.setDaemon(true);
            return t;
          });
  private final Signal signal = new Signal("INT");
  private SignalHandler previous;
  private volatile boolean closed;
  private Thread pump;
  private boolean pumpTerminated;

  public HostConsole(
      VirtualTerminalService vts,
      Supplier<VirtualTerminalService.Terminal> terminal,
      InputStream input,
      PrintStream diagnostics) {
    this.vts = vts;
    this.terminal = terminal;
    this.input = input;
    this.diagnostics = diagnostics;
    synchronized (HostConsole.class) {
      if (active != null) {
        events.shutdownNow();
        throw new KernelException(ErrorCode.INVALID_STATE, "Host Console already active");
      }
      try {
        previous = Signal.handle(signal, ignored -> enqueueInterrupt());
        active = this;
      } catch (RuntimeException failure) {
        events.shutdownNow();
        throw failure;
      }
    }
  }

  public void start() {
    synchronized (HostConsole.class) {
      if (closed || pump != null)
        throw new IllegalStateException("Console already started or closed");
      pump = new Thread(this::pump, "host-console-input");
      pump.setDaemon(true);
      pump.start();
    }
  }

  private void enqueueInterrupt() {
    if (closed) return;
    try {
      events.execute(
          () -> {
            if (closed) return;
            var t = terminal.get();
            if (t == null) return;
            try {
              vts.interrupt(t.frontendId());
            } catch (KernelException gone) {
              if (!closed
                  && gone.code() != ErrorCode.ACCESS_DENIED
                  && gone.code() != ErrorCode.RESOURCE_CLOSED)
                diagnostics.println("CONSOLE_INTERRUPT_FAILED: " + gone);
            }
          });
    } catch (RejectedExecutionException stopped) {
      /* close won the race */
    }
  }

  private void pump() {
    VirtualTerminalService.Terminal t = null;
    try {
      while (!closed && (t = terminal.get()) == null) Thread.sleep(10);
      if (closed) return;
      while (!closed) {
        int value = input.read();
        if (closed) return;
        if (value < 0) {
          if (input == System.in && System.console() != null) {
            lost(t);
          } else {
            // A pipe writer can finish before esh starts reading. Drain its bytes before EOF.
            vts.inputEnded(t.frontendId());
          }
          return;
        }
        // A byte-producing frontend uses the same control route as SIGINT.
        if (value == 3) enqueueInterrupt();
        else vts.input(t.frontendId(), new byte[] {(byte) value});
      }
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    } catch (IOException | RuntimeException failure) {
      if (!closed && t != null) {
        if (failure instanceof KernelException k
            && (k.code() == ErrorCode.RESOURCE_CLOSED || k.code() == ErrorCode.ACCESS_DENIED))
          return;
        diagnostics.println("CONSOLE_INPUT_FAILED: " + failure);
        lost(t);
      }
    } finally {
      synchronized (HostConsole.class) {
        pumpTerminated = true;
        if (closed && active == this) active = null;
      }
    }
  }

  private void lost(VirtualTerminalService.Terminal t) {
    if (closed) return;
    try {
      vts.lost(t.frontendId());
    } catch (KernelException gone) {
      if (gone.code() != ErrorCode.ACCESS_DENIED && gone.code() != ErrorCode.RESOURCE_CLOSED)
        throw gone;
    }
  }

  @Override
  public void close() {
    synchronized (HostConsole.class) {
      if (closed) return;
      closed = true;
      if (active == this) {
        Signal.handle(signal, previous);
        if (pump == null || pumpTerminated) active = null;
      }
    }
    events.shutdownNow();
    if (pump != null) pump.interrupt();
    // A borrowed blocking stdin cannot be forcibly closed. Ownership remains
    // reserved until pump finally confirms termination; interrupt is only a hint.
  }
}
