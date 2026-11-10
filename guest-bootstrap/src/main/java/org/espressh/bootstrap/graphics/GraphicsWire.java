package org.espressh.bootstrap.graphics;

import java.awt.RenderingHints;
import java.io.*;
import java.util.*;

/** Internal adapter/backend wire format. Not an application-facing graphics API. */
public final class GraphicsWire {
  public static final String VERSION = "experimental-1";
  public static final int MAX_BYTES = 8 * 1024 * 1024;
  public static final int MAX_COMMANDS = 10000;
  public static final int MAX_OPERANDS = 65536;
  public static final int COLOR = 1,
      FONT = 2,
      FILL = 3,
      RECT = 4,
      LINE = 5,
      TEXT = 6,
      CLIP = 7,
      TRANSFORM = 8,
      SAVE = 9,
      RESTORE = 10,
      IMAGE = 11,
      COPY = 12,
      ALPHA = 13,
      DRAW_PATH = 14,
      FILL_PATH = 15,
      CLIP_PATH = 16,
      STROKE = 17,
      COMPOSITE = 18,
      HINT = 19,
      XOR_MODE = 20,
      PAINT_MODE = 21,
      IMAGE_REGION = 22,
      CLEAR = 23,
      GRADIENT_PAINT = 24,
      TARGET_OPAQUE = 25;
  public static final int KEY_DOWN = 1,
      KEY_UP = 2,
      TEXT_INPUT = 3,
      MOUSE_DOWN = 4,
      MOUSE_UP = 5,
      MOUSE_MOVE = 6,
      MOUSE_WHEEL = 7,
      FOCUS_IN = 8,
      FOCUS_OUT = 9,
      RESIZE = 10,
      REPAINT = 11,
      CLOSE_REQUEST = 12,
      DISPOSED = 13,
      IME_TEXT_CHANGED = 14,
      IME_CARET_CHANGED = 15;

  private GraphicsWire() {}

  public record Command(int op, double[] numbers, String text) {
    public Command {
      if (numbers.length > MAX_OPERANDS)
        throw new IllegalArgumentException("Too many graphics operands");
      numbers = numbers.clone();
      text = Objects.requireNonNull(text);
    }

    @Override
    public double[] numbers() {
      return numbers.clone();
    }
  }

  public record Event(
      int type,
      long window,
      long generation,
      int arg1,
      int arg2,
      int modifiers,
      String text,
      long timestampNanos) {}

  public static final class Batch {
    private final List<Command> commands = new ArrayList<>();

    public Batch add(int op, String text, double... values) {
      if (commands.size() >= MAX_COMMANDS) throw new IllegalArgumentException("Too many commands");
      commands.add(new Command(op, values, text));
      return this;
    }

    public Batch color(int argb) {
      return add(COLOR, "", argb);
    }

    public Batch font(String name, int style, int size) {
      return add(FONT, name, style, size);
    }

    public Batch font(String name, int style, float size) {
      return add(FONT, name, style, size);
    }

    public Batch fill(int x, int y, int w, int h) {
      return add(FILL, "", x, y, w, h);
    }

    public Batch text(String text, int x, int baseline) {
      return add(TEXT, text, x, baseline);
    }

    public Batch text(String text, float x, float baseline) {
      return add(TEXT, text, x, baseline);
    }

    public Batch image(long handle, int x, int y) {
      return add(IMAGE, "", handle, x, y);
    }

    /** Eight destination/source corners, optionally followed by a background ARGB. */
    public Batch imageRegion(long handle, int... coordinates) {
      if (coordinates.length != 8 && coordinates.length != 9)
        throw new IllegalArgumentException("Invalid image region operands");
      double[] values = new double[coordinates.length + 1];
      values[0] = handle;
      for (int i = 0; i < coordinates.length; i++) values[i + 1] = coordinates[i];
      return add(IMAGE_REGION, "", values);
    }

    /** Winding rule followed by PathIterator segment tags and their coordinates. */
    public Batch path(int op, int windingRule, double... segments) {
      if (op != DRAW_PATH && op != FILL_PATH && op != CLIP_PATH)
        throw new IllegalArgumentException("Invalid path operation");
      if (segments.length >= MAX_OPERANDS)
        throw new IllegalArgumentException("Too many path operands");
      double[] values = new double[segments.length + 1];
      values[0] = windingRule;
      System.arraycopy(segments, 0, values, 1, segments.length);
      return add(op, "", values);
    }

