package org.espressh.bootstrap.awt;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.*;
import java.awt.image.*;
import java.awt.image.renderable.RenderableImage;
import java.nio.ByteBuffer;
import java.text.AttributedCharacterIterator;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import org.espressh.bootstrap.graphics.GraphicsBridge;
import org.espressh.bootstrap.graphics.GraphicsWire;

/** Guest Graphics2D state and values; only the caller-bound Host service renders pixels. */
final class GuestServiceGraphics extends Graphics2D {
  // Weak image keys and image-free values keep configurations stable without retaining rasters.
  private static final Map<BufferedImage, ImageConfiguration> CONFIGURATIONS = new java.util.WeakHashMap<>();
  private final BufferedImage destination;
  private final GraphicsConfiguration configuration;
  private AffineTransform transform = new AffineTransform();
  // A Java geometry value, never a SunGraphics2D/SurfaceData/native Region.
  private Area deviceClip;
  private Color color = Color.WHITE, background = Color.BLACK;
  private Paint paint = color;
  private Font font = new Font("Dialog", Font.PLAIN, 12);
  private AlphaComposite composite = AlphaComposite.SrcOver;
  private BasicStroke stroke = new BasicStroke();
  private RenderingHints hints = defaultHints();
  private XorComposite xor;
  private boolean disposed;

  GuestServiceGraphics(BufferedImage destination) {
    this(destination, null);
  }

  GuestServiceGraphics(BufferedImage destination, GraphicsConfiguration configuration) {
    requireImageFormat(Objects.requireNonNull(destination));
    this.destination = destination;
    this.configuration = configuration == null ? configuration(destination) : configuration;
  }

  private GuestServiceGraphics(GuestServiceGraphics original) {
    destination = original.destination;
    configuration = original.configuration;
    transform = new AffineTransform(original.transform);
    deviceClip = original.deviceClip == null ? null : new Area(original.deviceClip);
    color = original.color; paint = original.paint; background = original.background; font = original.font;
    composite = original.composite; stroke = original.stroke; xor = original.xor;
    hints = (RenderingHints) original.hints.clone();
  }

  private void check() {
    if (disposed) throw new IllegalStateException("AWT_GRAPHICS_DISPOSED");
  }

  private static void requireImageFormat(BufferedImage image) {
    if (!(image instanceof GuestSharedRaster)
        && image.getType() != BufferedImage.TYPE_INT_RGB
        && image.getType() != BufferedImage.TYPE_INT_ARGB)
      throw unsupported("BufferedImage format " + image.getType());
  }

  private static UnsupportedOperationException unsupported(String operation) {
    return new UnsupportedOperationException("AWT_SERVICE_GRAPHICS_UNSUPPORTED: " + operation);
  }

  private GraphicsWire.Batch state() {
    check();
    GraphicsWire.Batch batch = new GraphicsWire.Batch();
    if (!(destination instanceof GuestSharedRaster))
      batch.targetOpaque(destination.getTransparency() == Transparency.OPAQUE);
    if (deviceClip != null) path(batch, GraphicsWire.CLIP_PATH, deviceClip);
    double[] matrix = new double[6];
    transform.getMatrix(matrix);
    batch.add(GraphicsWire.TRANSFORM, "", matrix);
    batch.color(color.getRGB()).font(font.getName(), font.getStyle(), font.getSize2D());
    if (paint instanceof GradientPaint gradient) {
      Point2D first = gradient.getPoint1(), second = gradient.getPoint2();
      batch.gradient((float) first.getX(), (float) first.getY(), gradient.getColor1().getRGB(),
          (float) second.getX(), (float) second.getY(), gradient.getColor2().getRGB(), gradient.isCyclic());
    }
    batch.composite(composite.getRule(), composite.getAlpha());
    batch.stroke(stroke.getLineWidth(), stroke.getEndCap(), stroke.getLineJoin(),
        stroke.getMiterLimit(), stroke.getDashArray(), stroke.getDashPhase());
    for (Map.Entry<Object, Object> hint : hints.entrySet())
      batch.hint((RenderingHints.Key) hint.getKey(), hint.getValue());
    if (xor != null) batch.xorMode(xor.color.getRGB());
    return batch;
  }

