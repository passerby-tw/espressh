package org.espressh.runtime.substitutions;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.espresso.classfile.descriptors.Name;
import com.oracle.truffle.espresso.classfile.descriptors.Signature;
import com.oracle.truffle.espresso.classfile.descriptors.Symbol;
import com.oracle.truffle.espresso.classfile.descriptors.Type;
import com.oracle.truffle.espresso.descriptors.EspressoSymbols;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.io.Checks;
import com.oracle.truffle.espresso.io.FDAccess;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.substitutions.JavaSubstitution;
import com.oracle.truffle.espresso.substitutions.LanguageFilter;
import com.oracle.truffle.espresso.vm.VM;
import java.util.concurrent.atomic.AtomicLong;
import org.graalvm.polyglot.Context;

/**
 * The original Guest JDK performs its buffer
 * transfer algorithm and owns both channels' ordinary read/write locks.
 * Only its missing positional-write primitive uses existing TruffleIO calls.
 * Pinned SDK/JDK bytes and FD ownership stay intact. Seek/write/restore does
 * not provide atomic positional IO across distinct channels sharing one FD.
 */
public final class GuestBufferTransferSubstitution extends JavaSubstitution {
    private static final String CHANNEL = "Lsun/nio/ch/FileChannelImpl;";
    private static final String WRITABLE = "Ljava/nio/channels/WritableByteChannel;";
    private static final String READABLE = "Ljava/nio/channels/ReadableByteChannel;";
    private static final FDAccess FD_ACCESS = FDAccess.forFileDescriptor();
    private static final Symbol<Name> TO_NAME = EspressoSymbols.SYMBOLS.putName("transferToArbitraryChannel");
    private static final Symbol<Signature> TO_SIGNATURE = EspressoSymbols.SYMBOLS.putSignature(
            EspressoSymbols.SYMBOLS.putType("J"), EspressoSymbols.SYMBOLS.putType("J"),
            EspressoSymbols.SYMBOLS.putType("J"), EspressoSymbols.SYMBOLS.putType(WRITABLE));
    private static final Symbol<Name> FROM_NAME = EspressoSymbols.SYMBOLS.putName("transferFromArbitraryChannel");
    private static final Symbol<Signature> FROM_SIGNATURE = EspressoSymbols.SYMBOLS.putSignature(
            EspressoSymbols.SYMBOLS.putType("J"), EspressoSymbols.SYMBOLS.putType(READABLE),
            EspressoSymbols.SYMBOLS.putType("J"), EspressoSymbols.SYMBOLS.putType("J"));
    private static final Symbol<Name> DISPATCHER_NAME = EspressoSymbols.SYMBOLS.putName("nd");
    private static final Symbol<Type> DISPATCHER_TYPE = EspressoSymbols.SYMBOLS.putType("Lsun/nio/ch/FileDispatcher;");
    private static final Symbol<Name> NEEDS_LOCK_NAME = EspressoSymbols.SYMBOLS.putName("needsPositionLock");
    private static final Symbol<Signature> NEEDS_LOCK_SIGNATURE = EspressoSymbols.SYMBOLS.putSignature(
            EspressoSymbols.SYMBOLS.putType("Z"));
    private static final boolean TRACE = Boolean.getBoolean("espressh.nio.guestbuffer.trace");
    private static final AtomicLong[] TRACE_COUNTS = {
            new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()};

