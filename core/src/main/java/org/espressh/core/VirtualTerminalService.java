package org.espressh.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;

/** Host service. It owns terminal/foreground state and never executes a guest callback. */
public final class VirtualTerminalService {
  public enum CloseResult {
    STOP_FOREGROUND,
    CONFIRM_LAST
  }

  public enum Decision {
    SHUTDOWN,
    CONTINUE
  }

  public record Terminal(long id, long frontendId, boolean hostConsole) {}

  public record Size(int columns, int rows) {
    public Size {
      if (columns <= 0 || rows <= 0) {
        throw new IllegalArgumentException("Invalid terminal size");
      }
    }
  }

  public record CloseIntent(
      long token, long terminalId, CloseResult result, List<Long> foreground) {
    public CloseIntent {
      foreground = List.copyOf(foreground);
    }
  }

  private enum Phase {
    ACTIVE,
    CLOSING,
    LAST_PROMPT
  }

  private static final class State {
    final Terminal terminal;
    final List<Long> foreground = new ArrayList<>();
    TerminalChannels channels;
    Phase phase = Phase.ACTIVE;
    Size size;
    CloseIntent intent;

    State(Terminal terminal, long rootPid, Size size) {
      this.terminal = terminal;
      foreground.add(rootPid);
      this.size = size;
    }
  }

  private final Map<Long, State> terminals = new LinkedHashMap<>();
  private final AtomicLong ids = new AtomicLong(1);
  private final LongFunction<Runnable> interruptCapture;
  private final Runnable shutdownQueue;
  private final int maxTerminals;

  public VirtualTerminalService(LongConsumer interruptQueue, Runnable shutdownQueue) {
    this(interruptQueue, shutdownQueue, 64);
  }

  public VirtualTerminalService(LongConsumer interruptQueue, Runnable shutdownQueue, int maxTerminals) {
    this(shutdownQueue, maxTerminals, pid -> () -> interruptQueue.accept(pid));
  }

  /** Capture only Host identity under the VTS monitor; dispatch runs after releasing it. */
  public static VirtualTerminalService withInterruptCapture(
      LongFunction<Runnable> capture, Runnable shutdownQueue, int maxTerminals) {
    return new VirtualTerminalService(shutdownQueue, maxTerminals, capture);
  }

  private VirtualTerminalService(
      Runnable shutdownQueue, int maxTerminals, LongFunction<Runnable> interruptCapture) {
    if (maxTerminals <= 0) throw new IllegalArgumentException("Invalid terminal limit");
    this.interruptCapture = java.util.Objects.requireNonNull(interruptCapture);
    this.shutdownQueue = shutdownQueue;
    this.maxTerminals = maxTerminals;
  }

  /** Register only after root creation and frontend attachment have succeeded. */
  public synchronized Terminal attach(long rootPid, boolean hostConsole, Size size) {
    if (terminals.size() >= maxTerminals) {
      throw new KernelException(ErrorCode.INVALID_STATE, "VTS_TERMINAL_LIMIT: " + maxTerminals);
    }
    if (rootPid <= 0) {
      throw new IllegalArgumentException("Invalid root PID");
    }
    if (hostConsole && terminals.values().stream().anyMatch(s -> s.terminal.hostConsole())) {
      throw new KernelException(ErrorCode.INVALID_STATE, "Only one Host Console is permitted");
    }
    Terminal t = new Terminal(ids.getAndIncrement(), ids.getAndIncrement(), hostConsole);
    terminals.put(t.id(), new State(t, rootPid, size));
    return t;
  }

  /** Optional frontend byte endpoints. Borrowed Host outputs are never closed. */
  public synchronized Terminal attach(
      long rootPid,
      boolean hostConsole,
      Size size,
      java.io.OutputStream out,
      java.io.OutputStream err,
      int inputCapacity) {
    TerminalChannels channels = new TerminalChannels(out, err, inputCapacity);
    Terminal terminal = attach(rootPid, hostConsole, size);
    state(terminal.id()).channels = channels;
    return terminal;
  }

  /** Attach a frontend with VTS-owned bounded output, merging stdout/stderr by write order. */
  public synchronized Terminal attach(long rootPid, Size size, int capacity) {
    TerminalChannels channels = new TerminalChannels(capacity);
    Terminal terminal = attach(rootPid, false, size);
    state(terminal.id()).channels = channels;
    return terminal;
  }

  /** Trusted provider entry. Guest callers must receive a caller-bound adapter, not this object. */
  public synchronized org.espressh.vts.FrontendIo frontendIo(long frontendId) {
    State s = frontend(frontendId);
    if (s.channels == null) {
      throw new KernelException(ErrorCode.INVALID_STATE, "No frontend byte endpoints");
    }
    java.io.InputStream output = s.channels.frontendOutput();
    return new org.espressh.vts.FrontendIo() {
      private final java.io.OutputStream input = new java.io.OutputStream() {
        @Override public void write(int value) throws java.io.IOException {
          write(new byte[] {(byte) value});
        }
        @Override public void write(byte[] bytes, int offset, int length) throws java.io.IOException {
          java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
          VirtualTerminalService.this.input(frontendId,
              java.util.Arrays.copyOfRange(bytes, offset, offset + length));
        }
        @Override public void close() { inputEnded(frontendId); }
      };
      @Override public java.io.InputStream output() { return output; }
      @Override public java.io.OutputStream input() { return input; }
      @Override public void resize(int columns, int rows) {
        VirtualTerminalService.this.resize(frontendId, new Size(columns, rows));
      }
      @Override public void interrupt() { VirtualTerminalService.this.interrupt(frontendId); }
    };
  }

