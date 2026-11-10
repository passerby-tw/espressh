package org.espressh.runtime;

import java.util.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.espressh.core.*;
import org.graalvm.polyglot.PolyglotException;

/** Host coordinator between an esh attachment and its dedicated applet worker. Not exported to Guest. */
public final class TerminalAppletWorker implements AutoCloseable {
    private record Command(List<String> args,Path cwd,Map<String,String> environment,CompletableFuture<Integer> result) {}
    private record Closure(String trigger,String thread,String cause) {}
    private final VirtualTerminalService vts;
    private final long terminalId,callerPid;
    private final EspressoRuntimeProvider.Execution worker;
    private final ExecutorService dispatch;
    private final Queue<Command> commands=new ArrayDeque<>();
    private boolean draining,closed;
    private Closure firstClosure;

    public TerminalAppletWorker(VirtualTerminalService vts,long terminalId,long callerPid,
                                EspressoRuntimeProvider.Execution worker) {
        this.vts=Objects.requireNonNull(vts);this.worker=Objects.requireNonNull(worker);
        if(worker.process().parentPid()!=callerPid || vts.foreground(terminalId)!=callerPid)
            throw new KernelException(ErrorCode.ACCESS_DENIED,"Worker is not owned by foreground caller");
        this.terminalId=terminalId;this.callerPid=callerPid;
        dispatch=Executors.newSingleThreadExecutor(r->new Thread(r,"vt-applet-"+worker.process().pid()));
        worker.cleanupCompletion().whenComplete((ignored,failure)->{
            Throwable cause=worker.diagnosticCause();
            if(cause==null)cause=failure;
            Closure closure=new Closure(failure==null?"worker execution ended":"worker cleanup failed",
                oneLine(Thread.currentThread().getName()),cause==null?"none":cause.getClass().getName()
                    +": "+oneLine(cause.getMessage()));
            if(rejectPending(closure)) {
                System.err.println("APPLET_WORKER_TERMINATED: "+closedMessage(closure));
                if(cause!=null)cause.printStackTrace(System.err);
            }
            dispatch.shutdown();
        });
    }
    public long workerPid(){return worker.process().pid();}
    public CompletableFuture<Integer> invoke(List<String> args) {
        return invoke(args,worker.process().request().cwd());
    }
    public CompletableFuture<Integer> invoke(List<String> args,Path cwd) {
        return invoke(args,cwd,worker.process().request().environment());
    }
    public CompletableFuture<Integer> invoke(List<String> args,Path cwd,Map<String,String> env) {
        var command=new Command(List.copyOf(args),cwd.toAbsolutePath().normalize(),Map.copyOf(env),new CompletableFuture<>());
        synchronized(this) {
            if(closed)throw closedException(firstClosure);
            commands.add(command);
            if(!draining) {
                draining=true;
                try {dispatch.execute(this::drain);}
                catch(RejectedExecutionException failure){commands.remove(command);draining=false;throw failure;}
            }
        }
        return command.result().copy();
    }
    private void drain() {
        while(true) {
            Command command;
            synchronized(this) {
                command=commands.poll();
                if(command==null){draining=false;return;}
            }
            Integer code=null;Throwable failure=null;
            var attached=new java.util.concurrent.atomic.AtomicBoolean();
            try {
                worker.readyCompletion().get();
                code=worker.invokeApplet(command.args(),command.cwd(),command.environment(),()->{
                    vts.pushForeground(terminalId,callerPid,workerPid());attached.set(true);
                },()->vts.clearCommandInterrupt(terminalId,workerPid())).get();
            } catch(ExecutionException error) {
                failure=error.getCause();
                boolean invocationRejected=failure instanceof KernelException k && k.code()==ErrorCode.COMMAND_NOT_AVAILABLE;
                if(!invocationRejected && !(failure instanceof PolyglotException p && p.isGuestException() && !p.isExit()
                     && !p.isCancelled() && !p.isInternalError() && !p.isHostException())) {
                    try {worker.cleanupCompletion().join();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
                }
            } catch(Throwable error) {failure=error;}
            finally {
                if(attached.get()) {
                    try {vts.foregroundCompleted(terminalId,workerPid());}
                    catch(KernelException terminalLost) {
                        if(terminalLost.code()!=ErrorCode.RESOURCE_CLOSED) {
                            if(failure==null)failure=terminalLost;else failure.addSuppressed(terminalLost);
                        }
                    }
                }
            }
            if(failure==null)command.result().complete(code);else command.result().completeExceptionally(failure);
        }
    }
    private boolean rejectPending(Closure closure) {
        List<Command> rejected;
        synchronized(this) {
            if(closed)return false;
            closed=true;
            firstClosure=closure;
            rejected=new ArrayList<>(commands);
            commands.clear();
        }
        for(Command command:rejected)command.result().completeExceptionally(closedException(closure));
        return true;
    }
    private KernelException closedException(Closure closure) {
        // Retain and expose only diagnostic text, never a closed Guest Throwable graph.
        return new KernelException(ErrorCode.RESOURCE_CLOSED,closedMessage(closure));
    }
    private String closedMessage(Closure closure) {
        ProcessRecord process=worker.process();
        String outcome=process.outcome().map(result->result.state()+" exitCode="+result.exitCode()
            +" error="+result.error()+" message="+oneLine(result.message())).orElse("unconfirmed");
        return "Worker coordinator closed: ownerPid="+callerPid+" workerPid="+workerPid()
            +" state="+process.state()+" outcome=["+outcome+"] firstClose="+closure.trigger()
            +" thread="+closure.thread()+" cause="+closure.cause();
    }
    private static String oneLine(String message) {
        String text=String.valueOf(message).replace('\r',' ').replace('\n',' ');
        return text.length()<=1024?text:text.substring(0,1021)+"...";
    }
    /** Called on a Kernel control worker, not on a signal/UI thread. Cancels the entire applet worker. */
    public void interrupt() { close(); }
    @Override public void close() {
        rejectPending(new Closure("coordinator close requested",oneLine(Thread.currentThread().getName()),"none"));
        worker.stopWithIoUnblock(()->{
            try{vts.releaseProcessIo(terminalId,workerPid());}
            catch(KernelException lost){if(lost.code()!=ErrorCode.RESOURCE_CLOSED)throw lost;}
        });
        worker.cleanupCompletion().join();
        dispatch.shutdown();
    }
}