    public static final Factory MAX_DIRECT_TRANSFER_SIZE = new Factory(
            "maxDirectTransferSize0", "Lsun/nio/ch/FileDispatcherImpl;", "I",
            new String[0], false, LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null,
            () -> new GuestBufferTransferSubstitution(Operation.MAX_SIZE));
    public static final Factory TRANSFER_TO = new Factory(
            "transferToDirect", CHANNEL, "J",
            new String[]{CHANNEL, "J", "I", WRITABLE}, true,
            LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null,
            () -> new GuestBufferTransferSubstitution(Operation.TO));
    public static final Factory TRANSFER_FROM = new Factory(
            "transferFromDirect", CHANNEL, "J",
            new String[]{CHANNEL, CHANNEL, "J", "J"}, true,
            LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null,
            () -> new GuestBufferTransferSubstitution(Operation.FROM));
    public static final Factory PWRITE = new Factory(
            "pwrite0", "Lsun/nio/ch/FileDispatcherImpl;", "I",
            new String[]{"Ljava/io/FileDescriptor;", "J", "I", "J"}, false,
            LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null,
            () -> new GuestBufferTransferSubstitution(Operation.PWRITE));

    private enum Operation { MAX_SIZE, TO, FROM, PWRITE }
    private final Operation operation;
    private boolean provedFromDelegate;
    private volatile EspressoContext verifiedContext;

    private GuestBufferTransferSubstitution(Operation operation) {
        super(switch (operation) {
            case MAX_SIZE -> MAX_DIRECT_TRANSFER_SIZE;
            case TO -> TRANSFER_TO;
            case FROM -> TRANSFER_FROM;
            case PWRITE -> PWRITE;
        });
        this.operation = operation;
    }

    @Override public JavaSubstitution split() { return new GuestBufferTransferSubstitution(operation); }

    @Override @TruffleBoundary public Object invoke(Object[] arguments) {
        var context = EspressoContext.get(null);
        verifyConfiguration(context);
        long invocation = TRACE ? TRACE_COUNTS[operation.ordinal()].incrementAndGet() : 0;
        trace(context, invocation, "enter", null);
        Object result;
        switch (operation) {
            case MAX_SIZE -> result = 0x7ffff000;
            case PWRITE -> {
                proveFromDelegate(context);
                result = positionalWrite(context, (StaticObject) arguments[0],
                        (long) arguments[1], (int) arguments[2], (long) arguments[3]);
            }
            case TO -> {
                var receiver = Checks.nullCheck((StaticObject) arguments[0], context);
                var delegate = requireDelegate(context, TO_NAME, TO_SIGNATURE);
                result = delegate.invokeDirectSpecial(receiver, (long) arguments[1],
                        (long) (int) arguments[2], arguments[3]);
            }
            case FROM -> {
                var receiver = Checks.nullCheck((StaticObject) arguments[0], context);
                var delegate = requireDelegate(context, FROM_NAME, FROM_SIGNATURE);
                result = delegate.invokeDirectSpecial(receiver, arguments[1],
                        (long) arguments[2], (long) arguments[3]);
            }
            default -> throw new AssertionError(operation);
        }
        trace(context, invocation, "result", result);
        return result;
    }

    private void verifyConfiguration(EspressoContext context) {
        if (verifiedContext == context) return;
        var meta = context.getMeta();
        String version = meta.toHostString((StaticObject) meta.java_lang_System_getProperty
                .invokeDirectStatic(meta.toGuestString("java.version")));
        String os = meta.toHostString((StaticObject) meta.java_lang_System_getProperty
                .invokeDirectStatic(meta.toGuestString("os.name")));
        String arch = meta.toHostString((StaticObject) meta.java_lang_System_getProperty
                .invokeDirectStatic(meta.toGuestString("os.arch")));
        if (!"Linux".equals(System.getProperty("os.name"))
                || !"25.0.4.1.1".equals(version) || !"Linux".equals(os)
                || !("amd64".equals(arch) || "x86_64".equals(arch))
                || !"25.4.4.1.1".equals(Context.getCurrent().getEngine().getVersion())
                || context.getEnv().isNativeAccessAllowed()
                || !"no-native".equals(context.getLanguage().nativeBackendId())
                || !context.getLanguage().useEspressoLibs()) {
            throw meta.throwExceptionWithMessage(meta.java_lang_UnsupportedOperationException,
                    "NIO_TRANSFER_UNSUPPORTED_RUNTIME: requires Linux x86_64 Guest25.0.4.1.1"
                    + " / Espresso25.4.4.1.1 / native=false / no-native / EspressoLibs");
        }
        requireDelegate(context, TO_NAME, TO_SIGNATURE);
        requireDelegate(context, FROM_NAME, FROM_SIGNATURE);
        verifyPositionLockPolicy(context);
        verifiedContext = context;
    }

