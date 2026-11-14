package org.espressh.runtime;

import org.espressh.core.ProcessRecord;

/** Optional Host-only, sequential wall-clock phases; no Guest capability or retained history. */
final class ExecutionTiming {
    private static final boolean ENABLED = Boolean.getBoolean("espressh.runtime.timing");
    private static final ExecutionTiming DISABLED = new ExecutionTiming(false);

    enum Phase {
        WORKER_START, IO_SETUP, ENTRY_RESOLVE, CONTEXT_CONFIG, CONTEXT_BUILD, HOST_BINDINGS,
        JAVA_INITIALIZE, ENV_LINK, BOOTSTRAP_LOOKUP, BOOTSTRAP_INSTALL, APP_LOOKUP,
        ARGV_SETUP, ENTRY_READY, MAIN, ENTRY_END, GRAPHICS_BEGIN_CLOSE, CHILDREN_CLOSE,
        IO_CLOSE, CONTEXT_CLOSE, GRAPHICS_CLOSE, CONFIRM
    }

    private final long started;
    private final long[] durations;
    private long cursor;
    private Phase phase = Phase.WORKER_START;

    private ExecutionTiming(boolean enabled) {
        durations = enabled ? new long[Phase.values().length] : null;
        started = cursor = enabled ? System.nanoTime() : 0;
    }

    static ExecutionTiming create() { return ENABLED ? new ExecutionTiming(true) : DISABLED; }

    void begin(Phase next) {
        if (durations == null) return;
        long now = System.nanoTime();
        durations[phase.ordinal()] += now - cursor;
        cursor = now;
        phase = next;
    }

    void emit(ProcessRecord process, boolean worker, boolean confirmed) {
        if (durations == null) return;
        long ended = System.nanoTime();
        durations[phase.ordinal()] += ended - cursor;
        StringBuilder line = new StringBuilder(768).append("JVP_TIMING pid=")
                .append(process.pid()).append(" ppid=").append(process.parentPid())
                .append(" mode=").append(worker ? "worker" : "application")
                .append(" state=").append(process.state()).append(" confirmed=").append(confirmed);
        millis(line, "total", ended - started);
        for (Phase item : Phase.values())
            millis(line, item.name().toLowerCase(java.util.Locale.ROOT), durations[item.ordinal()]);
        // The line is a Host diagnostic, outside Guest redirection. Formatting/output
        // is excluded from total; tracing itself can still perturb benchmark timings.
        System.err.println(line);
    }

    private static void millis(StringBuilder line, String name, long nanos) {
        long micros = nanos / 1000;
        long fraction = micros % 1000;
        line.append(' ').append(name).append("_ms=").append(micros / 1000).append('.');
        if (fraction < 100) line.append('0');
        if (fraction < 10) line.append('0');
        line.append(fraction);
    }
}
