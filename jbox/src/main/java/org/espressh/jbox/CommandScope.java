package org.espressh.jbox;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** One cooperative applet invocation inside this Guest JVM, never a process or signal. */
final class CommandScope implements AutoCloseable {
  private static volatile CommandScope active;

  private final long generation;
  private final boolean managed;
  private volatile boolean cancelled;
  private boolean entered, finished;

  private CommandScope(long generation, boolean managed) {
    this.generation = generation;
    this.managed = managed;
  }

  static synchronized void begin(long generation) {
    if (generation <= 0) throw new IllegalArgumentException("Invalid applet generation");
    if (active != null) throw new IllegalStateException("Applet invocation already active");
    active = new CommandScope(generation, true);
  }

  static synchronized boolean interrupt(long generation) {
    CommandScope scope = active;
    if (scope == null || scope.generation != generation || scope.finished) return false;
    scope.cancelled = true;
    return true;
  }

  static synchronized void end(long generation) {
    CommandScope scope = active;
    if (scope == null || scope.generation != generation) return;
    if (scope.entered && !scope.finished)
      throw new IllegalStateException("Applet invocation has not returned");
    active = null;
  }

  static synchronized CommandScope enter() {
    CommandScope scope = active;
    if (scope == null) active = scope = new CommandScope(0, false);
    if (scope.entered || scope.finished)
      throw new IllegalStateException("Applet invocation already entered");
    scope.entered = true;
    return scope;
  }

  static CommandScope current() {
    return active;
  }

  static void check() {
    CommandScope scope = active;
    if (scope != null) scope.checkCancelled();
  }

  void checkCancelled() {
    if (cancelled) throw new Interrupted();
  }

  boolean isCancelled() {
    return cancelled;
  }

  int complete(int status) {
    synchronized (CommandScope.class) {
      finished = true;
      return cancelled ? 130 : status;
    }
  }

  /** Preserve byte data and borrowed-stream ownership; interruption is out of band. */
  static InputStream input(InputStream source) {
    Objects.requireNonNull(source);
    CommandScope scope = active;
    if (scope == null) return source;
    return new InputStream() {
      @Override
      public int read() throws IOException {
        scope.checkCancelled();
        try {
          int value = source.read();
          scope.checkCancelled();
          return value;
        } catch (IOException failure) {
          scope.checkCancelled();
          throw failure;
        }
      }

      @Override
      public int read(byte[] bytes, int offset, int length) throws IOException {
        scope.checkCancelled();
        try {
          int count = source.read(bytes, offset, length);
          scope.checkCancelled();
          return count;
        } catch (IOException failure) {
          // EspressoLibs can translate the Host read abort into plain Guest IOException.
          scope.checkCancelled();
          throw failure;
        }
      }
      // InputStream.close() leaves the borrowed source open.
    };
  }

  @Override
  public void close() {
    synchronized (CommandScope.class) {
      finished = true;
      if (!managed && active == this) active = null;
    }
  }

  static final class Interrupted extends RuntimeException {
    private Interrupted() {
      super(null, null, false, false);
    }
  }
}