  public synchronized ProcessIo processIo(long terminalId, long pid) {
    State s = state(terminalId);
    if (s.channels == null) {
      throw new KernelException(ErrorCode.INVALID_STATE, "No frontend byte endpoints");
    }
    return s.channels.endpoint(pid);
  }

  /**
   * Frontend input routes to the foreground snapshot; blocking pipe write is outside the VTS lock.
   */
  public void input(long frontendId, byte[] bytes) throws java.io.IOException {
    java.io.OutputStream input;
    TerminalChannels channels;
    boolean echo;
    long pid;
    synchronized (this) {
      State s = frontend(frontendId);
      if (s.phase == Phase.CLOSING) {
        throw new KernelException(ErrorCode.RESOURCE_CLOSED, "Terminal closing");
      }
      if (s.channels == null) {
        throw new KernelException(ErrorCode.INVALID_STATE, "No frontend byte endpoints");
      }
      channels = s.channels;
      echo = !s.terminal.hostConsole();
      pid = s.foreground.get(s.foreground.size() - 1);
      input = channels.input(pid);
    }
    byte[] snapshot = bytes.clone();
    if (echo) channels.echo(pid, snapshot);
    input.write(snapshot);
  }

  /** Transport EOF only; not a Ctrl-D or esh exit key binding. */
  public void inputEnded(long frontendId) {
    TerminalChannels channels;
    synchronized (this) {
      channels = frontend(frontendId).channels;
    }
    if (channels != null) {
      channels.inputEnded();
    }
  }

  public void releaseProcessIo(long terminalId, long pid) {
    TerminalChannels channels;
    synchronized (this) {
      channels = state(terminalId).channels;
    }
    if (channels != null) {
      channels.release(pid);
    }
  }

  /** End a command attachment. Close already owns its captured chain when CLOSING. */
  public synchronized boolean foregroundCompleted(long terminalId, long childPid) {
    State s = state(terminalId);
    if (s.phase == Phase.CLOSING) {
      return false;
    }
    if (s.foreground.size() < 2 || s.foreground.get(s.foreground.size() - 1) != childPid) {
      throw new KernelException(ErrorCode.INVALID_STATE, "Not the foreground child");
    }
    s.foreground.remove(s.foreground.size() - 1);
    return true;
  }

  private State state(long id) {
    State s = terminals.get(id);
    if (s == null) {
      throw new KernelException(ErrorCode.RESOURCE_CLOSED, "Unknown terminal " + id);
    }
    return s;
  }

  private State frontend(long id) {
    return terminals.values().stream()
        .filter(s -> s.terminal.frontendId() == id)
        .findFirst()
        .orElseThrow(() -> new KernelException(ErrorCode.ACCESS_DENIED, "Unknown frontend " + id));
  }

  public synchronized void pushForeground(long terminalId, long expectedParent, long childPid) {
    State s = state(terminalId);
    if (s.phase != Phase.ACTIVE
        || s.foreground.get(s.foreground.size() - 1) != expectedParent
        || childPid <= 0
        || s.foreground.contains(childPid)) {
      throw new KernelException(ErrorCode.INVALID_STATE, "Invalid foreground transition");
    }
    s.foreground.add(childPid);
  }

  public synchronized void childFinished(long terminalId, long childPid) {
    State s = state(terminalId);
    if (s.phase != Phase.ACTIVE) {
      throw new KernelException(ErrorCode.INVALID_STATE, "Close owns the foreground chain");
    }
    if (s.foreground.size() < 2 || s.foreground.get(s.foreground.size() - 1) != childPid) {
      throw new KernelException(ErrorCode.INVALID_STATE, "Not the foreground child");
    }
    s.foreground.remove(s.foreground.size() - 1);
  }

  public void interrupt(long frontendId) {
    Runnable dispatch;
    synchronized (this) {
      State s = frontend(frontendId);
      dispatch = interruptCapture.apply(s.foreground.get(s.foreground.size() - 1));
    }
    dispatch.run();
  }

  public synchronized void resetInput(long terminalId, long expectedPid)
      throws java.io.IOException {
    State s = state(terminalId);
    if (s.foreground.get(s.foreground.size() - 1) != expectedPid) {
      return;
    }
    if (s.channels != null) {
      s.channels.resetForInterrupt(expectedPid);
    }
  }

  /** Transient command cancellation, separate from stream bytes and process lifetime. */
  public synchronized void interruptCommandInput(long terminalId, long expectedPid)
      throws java.io.IOException {
    State s = state(terminalId);
    if (s.phase != Phase.ACTIVE || s.foreground.get(s.foreground.size() - 1) != expectedPid) return;
    if (s.channels != null) s.channels.interruptCommandRead(expectedPid);
  }

