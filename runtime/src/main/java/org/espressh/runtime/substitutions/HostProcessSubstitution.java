package org.espressh.runtime.substitutions;

import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.descriptors.EspressoSymbols;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.substitutions.JavaSubstitution;
import com.oracle.truffle.espresso.substitutions.LanguageFilter;

/** Standard Process API creates only managed children; Host-process creation remains denied. */
public final class HostProcessSubstitution extends JavaSubstitution {
    private static final String GUEST = "Lorg/espressh/bootstrap/process/GuestProcess;";
    private static final String[] PARAMETERS = {"[Ljava/lang/String;", "Ljava/util/Map;", "Ljava/lang/String;",
        "[Ljava/lang/ProcessBuilder$Redirect;", "Z"};
    public static final Factory START = factory();

    private static Factory factory() {
        EspressoSymbols.SYMBOLS.putType(GUEST);
        EspressoSymbols.SYMBOLS.putName("start");
        signature();
        return new Factory(
        "start", "Ljava/lang/ProcessImpl;", "Ljava/lang/Process;",
        PARAMETERS,
        false, LanguageFilter.AlwaysValid.INSTANCE, (byte)0, null,
        HostProcessSubstitution::new);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static com.oracle.truffle.espresso.classfile.descriptors.Symbol<com.oracle.truffle.espresso.classfile.descriptors.Signature> signature() {
        var params = new com.oracle.truffle.espresso.classfile.descriptors.Symbol[PARAMETERS.length];
        for (int i=0; i<params.length; i++) params[i] = EspressoSymbols.SYMBOLS.putType(PARAMETERS[i]);
        return EspressoSymbols.SYMBOLS.putSignature(EspressoSymbols.SYMBOLS.putType("Ljava/lang/Process;"), params);
    }

    public HostProcessSubstitution() { super(START); }

    @Override public JavaSubstitution split() { return new HostProcessSubstitution(); }

    @Override public Object invoke(Object[] args) {
        var context=EspressoContext.get(null);
        var meta=context.getMeta();
        StaticObject loader=(StaticObject) meta.java_lang_ClassLoader_getSystemClassLoader.invokeDirectStatic();
        ObjectKlass factory=(ObjectKlass) meta.loadKlassOrFail(
            EspressoSymbols.SYMBOLS.putType(GUEST), loader, StaticObject.NULL);
        factory.safeInitialize();
        return factory.requireDeclaredMethod(EspressoSymbols.SYMBOLS.putName("start"), signature())
            .invokeDirectStatic(args);
    }
}
