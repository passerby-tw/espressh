package org.espressh.runtime.substitutions;
import com.oracle.truffle.espresso.substitutions.*;
import com.oracle.truffle.espresso.runtime.EspressoContext;
/** Pinned Espresso 25.4.4.1.1: deny public Host process discovery in Guest Java. */
public final class ProcessHandleSubstitution extends JavaSubstitution {
    public static final Factory CURRENT = factory("current", "Ljava/lang/ProcessHandle;", new String[0]);
    public static final Factory OF = factory("of", "Ljava/util/Optional;", new String[]{"J"});
    public static final Factory ALL = factory("allProcesses", "Ljava/util/stream/Stream;", new String[0]);
    private static Factory factory(String name, String result, String[] params) {
        return new Factory(name, "Ljava/lang/ProcessHandle;", result, params, false,
            LanguageFilter.AlwaysValid.INSTANCE, (byte)0, null,
            () -> new ProcessHandleSubstitution(name));
    }
    private final String name;
    public ProcessHandleSubstitution(String name) {
        super(name.equals("current") ? CURRENT : name.equals("of") ? OF : ALL);
        this.name=name;
    }
    public JavaSubstitution split() { return new ProcessHandleSubstitution(name); }
    public Object invoke(Object[] args) {
        var meta=EspressoContext.get(null).getMeta();
        throw meta.throwExceptionWithMessage(meta.java_lang_SecurityException,
            "JVK_PROCESS_HANDLE_DENIED: " + name);
    }
}
