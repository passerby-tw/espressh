package org.espressh.runtime.substitutions;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.substitutions.JavaSubstitution;
import com.oracle.truffle.espresso.substitutions.LanguageFilter;
import com.oracle.truffle.espresso.vm.Management;

/** Expose the existing SDK's whole-Host-JVM memory and GC readings. */
public final class ManagementSubstitution extends JavaSubstitution {
    private enum Operation { MEMORY_USAGE, GC_COLLECTION_TIME, GC_COLLECTION_COUNT }

    public static final Factory MEMORY_USAGE = new Factory(
        "getMemoryUsage0", "Lsun/management/MemoryImpl;", "Ljava/lang/management/MemoryUsage;",
        new String[]{"Lsun/management/MemoryImpl;", "Z"}, true,
        LanguageFilter.AlwaysValid.INSTANCE, (byte)0, null,
        () -> new ManagementSubstitution(Operation.MEMORY_USAGE));

    public static final Factory GC_COLLECTION_TIME = new Factory(
        "getCollectionTime", "Lsun/management/GarbageCollectorImpl;", "J",
        new String[]{"Lsun/management/GarbageCollectorImpl;"}, true,
        LanguageFilter.AlwaysValid.INSTANCE, (byte)0, null,
        () -> new ManagementSubstitution(Operation.GC_COLLECTION_TIME));

    public static final Factory GC_COLLECTION_COUNT = new Factory(
        "getCollectionCount", "Lsun/management/GarbageCollectorImpl;", "J",
        new String[]{"Lsun/management/GarbageCollectorImpl;"}, true,
        LanguageFilter.AlwaysValid.INSTANCE, (byte)0, null,
        () -> new ManagementSubstitution(Operation.GC_COLLECTION_COUNT));

    private final Operation operation;

    private ManagementSubstitution(Operation operation) {
        super(switch (operation) {
            case MEMORY_USAGE -> MEMORY_USAGE;
            case GC_COLLECTION_TIME -> GC_COLLECTION_TIME;
            case GC_COLLECTION_COUNT -> GC_COLLECTION_COUNT;
        });
        this.operation = operation;
    }

    @Override public JavaSubstitution split() { return new ManagementSubstitution(operation); }

    @Override @TruffleBoundary public Object invoke(Object[] args) {
        var context = EspressoContext.get(null);
        var management = context.getLibsState().checkAndGetManagement();
        // The SDK retains Context-local Guest beans/MemoryUsage objects and the reverse
        // Host-bean mapping. Its getters preserve unknown values and management errors.
        return switch (operation) {
            case MEMORY_USAGE -> management.GetMemoryUsage((boolean)args[1], context.getMeta());
            case GC_COLLECTION_TIME -> management.GetLongAttribute(
                (StaticObject)args[0], Management.JMM_GC_TIME_MS);
            case GC_COLLECTION_COUNT -> management.GetLongAttribute(
                (StaticObject)args[0], Management.JMM_GC_COUNT);
        };
    }
}
