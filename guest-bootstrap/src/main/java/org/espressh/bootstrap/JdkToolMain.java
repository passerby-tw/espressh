package org.espressh.bootstrap;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.spi.ToolProvider;

/** Runs entirely in the selected Guest JDK; never starts a Host executable. */
public final class JdkToolMain {
  public static void main(String[] args) {
    if (args.length == 0) throw new IllegalArgumentException("Missing JDK tool");
    ToolProvider tool =
        ToolProvider.findFirst(args[0])
            .orElseThrow(
                () -> new IllegalArgumentException("Guest JDK tool unavailable: " + args[0]));
    int code =
        tool.run(
            new PrintWriter(System.out, true),
            new PrintWriter(System.err, true),
            Arrays.copyOfRange(args, 1, args.length));
    System.out.flush();
    System.err.flush();
    if (code != 0) System.exit(code);
  }
}
