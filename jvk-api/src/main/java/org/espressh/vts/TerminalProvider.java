package org.espressh.vts;

/** Runtime-supplied provider. Consumers compile against this API without Espresso dependencies. */
public interface TerminalProvider {
  TerminalEndpoint createTerminal(int columns, int rows);

  static TerminalProvider current() {
    return java.util.ServiceLoader.load(TerminalProvider.class).findFirst()
        .orElseThrow(() -> new IllegalStateException("VTS_PROVIDER_UNAVAILABLE: managed Espressh required"));
  }
}
