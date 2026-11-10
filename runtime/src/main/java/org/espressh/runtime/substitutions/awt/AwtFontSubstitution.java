package org.espressh.runtime.substitutions.awt;

import com.oracle.truffle.espresso.descriptors.EspressoSymbols;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.substitutions.*;

/** Experimental metric boundary: preserve Guest Component and its subclasses. */
public final class AwtFontSubstitution extends JavaSubstitution {
  public static final Factory COMPONENT = factory("Ljava/awt/Component;", false);
  public static final Factory GRAPHICS = factory("Lsun/java2d/SunGraphics2D;", true);
  public static final Factory GRAPHICS_DEFAULT = new Factory("getFontMetrics", "Lsun/java2d/SunGraphics2D;", "Ljava/awt/FontMetrics;",
      new String[]{"Lsun/java2d/SunGraphics2D;"}, true, LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null,
      () -> new AwtFontSubstitution(true, true));
  private final boolean graphics;
  private final boolean defaults;
  private static Factory factory(String owner, boolean graphics) {
    EspressoSymbols.SYMBOLS.putType("Lorg/espressh/bootstrap/awt/AwtFactories;");
    EspressoSymbols.SYMBOLS.putName("fontMetrics");
    EspressoSymbols.SYMBOLS.putName("getFont");
    EspressoSymbols.SYMBOLS.putSignature(EspressoSymbols.SYMBOLS.putType("Ljava/awt/Font;"));
    EspressoSymbols.SYMBOLS.putSignature(EspressoSymbols.SYMBOLS.putType("Ljava/awt/FontMetrics;"), EspressoSymbols.SYMBOLS.putType("Ljava/awt/Font;"));
    return new Factory("getFontMetrics", owner, "Ljava/awt/FontMetrics;", new String[]{owner, "Ljava/awt/Font;"}, true,
        LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null, () -> new AwtFontSubstitution(graphics));
  }
  public AwtFontSubstitution(boolean graphics) { this(graphics, false); }
  public AwtFontSubstitution(boolean graphics, boolean defaults) { super(defaults ? GRAPHICS_DEFAULT : graphics ? GRAPHICS : COMPONENT); this.graphics = graphics; this.defaults = defaults; }
  public JavaSubstitution split() { return new AwtFontSubstitution(graphics, defaults); }
  public Object invoke(Object[] args) {
    var meta = EspressoContext.get(null).getMeta();
    StaticObject loader = (StaticObject) meta.java_lang_ClassLoader_getSystemClassLoader.invokeDirectStatic();
    ObjectKlass factory = (ObjectKlass) meta.loadKlassOrFail(EspressoSymbols.SYMBOLS.putType("Lorg/espressh/bootstrap/awt/AwtFactories;"), loader, StaticObject.NULL);
    factory.safeInitialize();
    var signature = EspressoSymbols.SYMBOLS.putSignature(EspressoSymbols.SYMBOLS.putType("Ljava/awt/FontMetrics;"), EspressoSymbols.SYMBOLS.putType("Ljava/awt/Font;"));
    Object font = defaults ? ((ObjectKlass) ((StaticObject) args[0]).getKlass()).requireDeclaredMethod(
        EspressoSymbols.SYMBOLS.putName("getFont"), EspressoSymbols.SYMBOLS.putSignature(EspressoSymbols.SYMBOLS.putType("Ljava/awt/Font;"))).invokeDirectVirtual(args[0]) : args[1];
    return factory.requireDeclaredMethod(EspressoSymbols.SYMBOLS.putName("fontMetrics"), signature).invokeDirectStatic(font);
  }
}
