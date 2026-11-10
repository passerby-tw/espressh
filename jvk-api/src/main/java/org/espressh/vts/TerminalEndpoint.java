package org.espressh.vts;

/** Frontend IO plus explicit normal-close intent. Transport EOF alone never closes a terminal. */
public interface TerminalEndpoint extends FrontendIo {
  /** Zero means non-last close completed; a positive token requires Shutdown/Continue decision. */
  long requestClose();
  void decideClose(long token, boolean shutdown);
}
