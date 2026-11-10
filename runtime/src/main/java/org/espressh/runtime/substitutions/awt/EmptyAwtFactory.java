package org.espressh.runtime.substitutions.awt;

import com.oracle.truffle.espresso.libs.Lib;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** A Context-owned empty EspressoLibs library with no JNI symbols. */
public final class EmptyAwtFactory implements Lib.Factory {
  private static final AtomicInteger creations = new AtomicInteger();

  @Override public String name() { return "awt"; }

  @Override public Lib create(EspressoContext context) {
    AwtRuntimeGuard.requireLibraryRuntime(context);
    Lib library = new Lib(context, List.of(), "awt");
    AwtRuntimeGuard.trace("EMPTY_AWT_LIB_CREATED=" + creations.incrementAndGet()
        + " BINDINGS=0 SDK_HANDLE_MANAGEMENT=UNCHANGED");
    // SDK manages this object's per-Context synthetic handle. No Lib/Context/Value
    // is retained in static state and no pointer or native-success result is forged.
    return library;
  }

  public static int creationCount() { return creations.get(); }
}
