package org.espressh.bootstrap.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Guest-local Process and streams; the opaque endpoint belongs to this caller only. */
public final class GuestProcess extends Process {
    private static final int MAX_BYTES = 65536;
    private static final long WAIT_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static Bridge binding;

    private final Bridge bridge;
    private final Object endpoint;
    private final InputStream stdout;
    private final InputStream stderr;
    private final OutputStream stdin;

    private GuestProcess(Bridge bridge, Object endpoint) {
        this.bridge = bridge;
        this.endpoint = endpoint;
        stdout = new PipeInput(1);
        stderr = new PipeInput(2);
        stdin = new PipeOutput();
    }

    /** Exact target used by the ProcessImpl.start substitution. */
    public static Process start(String[] command, Map<String, String> environment,
            String directory, ProcessBuilder.Redirect[] redirects, boolean mergeError)
            throws IOException {
        // RedirectPipeImpl also reports Type.PIPE. Only the public PIPE singleton is valid.
        if (redirects != null) {
            if (redirects.length != 3) throw new IOException("Invalid process redirects");
            for (ProcessBuilder.Redirect redirect : redirects) {
                if (redirect != ProcessBuilder.Redirect.PIPE) {
                    throw new IOException("Only PIPE process redirects are supported");
                }
            }
        }
        if (command == null || command.length == 0) throw new IOException("Missing Java command");
        for (String argument : command) {
            if (argument == null) throw new IOException("Null process command argument");
        }
        var request = new ManagedProcessRequest(command, environment, directory, mergeError);
        try {
            Bridge bridge = binding();
            return new GuestProcess(bridge, bridge.call(bridge.sys, "startProcess", request));
        } catch (RuntimeException failure) {
            throw ioFailure("Cannot start managed Java process", failure);
        }
    }

    private static synchronized Bridge binding() throws IOException {
        if (binding == null) binding = new Bridge();
        return binding;
    }

    @Override public OutputStream getOutputStream() { return stdin; }
    @Override public InputStream getInputStream() { return stdout; }
    @Override public InputStream getErrorStream() { return stderr; }

    @Override
    public int waitFor() throws InterruptedException {
        for (;;) {
            checkInterrupted();
            boolean exited = await(WAIT_SLICE_NANOS);
            checkInterrupted();
            if (exited) return exitValue();
        }
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        checkInterrupted();
        long budget = unit.toNanos(timeout); // TimeUnit saturates rather than overflowing.
        if (budget <= 0) return !isAlive();
        long started = System.nanoTime();
        for (;;) {
            checkInterrupted();
            long elapsed = System.nanoTime() - started;
            if (elapsed >= budget) return !isAlive();
            boolean exited = await(Math.min(WAIT_SLICE_NANOS, budget - elapsed));
            checkInterrupted();
            if (exited) return true;
        }
    }

    private boolean await(long timeoutNanos) {
        return bridge.bool(bridge.call(endpoint, "awaitExit", timeoutNanos));
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
    }

    @Override
    public int exitValue() {
        if (isAlive()) throw new IllegalThreadStateException("Process has not exited");
        return bridge.integer(bridge.call(endpoint, "exitValue"));
    }

    @Override public boolean isAlive() { return bridge.bool(bridge.call(endpoint, "isAlive")); }
    @Override public long pid() { return bridge.number(bridge.call(endpoint, "pid")); }
    @Override public void destroy() { bridge.call(endpoint, "destroy", false); }
    @Override public Process destroyForcibly() { bridge.call(endpoint, "destroy", true); return this; }
    @Override public boolean supportsNormalTermination() { return false; }

