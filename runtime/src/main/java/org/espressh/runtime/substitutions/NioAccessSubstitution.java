package org.espressh.runtime.substitutions;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.espresso.io.Throw;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.substitutions.JavaSubstitution;
import com.oracle.truffle.espresso.substitutions.LanguageFilter;

/** Preserve missing-path recovery in Guest Files.createDirectories. */
public final class NioAccessSubstitution extends JavaSubstitution {
    public static final Factory CHECK_ACCESS = new Factory(
        "checkAccess0", "Lsun/nio/fs/TruffleFileSystemProvider;", "V",
        new String[]{"Lsun/nio/fs/TrufflePath;", "I"}, false,
        LanguageFilter.AlwaysValid.INSTANCE, (byte)0, null,
        NioAccessSubstitution::new);

    public NioAccessSubstitution() { super(CHECK_ACCESS); }

    @Override public JavaSubstitution split() { return new NioAccessSubstitution(); }

    @Override @TruffleBoundary public Object invoke(Object[] args) {
        var context = EspressoContext.get(null);
        var path = (StaticObject)args[0];
        int accessModesMask = (int)args[1];
        var file = (TruffleFile)context.getTruffleIO()
            .sun_nio_fs_TrufflePath_0file.getHiddenObject(path);
        // Same Context-local file and ordered checks as EspressoLibs 25.4.4.1.1.
        // Only the missing-path exception differs; permission failures stay unchanged.
        if (!file.exists()) {
            throw Throw.throwNoSuchFileException(file.getPath(), context);
        }
        if ((accessModesMask & (1 << 0)) != 0 && !file.isReadable()) {
            throw Throw.throwIOException("Read Access was denied for path: " + file.getPath(), context);
        }
        if ((accessModesMask & (1 << 1)) != 0 && !file.isWritable()) {
            throw Throw.throwIOException("Write Access was denied for path: " + file.getPath(), context);
        }
        if ((accessModesMask & (1 << 2)) != 0 && !file.isExecutable()) {
            throw Throw.throwIOException("Executable Access was denied for path: " + file.getPath(), context);
        }
        return StaticObject.NULL;
    }
}
