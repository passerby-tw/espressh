package org.espressh.runtime.substitutions.awt;

import com.oracle.truffle.espresso.classfile.descriptors.Name;
import com.oracle.truffle.espresso.classfile.descriptors.Signature;
import com.oracle.truffle.espresso.classfile.descriptors.Symbol;
import com.oracle.truffle.espresso.classfile.descriptors.Type;
import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.shared.lookup.LookupMode;
import com.oracle.truffle.espresso.substitutions.JavaSubstitution;
import com.oracle.truffle.espresso.substitutions.LanguageFilter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Finite image-data initialization for Graphics2D pass-through.
 * The seven native bodies perform 44 metadata lookups; none supplies a renderer.
 * No Guest object, Klass, Field, Method, JNI ID, or native pointer is retained here.
 */
public final class ImageDataInitialization extends JavaSubstitution {
  private enum Kind { FIELD, METHOD, STATIC_METHOD }

  private record Lookup(Kind kind, String name, String descriptor) {}
  private record Entry(String owner, List<Lookup> lookups) {
    String ownerDescriptor() { return "L" + owner.replace('.', '/') + ";"; }
    String key() { return owner + ".initIDs()V"; }
  }

  // Preserve the pinned native lookup order, including Raster's repeated numBands lookup.
  private static final List<Entry> ENTRIES = List.of(
      new Entry("sun.java2d.pipe.Region", List.of(
          field("endIndex", "I"), field("bands", "[I"), field("lox", "I"),
          field("loy", "I"), field("hix", "I"), field("hiy", "I"))),
      new Entry("java.awt.image.ColorModel", List.of(
          field("nBits", "[I"), field("colorSpace", "Ljava/awt/color/ColorSpace;"),
          field("numComponents", "I"), field("supportsAlpha", "Z"),
          field("isAlphaPremultiplied", "Z"), field("transparency", "I"),
          field("colorSpaceType", "I"), field("is_sRGB", "Z"),
          new Lookup(Kind.STATIC_METHOD, "getRGBdefault", "()Ljava/awt/image/ColorModel;"))),
      new Entry("java.awt.image.BufferedImage", List.of(
          field("raster", "Ljava/awt/image/WritableRaster;"), field("imageType", "I"),
          field("colorModel", "Ljava/awt/image/ColorModel;"),
          method("getRGB", "(IIII[III)[I"), method("setRGB", "(IIII[III)V"))),
      new Entry("java.awt.image.Raster", List.of(
          field("width", "I"), field("height", "I"), field("numBands", "I"),
          field("minX", "I"), field("minY", "I"), field("sampleModelTranslateX", "I"),
          field("sampleModelTranslateY", "I"),
          field("sampleModel", "Ljava/awt/image/SampleModel;"),
          field("numDataElements", "I"), field("numBands", "I"),
          field("dataBuffer", "Ljava/awt/image/DataBuffer;"))),
      new Entry("java.awt.image.SampleModel", List.of(
          field("width", "I"), field("height", "I"),
          method("getPixels", "(IIII[ILjava/awt/image/DataBuffer;)[I"),
          method("setPixels", "(IIII[ILjava/awt/image/DataBuffer;)V"))),
      new Entry("java.awt.image.SinglePixelPackedSampleModel", List.of(
          field("bitMasks", "[I"), field("bitOffsets", "[I"),
          field("bitSizes", "[I"), field("maxBitSize", "I"))),
      new Entry("sun.awt.image.IntegerComponentRaster", List.of(
          field("data", "[I"), field("scanlineStride", "I"), field("pixelStride", "I"),
          field("dataOffsets", "[I"), field("type", "I"))));

  private static final AtomicIntegerArray HITS = new AtomicIntegerArray(ENTRIES.size());
  private static final AtomicIntegerArray COMPLETED = new AtomicIntegerArray(ENTRIES.size());
  public static final List<Factory> FACTORIES = factories();

  private final int index;

  private static Lookup field(String name, String descriptor) {
    return new Lookup(Kind.FIELD, name, descriptor);
  }

  private static Lookup method(String name, String descriptor) {
    return new Lookup(Kind.METHOD, name, descriptor);
  }

