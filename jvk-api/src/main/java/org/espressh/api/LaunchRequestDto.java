package org.espressh.api;

import java.util.Objects;

/** Guest value projection. The runtime validates and copies all fields before launch. */
public final class LaunchRequestDto {
    public final String runtimeId, cwd;
    public final String[] argv, environmentKeys, environmentValues, classpath;
    /** Null paths inherit the caller's output. Files are owned by the new JVP. */
    public final String stdoutPath, stderrPath;
    public final boolean stdoutAppend, stderrAppend;

    public LaunchRequestDto(String runtimeId, String[] argv, String cwd,
                            String[] environmentKeys, String[] environmentValues,
                            String[] classpath) {
        this(runtimeId, argv, cwd, environmentKeys, environmentValues, classpath,
                null, false, null, false);
    }

    public LaunchRequestDto(String runtimeId, String[] argv, String cwd,
                            String[] environmentKeys, String[] environmentValues,
                            String[] classpath, String stdoutPath, boolean stdoutAppend,
                            String stderrPath, boolean stderrAppend) {
        this.runtimeId = Objects.requireNonNull(runtimeId);
        this.argv = Objects.requireNonNull(argv).clone();
        this.cwd = Objects.requireNonNull(cwd);
        this.environmentKeys = Objects.requireNonNull(environmentKeys).clone();
        this.environmentValues = Objects.requireNonNull(environmentValues).clone();
        this.classpath = Objects.requireNonNull(classpath).clone();
        if ((stdoutAppend && stdoutPath == null) || (stderrAppend && stderrPath == null))
            throw new IllegalArgumentException("Append requires an output path");
        this.stdoutPath = stdoutPath;
        this.stdoutAppend = stdoutAppend;
        this.stderrPath = stderrPath;
        this.stderrAppend = stderrAppend;
    }
}
