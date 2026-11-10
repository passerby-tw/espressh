package org.espressh.runtime.substitutions.awt;

import com.oracle.truffle.espresso.EspressoLanguage;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import org.graalvm.polyglot.Context;

/** Exact runtime fence for the no-native AWT implementations. */
final class AwtRuntimeGuard {
    private AwtRuntimeGuard() {}

    static void requireSupported(EspressoContext context) {
        Meta meta = context.getMeta();
        String version = property(meta, "java.version");
        String os = property(meta, "os.name");
        String arch = property(meta, "os.arch");
        String languageInfoVersion = context.getEnv().getLanguageInfo(EspressoLanguage.class).getVersion();
        String backend = context.getLanguage().nativeBackendId();
        boolean nativeAccess = context.getEnv().isNativeAccessAllowed();
        boolean libs = context.getLanguage().useEspressoLibs();
        String engineVersion;
        try {
            // Read only this execution's Engine. Never enter, leave, close, create,
            // or retain a Context/Engine while validating an initialization hook.
            engineVersion = Context.getCurrent().getEngine().getVersion();
        } catch (IllegalStateException unavailable) {
            engineVersion = "UNAVAILABLE:" + unavailable.getClass().getSimpleName();
        }
        String observed = "GUEST_JAVA=" + quoted(version) + " GUEST_OS=" + quoted(os)
                + " GUEST_ARCH=" + quoted(arch) + " LANGUAGE_INFO_VERSION=" + quoted(languageInfoVersion)
                + " SAME_ENGINE_VERSION=" + quoted(engineVersion) + " BACKEND=" + quoted(backend)
                + " NATIVE_ACCESS=" + nativeAccess + " ESPRESSO_LIBS=" + libs;
        trace("AWT_GUARD_OBSERVED " + observed);
        if (!"25.0.4.1.1".equals(version) || !"Linux".equals(os)
                || !("amd64".equals(arch) || "x86_64".equals(arch))
                || !"25.4.4.1.1".equals(engineVersion) || nativeAccess
                || !"no-native".equals(backend) || !libs) {
            throw meta.throwExceptionWithMessage(meta.java_lang_UnsupportedOperationException,
                    "AWT_ADAPTER_UNSUPPORTED_RUNTIME: requires Linux x86_64 Guest JDK25.0.4.1.1"
                            + " / same Engine25.4.4.1.1 / native=false / no-native / EspressoLibs; " + observed);
        }
    }

    static void requireLibraryRuntime(EspressoContext context) {
        if (context == null) throw new IllegalArgumentException("Context ownership required");
        // Keep this factory independent of Guest System/Meta readiness. The SDK
        // registers factories eagerly but calls create only for an actual awt load;
        // the 39 native entries validate real Guest version/OS/architecture. Do
        // not infer Guest compatibility from Host System properties or narrow
        // the existing Host-JDK range when constructing an empty library.
        String engineVersion;
        try {
            engineVersion = Context.getCurrent().getEngine().getVersion();
        } catch (IllegalStateException unavailable) {
            engineVersion = "UNAVAILABLE:" + unavailable.getClass().getSimpleName();
        }
        if (!"25.4.4.1.1".equals(engineVersion) || context.getEnv().isNativeAccessAllowed()
                || !"no-native".equals(context.getLanguage().nativeBackendId())
                || !context.getLanguage().useEspressoLibs()) {
            throw new IllegalStateException("Empty awt requires same Engine25.4.4.1.1"
                    + " / native=false / no-native / EspressoLibs; actual Engine=" + engineVersion);
        }
    }

    static void trace(String message) {
        if (Boolean.getBoolean("espressh.awt.trace")) System.out.println(message);
    }

    private static String property(Meta meta, String key) {
        return meta.toHostString((StaticObject)
                meta.java_lang_System_getProperty.invokeDirectStatic(meta.toGuestString(key)));
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
