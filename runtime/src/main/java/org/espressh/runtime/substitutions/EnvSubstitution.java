package org.espressh.runtime.substitutions;

import com.oracle.truffle.espresso.substitutions.*;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.descriptors.EspressoSymbols;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.impl.Method;

/** Pinned Espresso 25.4.4.1.1 internal API. No Host map is exported. */
public final class EnvSubstitution extends JavaSubstitution {
    static {
        EspressoSymbols.SYMBOLS.putType("Ljava/util/HashMap;");
        EspressoSymbols.SYMBOLS.putType("Ljava/util/Collections;");
        EspressoSymbols.SYMBOLS.putName("<init>");
        EspressoSymbols.SYMBOLS.putName("put");
        EspressoSymbols.SYMBOLS.putName("unmodifiableMap");
        signature("()V");
        signature("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        signature("(Ljava/util/Map;)Ljava/util/Map;");
    }
    /** Host-owned per-Context environment. No guest receiver or mutable map is exported. */
    public static final class EnvironmentState {
        private volatile java.util.Map<String,String> snapshot;
        public EnvironmentState(java.util.Map<String,String> env) { replace(env); }
        public void replace(java.util.Map<String,String> env) { snapshot=java.util.Map.copyOf(env); }
        public java.util.Map<String,String> snapshot() { return snapshot; }
    }
    private static final ThreadLocal<EnvironmentState> INITIALIZING=new ThreadLocal<>();
    private static final java.util.Map<EspressoContext,EnvironmentState> STATES=
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    public static void begin(EnvironmentState state) { INITIALIZING.set(state); }
    public static void end() { INITIALIZING.remove(); }
    private static java.util.Map<String,String> environment(EspressoContext ctx) {
        EnvironmentState state;
        synchronized(STATES) {
            state=STATES.get(ctx);
            if(state==null && INITIALIZING.get()!=null) { state=INITIALIZING.get();STATES.put(ctx,state); }
        }
        return state==null?ctx.getEnv().getEnvironment():state.snapshot;
    }
    private final boolean named;
    public static final Factory NAMED = factory(true);
    public static final Factory ALL = factory(false);
    private static Factory factory(boolean named) {
        return new Factory("getenv", "Ljava/lang/System;",
            named ? "Ljava/lang/String;" : "Ljava/util/Map;",
            named ? new String[]{"Ljava/lang/String;"} : new String[0], false,
            LanguageFilter.AlwaysValid.INSTANCE, (byte)0, null,
            () -> new EnvSubstitution(named));
    }
    public EnvSubstitution(boolean named) { super(named ? NAMED : ALL); this.named=named; }
    public JavaSubstitution split() { return new EnvSubstitution(named); }
    private static Method method(ObjectKlass klass, String name, String sig) {
        return klass.requireDeclaredMethod(EspressoSymbols.SYMBOLS.putName(name),
                signature(sig));
    }
    @SuppressWarnings("unchecked")
    private static com.oracle.truffle.espresso.classfile.descriptors.Symbol<com.oracle.truffle.espresso.classfile.descriptors.Signature> signature(String sig) {
        String[] halves=sig.substring(1).split("\\)");
        var params=new java.util.ArrayList<com.oracle.truffle.espresso.classfile.descriptors.Symbol<com.oracle.truffle.espresso.classfile.descriptors.Type>>();
        var matcher=java.util.regex.Pattern.compile("L[^;]+;|[ZBCSIJFD]").matcher(halves[0]);
        while(matcher.find()) params.add(EspressoSymbols.SYMBOLS.putType(matcher.group()));
        return EspressoSymbols.SYMBOLS.putSignature(EspressoSymbols.SYMBOLS.putType(halves[1]), params.toArray(new com.oracle.truffle.espresso.classfile.descriptors.Symbol[0]));
    }
    public Object invoke(Object[] args) {
        EspressoContext ctx=EspressoContext.get(null);
        var meta=ctx.getMeta();
        var env=environment(ctx);
        if(named) {
            StaticObject key=(StaticObject)args[0];
            if(StaticObject.isNull(key)) throw meta.throwNullPointerException();
            return meta.toGuestString(env.get(meta.toHostString(key)));
        }
        ObjectKlass hash=meta.knownKlass(EspressoSymbols.SYMBOLS.putType("Ljava/util/HashMap;"));
        hash.safeInitialize();
        StaticObject map=ctx.getAllocator().createNew(hash);
        method(hash,"<init>","()V").invokeDirectSpecial(map);
        Method put=method(hash,"put","(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        for(var entry:env.entrySet()) put.invokeDirectSpecial(map,
                meta.toGuestString(entry.getKey()),meta.toGuestString(entry.getValue()));
        ObjectKlass collections=meta.knownKlass(EspressoSymbols.SYMBOLS.putType("Ljava/util/Collections;"));
        collections.safeInitialize();
        return method(collections,"unmodifiableMap","(Ljava/util/Map;)Ljava/util/Map;").invokeDirectStatic(map);
    }
}
