package org.espressh.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.espressh.core.BytePipe;
import org.espressh.core.ProcessIo;
import org.espressh.core.ProcessRecord;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

/** Caller-bound, direct-child capability. Contains no Guest value or exported Host stream. */
final class ManagedProcessEndpoint implements ProxyObject {
    static final int MAX_BYTES = 65536;
    private static final Set<String> MEMBERS = Set.of("pid", "read", "writeStdin", "closeStream",
            "awaitExit", "exitValue", "isAlive", "destroy");
    static final class Pipes {
        final BytePipe input = new BytePipe(MAX_BYTES), output = new BytePipe(MAX_BYTES), error;
        final boolean merged;
        Pipes(boolean merged) { this.merged = merged; error = merged ? output : new BytePipe(MAX_BYTES); }
        ProcessIo childIo() { return new ProcessIo(input.input(), output.output(), error.output()); }
        void abort() { input.close(); output.close(); error.close(); }
    }
    private final JvkProcessApi owner;
    private final ProcessRecord process;
    private final Pipes pipes;
    private final CompletableFuture<Void> childCleanup;
    private final CompletableFuture<Object> completion;

    ManagedProcessEndpoint(JvkProcessApi owner, ProcessRecord process,
                           EspressoRuntimeProvider.Execution execution, Pipes pipes) {
        this.owner = owner; this.process = process; this.pipes = pipes;
        childCleanup = execution.cleanupCompletion();
        completion = CompletableFuture.anyOf(childCleanup, owner.ownerClosedFuture());
    }

    void ownerClosed() { pipes.abort(); }

    @Override public Object getMember(String member) {
        if (!MEMBERS.contains(member)) return null;
        return (ProxyExecutable) args -> {
            owner.processAuthorized();
            try {
                return switch (member) {
                    case "pid" -> { arity(args, 0); yield process.pid(); }
                    case "isAlive" -> { arity(args, 0); yield !finished(); }
                    case "exitValue" -> {
                        arity(args, 0);
                        if (!finished()) throw new IllegalThreadStateException("Child is still alive");
                        Integer code = process.outcome().orElseThrow().exitCode();
                        yield code == null ? 1 : code;
                    }
                    case "awaitExit" -> { arity(args, 1); yield await(args[0].asLong()); }
                    case "destroy" -> {
                        arity(args, 1); args[0].asBoolean();
                        if (!finished()) owner.stopChild(process.pid());
                        yield null;
                    }
                    case "read" -> { arity(args, 2); yield read(args[0].asInt(), args[1].asInt()); }
                    case "writeStdin" -> {
                        arity(args, 1); byte[] bytes = bytes(args[0]);
                        pipes.input.output().write(bytes); yield null;
                    }
                    case "closeStream" -> { arity(args, 1); close(args[0].asInt()); yield null; }
                    default -> throw new AssertionError(member);
                };
            } catch (IOException failure) { throw new UncheckedIOException(failure); }
        };
    }

    private boolean finished() {
        return process.state().terminal() && childCleanup.isDone() && !childCleanup.isCompletedExceptionally();
    }

    private boolean await(long nanos) throws IOException {
        if (nanos < -1) throw new IOException("Invalid wait timeout");
        try {
            if (nanos == -1) completion.get();
            else completion.get(nanos, TimeUnit.NANOSECONDS);
            owner.processAuthorized();
            return finished();
        } catch (TimeoutException expected) { return false; }
        catch (InterruptedException interrupted) {
            // Espresso may also interrupt the backing Host thread. Return to
            // Guest Java, where Thread.interrupted() clears the status and throws
            // the required Guest InterruptedException without stopping the child.
            Thread.currentThread().interrupt(); return false;
        } catch (ExecutionException failed) { throw new IOException("Child cleanup failed", failed.getCause()); }
    }

    private Object read(int stream, int count) throws IOException {
        if (count <= 0 || count > MAX_BYTES || (stream != 1 && stream != 2))
            throw new IOException("Invalid read range or stream");
        byte[] bytes = new byte[count];
        int n = stream == 2 && pipes.merged ? -1
                : (stream == 1 ? pipes.output : pipes.error).input().read(bytes);
        byte[] snapshot = n < 0 ? new byte[0] : Arrays.copyOf(bytes, n);
        // Materialize a byte[] owned by this very Guest Context. No Host array or
        // cross-Context Value escapes, and the Guest copies it without per-byte interop.
        return com.oracle.truffle.espresso.runtime.staticobject.StaticObject.wrap(snapshot,
                com.oracle.truffle.espresso.runtime.EspressoContext.get(null).getMeta());
    }

    private static byte[] bytes(Value value) throws IOException {
        var context = com.oracle.truffle.espresso.runtime.EspressoContext.get(null);
        Object receiver = context.getEnv().asGuestValue(value);
        if (!(receiver instanceof com.oracle.truffle.espresso.runtime.staticobject.StaticObject array)
                || array.isForeignObject() || array.getKlass() != context.getMeta()._byte_array
                || array.length(context.getLanguage()) > MAX_BYTES)
            throw new IOException("Invalid stdin chunk");
        // Unwrap only a byte[] from this Context and copy before crossing to the
        // child pipe. No Guest object or backing array is retained by the endpoint.
        return (byte[]) array.cloneWrappedArray(context.getLanguage());
    }

    private void close(int stream) throws IOException {
        switch (stream) {
            case 0 -> pipes.input.output().close();
            case 1 -> pipes.output.input().close();
            case 2 -> { if (!pipes.merged) pipes.error.input().close(); }
            default -> throw new IOException("Invalid stream");
        }
    }

    private static void arity(Value[] args, int count) {
        if (args.length != count) throw new IllegalArgumentException("Invalid process endpoint arguments");
    }
    @Override public Object getMemberKeys() { return MEMBERS.toArray(String[]::new); }
    @Override public boolean hasMember(String key) { return MEMBERS.contains(key); }
    @Override public void putMember(String key, Value value) { throw new UnsupportedOperationException("Immutable endpoint"); }
}