    public Batch stroke(float width, int cap, int join, float miter, float[] dash, float phase) {
      int count = dash == null ? 0 : dash.length;
      if (count > MAX_OPERANDS - 5) throw new IllegalArgumentException("Too many dash operands");
      double[] values = new double[5 + count];
      values[0] = width;
      values[1] = cap;
      values[2] = join;
      values[3] = miter;
      values[4] = phase;
      if (dash != null) for (int i = 0; i < count; i++) values[i + 5] = dash[i];
      return add(STROKE, "", values);
    }

    public Batch composite(int rule, float alpha) {
      return add(COMPOSITE, "", rule, alpha);
    }

    public Batch hint(int key, int value) { return add(HINT, "", key, value); }

    public Batch hint(RenderingHints.Key key, Object value) {
      int id = hintKey(key);
      return hint(id, hintValue(id, value));
    }

    public Batch xorMode(int argb) { return add(XOR_MODE, "", argb); }

    public Batch paintMode() { return add(PAINT_MODE, ""); }

    public Batch clear(int x, int y, int width, int height, int backgroundArgb) {
      return add(CLEAR, "", x, y, width, height, backgroundArgb);
    }

    public Batch gradient(float x1, float y1, int color1, float x2, float y2, int color2,
        boolean cyclic) {
      return add(GRADIENT_PAINT, "", x1, y1, color1, x2, y2, color2, cyclic ? 1 : 0);
    }

    /** Initial surface format selection; must precede publication of any shared pixel view. */
    public Batch targetOpaque() { return targetOpaque(true); }

    public Batch targetOpaque(boolean opaque) { return add(TARGET_OPAQUE, "", opaque ? 1 : 0); }

    public String encode() {
      return encodeData(
          out -> {
            out.writeInt(1);
            out.writeInt(commands.size());
            for (Command c : commands) {
              out.writeInt(c.op());
              out.writeInt(c.numbers.length);
              for (double n : c.numbers) out.writeDouble(n);
              out.writeUTF(c.text());
            }
          });
    }
  }

