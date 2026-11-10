package org.espressh.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Snapshot of child inputs; caller identity is deliberately absent. */
public record LaunchRequest(String runtimeId, List<String> argv, Path cwd,
                            Map<String, String> environment, List<Path> classpath,
                            Path stdoutPath, boolean stdoutAppend,
                            Path stderrPath, boolean stderrAppend) {
    public LaunchRequest(String runtimeId, List<String> argv, Path cwd,
                         Map<String, String> environment, List<Path> classpath) {
        this(runtimeId, argv, cwd, environment, classpath, null, false, null, false);
    }
    public LaunchRequest {
        Objects.requireNonNull(runtimeId); Objects.requireNonNull(cwd);
        if (runtimeId.isBlank()) throw new IllegalArgumentException("Empty runtime");
        argv = List.copyOf(argv); environment = Map.copyOf(environment); classpath = List.copyOf(classpath);
        if (argv.isEmpty()) throw new IllegalArgumentException("Empty command");
        if (!cwd.isAbsolute()) throw new IllegalArgumentException("CWD must be absolute");
        cwd = cwd.normalize();
        for (Path p : classpath) if (!p.isAbsolute()) throw new IllegalArgumentException("Classpath must be resolved");
        if (stdoutPath != null && !stdoutPath.isAbsolute()
                || stderrPath != null && !stderrPath.isAbsolute())
            throw new IllegalArgumentException("Output paths must be resolved");
        if (stdoutAppend && stdoutPath == null || stderrAppend && stderrPath == null)
            throw new IllegalArgumentException("Append requires an output path");
    }

    public boolean redirectsOutput() { return stdoutPath != null || stderrPath != null; }
}
