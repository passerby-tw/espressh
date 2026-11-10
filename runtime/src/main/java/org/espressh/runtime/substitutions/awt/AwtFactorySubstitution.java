package org.espressh.runtime.substitutions.awt;

import com.oracle.truffle.espresso.descriptors.EspressoSymbols;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.substitutions.*;

/** Pinned Guest AWT adapter; no Host application fallback. */
public final class AwtFactorySubstitution extends JavaSubstitution {
  public static final Factory ENVIRONMENT = factory("createGE", "Ljava/awt/GraphicsEnvironment;");
  public static final Factory TOOLKIT = factory("createToolkit", "Ljava/awt/Toolkit;");
  private final boolean toolkit;

  private static Factory factory(String name, String result) {
    EspressoSymbols.SYMBOLS.putType("Lorg/espressh/bootstrap/awt/AwtFactories;");
    EspressoSymbols.SYMBOLS.putName(name);
    EspressoSymbols.SYMBOLS.putSignature(EspressoSymbols.SYMBOLS.putType(result));
    EspressoSymbols.SYMBOLS.putType("Lsun/awt/SunToolkit;");
    EspressoSymbols.SYMBOLS.putName("getSystemEventQueueImplPP");
    AwtImageGraphicsSubstitution.signature("Ljava/awt/EventQueue;");
    AwtImageGraphicsSubstitution.signature("Ljava/awt/Toolkit;", "Ljava/awt/EventQueue;");
    return new Factory(name, "Lsun/awt/PlatformGraphicsInfo;", result,
        new String[0], false, LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null,
        () -> new AwtFactorySubstitution(name.equals("createToolkit")));
  }

  public AwtFactorySubstitution(boolean toolkit) {
    super(toolkit ? TOOLKIT : ENVIRONMENT);
    this.toolkit = toolkit;
  }

  public JavaSubstitution split() { return new AwtFactorySubstitution(toolkit); }

  public Object invoke(Object[] args) {
    var context = EspressoContext.get(null);
    var meta = context.getMeta();
    StaticObject loader = (StaticObject) meta.java_lang_ClassLoader_getSystemClassLoader.invokeDirectStatic();
    ObjectKlass factory = (ObjectKlass) meta.loadKlassOrFail(
        EspressoSymbols.SYMBOLS.putType("Lorg/espressh/bootstrap/awt/AwtFactories;"), loader, StaticObject.NULL);
    factory.safeInitialize();
    if(toolkit) {
      // Reuse the Guest AppContext queue so AWT auto-shutdown reaches the actual EDT.
      ObjectKlass sunToolkit=(ObjectKlass)meta.loadKlassOrFail(
          EspressoSymbols.SYMBOLS.putType("Lsun/awt/SunToolkit;"),StaticObject.NULL,StaticObject.NULL);
      sunToolkit.safeInitialize();
      Object queue=sunToolkit.requireDeclaredMethod(EspressoSymbols.SYMBOLS.putName("getSystemEventQueueImplPP"),
          AwtImageGraphicsSubstitution.signature("Ljava/awt/EventQueue;")).invokeDirectStatic();
      return factory.requireDeclaredMethod(EspressoSymbols.SYMBOLS.putName("createToolkit"),
          AwtImageGraphicsSubstitution.signature("Ljava/awt/Toolkit;","Ljava/awt/EventQueue;")).invokeDirectStatic(queue);
    }
    var signature = EspressoSymbols.SYMBOLS.putSignature(EspressoSymbols.SYMBOLS.putType(
        toolkit ? "Ljava/awt/Toolkit;" : "Ljava/awt/GraphicsEnvironment;"));
    return factory.requireDeclaredMethod(EspressoSymbols.SYMBOLS.putName(
        toolkit ? "createToolkit" : "createGE"), signature).invokeDirectStatic();
  }
}
