package org.espressh.runtime.graphics;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.function.BooleanSupplier;
import org.graalvm.polyglot.HostAccess;

/** Service-owned pixels. The exported view retains no image or array after revocation. */
public final class SharedPixels {
  private int[] pixels;
  private final boolean opaque;
  private final BooleanSupplier closed;
  SharedPixels(BufferedImage image, BooleanSupplier closed) {
    pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    opaque = !image.getColorModel().hasAlpha();
    this.closed = closed;
  }
  private void check(int index) {
    if (pixels == null || closed.getAsBoolean())
      throw new IllegalStateException("GRAPHICS_BUFFER_REVOKED");
    if (index < 0 || index >= pixels.length)
      throw new IndexOutOfBoundsException("GRAPHICS_BUFFER_BOUNDS");
  }
  @HostAccess.Export public synchronized int readInt(int index) {
    check(index); return opaque ? pixels[index] | 0xff000000 : pixels[index];
  }
  @HostAccess.Export public synchronized void writeInt(int index, int argb) {
    check(index); pixels[index] = opaque ? argb | 0xff000000 : argb;
  }
  synchronized void revoke() { pixels = null; }
}
