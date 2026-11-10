package org.espressh.bootstrap.sys;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.espressh.api.JvkSystem;
import org.espressh.api.LaunchRequestDto;
import org.espressh.api.ProcessInfoDto;
import org.espressh.api.ProcessOutcomeDto;
import org.espressh.api.RuntimeInfoDto;
import org.espressh.api.RuntimeSnapshotDto;
import org.espressh.api.TerminalCreationDto;
import org.espressh.api.TerminalStateDto;

/** Guest-local binding and value conversion; Host sys remains the sole authority. */
public final class GuestJvkSystem extends JvkSystem {
    private final Object sys;
    private final Method invoke, read, arraySize, element, asLong, asInt, asString, asBoolean, isNull;

    public GuestJvkSystem() {
        try {
            Class<?> polyglot = Class.forName("com.oracle.truffle.espresso.polyglot.Polyglot");
            Class<?> interop = Class.forName("com.oracle.truffle.espresso.polyglot.Interop");
            invoke = interop.getMethod("invokeMember", Object.class, String.class, Object[].class);
            read = interop.getMethod("readMember", Object.class, String.class);
            arraySize = interop.getMethod("getArraySize", Object.class);
            element = interop.getMethod("readArrayElement", Object.class, long.class);
            asLong = interop.getMethod("asLong", Object.class);
            asInt = interop.getMethod("asInt", Object.class);
            asString = interop.getMethod("asString", Object.class);
            asBoolean = interop.getMethod("asBoolean", Object.class);
            isNull = interop.getMethod("isNull", Object.class);
            Object imported = polyglot.getMethod("importObject", String.class).invoke(null, "sys");
            if ((Boolean) isNull.invoke(null, imported)) {
                throw new IllegalStateException("JVK_PROVIDER_UNAVAILABLE: sys binding is unavailable");
            }
            sys = imported;
        } catch (ReflectiveOperationException failure) {
            Throwable cause = unwrap(failure);
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("JVK_PROVIDER_UNAVAILABLE: cannot bind Guest sys", cause);
        }
    }

    @Override
    public TerminalCreationDto createTerminal(LaunchRequestDto request) {
        Object result = call("createTerminal", request);
        return new TerminalCreationDto(number(member(result, "terminalId")),
                number(member(result, "rootPid")));
    }

    @Override
    public void awaitShutdown() {
        call("awaitShutdown");
    }

    @Override
    public void requestShutdown() {
        call("requestShutdown");
    }

    @Override
    public TerminalStateDto terminalState() {
        Object result = call("terminalState");
        return new TerminalStateDto(bool(member(result, "root")));
    }

    @Override
    public boolean tryCloseTerminal() {
        return bool(call("tryCloseTerminal"));
    }

    @Override
    public boolean kill(long pid) {
        return bool(call("kill", pid));
    }

    @Override
    public RuntimeSnapshotDto runtimeList() {
        Object result = call("runtimeList");
        Object rows = member(result, "runtimes");
        long size = size(rows);
        var runtimes = new ArrayList<RuntimeInfoDto>(Math.toIntExact(size));
        for (long index = 0; index < size; index++) {
            Object row = element(rows, index);
            runtimes.add(new RuntimeInfoDto(text(member(row, "id")), text(member(row, "version")),
                    text(member(row, "vendor")), text(member(row, "javaHome"))));
        }
        return new RuntimeSnapshotDto(text(member(result, "hostVersion")),
                text(member(result, "hostJavaHome")), text(member(result, "espressoVersion")), runtimes);
    }

    @Override
    public List<ProcessInfoDto> processList() {
        Object rows = call("processList");
        long size = size(rows);
        var processes = new ArrayList<ProcessInfoDto>(Math.toIntExact(size));
        for (long index = 0; index < size; index++) {
            Object row = element(rows, index);
            processes.add(new ProcessInfoDto(number(member(row, "pid")),
                    number(member(row, "parentPid")), number(member(row, "terminalId")),
                    text(member(row, "state")), text(member(row, "runtimeId")),
                    text(member(row, "command"))));
        }
        return List.copyOf(processes);
    }

    @Override
    public long spawn(LaunchRequestDto request) {
        return number(call("spawn", request));
    }

    @Override
    public ProcessOutcomeDto waitOutcome(long pid) {
        Object result = call("waitOutcome", pid);
        Object exitCode = member(result, "exitCode");
        Integer code = nil(exitCode) ? null : integer(exitCode);
        return new ProcessOutcomeDto(text(member(result, "state")), code,
                text(member(result, "error")), text(member(result, "message")));
    }

    @Override
    public int runApplet(LaunchRequestDto request) {
        return integer(call("runApplet", request));
    }

    private Object call(String name, Object... arguments) {
        return reflect(invoke, sys, name, arguments);
    }

    private Object member(Object target, String name) {
        return reflect(read, target, name);
    }

    private long size(Object target) {
        return (Long) reflect(arraySize, target);
    }

    private Object element(Object target, long index) {
        return reflect(element, target, index);
    }

    private long number(Object value) {
        return (Long) reflect(asLong, value);
    }

    private int integer(Object value) {
        return (Integer) reflect(asInt, value);
    }

    private String text(Object value) {
        return (String) reflect(asString, value);
    }

    private boolean bool(Object value) {
        return (Boolean) reflect(asBoolean, value);
    }

    private boolean nil(Object value) {
        return (Boolean) reflect(isNull, value);
    }

    private static Object reflect(Method method, Object... arguments) {
        try {
            return method.invoke(null, arguments);
        } catch (ReflectiveOperationException failure) {
            Throwable cause = unwrap(failure);
            // Preserve Host rejection and cancellation details for existing caller diagnostics.
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("JVK binding failed: " + method.getName(), cause);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof InvocationTargetException wrapped && wrapped.getCause() != null) {
            failure = wrapped.getCause();
        }
        return failure;
    }
}