  private static List<Factory> factories() {
    var result = new ArrayList<Factory>(ENTRIES.size());
    var keys = new HashSet<String>();
    for (int i = 0; i < ENTRIES.size(); i++) {
      Entry entry = ENTRIES.get(i);
      if (!keys.add(entry.key())) throw new IllegalStateException("Duplicate metadata target");
      final int entryIndex = i;
      result.add(new Factory("initIDs", entry.ownerDescriptor(), "V", new String[0], false,
          LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null,
          () -> new ImageDataInitialization(entryIndex)));
    }
    if (result.size() != 7 || ENTRIES.stream().mapToInt(e -> e.lookups().size()).sum() != 44) {
      throw new IllegalStateException("Finite image-data initialization must have 7 targets / 44 lookups");
    }
    return List.copyOf(result);
  }

  private ImageDataInitialization(int index) {
    super(FACTORIES.get(index));
    this.index = index;
  }

  @Override public JavaSubstitution split() { return new ImageDataInitialization(index); }

  @Override public Object invoke(Object[] arguments) {
    EspressoContext context = EspressoContext.get(null);
    Meta meta = context.getMeta();
    AwtRuntimeGuard.requireSupported(context);
    Entry entry = ENTRIES.get(index);
    AwtRuntimeGuard.trace("IMAGE_DATA_INIT_HIT=" + entry.key() + " COUNT="
        + HITS.incrementAndGet(index));
    // This bootstrap owner is already executing its original Java clinit. Each
    // lookup retains the pinned JNI initialization point and same-thread reentry.
    Klass owner = meta.loadKlassOrFail(context.getTypes().getOrCreateValidType(entry.ownerDescriptor()),
        StaticObject.NULL, StaticObject.NULL);
    for (int i = 0; i < entry.lookups().size(); i++) {
      Lookup lookup = entry.lookups().get(i);
      String marker = " OWNER=" + entry.owner() + " STEP=" + (i + 1) + " KIND=" + lookup.kind()
          + " MEMBER=" + lookup.name() + " DESCRIPTOR=" + lookup.descriptor();
      AwtRuntimeGuard.trace("IMAGE_DATA_INIT_LOOKUP_BEGIN" + marker);
      lookup(context, owner, lookup);
      COMPLETED.incrementAndGet(index);
      AwtRuntimeGuard.trace("IMAGE_DATA_INIT_LOOKUP_SUCCESS" + marker);
    }
    // Omit JNI ID allocation / C cache writes only. Do not initialize a rendering
    // resource, allocate pData, register loops or native symbols, or invoke methods.
    return null;
  }

  private static void lookup(EspressoContext context, Klass owner, Lookup lookup) {
    Meta meta = context.getMeta();
    Symbol<Name> name = context.getNames().lookup(lookup.name());
    if (lookup.kind() == Kind.FIELD) {
      Symbol<Type> type = context.getTypes().lookupValidType(lookup.descriptor());
      Field field = null;
      if (name != null && type != null) {
        owner.safeInitialize();
        field = owner.lookupField(name, type, LookupMode.INSTANCE_ONLY);
      }
      if (field == null || field.isStatic()) {
        throw meta.throwExceptionWithMessage(meta.java_lang_NoSuchFieldError, lookup.name());
      }
    } else {
      Symbol<Signature> signature = context.getSignatures().lookupValidSignature(lookup.descriptor());
      Method method = null;
      if (name != null && signature != null) {
        owner.safeInitialize();
        method = owner.lookupMethod(name, signature);
      }
      if (method == null || method.isStatic() != (lookup.kind() == Kind.STATIC_METHOD)) {
        throw meta.throwExceptionWithMessage(meta.java_lang_NoSuchMethodError, lookup.name());
      }
    }
    // Guest Throwable propagation is intentional. A failed lookup must not be
    // converted to a Host exception or continue to the next metadata effect.
  }



  public static List<String> entryKeys() { return ENTRIES.stream().map(Entry::key).toList(); }

  public static List<String> lookupKeys() {
    var keys = new ArrayList<String>();
    for (Entry entry : ENTRIES) {
      for (int i = 0; i < entry.lookups().size(); i++) {
        Lookup lookup = entry.lookups().get(i);
        keys.add(entry.owner() + "|" + (i + 1) + "|" + lookup.kind() + "|"
            + lookup.name() + "|" + lookup.descriptor());
      }
    }
    return List.copyOf(keys);
  }

  /** These are diagnostics, not completed Guest Java class-initialization counts. */
  public static int hitCount(String owner) { return HITS.get(indexOf(owner)); }
  public static int completedLookupCount(String owner) { return COMPLETED.get(indexOf(owner)); }
  private static int indexOf(String owner) {
    for (int i = 0; i < ENTRIES.size(); i++) if (ENTRIES.get(i).owner().equals(owner)) return i;
    throw new IllegalArgumentException("Unregistered image-data owner: " + owner);
  }
}