  private static void path(GraphicsWire.Batch batch, int operation, Shape shape) {
    PathIterator iterator = Objects.requireNonNull(shape).getPathIterator(null);
    double[] coordinates = new double[6];
    double[] values = new double[64];
    int size = 0;
    while (!iterator.isDone()) {
      int type = iterator.currentSegment(coordinates);
      int count = switch (type) {
        case PathIterator.SEG_MOVETO, PathIterator.SEG_LINETO -> 2;
        case PathIterator.SEG_QUADTO -> 4;
        case PathIterator.SEG_CUBICTO -> 6;
        case PathIterator.SEG_CLOSE -> 0;
        default -> throw new IllegalArgumentException("Invalid path segment");
      };
      if (size + count + 1 > 65535) throw unsupported("path exceeds wire limit");
      if (size + count + 1 > values.length)
        values = java.util.Arrays.copyOf(values, Math.min(65535, Math.max(values.length * 2, size + count + 1)));
      values[size++] = type;
      for (int i = 0; i < count; i++) values[size++] = coordinates[i];
      iterator.next();
    }
    batch.path(operation, iterator.getWindingRule(), java.util.Arrays.copyOf(values, size));
  }

  private void render(GraphicsWire.Batch batch) {
    check();
    if (destination instanceof GuestSharedRaster shared) {
      shared.submit(batch);
      return;
    }
    // Ordinary BufferedImage remains Guest-owned. Capture direct Raster/DataBuffer writes
    // before every operation and publish Host results before that operation returns.
    synchronized (destination) {
      GraphicsBridge bridge = GuestWindowPeer.bridge();
      int width = destination.getWidth(), height = destination.getHeight();
      long handle = bridge.createImage(width, height,
          destination.getRGB(0, 0, width, height, null, 0, width));
      try {
        bridge.submit(handle, 1, 1, batch);
        byte[] encoded = Base64.getDecoder().decode(bridge.readPixels(handle, 1));
        if (encoded.length != Math.addExact(12, Math.multiplyExact(Math.multiplyExact(width, height), 4)))
          throw new IllegalStateException("Invalid Graphics Service pixel response");
        ByteBuffer values = ByteBuffer.wrap(encoded);
        if (values.getInt() != 1 || values.getInt() != width || values.getInt() != height)
          throw new IllegalStateException("Invalid Graphics Service pixel dimensions");
        int[] pixels = new int[width * height];
        values.asIntBuffer().get(pixels);
        destination.setRGB(0, 0, width, height, pixels, 0, width);
      } finally {
        bridge.release(handle);
      }
    }
  }

