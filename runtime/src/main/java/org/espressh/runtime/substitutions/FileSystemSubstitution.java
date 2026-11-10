package org.espressh.runtime.substitutions;

import com.oracle.truffle.espresso.io.Checks;
import com.oracle.truffle.espresso.io.Throw;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.substitutions.JavaSubstitution;
import com.oracle.truffle.espresso.substitutions.LanguageFilter;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;

/** Complete the pinned EspressoLibs exclusive-create TODO through Context filesystem IO. */
public final class FileSystemSubstitution extends JavaSubstitution {
    public static final Factory EXCLUSIVE_CREATE = new Factory(
        "createFileExclusively0", "Ljava/io/TruffleFileSystem;", "Z",
        new String[]{"Ljava/lang/String;"}, false,
        LanguageFilter.AlwaysValid.INSTANCE, (byte)0, null,
        FileSystemSubstitution::new);

    public FileSystemSubstitution() { super(EXCLUSIVE_CREATE); }

    @Override public JavaSubstitution split() { return new FileSystemSubstitution(); }

    @Override public Object invoke(Object[] args) {
        var context = EspressoContext.get(null);
        var guestPath = Checks.nullCheck((StaticObject)args[0], context);
        String path = context.getMeta().toHostString(guestPath);
        try {
            // TruffleFile.createFile uses CREATE_NEW + WRITE and closes its channel.
            // The Context filesystem supplies CWD, atomic collision and symlink behavior.
            context.getEnv().getPublicTruffleFile(path).createFile();
            return true;
        } catch (FileAlreadyExistsException exists) {
            return false;
        } catch (IOException failure) {
            throw Throw.throwIOException(failure, context);
        } catch (SecurityException denied) {
            throw Throw.throwSecurityException(denied, context);
        }
    }
}
