package org.espressh.runtime.substitutions;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.espresso.io.Checks;
import com.oracle.truffle.espresso.io.Throw;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.substitutions.JavaSubstitution;
import com.oracle.truffle.espresso.substitutions.LanguageFilter;
import java.io.IOException;
import java.nio.file.NoSuchFileException;

/** Retain File.length's zero result for a nonexistent path. */
public final class FileLengthSubstitution extends JavaSubstitution {
    public static final Factory GET_LENGTH = new Factory(
        "getLength0", "Ljava/io/TruffleFileSystem;", "J",
        new String[]{"Ljava/io/File;"}, false,
        LanguageFilter.AlwaysValid.INSTANCE, (byte)0, null,
        FileLengthSubstitution::new);

    public FileLengthSubstitution() { super(GET_LENGTH); }

    @Override public JavaSubstitution split() { return new FileLengthSubstitution(); }

    @Override @TruffleBoundary public Object invoke(Object[] args) {
        var context = EspressoContext.get(null);
        var io = context.getTruffleIO();
        var guestFile = Checks.nullCheck((StaticObject)args[0], context);
        var guestPath = Checks.nullCheck(io.java_io_File_path.getObject(guestFile), context);
        var file = io.getInternalTruffleFile(context.getMeta().toHostString(guestPath));
        try {
            // Query once: a separate exists() check would race with removal/creation
            // and would hide failures that are not a missing file.
            return file.size();
        } catch (NoSuchFileException missing) {
            return 0L;
        } catch (IOException failure) {
            throw Throw.throwIOException(failure, context);
        } catch (SecurityException denied) {
            throw Throw.throwSecurityException(denied, context);
        }
    }
}