  @Override public Graphics create() { check(); return new GuestServiceGraphics(this); }
  @Override public void dispose() { disposed = true; }
  @Override public Color getColor() { check(); return color; }
  @Override public void setColor(Color value) { check(); if (value != null) { color = value; paint = value; } }
  @Override public Paint getPaint() { check(); return paint; }
  @Override public void setPaint(Paint value) {
    check(); if (value == null) return;
    if (!(value instanceof Color) && !(value instanceof GradientPaint))
      throw unsupported("Paint " + value.getClass().getName());
    paint = value;
    if (value instanceof Color next) color = next;
  }
  @Override public void setPaintMode() { check(); xor = null; composite = AlphaComposite.SrcOver; }
  @Override public void setXORMode(Color value) { check(); xor = new XorComposite(Objects.requireNonNull(value)); }
  @Override public Font getFont() { check(); return font; }
  @Override public void setFont(Font value) {
    check(); if (value == null) return;
    if (value.hasLayoutAttributes() || value.isTransformed())
      throw unsupported("transformed/layout Font");
    font = value;
  }
  @Override public FontMetrics getFontMetrics(Font value) { check(); return ServiceFontMetrics.forFont(value == null ? font : value); }
  @Override public Color getBackground() { check(); return background; }
  @Override public void setBackground(Color value) { check(); background = Objects.requireNonNull(value); }
  @Override public Composite getComposite() { check(); return xor == null ? composite : xor; }
  @Override public void setComposite(Composite value) {
    check();
    if (value instanceof XorComposite next) { xor = next; composite = AlphaComposite.SrcOver; return; }
    if (!(value instanceof AlphaComposite next)) throw unsupported("custom Composite");
    composite = next; xor = null;
  }
  @Override public Stroke getStroke() { check(); return stroke; }
  @Override public void setStroke(Stroke value) {
    check(); if (!(value instanceof BasicStroke next)) throw unsupported("custom Stroke");
    stroke = next;
  }
  @Override public AffineTransform getTransform() { check(); return new AffineTransform(transform); }
  @Override public void setTransform(AffineTransform value) { check(); transform = new AffineTransform(Objects.requireNonNull(value)); }
  @Override public void transform(AffineTransform value) { check(); transform.concatenate(Objects.requireNonNull(value)); }
  @Override public void translate(int x, int y) { translate((double) x, y); }
  @Override public void translate(double x, double y) { check(); transform.translate(x, y); }
  @Override public void rotate(double angle) { check(); transform.rotate(angle); }
  @Override public void rotate(double angle, double x, double y) { check(); transform.rotate(angle, x, y); }
  @Override public void scale(double x, double y) { check(); transform.scale(x, y); }
  @Override public void shear(double x, double y) { check(); transform.shear(x, y); }
  @Override public void setClip(Shape clip) {
    check(); deviceClip = clip == null ? null : new Area(transform.createTransformedShape(clip));
  }
  @Override public void setClip(int x, int y, int width, int height) { setClip(new Rectangle(x, y, width, height)); }
  @Override public void clip(Shape clip) {
    check(); Area next = new Area(transform.createTransformedShape(Objects.requireNonNull(clip)));
    if (deviceClip == null) deviceClip = next; else deviceClip.intersect(next);
  }
  @Override public void clipRect(int x, int y, int width, int height) { clip(new Rectangle(x, y, width, height)); }
  @Override public Shape getClip() {
    check(); if (deviceClip == null) return null;
    try { return transform.createInverse().createTransformedShape(deviceClip); }
    catch (NoninvertibleTransformException failure) { return null; }
  }
  @Override public Rectangle getClipBounds() { Shape clip = getClip(); return clip == null ? null : clip.getBounds(); }
  @Override public void draw(Shape shape) { GraphicsWire.Batch batch = state(); path(batch, GraphicsWire.DRAW_PATH, shape); render(batch); }
  @Override public void fill(Shape shape) { GraphicsWire.Batch batch = state(); path(batch, GraphicsWire.FILL_PATH, shape); render(batch); }
  @Override public void fillRect(int x, int y, int width, int height) { if (width > 0 && height > 0) render(state().fill(x, y, width, height)); else check(); }
  @Override public void drawRect(int x, int y, int width, int height) { if (width >= 0 && height >= 0) render(state().add(GraphicsWire.RECT, "", x, y, width, height)); else check(); }
  @Override public void clearRect(int x, int y, int width, int height) { if (width > 0 && height > 0) render(state().clear(x, y, width, height, background.getRGB())); else check(); }
  @Override public void drawLine(int x1, int y1, int x2, int y2) { render(state().add(GraphicsWire.LINE, "", x1, y1, x2, y2)); }
  @Override public void copyArea(int x, int y, int width, int height, int dx, int dy) { render(state().add(GraphicsWire.COPY, "", x, y, width, height, dx, dy)); }
  @Override public void drawRoundRect(int x, int y, int w, int h, int aw, int ah) { draw(new RoundRectangle2D.Double(x, y, w, h, aw, ah)); }
  @Override public void fillRoundRect(int x, int y, int w, int h, int aw, int ah) { fill(new RoundRectangle2D.Double(x, y, w, h, aw, ah)); }
  @Override public void drawOval(int x, int y, int w, int h) { draw(new Ellipse2D.Double(x, y, w, h)); }
  @Override public void fillOval(int x, int y, int w, int h) { fill(new Ellipse2D.Double(x, y, w, h)); }
  @Override public void drawArc(int x, int y, int w, int h, int start, int extent) { draw(new Arc2D.Double(x, y, w, h, start, extent, Arc2D.OPEN)); }
  @Override public void fillArc(int x, int y, int w, int h, int start, int extent) { fill(new Arc2D.Double(x, y, w, h, start, extent, Arc2D.PIE)); }
  private static Path2D polygon(int[] x, int[] y, int count, boolean close) {
    Objects.requireNonNull(x); Objects.requireNonNull(y);
    if (count > x.length || count > y.length) throw new ArrayIndexOutOfBoundsException(count);
    Path2D path = new Path2D.Double();
    if (count > 0) { path.moveTo(x[0], y[0]); for (int i = 1; i < count; i++) path.lineTo(x[i], y[i]); if (close) path.closePath(); }
    return path;
  }
  @Override public void drawPolyline(int[] x, int[] y, int count) { draw(polygon(x, y, count, false)); }
  @Override public void drawPolygon(int[] x, int[] y, int count) { draw(polygon(x, y, count, true)); }
  @Override public void fillPolygon(int[] x, int[] y, int count) { fill(polygon(x, y, count, true)); }
  @Override public void drawString(String text, int x, int y) { drawString(text, (float) x, y); }
  @Override public void drawString(String text, float x, float y) {
    if (!Objects.requireNonNull(text).isEmpty()) {
      render(state().text(text, x, y));
      if (destination instanceof GuestSharedRaster) GuestText.countSharedText(text);
    } else check();
  }
  @Override public void drawChars(char[] data, int offset, int length, int x, int y) { drawString(new String(data, offset, length), x, y); }
  @Override public void drawBytes(byte[] data, int offset, int length, int x, int y) { drawString(new String(data, offset, length, java.nio.charset.StandardCharsets.ISO_8859_1), x, y); }
  @Override public void drawString(AttributedCharacterIterator text, int x, int y) { drawString(text, (float) x, y); }
  @Override public void drawString(AttributedCharacterIterator text, float x, float y) {
    check(); Objects.requireNonNull(text);
    if (!text.getAllAttributeKeys().isEmpty()) throw unsupported("attributed text layout");
    StringBuilder value = new StringBuilder();
    for (char character = text.first(); character != AttributedCharacterIterator.DONE; character = text.next()) value.append(character);
    // Preserve the previously connected plain-iterator contract: TextLayout's empty
    // attribute map selects Dialog plain 12 rather than this Graphics' current font.
    GuestServiceGraphics copy = new GuestServiceGraphics(this);
    try { copy.setFont(new Font(Font.DIALOG, Font.PLAIN, 12)); copy.drawString(value.toString(), x, y); }
    finally { copy.dispose(); }
  }
  @Override public void drawGlyphVector(GlyphVector glyphs, float x, float y) { throw unsupported("GlyphVector"); }