  public static List<Command> decodeCommands(String payload) {
    try (DataInputStream in = input(payload)) {
      if (in.readInt() != 1) throw new IllegalArgumentException("Graphics wire version mismatch");
      int count = in.readInt();
      if (count < 0 || count > MAX_COMMANDS)
        throw new IllegalArgumentException("Invalid command count");
      List<Command> result = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        int op = in.readInt(), size = in.readInt();
        if (size < 0 || size > MAX_OPERANDS)
          throw new IllegalArgumentException("Invalid operand count");
        double[] n = new double[size];
        for (int j = 0; j < size; j++) {
          n[j] = in.readDouble();
          if (!Double.isFinite(n[j])) throw new IllegalArgumentException("Nonfinite operand");
        }
        result.add(new Command(op, n, in.readUTF()));
      }
      if (in.read() != -1) throw new IllegalArgumentException("Trailing graphics data");
      return List.copyOf(result);
    } catch (IOException ex) {
      throw new IllegalArgumentException("Malformed graphics batch", ex);
    }
  }

  private static final RenderingHints.Key[] HINT_KEYS = {
    RenderingHints.KEY_ANTIALIASING, RenderingHints.KEY_TEXT_ANTIALIASING,
    RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.KEY_RENDERING,
    RenderingHints.KEY_INTERPOLATION, RenderingHints.KEY_ALPHA_INTERPOLATION,
    RenderingHints.KEY_COLOR_RENDERING, RenderingHints.KEY_DITHERING,
    RenderingHints.KEY_STROKE_CONTROL, RenderingHints.KEY_TEXT_LCD_CONTRAST,
    RenderingHints.KEY_RESOLUTION_VARIANT
  };
  private static final Object[][] HINT_VALUES = {
    {RenderingHints.VALUE_ANTIALIAS_DEFAULT, RenderingHints.VALUE_ANTIALIAS_OFF,
        RenderingHints.VALUE_ANTIALIAS_ON},
    {RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
        RenderingHints.VALUE_TEXT_ANTIALIAS_ON, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP,
        RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HBGR,
        RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_VRGB, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_VBGR},
    {RenderingHints.VALUE_FRACTIONALMETRICS_DEFAULT, RenderingHints.VALUE_FRACTIONALMETRICS_OFF,
        RenderingHints.VALUE_FRACTIONALMETRICS_ON},
    {RenderingHints.VALUE_RENDER_DEFAULT, RenderingHints.VALUE_RENDER_SPEED,
        RenderingHints.VALUE_RENDER_QUALITY},
    {RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR, RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        RenderingHints.VALUE_INTERPOLATION_BICUBIC},
    {RenderingHints.VALUE_ALPHA_INTERPOLATION_DEFAULT, RenderingHints.VALUE_ALPHA_INTERPOLATION_SPEED,
        RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY},
    {RenderingHints.VALUE_COLOR_RENDER_DEFAULT, RenderingHints.VALUE_COLOR_RENDER_SPEED,
        RenderingHints.VALUE_COLOR_RENDER_QUALITY},
    {RenderingHints.VALUE_DITHER_DEFAULT, RenderingHints.VALUE_DITHER_DISABLE,
        RenderingHints.VALUE_DITHER_ENABLE},
    {RenderingHints.VALUE_STROKE_DEFAULT, RenderingHints.VALUE_STROKE_NORMALIZE,
        RenderingHints.VALUE_STROKE_PURE},
    {},
    {RenderingHints.VALUE_RESOLUTION_VARIANT_DEFAULT, RenderingHints.VALUE_RESOLUTION_VARIANT_BASE,
        RenderingHints.VALUE_RESOLUTION_VARIANT_SIZE_FIT, RenderingHints.VALUE_RESOLUTION_VARIANT_DPI_FIT}
  };

  public static int hintKey(RenderingHints.Key key) {
    for (int i = 0; i < HINT_KEYS.length; i++) if (HINT_KEYS[i] == key) return i + 1;
    throw new IllegalArgumentException("GRAPHICS_UNSUPPORTED_RENDERING_HINT");
  }

  public static RenderingHints.Key hintKey(int id) {
    if (id < 1 || id > HINT_KEYS.length)
      throw new IllegalArgumentException("GRAPHICS_UNSUPPORTED_RENDERING_HINT");
    return HINT_KEYS[id - 1];
  }

  public static int hintValue(int id, Object value) {
    hintKey(id);
    if (id == 10) {
      if (value instanceof Integer v && v >= 100 && v <= 250) return v;
    } else {
      Object[] values = HINT_VALUES[id - 1];
      for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
    }
    throw new IllegalArgumentException("GRAPHICS_UNSUPPORTED_RENDERING_HINT_VALUE");
  }

  public static Object hintValue(int id, int value) {
    hintKey(id);
    if (id == 10 && value >= 100 && value <= 250) return Integer.valueOf(value);
    if (id != 10 && value >= 0 && value < HINT_VALUES[id - 1].length)
      return HINT_VALUES[id - 1][value];
    throw new IllegalArgumentException("GRAPHICS_UNSUPPORTED_RENDERING_HINT_VALUE");
  }

  public static String encodeEvents(List<Event> events) {
    return encodeData(
        out -> {
          out.writeInt(1);
          out.writeInt(events.size());
          for (Event e : events) {
            out.writeInt(e.type());
            out.writeLong(e.window());
            out.writeLong(e.generation());
            out.writeInt(e.arg1());
            out.writeInt(e.arg2());
            out.writeInt(e.modifiers());
            out.writeUTF(e.text());
            out.writeLong(e.timestampNanos());
          }
        });
  }

  public static List<Event> decodeEvents(String payload) {
    try (DataInputStream in = input(payload)) {
      if (in.readInt() != 1) throw new IllegalArgumentException("Event wire version mismatch");
      int count = in.readInt();
      if (count < 0 || count > 1024) throw new IllegalArgumentException("Invalid event count");
      List<Event> result = new ArrayList<>(count);
      for (int i = 0; i < count; i++)
        result.add(
            new Event(
                in.readInt(),
                in.readLong(),
                in.readLong(),
                in.readInt(),
                in.readInt(),
                in.readInt(),
                in.readUTF(),
                in.readLong()));
      if (in.read() != -1) throw new IllegalArgumentException("Trailing event data");
      return List.copyOf(result);
    } catch (IOException ex) {
      throw new IllegalArgumentException("Malformed event batch", ex);
    }
  }

  private interface Encoder {
    void write(DataOutputStream out) throws IOException;
  }

  private static String encodeData(Encoder encoder) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(bytes)) {
        encoder.write(out);
      }
      if (bytes.size() > MAX_BYTES)
        throw new IllegalArgumentException("Graphics payload too large");
      return Base64.getEncoder().encodeToString(bytes.toByteArray());
    } catch (IOException ex) {
      throw new IllegalArgumentException("Cannot encode graphics payload", ex);
    }
  }

  private static DataInputStream input(String payload) {
    if (payload.length() > (MAX_BYTES + 2L) / 3 * 4)
      throw new IllegalArgumentException("Graphics payload too large");
    return new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(payload)));
  }
}
