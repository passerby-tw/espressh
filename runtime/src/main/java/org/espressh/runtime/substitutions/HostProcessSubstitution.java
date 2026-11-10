package org.espressh.runtime.substitutions;

import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.substitutions.JavaSubstitution;
import com.oracle.truffle.espresso.substitutions.LanguageFilter;

/** Enforce Host-process DENY before the pinned EspressoLibs forkAndExec receiver defect. */
public final class HostProcessSubstitution extends JavaSubstitution {
    public static final Factory START = new Factory(
        "start", "Ljava/lang/ProcessImpl;", "Ljava/lang/Process;",
        new String[]{"[Ljava/lang/String;", "Ljava/util/Map;", "Ljava/lang/String;",
            "[Ljava/lang/ProcessBuilder$Redirect;", "Z"},
        false, LanguageFilter.AlwaysValid.INSTANCE, (byte)0, null,
        HostProcessSubstitution::new);

    public HostProcessSubstitution() { super(START); }

    @Override public JavaSubstitution split() { return new HostProcessSubstitution(); }

    @Override public Object invoke(Object[] args) {
        var meta=EspressoContext.get(null).getMeta();
        throw meta.throwExceptionWithMessage(meta.java_lang_SecurityException,
            "process creation is not allowed!");
    }
}