  private boolean image(Image original, int dx1, int dy1, int dx2, int dy2,
      int sx1, int sy1, int sx2, int sy2, Color bg, ImageObserver observer) {
    check(); if (original == null) return true;
    Image source = GuestVolatileImage.source(original);
    if (!(source instanceof BufferedImage buffered)) throw unsupported("asynchronous/non-BufferedImage source");
    requireImageFormat(buffered);
    GraphicsBridge bridge = GuestWindowPeer.bridge();
    boolean borrowed = buffered instanceof GuestSharedRaster;
    long handle = borrowed ? ((GuestSharedRaster) buffered).handle() : bridge.createImage(buffered.getWidth(), buffered.getHeight(),
        buffered.getRGB(0, 0, buffered.getWidth(), buffered.getHeight(), null, 0, buffered.getWidth()));
    try {
      GraphicsWire.Batch batch = state();
      if (bg == null) batch.imageRegion(handle, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2);
      else batch.imageRegion(handle, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, bg.getRGB());
      render(batch);
      return true;
    } finally { if (!borrowed) bridge.release(handle); }
  }
  @Override public boolean drawImage(Image image, int x, int y, ImageObserver observer) { return drawImage(image, x, y, null, observer); }
  @Override public boolean drawImage(Image image, int x, int y, Color bg, ImageObserver observer) {
    if (image == null) { check(); return true; }
    Image source = GuestVolatileImage.source(image); int w = source.getWidth(observer), h = source.getHeight(observer);
    return image(source, x, y, x + w, y + h, 0, 0, w, h, bg, observer);
  }
  @Override public boolean drawImage(Image image, int x, int y, int w, int h, ImageObserver observer) { return drawImage(image, x, y, w, h, null, observer); }
  @Override public boolean drawImage(Image image, int x, int y, int w, int h, Color bg, ImageObserver observer) {
    if (image == null) { check(); return true; }
    Image source = GuestVolatileImage.source(image);
    return image(source, x, y, x + w, y + h, 0, 0, source.getWidth(observer), source.getHeight(observer), bg, observer);
  }
  @Override public boolean drawImage(Image image, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, ImageObserver observer) {
    return image(image, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, null, observer);
  }
  @Override public boolean drawImage(Image image, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, Color bg, ImageObserver observer) {
    return image(image, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, bg, observer);
  }
  @Override public boolean drawImage(Image image, AffineTransform imageTransform, ImageObserver observer) {
    check(); GuestServiceGraphics copy = new GuestServiceGraphics(this);
    try { copy.transform(imageTransform); return copy.drawImage(image, 0, 0, observer); }
    finally { copy.dispose(); }
  }
  @Override public void drawImage(BufferedImage image, BufferedImageOp operation, int x, int y) {
    if (operation != null) throw unsupported("BufferedImageOp");
    drawImage(image, x, y, null);
  }
  @Override public void drawRenderedImage(RenderedImage image, AffineTransform transform) {
    if (!(image instanceof BufferedImage buffered)) throw unsupported("non-BufferedImage RenderedImage");
    drawImage(buffered, transform, null);
  }
  @Override public void drawRenderableImage(RenderableImage image, AffineTransform transform) { throw unsupported("RenderableImage"); }
  @Override public boolean hit(Rectangle rectangle, Shape shape, boolean onStroke) {
    check(); if (onStroke) throw unsupported("hit with stroked shape");
    Area result = new Area(transform.createTransformedShape(shape));
    if (deviceClip != null) result.intersect(deviceClip);
    return result.intersects(rectangle);
  }
  @Override public GraphicsConfiguration getDeviceConfiguration() { check(); return configuration; }
  @Override public FontRenderContext getFontRenderContext() {
    check();
    Object aa = hints.get(RenderingHints.KEY_TEXT_ANTIALIASING);
    Object fm = hints.get(RenderingHints.KEY_FRACTIONALMETRICS);
    AffineTransform metricsTransform = new AffineTransform(transform.getScaleX(), transform.getShearY(), transform.getShearX(), transform.getScaleY(), 0, 0);
    return new FontRenderContext(metricsTransform,
        aa == null ? RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT : aa,
        fm == null ? RenderingHints.VALUE_FRACTIONALMETRICS_DEFAULT : fm);
  }

