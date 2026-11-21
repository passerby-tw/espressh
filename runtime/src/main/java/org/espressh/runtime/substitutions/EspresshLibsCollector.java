package org.espressh.runtime.substitutions;

import com.oracle.truffle.espresso.libs.Libs;
import com.oracle.truffle.espresso.libs.Lib;
import java.util.List;

public final class EspresshLibsCollector implements Libs.Extension {
    @Override
    public void register(List<Lib.Factory> factories) {
        factories.add(new org.espressh.runtime.substitutions.awt.EmptyAwtFactory());
        factories.add(new org.espressh.runtime.substitutions.EmptyJaasFactory());
    }
}
