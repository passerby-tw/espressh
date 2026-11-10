package org.espressh.runtime.substitutions.awt;

import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.substitutions.JavaSubstitution;
import com.oracle.truffle.espresso.substitutions.LanguageFilter;
import java.util.concurrent.atomic.AtomicInteger;

/** Omit only the process-wide native splash lookup/close. */
public final class SplashCloseSubstitution extends JavaSubstitution {
  public static final Factory FACTORY = new Factory("closeSplashScreen", "Lsun/awt/SunToolkit;",
      "V", new String[0], false, LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null,
      SplashCloseSubstitution::new);
  private static final AtomicInteger HITS = new AtomicInteger();

  public SplashCloseSubstitution() { super(FACTORY); }
  @Override public JavaSubstitution split() { return new SplashCloseSubstitution(); }

  @Override public Object invoke(Object[] arguments) {
    EspressoContext context = EspressoContext.get(null);
    AwtRuntimeGuard.requireSupported(context);
    AwtRuntimeGuard.trace("SPLASH_CLOSE_HIT=sun.awt.SunToolkit.closeSplashScreen()V COUNT="
        + HITS.incrementAndGet());
    // Exactly the approved omission: no process-wide native splash lookup/close,
    // no Host splash effect, no edits to original Guest once/markClosed/show code.
    return null;
  }

  public static int hitCount() { return HITS.get(); }
}
