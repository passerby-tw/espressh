package org.espressh.runtime.substitutions;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.substitutions.JavaSubstitution;
import com.oracle.truffle.espresso.substitutions.LanguageFilter;
import org.graalvm.polyglot.Context;

/** Guest borrowed streams are not native operating-system TTYs. */
public final class ConsoleTtySubstitution extends JavaSubstitution {
    public static final Factory TTY_STATUS = new Factory(
            "ttyStatus", "Ljava/io/Console;", "I", new String[0], false,
            LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null,
            ConsoleTtySubstitution::new);
    private volatile EspressoContext verifiedContext;

    public ConsoleTtySubstitution() { super(TTY_STATUS); }

    @Override public JavaSubstitution split() { return new ConsoleTtySubstitution(); }

    @Override @TruffleBoundary public Object invoke(Object[] args) {
        requireRuntime(EspressoContext.get(null));
        // No native TTY capability is supplied for any of the three Guest streams.
        // Keep Console's original Java initialization, provider and charset behavior.
        return 0;
    }

    private void requireRuntime(EspressoContext context) {
        if (verifiedContext == context) return;
        var meta = context.getMeta();
        String version = property(context, "java.version");
        String os = property(context, "os.name");
        String arch = property(context, "os.arch");
        if (!"Linux".equals(System.getProperty("os.name")) || !"Linux".equals(os)
                || !("amd64".equals(arch) || "x86_64".equals(arch))
                || !"25.0.4.1.1".equals(version)
                || !"25.4.4.1.1".equals(Context.getCurrent().getEngine().getVersion())
                || context.getEnv().isNativeAccessAllowed()
                || !"no-native".equals(context.getLanguage().nativeBackendId())
                || !context.getLanguage().useEspressoLibs()) {
            throw meta.throwExceptionWithMessage(meta.java_lang_UnsupportedOperationException,
                    "CONSOLE_TTY_UNSUPPORTED_RUNTIME: requires Linux x86_64 Guest25.0.4.1.1"
                    + " / Espresso25.4.4.1.1 / native=false / no-native / EspressoLibs");
        }
        verifiedContext = context;
    }

    private static String property(EspressoContext context, String name) {
        var meta = context.getMeta();
        return meta.toHostString((StaticObject) meta.java_lang_System_getProperty
                .invokeDirectStatic(meta.toGuestString(name)));
    }
}
