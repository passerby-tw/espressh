package org.espressh.runtime.substitutions;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.espresso.io.Checks;
import com.oracle.truffle.espresso.io.Throw;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.substitutions.JavaSubstitution;
import com.oracle.truffle.espresso.substitutions.LanguageFilter;
import java.io.IOException;
import java.util.ArrayDeque;

/** Anchor the pinned EspressoLibs ancestor lookup at the Context's CWD. */
public final class CanonicalizeSubstitution extends JavaSubstitution {
    public static final Factory CANONICALIZE = new Factory(
        "canonicalize0", "Ljava/io/TruffleFileSystem;", "Ljava/lang/String;",
        new String[]{"Ljava/lang/String;"}, false,
        LanguageFilter.AlwaysValid.INSTANCE, (byte)0, null,
        CanonicalizeSubstitution::new);

    public CanonicalizeSubstitution() { super(CANONICALIZE); }

    @Override public JavaSubstitution split() { return new CanonicalizeSubstitution(); }

    @Override @TruffleBoundary public Object invoke(Object[] args) {
        var context = EspressoContext.get(null);
        var guestPath = Checks.nullCheck((StaticObject)args[0], context);
        var meta = context.getMeta();
        try {
            var file = context.getTruffleIO().getInternalTruffleFile(meta.toHostString(guestPath))
                .getAbsoluteFile();
            var suffix = new ArrayDeque<String>();
            // Resolve the existing ancestor first, then append missing names in their original
            // order, matching EspressoLibs 25.4.4.1.1. Do not normalize before resolving symlinks.
            while (!file.exists()) {
                var parent = file.getParent();
                if (parent == null)
                    throw Throw.throwIOException("Canonicalize failed for path: " + file.getPath(), context);
                suffix.addFirst(file.getName());
                file = parent;
            }
            var canonical = file.getCanonicalFile();
            for (String name : suffix) canonical = canonical.resolve(name);
            return meta.toGuestString(canonical.getPath());
        } catch (IOException failure) {
            throw Throw.throwIOException(failure, context);
        } catch (SecurityException denied) {
            throw Throw.throwSecurityException(denied, context);
        }
    }
}