    @Override public ProcessHandle toHandle() { throw unsupported(); }
    @Override public ProcessHandle.Info info() { throw unsupported(); }
    @Override public Stream<ProcessHandle> children() { throw unsupported(); }
    @Override public Stream<ProcessHandle> descendants() { throw unsupported(); }
    @Override public CompletableFuture<Process> onExit() { throw unsupported(); }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Managed process graph operations are not supported");
    }

    private final class PipeInput extends InputStream {
        private final int stream;
        private final Object readLock = new Object();
        private volatile boolean closed;
        private boolean eof;

        PipeInput(int stream) { this.stream = stream; }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            synchronized (readLock) {
                if (closed) throw new IOException("Process stream closed");
                if (length == 0) return 0;
                if (eof) return -1;
                int requested = Math.min(length, MAX_BYTES);
                try {
                    byte[] result = (byte[]) bridge.call(endpoint, "read", stream, requested);
                    if (result.length > requested) throw new IOException("Invalid process read size");
                    if (result.length == 0) {
                        eof = true;
                        return -1;
                    }
                    System.arraycopy(result, 0, bytes, offset, result.length);
                    return result.length;
                } catch (RuntimeException failure) {
                    throw ioFailure("Process read failed", failure);
                }
            }
        }

        @Override
        public void close() throws IOException {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            // Do not take readLock: closing must wake another thread blocked in read().
            try {
                bridge.call(endpoint, "closeStream", stream);
            } catch (RuntimeException failure) {
                throw ioFailure("Process stream close failed", failure);
            }
        }
    }

    private final class PipeOutput extends OutputStream {
        private final Object writeLock = new Object();
        private volatile boolean closed;

        @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}); }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            synchronized (writeLock) {
                if (closed) throw new IOException("Process stream closed");
                try {
                    for (int done = 0; done < length;) {
                        if (closed) throw new IOException("Process stream closed");
                        int count = Math.min(length - done, MAX_BYTES);
                        byte[] chunk = Arrays.copyOfRange(bytes, offset + done, offset + done + count);
                        bridge.call(endpoint, "writeStdin", chunk);
                        done += count;
                    }
                } catch (RuntimeException failure) {
                    throw ioFailure("Process write failed", failure);
                }
            }
        }

        @Override
        public void close() throws IOException {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            // Do not take writeLock: a full pipe must be closable while write() is blocked.
            try {
                bridge.call(endpoint, "closeStream", 0);
            } catch (RuntimeException failure) {
                throw ioFailure("Process stream close failed", failure);
            }
        }
    }

    private static IOException ioFailure(String operation, RuntimeException failure) {
        return new IOException(operation + ": " + failure.getMessage(), failure);
    }

    /** Reflection metadata and imported binding remain local to this Guest Context. */
    private static final class Bridge {
        final Object sys;
        private final Method invoke, asLong, asInt, asBoolean;

        Bridge() throws IOException {
            try {
                Class<?> polyglot = Class.forName("com.oracle.truffle.espresso.polyglot.Polyglot");
                Class<?> interop = Class.forName("com.oracle.truffle.espresso.polyglot.Interop");
                invoke = interop.getMethod("invokeMember", Object.class, String.class, Object[].class);
                asLong = interop.getMethod("asLong", Object.class);
                asInt = interop.getMethod("asInt", Object.class);
                asBoolean = interop.getMethod("asBoolean", Object.class);
                Method isNull = interop.getMethod("isNull", Object.class);
                Object imported = polyglot.getMethod("importObject", String.class).invoke(null, "sys");
                if ((Boolean) reflect(isNull, imported)) throw new IOException("Managed process service unavailable");
                sys = imported;
            } catch (ReflectiveOperationException failure) {
                Throwable cause = unwrap(failure);
                if (cause instanceof Error error) throw error;
                throw new IOException("Cannot bind Guest process service", cause);
            }
        }

        Object call(Object target, String name, Object... arguments) {
            return reflect(invoke, target, name, arguments);
        }

        long number(Object value) { return (Long) reflect(asLong, value); }
        int integer(Object value) { return (Integer) reflect(asInt, value); }
        boolean bool(Object value) { return (Boolean) reflect(asBoolean, value); }

        private static Object reflect(Method method, Object... arguments) {
            try {
                return method.invoke(null, arguments);
            } catch (ReflectiveOperationException failure) {
                Throwable cause = unwrap(failure);
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException("Managed process binding failed: " + method.getName(), cause);
            }
        }

        private static Throwable unwrap(Throwable failure) {
            while (failure instanceof InvocationTargetException wrapped && wrapped.getCause() != null) {
                failure = wrapped.getCause();
            }
            return failure;
        }
    }
}
