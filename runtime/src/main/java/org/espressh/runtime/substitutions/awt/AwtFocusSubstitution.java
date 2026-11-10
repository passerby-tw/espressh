package org.espressh.runtime.substitutions.awt;

import com.oracle.truffle.espresso.descriptors.EspressoSymbols;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.substitutions.*;

/** Replace only platform peer creation; retain existing Guest focus manager. */
public final class AwtFocusSubstitution extends JavaSubstitution {
  public static final Factory FACTORY = factory();
  private static Factory factory() {
    EspressoSymbols.SYMBOLS.putName("focusPeer");
    EspressoSymbols.SYMBOLS.putSignature(EspressoSymbols.SYMBOLS.putType("Ljava/lang/Object;"));
    return new Factory("initPeer", "Ljava/awt/KeyboardFocusManager;", "V",
        new String[]{"Ljava/awt/KeyboardFocusManager;"}, true, LanguageFilter.AlwaysValid.INSTANCE,
        (byte) 0, null, AwtFocusSubstitution::new);
  }
  public AwtFocusSubstitution() { super(FACTORY); }
  public JavaSubstitution split() { return new AwtFocusSubstitution(); }
  public Object invoke(Object[] args) {
    var meta = EspressoContext.get(null).getMeta();
    StaticObject loader = (StaticObject) meta.java_lang_ClassLoader_getSystemClassLoader.invokeDirectStatic();
    ObjectKlass factory = (ObjectKlass) meta.loadKlassOrFail(EspressoSymbols.SYMBOLS.putType("Lorg/espressh/bootstrap/awt/AwtFactories;"), loader, StaticObject.NULL);
    factory.safeInitialize();
    Object peer = factory.requireDeclaredMethod(EspressoSymbols.SYMBOLS.putName("focusPeer"),
        EspressoSymbols.SYMBOLS.putSignature(EspressoSymbols.SYMBOLS.putType("Ljava/lang/Object;"))).invokeDirectStatic();
    ObjectKlass focus = (ObjectKlass) meta.loadKlassOrFail(EspressoSymbols.SYMBOLS.putType("Ljava/awt/KeyboardFocusManager;"), StaticObject.NULL, StaticObject.NULL);
    for (var field : focus.getDeclaredFields()) {
      if (field.getNameAsString().equals("peer")) {
        field.setObject((StaticObject) args[0], peer);
        return null;
      }
    }
    throw new IllegalStateException("Pinned Guest focus peer field missing");
  }
}
