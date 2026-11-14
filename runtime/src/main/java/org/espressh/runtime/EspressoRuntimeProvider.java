package org.espressh.runtime;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.espressh.core.*;
import org.graalvm.polyglot.*;
import org.graalvm.polyglot.io.IOAccess;
import static org.espressh.runtime.ExecutionTiming.Phase.*;

/**
 * Runtime module implementation with MAIN_BOUND lifetime. Pinned environment/ProcessHandle
 * substitutions are required before Engine creation. Production VTS and full standard Process API
 * enforcement remain incomplete. No methods of this class are exported to Guest code.
 */
public final class EspressoRuntimeProvider implements AutoCloseable {
  private static final boolean TRACE_WORKERS = Boolean.getBoolean("espressh.worker.trace");
  private final Engine engine;

  String engineVersion() {
    return engine.getVersion();
  }

  private static Engine newEngine() {
    var factories =
        com.oracle.truffle.espresso.substitutions.SubstitutionCollector.getInstances(
            com.oracle.truffle.espresso.substitutions.JavaSubstitution.Factory.class);
    if (!factories.containsAll(
        List.of(
            org.espressh.runtime.substitutions.EnvSubstitution.NAMED,
            org.espressh.runtime.substitutions.EnvSubstitution.ALL,
            org.espressh.runtime.substitutions.ProcessHandleSubstitution.CURRENT,
            org.espressh.runtime.substitutions.ProcessHandleSubstitution.OF,
            org.espressh.runtime.substitutions.ProcessHandleSubstitution.ALL,
            org.espressh.runtime.substitutions.HostProcessSubstitution.START,
            org.espressh.runtime.substitutions.ChannelInputSubstitution.READ_ALL_BYTES,
            org.espressh.runtime.substitutions.FileSystemSubstitution.EXCLUSIVE_CREATE,
            org.espressh.runtime.substitutions.NioAccessSubstitution.CHECK_ACCESS,
            org.espressh.runtime.substitutions.CanonicalizeSubstitution.CANONICALIZE,
            org.espressh.runtime.substitutions.ManagementSubstitution.MEMORY_USAGE,
            org.espressh.runtime.substitutions.ManagementSubstitution.GC_COLLECTION_TIME,
            org.espressh.runtime.substitutions.ManagementSubstitution.GC_COLLECTION_COUNT,
            org.espressh.runtime.substitutions.UnixIdentitySubstitution.UNIX_INFO,
            org.espressh.runtime.substitutions.RuntimeMemorySubstitution.TOTAL_MEMORY,
            org.espressh.runtime.substitutions.RuntimeMemorySubstitution.FREE_MEMORY,
            org.espressh.runtime.substitutions.RuntimeMemorySubstitution.GC,
            org.espressh.runtime.substitutions.SerializationSubstitution.INIT_NATIVE,
            org.espressh.runtime.substitutions.SerializationSubstitution.HAS_STATIC_INITIALIZER,
            org.espressh.runtime.substitutions.FileLengthSubstitution.GET_LENGTH,
            org.espressh.runtime.substitutions.GuestBufferTransferSubstitution.MAX_DIRECT_TRANSFER_SIZE,
            org.espressh.runtime.substitutions.GuestBufferTransferSubstitution.TRANSFER_TO,
            org.espressh.runtime.substitutions.GuestBufferTransferSubstitution.TRANSFER_FROM,
            org.espressh.runtime.substitutions.GuestBufferTransferSubstitution.PWRITE,
            org.espressh.runtime.substitutions.FileRenameSubstitution.RENAME,
            org.espressh.runtime.substitutions.FileInputSkipSubstitution.SKIP,
            org.espressh.runtime.substitutions.ConsoleTtySubstitution.TTY_STATUS,
            org.espressh.runtime.substitutions.awt.AwtFactorySubstitution.ENVIRONMENT,
            org.espressh.runtime.substitutions.awt.AwtFactorySubstitution.TOOLKIT,
            org.espressh.runtime.substitutions.awt.AwtFontSubstitution.COMPONENT,
            org.espressh.runtime.substitutions.awt.AwtSwingFontSubstitution.FACTORY,
            org.espressh.runtime.substitutions.awt.AwtFontSubstitution.GRAPHICS,
            org.espressh.runtime.substitutions.awt.AwtFontSubstitution.GRAPHICS_DEFAULT,
            org.espressh.runtime.substitutions.awt.AwtFocusSubstitution.FACTORY,
            org.espressh.runtime.substitutions.awt.AwtImageGraphicsSubstitution.FACTORY,
            org.espressh.runtime.substitutions.awt.AwtComponentFactorySubstitution.FACTORY,
            org.espressh.runtime.substitutions.awt.AwtInputContextSubstitution.FACTORY,
            org.espressh.runtime.substitutions.awt.UnsupportedFontSubstitution.FACTORY)))
      throw new IllegalStateException(
          "Missing pinned Espresso substitutions: espressh-runtime must precede Espresso dependency"
              + " JARs on the Host classpath");
    if (!factories.containsAll(java.util.Arrays.asList(org.espressh.runtime.substitutions.awt.AwtTextSubstitution.FACTORIES)))
      throw new IllegalStateException("Missing Guest AWT text substitutions");
    var builder = Engine.newBuilder("java").option("engine.WarnInterpreterOnly", "false");
    if (System.getProperty("polyglot.log.file") == null) {
      // Runtime diagnostics belong to the Host, not a Guest's terminal stderr.
      // Engine close must not close the borrowed Host stream.
      builder.logHandler(new java.io.FilterOutputStream(System.err) {
        @Override public void write(byte[] bytes, int offset, int length) throws java.io.IOException {
          out.write(bytes, offset, length);
        }
        @Override public void close() throws java.io.IOException { flush(); }
      });
    }
    return builder.build();
  }

