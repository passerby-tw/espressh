package org.espressh.bootstrap;

import java.io.PrintStream;

/** Reports the selected Guest JDK and its actual VM through Guest standard error. */
public final class JavaVersionMain {
  private JavaVersionMain() {}

  public static void main(String[] args) {
    if (args.length != 0) throw new IllegalArgumentException("-version takes no arguments");

    String version = property("java.version");
    String date = System.getProperty("java.version.date", "");
    String runtimeName = property("java.runtime.name");
    String runtimeVersion = property("java.runtime.version");
    String vmName = property("java.vm.name");
    String vmVersion = property("java.vm.version");
    String vmInfo = property("java.vm.info");

    PrintStream err = System.err;
    err.println("java version \"" + version + "\"" + (date.isEmpty() ? "" : " " + date));
    err.println(runtimeName + " (build " + runtimeVersion + ")");
    err.println(vmName + " (build " + vmVersion + ", " + vmInfo + ")");
    err.flush();
  }

  private static String property(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isEmpty())
      throw new IllegalStateException("Missing Guest version property: " + name);
    return value;
  }
}
