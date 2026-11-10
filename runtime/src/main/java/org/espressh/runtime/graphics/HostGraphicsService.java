package org.espressh.runtime.graphics;

import static org.espressh.bootstrap.graphics.GraphicsWire.*;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.espressh.bootstrap.graphics.GraphicsWire;
import org.espressh.bootstrap.graphics.GraphicsWire.Event;
import org.graalvm.polyglot.HostAccess;

/** Per-execution Host backend. No Guest Value or Host GUI object crosses the boundary. */
public final class HostGraphicsService implements AutoCloseable {
  // Defensive per-request size validation, not a per-JVP memory quota.
  private static final long MAX_PIXELS = 16_777_216;
  private static final int EVENT_CAPACITY = 1024;
  private final long ownerPid;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Object eventLock = new Object();
  private final ArrayDeque<Event> events = new ArrayDeque<>();
  private boolean eventOverflow;
  // Only the Host EDT accesses resource maps, counters and Surface state.
  private final Map<Long, Surface> surfaces = new HashMap<>();
  private static final AtomicLong NEXT_HANDLE = new AtomicLong(1);
  private volatile boolean edtUsed;
  private FutureTask<Void> disposal;

  public HostGraphicsService(long ownerPid) {
    if (ownerPid < 1) throw new IllegalArgumentException("Invalid owner PID");
    this.ownerPid = ownerPid;
  }

  private static final class Surface {
    final long id;
    BufferedImage image;
    SharedPixels shared;
    boolean opaque;
    long generation = 1, sequence;
    Frame frame;
    Canvas canvas;
    HostInputMethodRequests inputMethods;

    Surface(long id, int width, int height) {
      this.id = id;
      image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }
  }

  @HostAccess.Export
  public String apiVersion() {
    return GraphicsWire.VERSION;
  }

  @HostAccess.Export
  public String defaultDisplayInfo() {
    return onEdt(() -> {
      live();
      if (GraphicsEnvironment.isHeadless())
        throw new IllegalStateException("GRAPHICS_DISPLAY_UNAVAILABLE");
      GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
          .getDefaultScreenDevice().getDefaultConfiguration();
      Rectangle b = gc.getBounds();
      AffineTransform t = gc.getDefaultTransform();
      return "1;" + b.x + ";" + b.y + ";" + b.width + ";" + b.height + ";"
          + Toolkit.getDefaultToolkit().getScreenResolution() + ";" + t.getScaleX() + ";" + t.getScaleY();
    });
  }

  @HostAccess.Export
  public String windowGeometry(long handle) {
    return onEdt(() -> {
      Surface s = window(handle);
      Rectangle b = s.frame.getBounds();
      Insets i = s.frame.getInsets();
      return "1;" + s.generation + ";" + b.x + ";" + b.y + ";" + b.width + ";" + b.height
          + ";" + s.canvas.getX() + ";" + s.canvas.getY() + ";" + s.canvas.getWidth() + ";"
          + s.canvas.getHeight() + ";" + i.top + ";" + i.left + ";" + i.bottom + ";" + i.right;
    });
  }

  @HostAccess.Export
  public String readPixels(long handle, long generation) {
    return onEdt(() -> {
      Surface s = surface(handle);
      if (s.generation != generation) throw new IllegalStateException("GRAPHICS_STALE_GENERATION");
      synchronized (s.shared == null ? s : s.shared) {
        int w = s.image.getWidth(), h = s.image.getHeight();
        long size = 12L + 4L * w * h;
        if (size > GraphicsWire.MAX_BYTES) throw new IllegalArgumentException("GRAPHICS_MALFORMED_PAYLOAD");
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate((int) size);
        bytes.putInt(1).putInt(w).putInt(h);
        for (int pixel : s.image.getRGB(0, 0, w, h, null, 0, w)) bytes.putInt(pixel);
        return java.util.Base64.getEncoder().encodeToString(bytes.array());
      }
    });
  }

