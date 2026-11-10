package org.espressh.bootstrap.awt;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Locale;

/** Pinned Guest factories; supported platform operations use caller-bound copied data. */
public final class AwtFactories {
  static { requireSupportedRuntime(); }
  private AwtFactories() {}

  public static GraphicsEnvironment createGE() {
    return new Environment();
  }

  public static Toolkit createToolkit(EventQueue queue) {
    return new GuestToolkit(queue);
  }
  public static FontMetrics fontMetrics(Font font) { return ServiceFontMetrics.forFont(font); }
  public static Object focusPeer() throws ClassNotFoundException { return GuestFocusPeer.create(); }
  public static Font graphicsFont() { return new Font("Dialog", Font.PLAIN, 12); }
  public static Graphics2D imageGraphics(BufferedImage image) { return new GuestServiceGraphics(image); }
  public static Color graphicsForeground() { return Color.WHITE; }
  public static Color graphicsBackground() { return Color.BLACK; }
  public static Object componentFactory() throws ClassNotFoundException { return GuestComponentFactory.create(); }
  public static java.awt.im.InputContext inputContext() { return new GuestInputContext(); }
  private static void requireSupportedRuntime() {
    String architecture = System.getProperty("os.arch", "");
    if (!System.getProperty("os.name", "").equals("Linux")
        || !System.getProperty("java.version", "").equals("25.0.4.1.1")
        || !(architecture.equals("amd64") || architecture.equals("x86_64")))
      throw new UnsupportedOperationException("AWT_ADAPTER_UNSUPPORTED_RUNTIME: requires Linux x86_64 Guest JDK 25.0.4.1.1; Guest JDK 21 GUI is UNVERIFIED");
  }

  private static UnsupportedOperationException unsupported() {
    return new UnsupportedOperationException("AWT_FONT_ENUMERATION_NOT_CONNECTED");
  }

  public static final class Environment extends GraphicsEnvironment {
    private GuestDisplay display;
    private synchronized GuestDisplay display() {
      if (display == null) display = new GuestDisplay();
      return display;
    }
    public GraphicsDevice[] getScreenDevices() { return new GraphicsDevice[]{display()}; }
    public GraphicsDevice getDefaultScreenDevice() { return display(); }
    public Graphics2D createGraphics(BufferedImage image) { return imageGraphics(image); }
    public Font[] getAllFonts() { throw unsupported(); }
    public String[] getAvailableFontFamilyNames() { throw unsupported(); }
    public String[] getAvailableFontFamilyNames(Locale locale) { throw unsupported(); }
  }
}
