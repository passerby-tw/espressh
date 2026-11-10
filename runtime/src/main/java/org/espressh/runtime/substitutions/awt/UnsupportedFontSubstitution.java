package org.espressh.runtime.substitutions.awt;

import com.oracle.truffle.espresso.substitutions.*;

/** Reject unsupported Guest font operations before native fontmanager initialization. */
public final class UnsupportedFontSubstitution extends JavaSubstitution {
  public static final Factory FACTORY = new Factory("run", "Lsun/font/FontManagerNativeLibrary$1;",
      "Ljava/lang/Object;", new String[]{"Lsun/font/FontManagerNativeLibrary$1;"}, true,
      LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null, UnsupportedFontSubstitution::new);
  public UnsupportedFontSubstitution() { super(FACTORY); }
  public JavaSubstitution split() { return new UnsupportedFontSubstitution(); }
  public Object invoke(Object[] args) {
    var meta = com.oracle.truffle.espresso.runtime.EspressoContext.get(null).getMeta();
    throw meta.throwExceptionWithMessage(meta.java_lang_UnsupportedOperationException,
        "AWT_FONT_OPERATION_NOT_CONNECTED");
  }
}
