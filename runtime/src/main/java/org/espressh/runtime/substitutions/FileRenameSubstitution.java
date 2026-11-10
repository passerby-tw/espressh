package org.espressh.runtime.substitutions;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.espresso.io.Checks;
import com.oracle.truffle.espresso.io.Throw;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.substitutions.JavaSubstitution;
import com.oracle.truffle.espresso.substitutions.LanguageFilter;
import java.io.IOException;
import java.nio.file.StandardCopyOption;
import org.graalvm.polyglot.Context;

/** Linux rename through the caller's existing Context filesystem. */
public final class FileRenameSubstitution extends JavaSubstitution {
    public static final Factory RENAME = new Factory(
            "rename0", "Ljava/io/TruffleFileSystem;", "Z",
            new String[]{"Ljava/io/File;", "Ljava/io/File;"}, false,
            LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null,
            FileRenameSubstitution::new);
    private volatile EspressoContext verifiedContext;

    public FileRenameSubstitution() { super(RENAME); }

    @Override public JavaSubstitution split() { return new FileRenameSubstitution(); }

    @Override @TruffleBoundary public Object invoke(Object[] args) {
        var context = EspressoContext.get(null);
        requireRuntime(context);
        var io = context.getTruffleIO();
        var source = Checks.nullCheck((StaticObject) args[0], context);
        var sourcePath = Checks.nullCheck(io.java_io_File_path.getObject(source), context);
        var target = Checks.nullCheck((StaticObject) args[1], context);
        var targetPath = Checks.nullCheck(io.java_io_File_path.getObject(target), context);
        String from = context.getMeta().toHostString(sourcePath);
        String to = context.getMeta().toHostString(targetPath);
        // TruffleFile's empty path denotes CWD; POSIX rename's empty pathname
        // must fail instead of attempting to move the Context's working directory.
        if (from.isEmpty() || to.isEmpty() || from.indexOf('\0') >= 0 || to.indexOf('\0') >= 0)
            return false;
        try {
            // Linux atomic move uses rename semantics, including replacement.
            // Never pre-delete a target or copy across filesystem boundaries.
            io.getInternalTruffleFile(from).move(io.getInternalTruffleFile(to),
                    StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException failure) {
            return false;
        } catch (SecurityException denied) {
            throw Throw.throwSecurityException(denied, context);
        }
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
                    "FILE_RENAME_UNSUPPORTED_RUNTIME: requires Linux x86_64 Guest25.0.4.1.1"
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