  @Override public void setRenderingHint(RenderingHints.Key key, Object value) {
    check(); hintKey(Objects.requireNonNull(key)); hintValue(key, value); hints.put(key, value);
  }
  @Override public Object getRenderingHint(RenderingHints.Key key) { check(); return hints.get(key); }
  @Override public void setRenderingHints(Map<?, ?> values) {
    check(); RenderingHints next = defaultHints();
    for (Map.Entry<?, ?> value : values.entrySet()) {
      RenderingHints.Key key = (RenderingHints.Key) value.getKey(); hintKey(key); hintValue(key, value.getValue()); next.put(key, value.getValue());
    }
    hints = next;
  }
  @Override public void addRenderingHints(Map<?, ?> values) {
    check(); RenderingHints next = (RenderingHints) hints.clone();
    for (Map.Entry<?, ?> value : values.entrySet()) {
      RenderingHints.Key key = (RenderingHints.Key) value.getKey(); hintKey(key); hintValue(key, value.getValue()); next.put(key, value.getValue());
    }
    hints = next;
  }
  @Override public RenderingHints getRenderingHints() { check(); return (RenderingHints) hints.clone(); }

  private static int hintKey(RenderingHints.Key key) { return GraphicsWire.hintKey(key); }
  private static int hintValue(RenderingHints.Key key, Object value) {
    return GraphicsWire.hintValue(GraphicsWire.hintKey(key), value);
  }
  private static RenderingHints defaultHints() {
    RenderingHints result = new RenderingHints(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
    result.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT);
    result.put(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
    result.put(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_DEFAULT);
    result.put(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_DEFAULT);
    result.put(RenderingHints.KEY_TEXT_LCD_CONTRAST, 140);
    return result;
  }

  private static ImageConfiguration configuration(BufferedImage image) {
    synchronized (CONFIGURATIONS) {
      ImageConfiguration configuration = CONFIGURATIONS.get(image);
      if (configuration == null) {
        configuration = new ImageConfiguration(image.getColorModel(), image.getWidth(), image.getHeight());
        CONFIGURATIONS.put(image, configuration);
      }
      return configuration;
    }
  }

  /** Guest-local value for the standard XOR mode, usable by save/restore callers. */
  private static final class XorComposite implements Composite {
    final Color color;
    XorComposite(Color color) { this.color = color; }
    public CompositeContext createContext(ColorModel source, ColorModel destination, RenderingHints hints) {
      throw unsupported("XOR CompositeContext outside service graphics");
    }
  }

  private static final class ImageConfiguration extends GraphicsConfiguration {
    private final ColorModel colorModel;
    private final int width, height;
    private final GraphicsDevice device = new GraphicsDevice() {
      public int getType() { return TYPE_IMAGE_BUFFER; }
      public String getIDstring() { return "GuestBufferedImage"; }
      public GraphicsConfiguration[] getConfigurations() { return new GraphicsConfiguration[]{ImageConfiguration.this}; }
      public GraphicsConfiguration getDefaultConfiguration() { return ImageConfiguration.this; }
    };
    ImageConfiguration(ColorModel colorModel, int width, int height) {
      this.colorModel = colorModel; this.width = width; this.height = height;
    }
    public GraphicsDevice getDevice() { return device; }
    public ColorModel getColorModel() { return colorModel; }
    public ColorModel getColorModel(int transparency) {
      return switch (transparency) {
        case Transparency.OPAQUE -> new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).getColorModel();
        case Transparency.TRANSLUCENT -> ColorModel.getRGBdefault();
        default -> throw unsupported("image transparency " + transparency);
      };
    }
    public AffineTransform getDefaultTransform() { return new AffineTransform(); }
    public AffineTransform getNormalizingTransform() { return new AffineTransform(); }
    public Rectangle getBounds() { return new Rectangle(0, 0, width, height); }
    public BufferedImage createCompatibleImage(int width, int height) { return createCompatibleImage(width, height, Transparency.OPAQUE); }
    public BufferedImage createCompatibleImage(int width, int height, int transparency) { return GuestSharedRaster.create(width, height, transparency); }
    public VolatileImage createCompatibleVolatileImage(int width, int height) {
      return createCompatibleVolatileImage(width, height, Transparency.OPAQUE);
    }
    public VolatileImage createCompatibleVolatileImage(int width, int height, int transparency) {
      getColorModel(transparency);
      return new GuestVolatileImage(this, width, height, transparency);
    }
    public VolatileImage createCompatibleVolatileImage(int width, int height, ImageCapabilities capabilities) throws AWTException {
      return createCompatibleVolatileImage(width, height, capabilities, Transparency.OPAQUE);
    }
    public VolatileImage createCompatibleVolatileImage(int width, int height, ImageCapabilities capabilities, int transparency) throws AWTException {
      if (capabilities != null && capabilities.isAccelerated()) throw new AWTException("AWT_ACCELERATED_IMAGE_UNSUPPORTED");
      return createCompatibleVolatileImage(width, height, transparency);
    }
  }
}
