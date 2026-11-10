package org.espressh.runtime.graphics;

/** Optional metadata-only diagnostics; never logs text or key codes. */
final class InputTrace {
  static final boolean ENABLED = Boolean.getBoolean("espressh.input.trace");
  static void log(String stage, String metadata) {
    if (ENABLED) System.err.println("[INPUT_TRACE] ms=" + System.currentTimeMillis()
        + " stage=" + stage + " " + metadata);
  }
  private InputTrace() {}
}