  private final ConcurrentHashMap<Long, Execution> executions = new ConcurrentHashMap<>();
  private boolean closed;

  private final GuestClasspath guestClasspath;
  private final java.util.function.Consumer<Execution> onStarted;

  public EspressoRuntimeProvider(Path bootstrapClasspath) {
    this(bootstrapClasspath, execution -> {});
  }

  EspressoRuntimeProvider(
      Path bootstrapClasspath, java.util.function.Consumer<Execution> onStarted) {
    this.onStarted = Objects.requireNonNull(onStarted);
    this.guestClasspath = GuestClasspath.open(Objects.requireNonNull(bootstrapClasspath));
    try {
      this.engine = newEngine();
    } catch (RuntimeException | Error failure) {
      try { guestClasspath.close(); }
      catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  List<Path> distributionClasspath(List<Path> application) {
    return guestClasspath.distributionSnapshot(application);
  }

  boolean sameDistribution(List<Path> parent, List<Path> child) {
    return distributionClasspath(parent).equals(guestClasspath.applicationSnapshot(child));
  }

  boolean sameBootClasspath(List<Path> parent, List<Path> child) {
    return guestClasspath.applicationSnapshot(parent).equals(guestClasspath.applicationSnapshot(child))
        || sameDistribution(parent, child);
  }

  public String lifetimeMode() {
    return "MAIN_BOUND";
  }

  /** Receives a Host-bound sys projection; does not construct or define Guest public APIs. */
  public synchronized Execution start(
      ProcessRecord process, RuntimeProfile profile, ProcessIo io, Object sys) {
    return start(process, profile, io, sys, false);
  }

  /** Temporary dedicated JBox worker; caller owns its lifetime. Not a Guest API. */
  public synchronized Execution startAppletWorker(
      ProcessRecord process, RuntimeProfile profile, ProcessIo io, Object sys) {
    return start(process, profile, io, sys, true);
  }

  private Execution start(
      ProcessRecord process,
      RuntimeProfile profile,
      ProcessIo io,
      Object sys,
      boolean appletWorker) {
    Objects.requireNonNull(process);
    Objects.requireNonNull(profile);
    Objects.requireNonNull(io);
    Objects.requireNonNull(sys);
    if (closed) throw new KernelException(ErrorCode.RESOURCE_CLOSED, "Provider is closed");
    if (!profile.id().equals(process.request().runtimeId()))
      throw new KernelException(ErrorCode.RUNTIME_NOT_FOUND, "Runtime profile mismatch");
    if (!profile.provider().equals("espresso") || !profile.contextOptions().isEmpty())
      throw new KernelException(
          ErrorCode.CONFIG_ERROR,
          "Only the fixed Espresso policy with no extra context options is supported");
    if (executions.containsKey(process.pid()))
      throw new KernelException(ErrorCode.INVALID_STATE, "PID already has an execution");
    process.starting();
    Execution execution = new Execution(process, profile, io, sys, appletWorker);
    executions.put(process.pid(), execution);
    try {
      execution.worker.start();
    } catch (RuntimeException | Error failure) {
      executions.remove(process.pid(), execution);
      process.confirm(
          ProcessOutcome.failed(ErrorCode.START_FAILED, "Host execution worker did not start"));
      try {
        io.close();
      } catch (Exception closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
    onStarted.accept(execution);
    return execution;
  }

  public final class Execution {
    private final ExecutionTiming timing = ExecutionTiming.create();
    private final ProcessRecord process;
    private final RuntimeProfile profile;
    private ProcessIo io;
    private final Object sys;
    private final org.espressh.runtime.graphics.HostGraphicsService graphics;
    private final Thread worker;
    private final CompletableFuture<Void> cleanup = new CompletableFuture<>();
    private final boolean appletWorker;
    private final LinkedBlockingQueue<AppletCommand> commands = new LinkedBlockingQueue<>();
    private volatile AppletCommand activeCommand;
    private volatile Value cooperativeDispatcher;
    private long appletGeneration;
    private final org.espressh.runtime.substitutions.EnvSubstitution.EnvironmentState environment;
    private boolean accepting = true;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private Context context;
    private boolean stop;
    private boolean contextClosing;
    private volatile boolean cancellationClosed;
    private final CompletableFuture<Void> contextClosed = new CompletableFuture<>();
    private volatile Throwable diagnosticCause;

    private Execution(
        ProcessRecord process,
        RuntimeProfile profile,
        ProcessIo io,
        Object sys,
        boolean appletWorker) {
      this.process = process;
      this.profile = profile;
      this.io = io;
      this.sys = sys;
      this.graphics = new org.espressh.runtime.graphics.HostGraphicsService(process.pid());
      this.appletWorker = appletWorker;
      environment =
          new org.espressh.runtime.substitutions.EnvSubstitution.EnvironmentState(
              process.request().environment());
      worker = new Thread(this::run, "jvp-" + process.pid());
    }

    public ProcessRecord process() {
      return process;
    }

    /** Host verification/diagnostics only; never exported as a Guest capability. */
    public int graphicsResourceCount() {
      return graphics.resourceCount();
    }

    long terminalId() {
      return sys instanceof JvkProcessApi api ? api.terminalId() : -1;
    }

    boolean shellCaller() {
      return sys instanceof JvkProcessApi api && api.shellCaller();
    }

    public CompletableFuture<Void> readyCompletion() {
      return ready.copy();
    }

    /** Existing dispatchers retain their original execution directory. */
    public CompletableFuture<Integer> invokeApplet(List<String> arguments) {
      return invokeApplet(arguments, process.request().cwd());
    }

    /** Command CWD is copied as invocation data; Context CWD remains fixed. */
    public CompletableFuture<Integer> invokeApplet(List<String> arguments, Path cwd) {
      return invokeApplet(arguments, cwd, process.request().environment());
    }

    public synchronized CompletableFuture<Integer> invokeApplet(
        List<String> arguments, Path cwd, Map<String, String> env) {
      return invokeApplet(arguments, cwd, env, () -> {}, () -> {});
    }

    synchronized CompletableFuture<Integer> invokeApplet(
        List<String> arguments, Path cwd, Map<String, String> env,
        Runnable beforeEntry, Runnable afterEntry) {
      if (!appletWorker) throw new KernelException(ErrorCode.INVALID_STATE, "Not an applet worker");
      if (!accepting || stop || process.state().terminal())
        throw new KernelException(ErrorCode.RESOURCE_CLOSED, "Applet worker is stopped");
      List<String> snapshot = List.copyOf(arguments);
      if (snapshot.isEmpty() || List.of("init", "esh", "shutdown").contains(snapshot.get(0)))
        throw new KernelException(ErrorCode.COMMAND_NOT_AVAILABLE, "Not a worker applet target");
      var command =
          new AppletCommand(
              Math.incrementExact(appletGeneration),
              snapshot,
              cwd.toAbsolutePath().normalize().toString(),
              Map.copyOf(env),
              Objects.requireNonNull(beforeEntry), Objects.requireNonNull(afterEntry),
              new CompletableFuture<>());
      appletGeneration = command.generation();
      commands.add(command);
      return command.result().copy();
    }

    private record AppletCommand(
        long generation,
        List<String> arguments,
        String cwd,
        Map<String, String> environment,
        Runnable beforeEntry,
        Runnable afterEntry,
        CompletableFuture<Integer> result) {}

    /** Capture invocation identity before control dispatch so a late event cannot hit its successor. */
    Runnable appletInterrupt(Runnable unblockInput) {
      Value dispatcher = cooperativeDispatcher;
      if (!appletWorker || dispatcher == null) return null;
      AppletCommand command = activeCommand;
      if (command == null) return () -> {};
      return () -> {
        synchronized (command) {
          if (activeCommand != command || command.result().isDone()
              || process.state() != ProcessState.RUNNING) return;
          if (dispatcher.invokeMember("interruptApplet", command.generation()).asBoolean()) {
            unblockInput.run();
          }
        }
      };
    }

    private synchronized void rejectCommands(Throwable cause) {
      accepting = false;
      if (activeCommand != null) activeCommand.result().completeExceptionally(cause);
      AppletCommand command;
      while ((command = commands.poll()) != null) command.result().completeExceptionally(cause);
      ready.completeExceptionally(cause);
    }

    private void runApplets(Context guest, Value dispatcher) throws InterruptedException {
      while (true) {
        synchronized (this) {
          if (stop) throw new StoppedBeforeEntry();
        }
        AppletCommand command = commands.poll(100, TimeUnit.MILLISECONDS);
        if (command == null) continue;
        synchronized (this) {
          activeCommand = command;
          if (stop) throw new StoppedBeforeEntry();
        }
        Integer code = null;
        Throwable commandFailure = null;
        boolean fatal = false, prepared = false;
        try {
          environment.replace(command.environment());
          boolean cwdEntry = dispatcher.canInvokeMember("runAppletAt");
          if (!cwdEntry
              && !command
                  .cwd()
                  .equals(process.request().cwd().toAbsolutePath().normalize().toString())) {
            throw new KernelException(ErrorCode.COMMAND_NOT_AVAILABLE,
                "COMMAND_NOT_AVAILABLE: dispatcher does not support per-command CWD");
          }
          var arguments = new ArrayList<>(command.arguments());
          if (cwdEntry) arguments.add(0, command.cwd());
          Value array =
              guest
                  .getBindings("java")
                  .getMember("[Ljava.lang.String;")
                  .newInstance(arguments.size());
          for (int i = 0; i < arguments.size(); i++) array.setArrayElement(i, arguments.get(i));
          if (cooperativeDispatcher != null) {
            dispatcher.invokeMember("beginApplet", command.generation());
            prepared = true;
          }
          command.beforeEntry().run();
          code = dispatcher.invokeMember(cwdEntry ? "runAppletAt" : "runApplet", array).asInt();
        } catch (PolyglotException failure) {
          commandFailure = failure;
          fatal = failure.isExit()
              || failure.isCancelled()
              || failure.isInternalError()
              || failure.isHostException();
        } catch (RuntimeException | Error failure) {
          // Espresso may unwind exit/cancel using an internal Error rather than PolyglotException.
          commandFailure = failure;
          fatal = !(failure instanceof KernelException k
              && k.code() == ErrorCode.COMMAND_NOT_AVAILABLE);
        } finally {
          synchronized (command) {
            try {
              if (prepared && !fatal) dispatcher.invokeMember("endApplet", command.generation());
            } catch (RuntimeException | Error cleanupFailure) {
              if (commandFailure == null) commandFailure = cleanupFailure;
              else commandFailure.addSuppressed(cleanupFailure);
              fatal = true;
            } finally {
              try {
                command.afterEntry().run();
                environment.replace(process.request().environment());
              } catch (RuntimeException | Error cleanupFailure) {
                if (commandFailure == null) commandFailure = cleanupFailure;
                else commandFailure.addSuppressed(cleanupFailure);
                fatal = true;
              } finally {
                activeCommand = null;
              }
            }
          }
        }
        if (commandFailure == null) command.result().complete(code);
        else command.result().completeExceptionally(commandFailure);
        if (fatal) {
          if (commandFailure instanceof Error error) throw error;
          throw (RuntimeException) commandFailure;
        }
      }
    }

    public Throwable diagnosticCause() {
      return diagnosticCause;
    }

    public CompletableFuture<Void> cleanupCompletion() {
      return cleanup.copy();
    }

    /**
     * Runs on a Host control worker, never a UI event thread. May block in runtime cancellation.
     */
    public void stop() {
      stopWithIoUnblock(() -> {});
    }

    /**
     * Core supplies owned-IO unblock before runtime cancellation; never run under an execution
     * lock.
     */
    public void stopWithIoUnblock(Runnable unblockIo) {
      Objects.requireNonNull(unblockIo);
      Context active;
      boolean firstStop;
      synchronized (this) {
        if (cleanup.isDone()) return;
        firstStop = !stop;
        process.requestStop();
        stop = true;
        accepting = false;
        active = contextClosing ? null : context;
        if (active != null) contextClosing = true;
      }
      if (firstStop) traceWorker("STOP_REQUEST", true);
      try {
        unblockIo.run();
        if (sys instanceof JvkProcessApi api) api.unblockProcessIo();
        io.unblockBorrowedWriters();
        graphics.beginClose();
        if (sys instanceof JvkProcessApi api) api.close();
      } finally {
        try {
          if (active != null) closeContext(active, true);
        } finally {
          graphics.close();
        }
      }
    }

    private void closeContext(Context guest, boolean cancel) {
      traceWorker("CONTEXT_CLOSE cancel=" + cancel, false);
      try {
        guest.close(cancel);
        synchronized (this) {
          cancellationClosed = cancel && stop;
        }
        contextClosed.complete(null);
      } catch (RuntimeException | Error failure) {
        contextClosed.completeExceptionally(failure);
        throw failure;
      }
    }

    private void finishContext(Context guest, boolean normalEntryCleanup) {
      boolean own, cancel;
      synchronized (this) {
        own = !contextClosing;
        // MAIN_BOUND applies to every application, not only PID 1. A returned
        // entry must not wait forever for residual Guest threads in close(false).
        // Keep the existing failure/System.exit translation path; a cleanup-only
        // cancellation preserves EXITED unless a stop request was accepted.
        cancel = stop || normalEntryCleanup;
        if (own) contextClosing = true;
      }
      if (own) closeContext(guest, cancel);
      else {
        try {
          contextClosed.join();
        } catch (CompletionException failure) {
          if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
          if (failure.getCause() instanceof Error error) throw error;
          throw failure;
        }
      }
    }

    private void traceWorker(String event, boolean includeCaller) {
      if (!TRACE_WORKERS || !appletWorker) return;
      System.err.println("WORKER_TRACE: " + event + " owner=" + process.parentPid()
          + " worker=" + process.pid() + " vt=" + terminalId()
          + " state=" + process.state() + " thread=" + Thread.currentThread().getName());
      if (includeCaller) new Exception("Worker stop request origin").printStackTrace(System.err);
    }

    private void run() {
      ProcessOutcome result = null;
      Context created = null;
      boolean entryStarted = false;
      org.espressh.runtime.substitutions.EnvSubstitution.begin(environment);
      try {
        LaunchRequest request = process.request();
        synchronized (this) {
          if (stop) throw new StoppedBeforeEntry();
        }
        // File opens run on this execution thread, outside Kernel/ProcessTable locks.
        // The original borrowed streams remain available if a partial open fails.
        timing.begin(IO_SETUP);
        try {
          io = io.redirectOutputs(request);
          if (sys instanceof JvkProcessApi api) api.bindProcessIo(io, environment::snapshot);
        } catch (java.io.IOException | SecurityException failure) {
          throw new KernelException(ErrorCode.START_FAILED,
              "Cannot open redirected output: " + failure.getMessage(), failure);
        }
        timing.begin(ENTRY_RESOLVE);
        Entry entry = resolve(request);
        timing.begin(CONTEXT_CONFIG);
        var builder =
            Context.newBuilder("java")
                .engine(engine)
                .allowExperimentalOptions(true)
                .allowIO(IOAccess.ALL)
                .allowCreateThread(true)
                .allowNativeAccess(false)
                .allowCreateProcess(false)
                .allowHostAccess(HostAccess.EXPLICIT)
                .useSystemExit(false)
                .allowHostClassLookup(name -> false)
                .allowValueSharing(false)
                .allowPolyglotAccess(PolyglotAccess.ALL)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .environment(request.environment())
                .currentWorkingDirectory(request.cwd())
                .in(io.in())
                .out(io.out())
                .err(io.err())
                .option("java.JavaHome", profile.javaHome().toString())
                .option("java.Classpath", guestClasspath.project(entry.classpath(),
                    sys instanceof JvkProcessApi api && api.distributionRole()).stream()
                    .map(Path::toString).collect(java.util.stream.Collectors.joining(File.pathSeparator)))
                // Parsing hint only; Host same-file checks remain the shell authority.
                .option("java.Properties.espressh.distribution", guestClasspath.distribution().toString())
                .option("java.Properties.user.dir", request.cwd().toString())
                // Display ownership belongs to the Host graphics backend, not Guest DISPLAY.
                .option(
                    "java.Properties.java.awt.headless",
                    Boolean.toString(java.awt.GraphicsEnvironment.isHeadless()))
                .option("java.Properties.espressh.input.trace",
                    Boolean.toString(Boolean.getBoolean("espressh.input.trace")))
                .option("java.Properties.espressh.runtime", request.runtimeId())
                .option("java.Polyglot", "true")
                .option("java.ExitHost", "false");
        // Manifest requests belong only to this JAR entry, never the Engine or inherited profile.
        // Espresso's list option uses the platform path separator rather than commas.
        if (!entry.addOpens().isEmpty())
          builder.option("java.AddOpens", String.join(File.pathSeparator, entry.addOpens()));
        timing.begin(CONTEXT_BUILD);
        created = builder.build();
        synchronized (this) {
          context = created;
          if (stop) throw new StoppedBeforeEntry();
        }
        timing.begin(HOST_BINDINGS);
        created.getPolyglotBindings().putMember("sys", sys);
        created.getPolyglotBindings().putMember("jvk_graphics", graphics);
        created.getPolyglotBindings().putMember("process_stdout", new IoEndpoint(io.out()));
        created.getPolyglotBindings().putMember("process_stderr", new IoEndpoint(io.err()));
        timing.begin(JAVA_INITIALIZE);
        created.initialize("java");
        // Associate this Context before applet class initialization; owned threads use the same
        // state.
        timing.begin(ENV_LINK);
        created.getBindings("java").getMember("java.lang.System").invokeMember("getenv");
        timing.begin(BOOTSTRAP_LOOKUP);
        Value bootstrap =
            created.getBindings("java").getMember("org.espressh.bootstrap.IoBootstrap");
        if (bootstrap == null || !bootstrap.canInvokeMember("install"))
          throw new KernelException(ErrorCode.START_FAILED, "Missing trusted IO bootstrap");
        timing.begin(BOOTSTRAP_INSTALL);
        bootstrap.invokeMember("install");
        timing.begin(APP_LOOKUP);
        Value guestClass = created.getBindings("java").getMember(entry.mainClass());
        if (guestClass == null || !guestClass.canInvokeMember(appletWorker ? "runApplet" : "main"))
          throw new KernelException(
              ErrorCode.ENTRY_NOT_FOUND, "Missing main: " + entry.mainClass());
        timing.begin(ARGV_SETUP);
        Value guestArray =
            created
                .getBindings("java")
                .getMember("[Ljava.lang.String;")
                .newInstance(entry.arguments().size());
        for (int i = 0; i < entry.arguments().size(); i++)
          guestArray.setArrayElement(i, entry.arguments().get(i));
        if (appletWorker && guestClass.canInvokeMember("beginApplet")
            && guestClass.canInvokeMember("interruptApplet") && guestClass.canInvokeMember("endApplet")) {
          cooperativeDispatcher = guestClass;
        }
        timing.begin(ENTRY_READY);
        synchronized (this) {
          if (stop) throw new StoppedBeforeEntry();
          process.running();
          entryStarted = true;
          ready.complete(null);
        }
        timing.begin(MAIN);
        if (appletWorker) runApplets(created, guestClass);
        else guestClass.invokeMember("main", guestArray);
        result = ProcessOutcome.exited(0);
      } catch (StoppedBeforeEntry e) {
        result = ProcessOutcome.cancelled(false);
      } catch (PolyglotException e) {
        diagnosticCause = e;
        if (e.isCancelled()) result = ProcessOutcome.cancelled(false);
        else if (e.isExit()) result = ProcessOutcome.exited(e.getExitStatus());
        else
          result =
              ProcessOutcome.failed(
                  entryStarted ? ErrorCode.START_FAILED : ErrorCode.ENTRY_NOT_FOUND,
                  entryStarted ? "Guest execution failed" : "Guest initialization or entry failed");
      } catch (Throwable e) {
        diagnosticCause = e;
        result =
            ProcessOutcome.failed(
                e instanceof KernelException k ? k.code() : ErrorCode.START_FAILED,
                e instanceof KernelException k ? k.getMessage() : "Runtime execution failed");
      } finally {
        timing.begin(ENTRY_END);
        boolean normalEntryCleanup = diagnosticCause == null;
        traceWorker("ENTRY_ENDED", false);
        org.espressh.runtime.substitutions.EnvSubstitution.end();
        rejectCommands(new KernelException(ErrorCode.RESOURCE_CLOSED, "Execution ended"));
        // Application mode is MAIN_BOUND; worker remains live between commands.
        Throwable terminationFailure = null;
        try {
          timing.begin(GRAPHICS_BEGIN_CLOSE);
          graphics.beginClose();
          try {
            // MAIN_BOUND must release Host-blocked Guest IO before Context cancellation.
            // Stop owned children while inherited streams remain open, then close only
            // our endpoint ends. A pipe writer close preserves pending output for its parent.
            try {
              timing.begin(CHILDREN_CLOSE);
              if (sys instanceof JvkProcessApi api) api.close();
            } finally {
              timing.begin(IO_CLOSE);
              try { io.close(); }
              catch (Throwable ioFailure) {
                diagnosticCause = ioFailure;
                result = ProcessOutcome.failed(ErrorCode.START_FAILED, "IO cleanup failed");
              }
            }
          } finally {
            try {
              timing.begin(CONTEXT_CLOSE);
              if (created != null) finishContext(created, normalEntryCleanup);
            } finally {
              timing.begin(GRAPHICS_CLOSE);
              graphics.close();
            }
          }
        } catch (PolyglotException e) {
          if (e.isExit()) {
            result = ProcessOutcome.exited(e.getExitStatus());
            diagnosticCause = null;
          } else if (e.isCancelled()) result = ProcessOutcome.cancelled(false);
          else {
            diagnosticCause = e;
            terminationFailure = e;
          }
        } catch (Throwable e) {
          diagnosticCause = e;
          terminationFailure = e;
        }
        timing.begin(CONFIRM);
        if (terminationFailure == null && cancellationClosed)
          result = ProcessOutcome.cancelled(false);
        if (terminationFailure != null) {
          // A failed close is not proof of termination. Retain the Context and PID.
          System.err.println("CONTEXT_CLEANUP_FAILED: PID " + process.pid());
          terminationFailure.printStackTrace(System.err);
          process.requestStop();
          try { timing.emit(process, appletWorker, false); }
          finally { cleanup.completeExceptionally(terminationFailure); }
        } else {
          synchronized (this) {
            context = null;
            cooperativeDispatcher = null;
          }
          if (result == null)
            result = ProcessOutcome.failed(ErrorCode.START_FAILED, "No runtime result");
          boolean confirmed = process.confirm(result);
          // Report ordinary application failures only after final cleanup mapping.
          // Worker failures already have their owner-aware coordinator diagnostic.
          Throwable original = diagnosticCause;
          if (confirmed && !appletWorker && result.state() == ProcessState.FAILED
              && original != null) {
            System.err.println("MANAGED_EXECUTION_FAILED: PID " + process.pid());
            original.printStackTrace(System.err);
          }
          traceWorker("CLEANUP_CONFIRMED exit=" + result.exitCode() + " error=" + result.error(), false);
          executions.remove(process.pid(), this);
          try { timing.emit(process, appletWorker, true); }
          finally { cleanup.complete(null); }
        }
      }
    }
  }

  private record Entry(String mainClass, List<String> arguments, List<Path> classpath,
                       List<String> addOpens) {}

  private static Entry resolve(LaunchRequest request) {
    List<String> words = new ArrayList<>(request.argv());
    List<Path> cp = request.classpath();
    List<String> addOpens = List.of();
    String main;
    if (words.get(0).equals("-version")) {
      if (words.size() != 1)
        throw new KernelException(ErrorCode.CONFIG_ERROR,
            "-version does not accept an application or arguments");
      return new Entry("org.espressh.bootstrap.JavaVersionMain", List.of(), List.of(), List.of());
    } else if (java.util.Set.of("javac", "javap", "jar", "javadoc").contains(words.get(0))) {
      main = "org.espressh.bootstrap.JdkToolMain";
      // Guest bootstrap is prepended separately to every Context classpath.
      return new Entry(
          main,
          List.copyOf(words),
          cp, List.of());
    } else if (words.get(0).equals("-jar")) {
      if (words.size() < 2) throw new KernelException(ErrorCode.ENTRY_NOT_FOUND, "Missing JAR");
      words.remove(0);
      JarEntryPlan plan = JarEntryPlan.read(request.cwd().resolve(words.remove(0)));
      main = plan.mainClass();
      cp = plan.classpath();
      addOpens = plan.addOpens();
    } else {
      main = words.remove(0);
      int colon = main.indexOf(':');
      if (colon > 0) {
        words.add(0, main.substring(colon + 1));
        main = main.substring(0, colon);
      }
      if (main.startsWith("-")
          || !main.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.]*"))
        throw new KernelException(
            ErrorCode.COMMAND_NOT_AVAILABLE, "Unsupported managed Java entry");
    }
    if (cp.isEmpty())
      throw new KernelException(ErrorCode.ENTRY_NOT_FOUND, "Empty managed classpath");
    return new Entry(
        main,
        List.copyOf(words),
        cp, addOpens);
  }

  private static final class StoppedBeforeEntry extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  @Override
  public void close() {
    List<Execution> active;
    synchronized (this) {
      closed = true;
      active = List.copyOf(executions.values());
    }
    for (Execution execution : active) execution.stop();
    for (Execution execution : active) execution.cleanupCompletion().join();
    engine.close();
    guestClasspath.close();
  }
}
