package org.espressh.runtime.substitutions.awt;

import com.oracle.truffle.espresso.classfile.descriptors.Name;
import com.oracle.truffle.espresso.classfile.descriptors.Signature;
import com.oracle.truffle.espresso.classfile.descriptors.Symbol;
import com.oracle.truffle.espresso.classfile.descriptors.Type;
import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.shared.lookup.LookupMode;
import com.oracle.truffle.espresso.substitutions.JavaSubstitution;
import com.oracle.truffle.espresso.substitutions.LanguageFilter;
import java.util.concurrent.atomic.AtomicInteger;

/** Pinned Component metadata validation; no native C storage. */
public final class ComponentInitialization extends JavaSubstitution {
  public static final Factory INIT_IDS = new Factory("initIDs", "Ljava/awt/Component;", "V",
      new String[0], false, LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null,
      ComponentInitialization::new);
  private static final AtomicInteger hits = new AtomicInteger();
  private static final AtomicInteger completed = new AtomicInteger();

  public ComponentInitialization() { super(INIT_IDS); }

  @Override public JavaSubstitution split() { return new ComponentInitialization(); }

  @Override public Object invoke(Object[] arguments) {
    EspressoContext context = EspressoContext.get(null);
    Meta meta = context.getMeta();
    AwtRuntimeGuard.requireSupported(context);
    AwtRuntimeGuard.trace("COMPONENT_EXPERIMENT_HIT=initIDs COUNT=" + hits.incrementAndGet());
    // Component is the bootstrap class whose clinit is already executing. Loading its
    // metadata does not rerun clinit; safeInitialize below preserves same-thread reentry.
    Klass component = meta.loadKlassOrFail(
        context.getTypes().getOrCreateValidType("Ljava/awt/Component;"),
        StaticObject.NULL, StaticObject.NULL);

    field(context, component, 1, "x", "I");
    field(context, component, 2, "y", "I");
    field(context, component, 3, "width", "I");
    field(context, component, 4, "height", "I");
    field(context, component, 5, "isPacked", "Z");
    field(context, component, 6, "peer", "Ljava/awt/peer/ComponentPeer;");
    field(context, component, 7, "background", "Ljava/awt/Color;");
    field(context, component, 8, "foreground", "Ljava/awt/Color;");
    field(context, component, 9, "graphicsConfig", "Ljava/awt/GraphicsConfiguration;");
    field(context, component, 10, "name", "Ljava/lang/String;");
    method(context, component, 11, "getParent_NoClientCode", "()Ljava/awt/Container;");
    method(context, component, 12, "getLocationOnScreen_NoTreeLock", "()Ljava/awt/Point;");

    String keyMember = "java.awt.event.KeyEvent.isProxyActive:Z";
    begin(13, "findClassAndField", keyMember);
    // Original native FindClass uses Component's bootstrap loader. Neither descriptor
    // validation nor field lookup should initialize unrelated referenced field types.
    Klass keyEvent = meta.loadKlassOrFail(
        context.getTypes().getOrCreateValidType("Ljava/awt/event/KeyEvent;"),
        component.getDefiningClassLoader(), StaticObject.NULL);
    AwtRuntimeGuard.trace("COMPONENT_EXPERIMENT_KEYEVENT=LOADED");
    AwtRuntimeGuard.trace("COMPONENT_EXPERIMENT_KEYEVENT_INITIALIZE=BEGIN");
    // In this pinned Espresso, JNI FindClass calls safeInitialize. This retains the
    // original InputEvent/NativeLibLoader/KeyEvent Java initialization and Guest failure.
    keyEvent.safeInitialize();
    AwtRuntimeGuard.trace("COMPONENT_EXPERIMENT_KEYEVENT_INITIALIZE=SUCCESS");
    lookupField(context, keyEvent, "isProxyActive", "Z");
    success(13, "findClassAndField", keyMember);

    field(context, component, 14, "appContext", "Lsun/awt/AppContext;");
    // No JNI handles or Guest componentIDs C cache are allocated or stored. Original
    // Component Java properties/accessor code runs only if this substitution returns.
    return null;
  }

  private static void field(EspressoContext context, Klass owner, int step,
      String name, String descriptor) {
    String member = owner.getExternalName() + "." + name + ":" + descriptor;
    begin(step, "field", member);
    lookupField(context, owner, name, descriptor);
    success(step, "field", member);
  }

  private static void lookupField(EspressoContext context, Klass owner,
      String name, String descriptor) {
    Meta meta = context.getMeta();
    Symbol<Name> nameSymbol = context.getNames().lookup(name);
    Symbol<Type> typeSymbol = context.getTypes().lookupValidType(descriptor);
    Field field = null;
    if (nameSymbol != null && typeSymbol != null) {
      // Mirror pinned JniEnv.GetFieldID's initialization point and instance lookup mode.
      owner.safeInitialize();
      field = owner.lookupField(nameSymbol, typeSymbol, LookupMode.INSTANCE_ONLY);
    }
    if (field == null || field.isStatic() || field.getDeclaringKlass() != owner
        || !field.getName().equals(nameSymbol) || !field.getType().equals(typeSymbol)) {
      // Declaring-owner equality is strict pinned-build validation beyond generic JNI
      // inherited-member lookup. Preserve the Guest linkage error category and message.
      throw meta.throwExceptionWithMessage(meta.java_lang_NoSuchFieldError, name);
    }
  }

  private static void method(EspressoContext context, Klass owner, int step,
      String name, String descriptor) {
    String member = owner.getExternalName() + "." + name + ":" + descriptor;
    begin(step, "method", member);
    Meta meta = context.getMeta();
    Symbol<Name> nameSymbol = context.getNames().lookup(name);
    Symbol<Signature> signature = context.getSignatures().lookupValidSignature(descriptor);
    Method method = null;
    if (nameSymbol != null && signature != null) {
      // Pinned JNI GetMethodID uses lookupMethod, followed by its non-static check.
      owner.safeInitialize();
      method = owner.lookupMethod(nameSymbol, signature);
    }
    if (method == null || method.isStatic() || method.getDeclaringKlass() != owner
        || !method.getName().equals(nameSymbol) || !method.getRawSignature().equals(signature)) {
      throw meta.throwExceptionWithMessage(meta.java_lang_NoSuchMethodError, name);
    }
    // Lookup only: do not invoke either cached Component instance method.
    success(step, "method", member);
  }

  private static void begin(int step, String kind, String member) {
    AwtRuntimeGuard.trace("COMPONENT_EXPERIMENT_STEP_BEGIN=" + step
        + " KIND=" + kind + " MEMBER=" + member);
  }

  private static void success(int step, String kind, String member) {
    completed.incrementAndGet();
    AwtRuntimeGuard.trace("COMPONENT_EXPERIMENT_STEP_SUCCESS=" + step
        + " KIND=" + kind + " MEMBER=" + member);
  }



  public static int hitCount() { return hits.get(); }
  public static int completedLookupCount() { return completed.get(); }
}
