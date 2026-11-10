package org.espressh.runtime.substitutions;

import com.oracle.truffle.espresso.libs.Lib;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import java.util.List;

/** Preserve Guest UnixSystem's load of jaas without native bindings. */
public final class EmptyJaasFactory implements Lib.Factory {
    @Override public String name() { return "jaas"; }

    @Override public Lib create(EspressoContext context) {
        UnixIdentitySubstitution.requireLibraryRuntime(context);
        // SDK owns the per-Context library and its synthetic handle. The exact
        // getUnixInfo substitution supplies identity; other JNI symbols stay absent.
        return new Lib(context, List.of(), "jaas");
    }
}