    private static Method requireDelegate(EspressoContext context, Symbol<Name> name,
            Symbol<Signature> signature) {
        var owner = context.getTruffleIO().sun_nio_ch_FileChannelImpl;
        var delegate = owner.requireDeclaredMethod(name, signature);
        int flags = delegate.getModifiers();
        // ACC_PRIVATE, ACC_STATIC, ACC_NATIVE and ACC_ABSTRACT, from Guest metadata.
        if ((flags & 0x0002) == 0 || (flags & (0x0008 | 0x0100 | 0x0400)) != 0
                || !delegate.hasBytecodes() || delegate.getDeclaringKlass() != owner) {
            throw new IllegalStateException("Unexpected pinned Guest transfer delegate: " + delegate);
        }
        return delegate;
    }

    private static void verifyPositionLockPolicy(EspressoContext context) {
        var owner = context.getTruffleIO().sun_nio_ch_FileChannelImpl;
        var dispatcher = owner.requireDeclaredField(DISPATCHER_NAME, DISPATCHER_TYPE)
                .getObject(owner.getStatics());
        var needsLock = dispatcher.getKlass().requireDeclaredMethod(NEEDS_LOCK_NAME, NEEDS_LOCK_SIGNATURE);
        if (!Boolean.TRUE.equals(needsLock.invokeDirectSpecial(dispatcher))) {
            throw new IllegalStateException("Pinned Guest dispatcher needsPositionLock must remain true");
        }
    }

    private static int positionalWrite(EspressoContext context, StaticObject descriptor,
            long address, int count, long position) {
        var io = context.getTruffleIO();
        long saved = io.position(descriptor, FD_ACCESS);
        Throwable primary = null;
        try {
            io.seek(descriptor, FD_ACCESS, position);
            return io.writeAddress(descriptor, FD_ACCESS, address, count);
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                io.seek(descriptor, FD_ACCESS, saved);
            } catch (RuntimeException | Error restoreFailure) {
                if (primary == null) throw restoreFailure;
                // Preserve the original Guest IO/cancellation failure. This is a
                // Host diagnostic and does not mutate Guest Throwable metadata.
                if (restoreFailure != primary) primary.addSuppressed(restoreFailure);
            }
        }
    }

    private void proveFromDelegate(EspressoContext context) {
        if (!TRACE || provedFromDelegate) return;
        Boolean found = Truffle.getRuntime().iterateFrames(frame -> {
            var root = VM.getEspressoRootFromFrame(frame, context);
            if (root == null) return null;
            var method = root.getMethod();
            return method.getName() == FROM_NAME
                    && method.getDeclaringKlass() == context.getTruffleIO().sun_nio_ch_FileChannelImpl
                    ? Boolean.TRUE : null;
        });
        if (Boolean.TRUE.equals(found)) {
            provedFromDelegate = true;
            System.err.println("NIO_GUEST_BUFFER_DELEGATE context="
                    + Integer.toHexString(System.identityHashCode(context))
                    + " method=FileChannelImpl.transferFromArbitraryChannel actual_guest_frame=true");
        }
    }

    private void trace(EspressoContext context, long invocation, String phase, Object result) {
        if (TRACE) {
            System.err.println("NIO_GUEST_BUFFER_HOOK operation=" + operation + " phase=" + phase
                    + " context=" + Integer.toHexString(System.identityHashCode(context))
                    + " invocation=" + invocation + " result=" + result);
        }
    }
}
