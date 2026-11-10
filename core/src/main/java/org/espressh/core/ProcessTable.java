package org.espressh.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Internal lifecycle component; it neither runs guest code nor claims a Context exists. */
public final class ProcessTable {
    private final Map<Long, ProcessRecord> records = new LinkedHashMap<>();
    private long nextPid = 100;
    private boolean accepting = true;
    private boolean initCreated;
    public synchronized ProcessRecord createInit(LaunchRequest request) {
        if (initCreated || !accepting) throw new KernelException(ErrorCode.INVALID_STATE, "PID 1 already created or shutdown started");
        ProcessRecord r = new ProcessRecord(1, 0, request); records.put(1L, r); initCreated = true; return r;
    }
    public synchronized ProcessRecord spawn(long hostBoundParentPid, LaunchRequest request) {
        if (!accepting) throw new KernelException(ErrorCode.ACCESS_DENIED, "Kernel is shutting down");
        ProcessRecord parent = inspect(hostBoundParentPid);
        if (parent.state() != ProcessState.RUNNING) throw new KernelException(ErrorCode.INVALID_STATE, "Parent is not running");
        if (nextPid == Long.MAX_VALUE) throw new KernelException(ErrorCode.START_FAILED, "PID space exhausted");
        long pid = nextPid++;
        ProcessRecord r = new ProcessRecord(pid, hostBoundParentPid, request); records.put(pid, r); return r;
    }
    public synchronized ProcessRecord inspect(long pid) {
        ProcessRecord r = records.get(pid);
        if (r == null) throw new KernelException(ErrorCode.PROCESS_NOT_FOUND, "Unknown PID " + pid);
        return r;
    }
    public synchronized List<ProcessRecord> records() { return List.copyOf(records.values()); }
    public synchronized void freezeSpawn() { accepting = false; }
    public synchronized void reap(long pid) {
        if (!inspect(pid).state().terminal()) throw new KernelException(ErrorCode.INVALID_STATE, "Cannot reap live PID " + pid);
        records.remove(pid);
    }
}
