package org.espressh.runtime.substitutions.awt;

import com.oracle.truffle.espresso.descriptors.EspressoSymbols;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.substitutions.*;

/** Retains the public InputContext API, replaces platform creation only. */
public final class AwtInputContextSubstitution extends JavaSubstitution {
  public static final Factory FACTORY = factory();
  private static Factory factory() {
    EspressoSymbols.SYMBOLS.putName("inputContext");
    AwtImageGraphicsSubstitution.signature("Ljava/awt/im/InputContext;");
    return new Factory("getInstance","Ljava/awt/im/InputContext;","Ljava/awt/im/InputContext;",new String[0],false,
        LanguageFilter.AlwaysValid.INSTANCE,(byte)0,null,AwtInputContextSubstitution::new);
  }
  public AwtInputContextSubstitution() {super(FACTORY);}
  public JavaSubstitution split() {return new AwtInputContextSubstitution();}
  public Object invoke(Object[] args) {
    var meta=EspressoContext.get(null).getMeta();
    StaticObject loader=(StaticObject)meta.java_lang_ClassLoader_getSystemClassLoader.invokeDirectStatic();
    ObjectKlass factory=(ObjectKlass)meta.loadKlassOrFail(EspressoSymbols.SYMBOLS.putType("Lorg/espressh/bootstrap/awt/AwtFactories;"),loader,StaticObject.NULL);
    factory.safeInitialize();
    return factory.requireDeclaredMethod(EspressoSymbols.SYMBOLS.putName("inputContext"),AwtImageGraphicsSubstitution.signature("Ljava/awt/im/InputContext;")).invokeDirectStatic();
  }
}
