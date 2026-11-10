package org.espressh.runtime.substitutions;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.espresso.descriptors.EspressoSymbols.Names;
import com.oracle.truffle.espresso.descriptors.EspressoSymbols.Signatures;
import com.oracle.truffle.espresso.io.Checks;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.substitutions.JavaSubstitution;
import com.oracle.truffle.espresso.substitutions.LanguageFilter;

/** Keep serialization in Guest Java, replacing only its two JNI helpers. */
public final class SerializationSubstitution extends JavaSubstitution {
    private static final String OWNER = "Ljava/io/ObjectStreamClass;";
    public static final Factory INIT_NATIVE = new Factory("initNative", OWNER, "V",
            new String[0], false, LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null,
            () -> new SerializationSubstitution(false));
    public static final Factory HAS_STATIC_INITIALIZER = new Factory("hasStaticInitializer", OWNER, "Z",
            new String[]{"Ljava/lang/Class;"}, false,
            LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null,
            () -> new SerializationSubstitution(true));

    private final boolean inspectInitializer;

    private SerializationSubstitution(boolean inspectInitializer) {
        super(inspectInitializer ? HAS_STATIC_INITIALIZER : INIT_NATIVE);
        this.inspectInitializer = inspectInitializer;
    }

    @Override public JavaSubstitution split() { return new SerializationSubstitution(inspectInitializer); }

    @Override @TruffleBoundary public Object invoke(Object[] arguments) {
        var context = EspressoContext.get(null);
        var meta = context.getMeta();
        if (!inspectInitializer) {
            // The pinned C initNative finds this Guest class and saves a JNI global
            // reference solely to distinguish NoSuchMethodError in the helper below.
            // Keep resolution/initialization, but no C cache or cross-Context reference.
            meta.java_lang_NoSuchMethodError.safeInitialize();
            return StaticObject.NULL;
        }
        var mirror = Checks.nullCheck((StaticObject) arguments[0], context);
        var klass = mirror.getMirrorKlass(meta);
        // Pinned JniEnv.GetStaticMethodID rejects primitive classes before initializing.
        // It special-cases <clinit> to lookupDeclaredMethod: inherited methods never count.
        if (klass.isPrimitive()) return false;
        klass.safeInitialize();
        var initializer = klass.lookupDeclaredMethod(Names._clinit_, Signatures._void);
        // A missing declared method is the native helper's swallowed NoSuchMethodError.
        // All initialization failures propagate as Guest errors, without Host reflection.
        return initializer != null && initializer.isStatic();
    }
}
