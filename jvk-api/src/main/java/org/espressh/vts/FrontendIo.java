package org.espressh.vts;

import java.io.InputStream;
import java.io.OutputStream;

/** Frontend-facing terminal IO. Instances are bound to one frontend by the provider.
 * Reads receive terminal output; writes supply foreground input.
 * Stream close affects transport only: it is not a normal terminal close intent.
 * The provider owns lifecycle cleanup. Blocking IO must run outside the UI thread.
 */
public interface FrontendIo {
  InputStream output();
  OutputStream input();
  void resize(int columns, int rows);
  void interrupt();
}
