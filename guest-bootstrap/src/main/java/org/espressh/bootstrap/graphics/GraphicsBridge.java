package org.espressh.bootstrap.graphics;

import java.lang.reflect.*;
import java.util.List;

/** Trusted Guest-side bridge for future Toolkit/Peer adapters. Applications need not import it. */
public final class GraphicsBridge {
  private final Object endpoint;
  private final Method invoke, asLong, asString, asBoolean;

  public GraphicsBridge() throws ReflectiveOperationException {
    Class<?> polyglot = Class.forName("com.oracle.truffle.espresso.polyglot.Polyglot");
    Class<?> interop = Class.forName("com.oracle.truffle.espresso.polyglot.Interop");
    endpoint = polyglot.getMethod("importObject", String.class).invoke(null, "jvk_graphics");
    invoke = interop.getMethod("invokeMember", Object.class, String.class, Object[].class);
    asLong = interop.getMethod("asLong", Object.class);
    asString = interop.getMethod("asString", Object.class);
    asBoolean = interop.getMethod("asBoolean", Object.class);
    if (!GraphicsWire.VERSION.equals(string("apiVersion")))
      throw new IllegalStateException("Graphics API version mismatch");
  }

  private Object call(String member, Object... args) {
    try {
      return invoke.invoke(null, endpoint, member, args);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException("Graphics operation failed: " + member, ex);
    }
  }

  private long number(String member, Object... args) {
    try {
      return (Long) asLong.invoke(null, call(member, args));
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private String string(String member, Object... args) {
    try {
      return (String) asString.invoke(null, call(member, args));
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public long createSurface(int width, int height) {
    return number("createSurface", width, height);
  }

  public PixelView sharedPixels(long handle, long generation) {
    return new PixelView(call("sharedPixels", handle, generation));
  }
  public final class PixelView {
    private final Object view;
    private PixelView(Object view) { this.view = view; }
    private Object invokeView(String member, Object... args) {
      try { return invoke.invoke(null, view, member, args); }
      catch (ReflectiveOperationException e) { throw new IllegalStateException("Graphics pixel access failed", e); }
    }
    public int read(int index) {
      try { return ((Long)asLong.invoke(null, invokeView("readInt", index))).intValue(); }
      catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    public void write(int index, int argb) { invokeView("writeInt", index, argb); }
  }

  public String defaultDisplayInfo() { return string("defaultDisplayInfo"); }
  public String windowGeometry(long handle) { return string("windowGeometry", handle); }
  public String readPixels(long handle, long generation) { return string("readPixels", handle, generation); }
  public void updateInputMethod(long handle, String snapshot) { call("updateInputMethod",handle,snapshot); }
  public void endComposition(long handle) { call("endComposition",handle); }
  public boolean requestFocus(long handle) {
    try {
      return (Boolean) asBoolean.invoke(null, call("requestFocus", handle));
    } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
  }

  public long createWindow(String title, int width, int height) {
    return number("createWindow", title, width, height);
  }

  public long createImage(int width, int height, int[] argb) {
    if ((long) width * height != argb.length)
      throw new IllegalArgumentException("Image dimensions mismatch");
    java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(Math.multiplyExact(argb.length, 4));
    bytes.asIntBuffer().put(argb);
    return number(
        "createImage", width, height, java.util.Base64.getEncoder().encodeToString(bytes.array()));
  }

  public void setVisible(long window, boolean visible) {
    call("setVisible", window, visible);
  }

  public void setTitle(long window, String title) {
    call("setTitle", window, title);
  }

  public void setBounds(long window, int x, int y, int width, int height) {
    call("setBounds", window, x, y, width, height);
  }

  public long generation(long surface) {
    return number("generation", surface);
  }

  public void submit(long surface, long generation, long sequence, GraphicsWire.Batch batch) {
    call("submit", surface, generation, sequence, batch.encode());
  }

  public int readPixel(long surface, int x, int y) {
    return (int) number("readPixel", surface, x, y);
  }

  public String measureText(String font, int style, int size, String text) {
    return string("measureText", font, style, size, text);
  }

  public List<GraphicsWire.Event> awaitEvents(int max, long timeoutMillis) {
    return GraphicsWire.decodeEvents(string("awaitEvents", max, timeoutMillis));
  }

  public void release(long handle) {
    call("release", handle);
  }
}
