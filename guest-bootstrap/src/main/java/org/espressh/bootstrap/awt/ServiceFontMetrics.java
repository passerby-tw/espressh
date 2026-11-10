package org.espressh.bootstrap.awt;

import java.awt.Font;
import java.awt.FontMetrics;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;
import org.espressh.bootstrap.graphics.GraphicsBridge;

/** Guest FontMetrics backed exclusively by caller-bound Host Graphics Service. */
public final class ServiceFontMetrics extends FontMetrics {
  // Bounded and Guest Context-local; never shared across applications.
  private static final Map<Font, ServiceFontMetrics> CACHE = new LinkedHashMap<>(16, .75f, true);
  private final Map<String,int[]> widths = new LinkedHashMap<>(16, .75f, true);
  private static final int MAX_FONTS = 32, MAX_TEXTS = 128, MAX_TEXT_LENGTH = 64;
  private final GraphicsBridge bridge;
  private final int[] metrics;

  public ServiceFontMetrics(Font font) {
    super(Objects.requireNonNull(font));
    if (font.hasLayoutAttributes() || font.isTransformed() || font.getSize2D() != font.getSize())
      throw new UnsupportedOperationException("AWT_FONT_LAYOUT_ATTRIBUTES_NOT_CONNECTED");
    try { bridge = new GraphicsBridge(); }
    catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    metrics = measure("");
  }

  static ServiceFontMetrics forFont(Font font) {
    Objects.requireNonNull(font);
    synchronized (CACHE) {
      ServiceFontMetrics cached = CACHE.get(font);
      if (cached != null) return cached;
      ServiceFontMetrics created = new ServiceFontMetrics(font);
      CACHE.put(font, created);
      if (CACHE.size() > MAX_FONTS) CACHE.remove(CACHE.keySet().iterator().next());
      return created;
    }
  }

  private synchronized int[] measure(String text) {
    if (text.length() > MAX_TEXT_LENGTH) return request(text);
    int[] cached = widths.get(text);
    if (cached != null) return cached;
    int[] values = request(text);
    widths.put(text, values);
    if (widths.size() > MAX_TEXTS) widths.remove(widths.keySet().iterator().next());
    return values;
  }

  private int[] request(String text) {
    if (GuestWindowPeer.TRACE) GuestWindowPeer.trace("guest-font-measure", "utf16Units="+text.length());
    String[] fields = bridge.measureText(font.getName(), font.getStyle(), font.getSize(), text).split(",", -1);
    if (fields.length != 5) throw new IllegalStateException("Invalid Graphics Service font metrics");
    int[] values = new int[5];
    for (int i = 0; i < values.length; i++) values[i] = Integer.parseInt(fields[i]);
    return values;
  }

  public int getAscent() { return metrics[1]; }
  public int getDescent() { return metrics[2]; }
  public int getLeading() { return metrics[3]; }
  public int getHeight() { return metrics[4]; }
  public int stringWidth(String text) { return measure(Objects.requireNonNull(text))[0]; }
  public int charWidth(char character) { return stringWidth(String.valueOf(character)); }
  public int charWidth(int codePoint) { return stringWidth(new String(Character.toChars(codePoint))); }
  public int charsWidth(char[] data, int offset, int length) { return stringWidth(new String(data, offset, length)); }
  public int bytesWidth(byte[] data, int offset, int length) {
    return stringWidth(new String(data, offset, length, java.nio.charset.StandardCharsets.ISO_8859_1));
  }
  public int[] getWidths() {
    int[] widths = new int[256];
    for (int i = 0; i < widths.length; i++) widths[i] = charWidth(i);
    return widths;
  }
  public int getMaxAdvance() { throw unsupported(); }
  private UnsupportedOperationException unsupported() {
    return new UnsupportedOperationException("AWT_FONT_LAYOUT_METRICS_NOT_CONNECTED");
  }
  public java.awt.geom.Rectangle2D getStringBounds(String text, java.awt.Graphics graphics) { throw unsupported(); }
  public java.awt.geom.Rectangle2D getStringBounds(String text, int begin, int limit, java.awt.Graphics graphics) { throw unsupported(); }
  public java.awt.geom.Rectangle2D getStringBounds(char[] text, int begin, int limit, java.awt.Graphics graphics) { throw unsupported(); }
  public java.awt.font.LineMetrics getLineMetrics(String text, java.awt.Graphics graphics) { throw unsupported(); }
  public java.awt.font.LineMetrics getLineMetrics(String text, int begin, int limit, java.awt.Graphics graphics) { throw unsupported(); }
  public java.awt.font.LineMetrics getLineMetrics(char[] text, int begin, int limit, java.awt.Graphics graphics) { throw unsupported(); }
  public boolean hasUniformLineMetrics() { throw unsupported(); }
}
