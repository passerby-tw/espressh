package org.espressh.jbox;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.espressh.api.JvkSystem;

/** Managed process values only. Never touches Guest/Host ProcessHandle. */
final class ProcessView {
  private static final Set<String> TERMINAL_STATES = Set.of("EXITED", "CANCELLED", "FAILED");

  static int run(String[] args) {
    boolean top = args[0].equals("top");
    if (args.length > 1
        && !(top
            && args.length == 4
            && args[1].equals("-b")
            && args[2].equals("-n")
            && args[3].equals("1"))) {
      System.err.println(
          args[0] + ": unsupported arguments; usage: " + (top ? "top [-b -n 1]" : "ps"));
      return 2;
    }
    try {
      var source = new ProcessSnapshot();
      if (top && args.length == 1) return monitor(source);
      source.print(top, false);
      return 0;
    } catch (CommandScope.Interrupted interrupted) {
      throw interrupted;
    } catch (Exception failure) {
      CommandScope.check();
      System.err.println(args[0] + ": " + message(failure));
      return 1;
    }
  }

  private static int monitor(ProcessSnapshot source) throws Exception {
    source.print(true, true);
    var refresher = new Refresher(source, CommandScope.current());
    refresher.start();
    try {
      // Read one byte at a time: the Guest stream does not implement nonblocking/bulk reads.
      var input = new ShellInput(CommandScope.input(System.in));
      var line = new ByteArrayOutputStream();
      for (int value; (value = input.read()) >= 0; ) {
        if (value == '\r' || value == '\n') {
          if (line.toString(StandardCharsets.UTF_8).trim().equalsIgnoreCase("q")) break;
          line.reset();
        } else ShellInput.append(line, value);
      }
    } finally {
      refresher.stopped = true;
      refresher.interrupt();
      // Applets must not leave an active thread in the persistent worker after returning.
      boolean interrupted = false;
      while (refresher.isAlive()) {
        try {
          refresher.join();
        } catch (InterruptedException failure) {
          interrupted = true;
        }
      }
      if (interrupted) Thread.currentThread().interrupt();
    }
    return refresher.failure == null ? 0 : 1;
  }

  private static final class Refresher extends Thread {
    private final ProcessSnapshot source;
    private final CommandScope scope;
    private volatile boolean stopped;
    private volatile Exception failure;

    private Refresher(ProcessSnapshot source, CommandScope scope) {
      super("jbox-top-refresh");
      this.source = source;
      this.scope = scope;
    }

    @Override
    public void run() {
      try {
        while (!stopped) {
          if (scope != null) scope.checkCancelled();
          Thread.sleep(2000);
          if (scope != null) scope.checkCancelled();
          if (!stopped) source.print(true, true);
        }
      } catch (CommandScope.Interrupted ignored) {
        // The command entry receives the same token and joins this thread before returning.
      } catch (InterruptedException ignored) {
        // The command is leaving; the entry thread joins this thread before returning.
      } catch (Exception problem) {
        if (scope == null || !scope.isCancelled()) {
          failure = problem;
          System.err.println("top: " + message(problem) + "; q + Enter to return");
        }
      }
    }
  }

  private static String message(Throwable failure) {
    while (failure.getCause() != null) failure = failure.getCause();
    return failure.getMessage() == null ? failure.toString() : failure.getMessage();
  }

  private static final class ProcessSnapshot {
    private final JvkSystem sys;

    private ProcessSnapshot() {
      sys = JvkSystem.current();
    }

    private void print(boolean top, boolean monitor) throws Exception {
      CommandScope.check();
      var rows = sys.processList();
      var lines = new ArrayList<String>();
      var counts = new TreeMap<String, Integer>();
      for (var row : rows) {
        CommandScope.check();
        String state = row.state();
        if (TERMINAL_STATES.contains(state)) continue;
        counts.merge(state, 1, Integer::sum);
        long vt = row.terminalId();
        lines.add(
            row.pid()
                + "\t"
                + row.parentPid()
                + "\t"
                + (vt < 0 ? "-" : Long.toString(vt))
                + "\t"
                + state
                + "\t"
                + row.runtimeId()
                + "\t"
                + row.command());
      }
      String newline = monitor ? "\r\n" : System.lineSeparator();
      var output = new StringBuilder(monitor ? "\u001b[H\u001b[2J" : "");
      if (top) {
        output.append(monitor ? "JVP monitor: " : "JVP snapshot: ")
            .append(lines.size()).append(" active processes ").append(counts).append(newline);
        output.append("CPU / memory accounting: unavailable; ")
            .append(monitor ? "refresh: 2s; q + Enter to quit" : "single snapshot").append(newline);
      }
      output.append("PID\tPPID\tVT\tSTATE\tRUNTIME\tCOMMAND").append(newline);
      for (String line : lines) output.append(line).append(newline);
      System.out.print(output);
      System.out.flush();
      CommandScope.check();
    }
  }
}