  @HostAccess.Export
  public Object sharedPixels(long handle, long generation) {
    return onEdt(() -> {
      Surface s = surface(handle);
      if (s.frame != null) throw new IllegalArgumentException("GRAPHICS_INVALID_HANDLE");
      if (s.generation != generation) throw new IllegalStateException("GRAPHICS_STALE_GENERATION");
      if (s.shared == null) s.shared = new SharedPixels(s.image, closed::get);
      return s.shared;
    });
  }

  @HostAccess.Export
  public boolean requestFocus(long handle) {
    return onEdt(() -> window(handle).canvas.requestFocusInWindow());
  }

  @HostAccess.Export
  public void updateInputMethod(long handle, String payload) {
    live();
    var snapshot = org.espressh.bootstrap.graphics.GraphicsImeWire.decodeSnapshot(payload);
    onEdt(() -> {
      Surface s = window(handle);
      s.inputMethods.snapshot = snapshot;
      s.canvas.enableInputMethods(snapshot.enabled());
      return null;
    });
  }

  @HostAccess.Export
  public void endComposition(long handle) {
    onEdt(() -> {
      Surface s = window(handle);
      java.awt.im.InputContext context = s.canvas.getInputContext();
      if (context != null) context.endComposition();
      return null;
    });
  }

  @HostAccess.Export
  public long createSurface(int width, int height) {
    dimensions(width, height);
    return onEdt(
        () -> {
          live();
          Surface surface = new Surface(nextHandle(), width, height);
          surfaces.put(surface.id, surface);
          return surface.id;
        });
  }

  @HostAccess.Export
  public long createWindow(String title, int width, int height) {
    Objects.requireNonNull(title);
    dimensions(width, height);
    return onEdt(
        () -> {
          live();
          if (GraphicsEnvironment.isHeadless())
            throw new IllegalStateException("GRAPHICS_DISPLAY_UNAVAILABLE");
          Surface surface = new Surface(nextHandle(), width, height);
          Frame frame = null;
          try {
            frame = new Frame(title);
            surface.frame = frame;
            surface.canvas =
                new Canvas() {
                  @Override
                  public java.awt.im.InputMethodRequests getInputMethodRequests() {
                    return surface.inputMethods;
                  }
                  @Override
                  public void paint(Graphics graphics) {
                    graphics.drawImage(surface.image, 0, 0, null);
                  }

                  @Override
                  public void update(Graphics graphics) {
                    paint(graphics);
                  }
                };
            surface.inputMethods = new HostInputMethodRequests(surface.canvas);
            surface.canvas.enableInputMethods(false);
            surface.canvas.setPreferredSize(new Dimension(width, height));
            frame.add(surface.canvas);
            frame.pack();
            installEvents(surface);
            surfaces.put(surface.id, surface);
            return surface.id;
          } catch (RuntimeException | Error failure) {
            if (frame != null) frame.dispose();
            throw failure;
          }
        });
  }

  @HostAccess.Export
  public void setVisible(long handle, boolean visible) {
    onEdt(
        () -> {
          Surface s = window(handle);
          s.frame.setVisible(visible);
          if (visible) s.canvas.requestFocusInWindow();
          return null;
        });
  }

  @HostAccess.Export
  public void setTitle(long handle, String title) {
    Objects.requireNonNull(title);
    onEdt(
        () -> {
          window(handle).frame.setTitle(title);
          return null;
        });
  }

  /** width/height are client logical units, x/y are outer-window screen coordinates. */
  @HostAccess.Export
  public void setBounds(long handle, int x, int y, int width, int height) {
    dimensions(width, height);
    onEdt(
        () -> {
          Surface s = window(handle);
          s.canvas.setPreferredSize(new Dimension(width, height));
          s.frame.pack();
          s.frame.setLocation(x, y);
          resize(s, width, height);
          return null;
        });
  }

  @HostAccess.Export
  public long generation(long handle) {
    return onEdt(() -> surface(handle).generation);
  }

