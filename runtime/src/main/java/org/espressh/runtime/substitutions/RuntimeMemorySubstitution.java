package org.espressh.runtime.substitutions;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.substitutions.JavaSubstitution;
import com.oracle.truffle.espresso.substitutions.LanguageFilter;
import com.oracle.truffle.espresso.vm.VM;

/** Reuse the SDK's whole-Host-JVM Runtime heap and GC operations. */
public final class RuntimeMemorySubstitution extends JavaSubstitution {
    private static final String OWNER = "Ljava/lang/Runtime;";

    private enum Operation { TOTAL_MEMORY, FREE_MEMORY, GC }

    public static final Factory TOTAL_MEMORY = new Factory("totalMemory", OWNER, "J",
            new String[]{OWNER}, true, LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null,
            () -> new RuntimeMemorySubstitution(Operation.TOTAL_MEMORY));

    public static final Factory FREE_MEMORY = new Factory("freeMemory", OWNER, "J",
            new String[]{OWNER}, true, LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null,
            () -> new RuntimeMemorySubstitution(Operation.FREE_MEMORY));

    public static final Factory GC = new Factory("gc", OWNER, "V",
            new String[]{OWNER}, true, LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null,
            () -> new RuntimeMemorySubstitution(Operation.GC));

    private final Operation operation;

    private RuntimeMemorySubstitution(Operation operation) {
        super(switch (operation) {
            case TOTAL_MEMORY -> TOTAL_MEMORY;
            case FREE_MEMORY -> FREE_MEMORY;
            case GC -> GC;
        });
        this.operation = operation;
    }

    @Override public JavaSubstitution split() { return new RuntimeMemorySubstitution(operation); }

    @Override @TruffleBoundary public Object invoke(Object[] arguments) {
        return switch (operation) {
            case TOTAL_MEMORY -> VM.JVM_TotalMemory();
            case FREE_MEMORY -> VM.JVM_FreeMemory();
            case GC -> {
                VM.JVM_GC();
                yield StaticObject.NULL;
            }
        };
    }
}