  public synchronized void clearCommandInterrupt(long terminalId, long pid) {
    State s = terminals.get(terminalId);
    if (s != null && s.channels != null) s.channels.clearCommandInterrupt(pid);
  }

  public synchronized void resize(long frontendId, Size size) {
    frontend(frontendId).size = size;
  }

  public synchronized Size size(long terminalId) {
    return state(terminalId).size;
  }

  public synchronized long foreground(long terminalId) {
    State s = state(terminalId);
    return s.foreground.get(s.foreground.size() - 1);
  }

  public synchronized int attachedCount() {
    return terminals.size();
  }

  public synchronized boolean closing(long terminalId) {
    return state(terminalId).phase == Phase.CLOSING;
  }

  public synchronized long root(long terminalId) {
    return state(terminalId).foreground.get(0);
  }

  /** Reserve only a non-last close; the last terminal remains unchanged. */
  public synchronized CloseIntent tryCloseRoot(long terminalId, long caller) {
    State s = state(terminalId);
    if (s.foreground.get(0) != caller || s.foreground.get(s.foreground.size() - 1) != caller) {
      throw new KernelException(ErrorCode.ACCESS_DENIED, "Only foreground terminal root may close");
    }
    if (s.intent != null) {
      if (s.intent.result() == CloseResult.STOP_FOREGROUND) {
        return s.intent;
      }
      throw new KernelException(ErrorCode.INVALID_STATE, "Frontend close already pending");
    }
    if (terminals.values().stream().filter(x -> x.phase != Phase.CLOSING).count() <= 1) {
      return null;
    }
    return requestClose(s.terminal.frontendId());
  }

  public synchronized CloseIntent requestClose(long frontendId) {
    State s = frontend(frontendId);
    if (s.intent != null) {
      return s.intent;
    }
    long available = terminals.values().stream().filter(x -> x.phase != Phase.CLOSING).count();
    CloseResult result = available == 1 ? CloseResult.CONFIRM_LAST : CloseResult.STOP_FOREGROUND;
    s.phase = result == CloseResult.CONFIRM_LAST ? Phase.LAST_PROMPT : Phase.CLOSING;
    List<Long> order = new ArrayList<>(s.foreground);
    java.util.Collections.reverse(order);
    s.intent = new CloseIntent(ids.getAndIncrement(), s.terminal.id(), result, order);
    return s.intent;
  }

  public void decide(CloseIntent intent, Decision decision) {
    synchronized (this) {
      State s = state(intent.terminalId());
      validIntent(s, intent);
      if (s.phase != Phase.LAST_PROMPT) {
        throw new KernelException(ErrorCode.INVALID_STATE, "Not a last-frontend prompt");
      }
      if (decision == Decision.CONTINUE) {
        s.phase = Phase.ACTIVE;
        s.intent = null;
        return;
      }
      // Prevent new foreground work until the kernel completes shutdown.
      s.phase = Phase.CLOSING;
    }
    shutdownQueue.run();
  }

  public void completeClose(CloseIntent intent, LongPredicate confirmedTerminated) {
    synchronized (this) {
      State s = state(intent.terminalId());
      validIntent(s, intent);
      if (intent.result() != CloseResult.STOP_FOREGROUND) {
        throw new KernelException(
            ErrorCode.INVALID_STATE, "Last frontend requires kernel shutdown");
      }
    }
    // Do not hold VTS lock while inspecting provider/process state.
    for (long pid : intent.foreground()) {
      if (!confirmedTerminated.test(pid)) {
        throw new KernelException(ErrorCode.TIMEOUT, "Unconfirmed terminal foreground PID " + pid);
      }
    }
    synchronized (this) {
      State s = state(intent.terminalId());
      validIntent(s, intent);
      terminals.remove(intent.terminalId());
      if (s.channels != null) {
        s.channels.close();
      }
    }
  }

  private static void validIntent(State s, CloseIntent intent) {
    if (s.intent == null || s.intent.token() != intent.token() || !s.intent.equals(intent)) {
      throw new KernelException(ErrorCode.ACCESS_DENIED, "Stale or forged close intent");
    }
  }

  /** Returns the affected chain for kernel cleanup; never infers scope from descendants. */
  public List<Long> lost(long frontendId) {
    List<Long> affected;
    boolean shutdown;
    synchronized (this) {
      State s = frontend(frontendId);
      affected = List.copyOf(s.foreground);
      terminals.remove(s.terminal.id());
      if (s.channels != null) {
        s.channels.close();
      }
      shutdown = terminals.values().stream().noneMatch(x -> x.phase != Phase.CLOSING);
    }
    if (shutdown) {
      shutdownQueue.run();
    }
    return affected;
  }

  public synchronized void shutdownCompleted() {
    for (State s : terminals.values()) {
      if (s.channels != null) {
        s.channels.close();
      }
    }
    terminals.clear();
  }
}
