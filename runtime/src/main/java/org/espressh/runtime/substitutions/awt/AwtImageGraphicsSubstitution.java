package org.espressh.runtime.substitutions.awt;

import com.oracle.truffle.espresso.descriptors.EspressoSymbols;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.substitutions.*;

/** Route Guest BufferedImage graphics to the caller-bound Graphics Service adapter. */
public final class AwtImageGraphicsSubstitution extends JavaSubstitution {
  public static final Factory FACTORY = factory();
  private static Factory factory() {
    EspressoSymbols.SYMBOLS.putName("imageGraphics");
    signature("Ljava/awt/Graphics2D;", "Ljava/awt/image/BufferedImage;");
    return new Factory("createGraphics", "Ljava/awt/image/BufferedImage;", "Ljava/awt/Graphics2D;",
        new String[]{"Ljava/awt/image/BufferedImage;"}, true, LanguageFilter.AlwaysValid.INSTANCE,
        (byte) 0, null, AwtImageGraphicsSubstitution::new);
  }
  @SuppressWarnings("unchecked")
  static com.oracle.truffle.espresso.classfile.descriptors.Symbol<com.oracle.truffle.espresso.classfile.descriptors.Signature> signature(String result, String... parameters) {
    var types = new com.oracle.truffle.espresso.classfile.descriptors.Symbol[parameters.length];
    for (int i = 0; i < types.length; i++) types[i] = EspressoSymbols.SYMBOLS.putType(parameters[i]);
    return EspressoSymbols.SYMBOLS.putSignature(EspressoSymbols.SYMBOLS.putType(result), types);
  }
  public AwtImageGraphicsSubstitution() { super(FACTORY); }
  public JavaSubstitution split() { return new AwtImageGraphicsSubstitution(); }
  public Object invoke(Object[] args) {
    var context = EspressoContext.get(null);
    var meta = context.getMeta();
    StaticObject loader = (StaticObject) meta.java_lang_ClassLoader_getSystemClassLoader.invokeDirectStatic();
    ObjectKlass factory = (ObjectKlass) meta.loadKlassOrFail(EspressoSymbols.SYMBOLS.putType("Lorg/espressh/bootstrap/awt/AwtFactories;"), loader, StaticObject.NULL);
    factory.safeInitialize();
    return factory.requireDeclaredMethod(EspressoSymbols.SYMBOLS.putName("imageGraphics"),
        signature("Ljava/awt/Graphics2D;", "Ljava/awt/image/BufferedImage;")).invokeDirectStatic(args[0]);
  }
}
