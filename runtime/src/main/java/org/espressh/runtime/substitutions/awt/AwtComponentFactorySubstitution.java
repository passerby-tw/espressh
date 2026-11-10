package org.espressh.runtime.substitutions.awt;

import com.oracle.truffle.espresso.descriptors.EspressoSymbols;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.substitutions.*;

/** Only the approved Component factory cut point, with a Guest-owned proxy. */
public final class AwtComponentFactorySubstitution extends JavaSubstitution {
  public static final Factory FACTORY = factory();
  private static Factory factory() {
    EspressoSymbols.SYMBOLS.putName("componentFactory");
    EspressoSymbols.SYMBOLS.putSignature(EspressoSymbols.SYMBOLS.putType("Ljava/lang/Object;"));
    return new Factory("getComponentFactory", "Ljava/awt/Component;", "Lsun/awt/ComponentFactory;",
        new String[]{"Ljava/awt/Component;"}, true, LanguageFilter.AlwaysValid.INSTANCE,
        (byte) 0, null, AwtComponentFactorySubstitution::new);
  }
  public AwtComponentFactorySubstitution() { super(FACTORY); }
  public JavaSubstitution split() { return new AwtComponentFactorySubstitution(); }
  public Object invoke(Object[] args) {
    var meta = EspressoContext.get(null).getMeta();
    StaticObject loader = (StaticObject) meta.java_lang_ClassLoader_getSystemClassLoader.invokeDirectStatic();
    ObjectKlass factory = (ObjectKlass) meta.loadKlassOrFail(EspressoSymbols.SYMBOLS.putType("Lorg/espressh/bootstrap/awt/AwtFactories;"), loader, StaticObject.NULL);
    factory.safeInitialize();
    return factory.requireDeclaredMethod(EspressoSymbols.SYMBOLS.putName("componentFactory"),
        EspressoSymbols.SYMBOLS.putSignature(EspressoSymbols.SYMBOLS.putType("Ljava/lang/Object;"))).invokeDirectStatic();
  }
}
