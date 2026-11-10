package org.espressh.core;

import java.util.concurrent.atomic.AtomicReference;

/** Exclusive active-JVK ownership in the Host application class loader. */
public final class KernelOwnership implements AutoCloseable {
    private static final AtomicReference<KernelOwnership> ACTIVE = new AtomicReference<>();
    private KernelOwnership() { }
    public static KernelOwnership acquire() {
        KernelOwnership owner = new KernelOwnership();
        if (!ACTIVE.compareAndSet(null, owner)) throw new KernelException(ErrorCode.INVALID_STATE, "A JVK is already active");
        return owner;
    }
    @Override public void close() { ACTIVE.compareAndSet(this, null); }
}
