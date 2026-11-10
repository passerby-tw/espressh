package org.espressh.runtime.substitutions.awt;

import com.oracle.truffle.espresso.classfile.descriptors.Name;
import com.oracle.truffle.espresso.classfile.descriptors.Signature;
import com.oracle.truffle.espresso.classfile.descriptors.Symbol;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.substitutions.JavaSubstitution;
import com.oracle.truffle.espresso.substitutions.LanguageFilter;
import java.util.concurrent.atomic.AtomicInteger;

/** Pinned Disposer metadata validation; no JNI C caches. */
public final class DisposerInitialization extends JavaSubstitution {
  public static final Factory INIT_IDS = new Factory("initIDs", "Lsun/java2d/Disposer;", "V",
      new String[0], false, LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null,
      DisposerInitialization::new);
  private static final AtomicInteger hits = new AtomicInteger();
  private static final AtomicInteger metadataLookups = new AtomicInteger();

  public DisposerInitialization() { super(INIT_IDS); }

  @Override public JavaSubstitution split() { return new DisposerInitialization(); }

  @Override public Object invoke(Object[] arguments) {
    EspressoContext context = EspressoContext.get(null);
    Meta meta = context.getMeta();
    AwtRuntimeGuard.requireSupported(context);
    AwtRuntimeGuard.trace("DISPOSER_EXPERIMENT_HIT=initIDs COUNT=" + hits.incrementAndGet());
    // Disposer is the bootstrap class whose original clinit invoked this exact native
    // entry. Keep all Guest metadata invocation-local; loading does not replace clinit.
    Klass disposer = meta.loadKlassOrFail(
        context.getTypes().getOrCreateValidType("Lsun/java2d/Disposer;"),
        StaticObject.NULL, StaticObject.NULL);
    String name = "addRecord";
    String descriptor = "(Ljava/lang/Object;JJ)V";
    String member = "sun.java2d.Disposer." + name + descriptor;
    AwtRuntimeGuard.trace("DISPOSER_EXPERIMENT_LOOKUP_BEGIN=" + member + " KIND=staticMethod");
    Symbol<Name> nameSymbol = context.getNames().lookup(name);
    Symbol<Signature> signature = context.getSignatures().lookupValidSignature(descriptor);
    Method method = null;
    if (nameSymbol != null && signature != null) {
      // Pinned JniEnv.GetStaticMethodID initializes before lookupMethod, then checks
      // static. During the original clinit this is same-thread reentry; its return
      // does not mean the remainder of Disposer initialization has completed.
      disposer.safeInitialize();
      method = disposer.lookupMethod(nameSymbol, signature);
    }
    if (method == null || !method.isStatic() || method.getDeclaringKlass() != disposer
        || !method.getName().equals(nameSymbol) || !method.getRawSignature().equals(signature)) {
      // Retain the pinned JNI Guest linkage error category/message. Declaring-owner
      // equality is additional exact-build validation, not a general JNI emulation.
      throw meta.throwExceptionWithMessage(meta.java_lang_NoSuchMethodError, name);
    }
    int verified = metadataLookups.incrementAndGet();
    AwtRuntimeGuard.trace("DISPOSER_EXPERIMENT_METADATA_LOOKUP=PASS COUNT=" + verified
        + " MEMBER=" + member + " STATIC=true");

    // Lookup only: do not call the native-pointer overload. The pinned native writes
    // addRecordMID, then NewGlobalRef(Disposer.class) into dispClass. Neither JNI ID
    // allocation nor C/global-reference storage is needed here. Their allocation/OOM
    // semantics and C consumers are not emulated or claimed equivalent. Original Java
    // property, queue, disposer thread and record initialization continue after return.
    return null;
  }



  /** Dispatch count only; does not indicate completed Guest class initialization. */
  public static int hitCount() { return hits.get(); }

  /** Successfully verified exact static tuple; does not indicate completed clinit. */
  public static int metadataLookupCount() { return metadataLookups.get(); }
}
