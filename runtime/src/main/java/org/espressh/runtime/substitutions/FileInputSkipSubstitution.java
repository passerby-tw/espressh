package org.espressh.runtime.substitutions;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.espresso.io.Checks;
import com.oracle.truffle.espresso.io.Throw;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.substitutions.JavaSubstitution;
import com.oracle.truffle.espresso.substitutions.LanguageFilter;
import java.io.IOException;
import org.graalvm.polyglot.Context;

/** Seek the existing Guest stream descriptor without native metadata. */
public final class FileInputSkipSubstitution extends JavaSubstitution {
    public static final Factory SKIP = new Factory(
            "skip", "Ljava/io/FileInputStream;", "J",
            new String[]{"Ljava/io/FileInputStream;", "J"}, true,
            LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null,
            FileInputSkipSubstitution::new);
    private volatile EspressoContext verifiedContext;

    public FileInputSkipSubstitution() { super(SKIP); }

    @Override public JavaSubstitution split() { return new FileInputSkipSubstitution(); }

    @Override @TruffleBoundary public Object invoke(Object[] args) {
        var context = EspressoContext.get(null);
        requireRuntime(context);
        var io = context.getTruffleIO();
        var stream = Checks.nullCheck((StaticObject) args[0], context);
        long count = (Long) args[1];
        var access = com.oracle.truffle.espresso.io.FDAccess.forFileInputStream();
        // Read the actual open descriptor, including for skip(0). No path lookup,
        // reopening, or cached classification can invalidate rename/unlink/shared FD.
        long position = io.position(stream, access);
        long target;
        try {
            target = Math.addExact(position, count);
        } catch (ArithmeticException overflow) {
            throw Throw.throwIOException(new IOException("File skip offset overflow"), context);
        }
        if (target < 0) {
            throw Throw.throwIOException(new IOException("Negative file skip position"), context);
        }
        // TruffleIO translates closed/non-seekable descriptors and IO failures to
        // Guest exceptions. A successful seek can intentionally move beyond EOF.
        io.seek(stream, access, target);
        return count;
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
                    "FILE_INPUT_SKIP_UNSUPPORTED_RUNTIME: requires Linux x86_64 Guest25.0.4.1.1"
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