  @HostAccess.Export
  public void submit(long handle, long generation, long sequence, String payload) {
    live();
    List<Command> commands = GraphicsWire.decodeCommands(payload);
    onEdt(
        () -> {
          Surface s = surface(handle);
          if (generation != s.generation)
            throw new IllegalStateException("GRAPHICS_STALE_GENERATION");
          if (sequence <= s.sequence) throw new IllegalStateException("GRAPHICS_STALE_SEQUENCE");
          validate(commands);
          boolean opaque = s.opaque;
          for (int i = 0; i < commands.size(); i++) {
            Command c = commands.get(i);
            if (c.op() == TARGET_OPAQUE) {
              if (i != 0 || sequence != 1 || s.sequence != 0 || s.shared != null || s.frame != null)
                throw new IllegalArgumentException("GRAPHICS_TARGET_FORMAT_ALREADY_PUBLISHED");
              opaque = c.numbers()[0] == 1;
            }
          }
          if (commands.size() == 1 && commands.get(0).op() == TARGET_OPAQUE) {
            // Format-only selection preserves the original storage before a view is published.
            if (opaque) s.image = opaqueView(s.image);
            s.opaque = opaque;
            s.sequence = sequence;
            return null;
          }
          if (s.shared != null) {
            synchronized (s.shared) { render(s, s.image, commands); }
            s.sequence = sequence;
            return null;
          }
          // Render into a private staging image; publication is all-or-nothing on the EDT.
          BufferedImage next =
              new BufferedImage(
                  s.image.getWidth(), s.image.getHeight(),
                  opaque ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
          Graphics2D initial = next.createGraphics();
          initial.setComposite(AlphaComposite.Src);
          initial.drawImage(s.image, 0, 0, null);
          initial.dispose();
          render(s, next, commands);
          s.image = next;
          s.opaque = opaque;
          s.sequence = sequence;
          if (s.canvas != null) s.canvas.repaint();
          return null;
        });
  }

  private void validate(List<Command> commands) {
    int depth = 0;
    AffineTransform transform = new AffineTransform();
    ArrayDeque<AffineTransform> transforms = new ArrayDeque<>();
    for (Command c : commands) {
      double[] n = c.numbers();
      int expected =
          switch (c.op()) {
            case COLOR, ALPHA, XOR_MODE, TARGET_OPAQUE -> 1;
            case FONT, TEXT, COMPOSITE, HINT -> 2;
            case IMAGE -> 3;
            case FILL, RECT, LINE, CLIP -> 4;
            case CLEAR -> 5;
            case COPY, TRANSFORM -> 6;
            case GRADIENT_PAINT -> 7;
            case SAVE, RESTORE, PAINT_MODE -> 0;
            case DRAW_PATH, FILL_PATH, CLIP_PATH, STROKE, IMAGE_REGION -> n.length;
            default ->
                throw new IllegalArgumentException("GRAPHICS_UNSUPPORTED_COMMAND: " + c.op());
          };
      if (n.length != expected) throw new IllegalArgumentException("Invalid graphics operands");
      if (c.op() != TRANSFORM && c.op() != ALPHA && c.op() != FONT && c.op() != TEXT
          && c.op() != DRAW_PATH && c.op() != FILL_PATH && c.op() != CLIP_PATH
          && c.op() != STROKE && c.op() != COMPOSITE && c.op() != GRADIENT_PAINT) {
        for (int i = 0; i < n.length; i++) {
          if ((c.op() == IMAGE || c.op() == IMAGE_REGION) && i == 0) {
            if (n[i] != (long) n[i] || n[i] < 1)
              throw new IllegalArgumentException("Invalid image handle");
          } else if (n[i] != (int) n[i])
            throw new IllegalArgumentException("Invalid integer operand");
        }
      }
      if (c.op() == FONT) {
        integer(n[0]);
        font(c.text(), (int) n[0], finiteFloat(n[1]));
      }
      if (c.op() == TEXT) { finiteFloat(n[0]); finiteFloat(n[1]); }
      if (c.op() == ALPHA && (n[0] < 0 || n[0] > 1))
        throw new IllegalArgumentException("Invalid alpha");
      if (c.op() == IMAGE_REGION && n.length != 9 && n.length != 10)
        throw new IllegalArgumentException("Invalid image region operands");
      if (c.op() == IMAGE || c.op() == IMAGE_REGION) surface((long) n[0]);
      if (c.op() == DRAW_PATH || c.op() == FILL_PATH || c.op() == CLIP_PATH) path(n);
      if (c.op() == STROKE) stroke(n);
      if (c.op() == COMPOSITE) {
        integer(n[0]);
        AlphaComposite.getInstance((int)n[0], finiteFloat(n[1]));
      }
      if (c.op() == HINT) GraphicsWire.hintValue((int)n[0], (int)n[1]);
      if (c.op() == GRADIENT_PAINT) gradient(n);
      if (c.op() == TARGET_OPAQUE && n[0] != 0 && n[0] != 1)
        throw new IllegalArgumentException("Invalid target format flag");
      if (c.op() == TRANSFORM) transform = new AffineTransform(n);
      if (c.op() == COPY && n[2] > 0 && n[3] > 0
          && (transform.getShearX() != 0 || transform.getShearY() != 0))
        throw new IllegalArgumentException("GRAPHICS_UNSUPPORTED_COPY_TRANSFORM");
      if (c.op() == SAVE && ++depth > 128)
        throw new IllegalArgumentException("Graphics state nesting too deep");
      if (c.op() == SAVE) transforms.push(transform);
      if (c.op() == RESTORE && --depth < 0)
        throw new IllegalArgumentException("Graphics restore without save");
      if (c.op() == RESTORE) transform = transforms.pop();
    }
    if (depth != 0) throw new IllegalArgumentException("Unbalanced graphics state");
  }

  private static BufferedImage opaqueView(BufferedImage image) {
    java.awt.image.WritableRaster raster = image.getRaster().createWritableChild(
        0, 0, image.getWidth(), image.getHeight(), 0, 0, new int[]{0, 1, 2});
    return new BufferedImage(new java.awt.image.DirectColorModel(
        24, 0xff0000, 0xff00, 0xff), raster, false, null);
  }

  private static void integer(double value) {
    if (value != (int)value) throw new IllegalArgumentException("Invalid integer operand");
  }

  private static float finiteFloat(double value) {
    float result = (float)value;
    if (!Float.isFinite(result)) throw new IllegalArgumentException("Invalid float operand");
    return result;
  }

  private static BasicStroke stroke(double[] n) {
    if (n.length < 5) throw new IllegalArgumentException("Invalid stroke operands");
    integer(n[1]); integer(n[2]);
    float[] dash = n.length == 5 ? null : new float[n.length - 5];
    if (dash != null) for (int i = 0; i < dash.length; i++) dash[i] = finiteFloat(n[i + 5]);
    return new BasicStroke(finiteFloat(n[0]), (int)n[1], (int)n[2], finiteFloat(n[3]), dash,
        finiteFloat(n[4]));
  }

  private static GradientPaint gradient(double[] n) {
    integer(n[2]); integer(n[5]);
    if (n[6] != 0 && n[6] != 1) throw new IllegalArgumentException("Invalid gradient cyclic flag");
    return new GradientPaint(finiteFloat(n[0]), finiteFloat(n[1]), new Color((int)n[2], true),
        finiteFloat(n[3]), finiteFloat(n[4]), new Color((int)n[5], true), n[6] == 1);
  }

  private static Path2D.Double path(double[] n) {
    if (n.length < 1 || (n[0] != Path2D.WIND_EVEN_ODD && n[0] != Path2D.WIND_NON_ZERO))
      throw new IllegalArgumentException("Invalid path winding rule");
    Path2D.Double result = new Path2D.Double((int)n[0]);
    boolean moved = false;
    for (int i = 1; i < n.length;) {
      integer(n[i]);
      int type = (int)n[i++];
      int count = switch (type) {
        case 0, 1 -> 2;
        case 2 -> 4;
        case 3 -> 6;
        case 4 -> 0;
        default -> throw new IllegalArgumentException("Invalid path segment");
      };
      if (i + count > n.length || (type != 0 && !moved))
        throw new IllegalArgumentException("Invalid path coordinates");
      switch (type) {
        case 0 -> { result.moveTo(n[i], n[i + 1]); moved = true; }
        case 1 -> result.lineTo(n[i], n[i + 1]);
        case 2 -> result.quadTo(n[i], n[i + 1], n[i + 2], n[i + 3]);
        case 3 -> result.curveTo(n[i], n[i + 1], n[i + 2], n[i + 3], n[i + 4], n[i + 5]);
        case 4 -> result.closePath();
      }
      i += count;
    }
    return result;
  }

  private void render(Surface destination, BufferedImage image, List<Command> commands) {
    Graphics2D g = image.createGraphics();
    ArrayDeque<Graphics2D> stack = new ArrayDeque<>();
    try {
      g.setColor(Color.BLACK);
      g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
      g.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      for (Command c : commands) {
        double[] n = c.numbers();
        switch (c.op()) {
          case TARGET_OPAQUE -> { /* Validated destination format was selected before rendering. */ }
          case COLOR -> g.setColor(new Color((int) n[0], true));
          case FONT -> g.setFont(font(c.text(), (int) n[0], (float) n[1]));
          case FILL -> g.fillRect((int) n[0], (int) n[1], (int) n[2], (int) n[3]);
          case RECT -> g.drawRect((int) n[0], (int) n[1], (int) n[2], (int) n[3]);
          case LINE -> g.drawLine((int) n[0], (int) n[1], (int) n[2], (int) n[3]);
          case TEXT -> g.drawString(c.text(), (float) n[0], (float) n[1]);
          case CLIP -> g.clipRect((int) n[0], (int) n[1], (int) n[2], (int) n[3]);
          case TRANSFORM -> g.setTransform(new AffineTransform(n));
          case ALPHA ->
              g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) n[0]));
          case DRAW_PATH -> g.draw(path(n));
          case FILL_PATH -> g.fill(path(n));
          case CLIP_PATH -> g.clip(path(n));
          case STROKE -> g.setStroke(stroke(n));
          case COMPOSITE -> g.setComposite(AlphaComposite.getInstance((int)n[0], (float)n[1]));
          case HINT -> g.setRenderingHint(GraphicsWire.hintKey((int)n[0]),
              GraphicsWire.hintValue((int)n[0], (int)n[1]));
          case XOR_MODE -> g.setXORMode(new Color((int)n[0], true));
          case PAINT_MODE -> g.setPaintMode();
          case GRADIENT_PAINT -> g.setPaint(gradient(n));
          case CLEAR -> {
            g.setBackground(new Color((int)n[4], true));
            g.clearRect((int)n[0], (int)n[1], (int)n[2], (int)n[3]);
          }
          case SAVE -> {
            stack.push(g);
            g = (Graphics2D) g.create();
          }
          case RESTORE -> {
            g.dispose();
            g = stack.pop();
          }
          case IMAGE -> {
            Surface source = surface((long) n[0]);
            BufferedImage sourceImage = source == destination ? image : source.image;
            if (source.shared == null) g.drawImage(sourceImage, (int)n[1], (int)n[2], null);
            else synchronized (source.shared) { g.drawImage(sourceImage, (int)n[1], (int)n[2], null); }
          }
          case IMAGE_REGION -> {
            Surface source = surface((long)n[0]);
            BufferedImage sourceImage = source == destination ? image : source.image;
            synchronized (source.shared == null ? source : source.shared) {
              if (n.length == 10)
                g.drawImage(sourceImage, (int)n[1], (int)n[2], (int)n[3], (int)n[4],
                    (int)n[5], (int)n[6], (int)n[7], (int)n[8], new Color((int)n[9], true), null);
              else
                g.drawImage(sourceImage, (int)n[1], (int)n[2], (int)n[3], (int)n[4],
                    (int)n[5], (int)n[6], (int)n[7], (int)n[8], null);
            }
          }
          case COPY ->
              g.copyArea((int) n[0], (int) n[1], (int) n[2], (int) n[3], (int) n[4], (int) n[5]);
          default -> throw new AssertionError("Command was not validated");
        }
      }
    } finally {
      g.dispose();
      while (!stack.isEmpty()) stack.pop().dispose();
    }
  }

  @HostAccess.Export
  public int readPixel(long handle, int x, int y) {
    return onEdt(() -> {
      Surface s = surface(handle);
      synchronized (s.shared == null ? s : s.shared) { return s.image.getRGB(x, y); }
    });
  }

  @HostAccess.Export
  public long createImage(int width, int height, String argbPixels) {
    dimensions(width, height);
    long expected = 4L * width * height;
    if (expected > GraphicsWire.MAX_BYTES || argbPixels.length() > (expected + 2) / 3 * 4)
      throw new IllegalArgumentException("Image payload too large");
    byte[] bytes = java.util.Base64.getDecoder().decode(argbPixels);
    if (bytes.length != expected)
      throw new IllegalArgumentException("Image payload length mismatch");
    return onEdt(
        () -> {
          live();
          Surface s = new Surface(nextHandle(), width, height);
          java.nio.IntBuffer pixels = java.nio.ByteBuffer.wrap(bytes).asIntBuffer();
          int[] rgb = new int[width * height];
          pixels.get(rgb);
          s.image.setRGB(0, 0, width, height, rgb, 0, width);
          surfaces.put(s.id, s);
          return s.id;
        });
  }

  /** width, ascent, descent, leading, height; integer pixel metrics, same Host font renderer. */
  @HostAccess.Export
  public String measureText(String name, int style, int size, String text) {
    Objects.requireNonNull(text);
    Font font = font(name, style, size);
    return onEdt(
        () -> {
          live();
          BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
          Graphics2D g = image.createGraphics();
          try {
            g.setFont(font);
            g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            FontMetrics m = g.getFontMetrics();
            return m.stringWidth(text)
                + ","
                + m.getAscent()
                + ","
                + m.getDescent()
                + ","
                + m.getLeading()
                + ","
                + m.getHeight();
          } finally {
            g.dispose();
          }
        });
  }

  private static Font font(String name, int style, float size) {
    Objects.requireNonNull(name);
    if (style < 0 || style > 3 || !Float.isFinite(size) || size < 1 || size > 512)
      throw new IllegalArgumentException("Invalid font");
    return new Font(name, style, 1).deriveFont(size);
  }

  @HostAccess.Export
  public void release(long handle) {
    onEdt(
        () -> {
          liveResource();
          Surface s = surfaces.get(handle);
          if (s == null) throw new IllegalArgumentException("GRAPHICS_INVALID_HANDLE");
          surfaces.remove(handle);
          if (s.shared != null) s.shared.revoke();
          if (s.frame != null) {
            s.frame.dispose();
            event(s, DISPOSED, 0, 0, 0, "");
          }
          return null;
        });
  }

  @HostAccess.Export
  public String awaitEvents(int maxEvents, long timeoutMillis) throws InterruptedException {
    if (EventQueue.isDispatchThread())
      throw new IllegalStateException("Cannot await events on Host EDT");
    if (maxEvents < 1 || maxEvents > EVENT_CAPACITY || timeoutMillis < 0 || timeoutMillis > 60000)
      throw new IllegalArgumentException("Invalid event wait");
    long deadline = System.nanoTime() + timeoutMillis * 1_000_000;
    List<Event> batch = new ArrayList<>();
    synchronized (eventLock) {
      while (events.isEmpty() && !closed.get() && !eventOverflow && timeoutMillis > 0) {
        long left = deadline - System.nanoTime();
        if (left <= 0) break;
        eventLock.wait(left / 1_000_000, (int) (left % 1_000_000));
      }
      if (eventOverflow) throw new IllegalStateException("GRAPHICS_EVENT_OVERFLOW");
      long bytes = 8;
      while (!events.isEmpty() && batch.size() < maxEvents) {
        Event next = events.peekFirst();
        long size = 42L + modifiedUtfSize(next.text());
        if (bytes + size > GraphicsWire.MAX_BYTES) break;
        bytes += size;
        batch.add(events.removeFirst());
      }
      if (batch.isEmpty() && closed.get())
        throw new IllegalStateException("GRAPHICS_SERVICE_CLOSED");
    }
    if (InputTrace.ENABLED) for (Event e : batch)
      InputTrace.log("host-dequeue", "window=" + e.window() + " generation=" + e.generation()
          + " type=" + e.type() + " eventNs=" + e.timestampNanos());
    return GraphicsWire.encodeEvents(batch);
  }

  private static int modifiedUtfSize(String text) {
    int bytes = 0;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      bytes += ch >= 1 && ch <= 127 ? 1 : ch <= 2047 ? 2 : 3;
      if (bytes > 65535) throw new IllegalArgumentException("GRAPHICS_MALFORMED_PAYLOAD");
    }
    return bytes;
  }

  private void event(Surface s, int type, int a, int b, int modifiers, String text) {
    synchronized (eventLock) {
      if (closed.get() || eventOverflow) return;
      Event e = new Event(type, s.id, s.generation, a, b, modifiers, text, System.nanoTime());
      Event last = events.peekLast();
      if (last != null
          && last.window() == s.id
          && last.type() == type
          && (type == MOUSE_MOVE || type == RESIZE || type == REPAINT)) events.removeLast();
      if (events.size() == EVENT_CAPACITY) {
        // Fail explicitly, rather than silently losing key-up/close/text input.
        eventOverflow = true;
      } else {
        events.addLast(e);
        if (InputTrace.ENABLED) InputTrace.log("host-enqueue", "window=" + e.window()
            + " generation=" + e.generation() + " type=" + type + " eventNs=" + e.timestampNanos());
      }
      eventLock.notifyAll();
    }
  }

  private void installEvents(Surface s) {
    s.frame.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            if (!closed.get() && surfaces.get(s.id) == s) event(s, CLOSE_REQUEST, 0, 0, 0, "");
          }
        });
    new GraphicsInputAdapter(
            () -> !closed.get() && surfaces.get(s.id) == s,
            (type, arg1, arg2, modifiers, text) -> event(s, type, arg1, arg2, modifiers, text))
        .install(s.canvas);
    s.canvas.addComponentListener(
        new ComponentAdapter() {
          @Override
          public void componentResized(ComponentEvent e) {
            if (!closed.get() && surfaces.containsKey(s.id))
              resize(s, s.canvas.getWidth(), s.canvas.getHeight());
          }

          @Override
          public void componentShown(ComponentEvent e) {
            if (!closed.get() && surfaces.get(s.id) == s) event(s, REPAINT, 0, 0, 0, "");
          }
        });
  }

  private void resize(Surface s, int width, int height) {
    if (width < 1 || height < 1 || ((long) width) * height > MAX_PIXELS) return;
    if (width == s.image.getWidth() && height == s.image.getHeight()) return;
    BufferedImage next = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = next.createGraphics();
    try {
      g.drawImage(s.image, 0, 0, null);
    } finally {
      g.dispose();
    }
    if (s.shared != null) { s.shared.revoke(); s.shared = null; }
    s.image = next;
    s.generation++;
    s.sequence = 0;
    event(s, RESIZE, width, height, 0, "");
  }

  private static void dimensions(int width, int height) {
    if (width < 1 || height < 1 || ((long) width) * height > MAX_PIXELS)
      throw new IllegalArgumentException("Invalid surface dimensions");
  }

  private static long nextHandle() {
    long value = NEXT_HANDLE.getAndIncrement();
    // IMAGE command operands use IEEE-754 doubles in experimental-1.
    if (value < 1 || value > 9_007_199_254_740_991L)
      throw new IllegalStateException("Graphics handle range exhausted");
    return value;
  }

  private void liveResource() {
    if (closed.get()) throw new IllegalStateException("GRAPHICS_SERVICE_CLOSED");
  }

  private void live() {
    liveResource();
    synchronized (eventLock) {
      if (eventOverflow) throw new IllegalStateException("GRAPHICS_EVENT_OVERFLOW");
    }
  }

  private Surface surface(long handle) {
    live();
    Surface result = surfaces.get(handle);
    if (result == null) throw new IllegalArgumentException("GRAPHICS_INVALID_HANDLE");
    return result;
  }

  private Surface window(long handle) {
    Surface s = surface(handle);
    if (s.frame == null) throw new IllegalArgumentException("Not a window handle");
    return s;
  }

  private <T> T onEdt(Callable<T> action) {
    edtUsed = true;
    FutureTask<T> task = new FutureTask<>(action);
    if (EventQueue.isDispatchThread()) task.run();
    else EventQueue.invokeLater(task);
    boolean interrupted = false;
    try {
      for (; ; ) {
        try {
          return task.get(100, TimeUnit.MILLISECONDS);
        } catch (TimeoutException pending) {
          if (closed.get()) {
            task.cancel(false);
            throw new IllegalStateException("GRAPHICS_SERVICE_CLOSED");
          }
        } catch (InterruptedException pending) {
          interrupted = true;
        } catch (ExecutionException failure) {
          Throwable cause = failure.getCause();
          if (cause instanceof RuntimeException runtime) throw runtime;
          if (cause instanceof Error error) throw error;
          throw new IllegalStateException("Host graphics operation failed", cause);
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  /** Host lifecycle only: revoke access and queue disposal without waiting for the EDT. */
  public synchronized void beginClose() {
    closed.set(true);
    synchronized (eventLock) {
      events.clear();
      eventLock.notifyAll();
    }
    if (disposal != null) return;
    disposal =
        new FutureTask<>(
            () -> {
              Throwable failure = null;
              for (Surface s : surfaces.values()) {
                if (s.shared != null) s.shared.revoke();
                try {
                  if (s.frame != null) s.frame.dispose();
                } catch (RuntimeException | Error e) {
                  if (failure == null) failure = e;
                  else failure.addSuppressed(e);
                }
              }
              if (failure instanceof RuntimeException e) throw e;
              if (failure instanceof Error e) throw e;
              surfaces.clear();
              return null;
            });
    if (!edtUsed || EventQueue.isDispatchThread()) disposal.run();
    else EventQueue.invokeLater(disposal);
  }

  @Override
  public void close() {
    beginClose();
    boolean interrupted = false;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    try {
      for (; ; ) {
        try {
          disposal.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
          return;
        } catch (InterruptedException e) {
          interrupted = true;
        } catch (TimeoutException e) {
          throw new IllegalStateException("GRAPHICS_CLEANUP_UNCONFIRMED", e);
        } catch (ExecutionException e) {
          throw new IllegalStateException("GRAPHICS_CLEANUP_FAILED", e.getCause());
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  /** Host diagnostics only; not exported to Guest. */
  public int resourceCount() {
    if (disposal != null && disposal.isDone() && !disposal.isCancelled()) {
      try {
        disposal.get();
        return 0;
      } catch (Exception e) {
        throw new IllegalStateException("GRAPHICS_CLEANUP_FAILED", e);
      }
    }
    return edtUsed ? onEdt(() -> surfaces.size()) : 0;
  }

  public long ownerPid() {
    return ownerPid;
  }
}
