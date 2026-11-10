package org.espressh.bootstrap.awt;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.text.AttributedCharacterIterator;
import org.espressh.bootstrap.graphics.*;

/** Host-mediated text; destination, graphics state and application remain Guest-local. */
public final class GuestText {
  private GuestText() {}
  // Diagnostic interval on the calling Guest thread, not an atomic frame counter.
  private static final ThreadLocal<long[]> TRACE_COUNTS = ThreadLocal.withInitial(() -> new long[3]);
  static void countSharedText(String text) {
    if (!GuestWindowPeer.TRACE) return;
    long[] counts = TRACE_COUNTS.get();
    counts[0]++; counts[1] += text.length();
    boolean spaces = true;
    for (int i=0;i<text.length();i++) if (text.charAt(i)!=' ') { spaces=false; break; }
    if (spaces) counts[2]++;
    if (counts[0]==1) {
      StringBuilder source = new StringBuilder();
      for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
        if (frame.getClassName().startsWith("com.jediterm.")) {
          if (source.length()>0) source.append(',');
          source.append(frame.getClassName()).append('.').append(frame.getMethodName());
        }
      }
      GuestWindowPeer.trace("guest-text-origin", "frames="+source);
    }
  }
  static void tracePresentation(long window) {
    if (!GuestWindowPeer.TRACE) return;
    long[] counts = TRACE_COUNTS.get();
    GuestWindowPeer.trace("guest-text-summary", "window="+window+" calls="+counts[0]
        +" utf16Units="+counts[1]+" spaceOnlyCalls="+counts[2]);
    java.util.Arrays.fill(counts,0);
  }
  public static void draw(Graphics2D g, Object image, String text, int x, int y) { render(g,image,text,x,y); }
  public static void draw(Graphics2D g, Object image, String text, float x, float y) { render(g,image,text,integer(x),integer(y)); }
  public static void draw(Graphics2D g, Object image, char[] data, int offset, int count, int x, int y) { render(g,image,new String(data,offset,count),x,y); }
  public static void draw(Graphics2D g, Object image, byte[] data, int offset, int count, int x, int y) { render(g,image,new String(data,offset,count,java.nio.charset.StandardCharsets.ISO_8859_1),x,y); }
  public static void draw(Graphics2D g, Object image, AttributedCharacterIterator text, int x, int y) { attributed(g,image,text,x,y); }
  public static void draw(Graphics2D g, Object image, AttributedCharacterIterator text, float x, float y) { attributed(g,image,text,integer(x),integer(y)); }
  private static int integer(float n) {
    if (!Float.isFinite(n) || n!=(int)n) throw unsupported("fractional coordinates");
    return (int)n;
  }
  private static void attributed(Graphics2D g, Object image, AttributedCharacterIterator text, int x, int y) {
    java.util.Objects.requireNonNull(text);
    if (!text.getAllAttributeKeys().isEmpty()) throw unsupported("attributed layout");
    StringBuilder value = new StringBuilder();
    for (char c=text.first();c!=AttributedCharacterIterator.DONE;c=text.next()) value.append(c);
    if(value.length()==0) return;
    // Pinned TextLayout's empty attribute map uses Dialog plain 12, not Graphics.getFont().
    // Preserve the caller's graphics state while applying the iterator's font.
    Graphics2D copy = (Graphics2D)g.create();
    try { copy.setFont(new Font(Font.DIALOG,Font.PLAIN,12)); render(copy,image,value.toString(),x,y); }
    finally { copy.dispose(); }
  }
  private static UnsupportedOperationException unsupported(String operation) { return new UnsupportedOperationException("AWT_TEXT_UNSUPPORTED: " + operation); }
  private static void render(Graphics2D g, Object destination, String text, int x, int y) {
    java.util.Objects.requireNonNull(text);
    if (!(destination instanceof BufferedImage image)) throw unsupported("non-raster destination");
    if (!(g.getPaint() instanceof Color color)) throw unsupported("custom Paint");
    Object antialias = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
    if (antialias != RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT && antialias != RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
      throw unsupported("text antialiasing hint");
    if (g.getRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS) == RenderingHints.VALUE_FRACTIONALMETRICS_ON)
      throw unsupported("fractional metrics");
    if (!(g.getComposite() instanceof AlphaComposite alpha) || alpha.getRule()!=AlphaComposite.SRC_OVER) throw unsupported("Composite");
    Font font = g.getFont();
    if (font.getSize2D()!=font.getSize() || font.isTransformed() || font.hasLayoutAttributes()) throw unsupported("font layout");
    Shape clip = g.getClip();
    if (clip != null && !(clip instanceof Rectangle2D)) throw unsupported("non-rectangular clip");
    Rectangle crop = new Rectangle(0,0,image.getWidth(),image.getHeight());
    if (clip != null) crop = crop.intersection(g.getTransform().createTransformedShape(clip).getBounds());
    if (crop.isEmpty() || text.isEmpty()) return;
    if (image instanceof GuestSharedRaster shared) {
      GraphicsWire.Batch batch = new GraphicsWire.Batch().color(color.getRGB())
          .font(font.getName(),font.getStyle(),font.getSize());
      double[] transform=new double[6];g.getTransform().getMatrix(transform);
      batch.add(GraphicsWire.TRANSFORM,"",transform);
      if (clip != null) {
        Rectangle2D r=(Rectangle2D)clip;
        if(r.getX()!=(int)r.getX()||r.getY()!=(int)r.getY()||r.getWidth()!=(int)r.getWidth()||r.getHeight()!=(int)r.getHeight())
          throw unsupported("fractional clip");
        batch.add(GraphicsWire.CLIP,"",r.getX(),r.getY(),r.getWidth(),r.getHeight());
      }
      batch.add(GraphicsWire.ALPHA,"",alpha.getAlpha()).text(text,x,y);
      shared.submit(batch);
      countSharedText(text);
      return;
    }
    long started = GuestWindowPeer.TRACE ? System.nanoTime() : 0;
    int w=crop.width, h=crop.height;
    if (12L+4L*w*h>GraphicsWire.MAX_BYTES) throw unsupported("raster payload limit");
    GraphicsBridge bridge = GuestWindowPeer.bridge();
    boolean packed = image.getType() == BufferedImage.TYPE_INT_ARGB || image.getType() == BufferedImage.TYPE_INT_RGB;
    int[] initial = packed
        ? (int[])image.getRaster().getDataElements(crop.x,crop.y,w,h,null)
        : image.getRGB(crop.x,crop.y,w,h,null,0,w);
    // RGB raster data has no alpha channel; the Host ARGB copy must remain opaque.
    if(image.getType() == BufferedImage.TYPE_INT_RGB)
      for(int i=0;i<initial.length;i++)initial[i]|=0xff000000;
    long copied = GuestWindowPeer.TRACE ? System.nanoTime() : 0;
    long handle = bridge.createImage(w,h,initial);
    long created = GuestWindowPeer.TRACE ? System.nanoTime() : 0;
    try {
      GraphicsWire.Batch batch = new GraphicsWire.Batch().color(color.getRGB()).font(font.getName(),font.getStyle(),font.getSize());
      double[] transform = new double[6];
      java.awt.geom.AffineTransform shifted=java.awt.geom.AffineTransform.getTranslateInstance(-crop.x,-crop.y);
      shifted.concatenate(g.getTransform());shifted.getMatrix(transform);
      batch.add(GraphicsWire.TRANSFORM,"",transform);
      if (clip != null) {
        Rectangle2D r=(Rectangle2D)clip;
        if (r.getX()!=(int)r.getX() || r.getY()!=(int)r.getY() || r.getWidth()!=(int)r.getWidth() || r.getHeight()!=(int)r.getHeight()) throw unsupported("fractional clip");
        batch.add(GraphicsWire.CLIP,"",r.getX(),r.getY(),r.getWidth(),r.getHeight());
      }
      batch.add(GraphicsWire.ALPHA,"",alpha.getAlpha()).text(text,x,y);
      bridge.submit(handle,1,1,batch);
      String payload=bridge.readPixels(handle,1);
      if (payload.length()>(GraphicsWire.MAX_BYTES+2L)/3*4) throw new IllegalStateException("GRAPHICS_MALFORMED_PAYLOAD");
      ByteBuffer bytes=ByteBuffer.wrap(java.util.Base64.getDecoder().decode(payload));
      if (bytes.remaining()!=12L+4L*w*h || bytes.getInt()!=1 || bytes.getInt()!=w || bytes.getInt()!=h) throw new IllegalStateException("GRAPHICS_MALFORMED_PAYLOAD");
      int[] pixels=new int[w*h]; bytes.asIntBuffer().get(pixels);
      if (packed) image.getRaster().setDataElements(crop.x,crop.y,w,h,pixels);
      else image.setRGB(crop.x,crop.y,w,h,pixels,0,w);
    } finally {
      try { bridge.release(handle); }
      finally {
        if (GuestWindowPeer.TRACE) GuestWindowPeer.trace("guest-text-end",
            "pixels=" + (long)w*h + " copyMs=" + (copied-started)/1_000_000
            + " createMs=" + (created-copied)/1_000_000
            + " renderReadApplyReleaseMs=" + (System.nanoTime()-created)/1_000_000
            + " elapsedMs=" + (System.nanoTime()-started)/1_000_000);
      }
    }
  }
}
