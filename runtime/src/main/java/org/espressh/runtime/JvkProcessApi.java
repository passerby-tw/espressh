package org.espressh.runtime;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.espressh.core.*;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.*;

/** Caller-bound projection of JVK's public process interface. Never exports Kernel objects. */
public final class JvkProcessApi implements ProxyObject, AutoCloseable {
  private final ProcessTable table;
  private final EspressoRuntimeProvider provider;
  private final Function<String, RuntimeProfile> profiles;
  private final VirtualTerminalService vts;
  private final long caller, terminal;
  private final boolean allowed;
  private final Map<Long, CompletableFuture<Void>> children = new ConcurrentHashMap<>();
  private TerminalAppletWorker worker;
  private LaunchRequest workerSnapshot;
  private String dispatcher;
  private volatile boolean closed;
  private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();

  long terminalId() {
    return terminal;
  }

  private JVK terminalKernel;
  private final Set<GuestTerminalEndpoint> frontends = ConcurrentHashMap.newKeySet();

  JvkProcessApi forTerminals(JVK owner) { terminalKernel = owner; return this; }

  void terminalCallerAuthorized() {
    if (closed) throw new KernelException(ErrorCode.RESOURCE_CLOSED, "Caller closed");
    if (terminalKernel == null || table.inspect(caller).state() != ProcessState.RUNNING)
      throw denied("Caller is not a running managed application");
  }

  private synchronized Object createGuestTerminal(int columns, int rows) {
    terminalCallerAuthorized();
    var created = terminalKernel.createGuestTerminal(caller, columns, rows);
    var endpoint = new GuestTerminalEndpoint(this, terminalKernel, vts, created.terminal());
    frontends.add(endpoint);
    return endpoint;
  }

  private JVK initKernel;
  private JVK kernel;
  private boolean shellCaller;
  private String shellDispatcher = "";
  private List<Path> shellClasspath = List.of();
  private boolean shutdownTarget;
  private boolean appletInspection;
  private long inspectionOwner = -1;

  private JvkProcessApi forApplets(JVK owner) {
    kernel = owner;
    terminalKernel = owner;
    appletInspection = true;
    return this;
  }

  public JvkProcessApi(
      ProcessTable table,
      EspressoRuntimeProvider provider,
      Function<String, RuntimeProfile> profiles,
      VirtualTerminalService vts,
      long terminal,
      long caller,
      boolean allowed) {
    this.table = table;
    this.provider = provider;
    this.profiles = profiles;
    this.vts = vts;
    this.terminal = terminal;
    this.caller = caller;
    this.allowed = allowed;
  }

  JvkProcessApi forInit(JVK kernel) {
    this.initKernel = kernel;
    return this;
  }

  JvkProcessApi forShell(JVK kernel) {
    var request = table.inspect(caller).request();
    String entry = request.argv().get(0);
    int separator = entry.indexOf(':');
    return forShell(kernel, separator > 0 ? entry.substring(0, separator) : "", request.classpath());
  }

  private JvkProcessApi forShell(JVK kernel, String dispatcher, List<Path> classpath) {
    this.kernel = kernel;
    shellCaller = true;
    shellDispatcher = dispatcher;
    shellClasspath = List.copyOf(classpath);
    return this;
  }

  boolean shellCaller() { return shellCaller; }

  private JvkProcessApi forShutdown(JVK kernel) {
    this.kernel = kernel;
    shutdownTarget = true;
    return this;
  }

  private void initAuthorized() {
    if (closed) {
      throw new KernelException(ErrorCode.RESOURCE_CLOSED, "Init capability closed");
    }
    if (initKernel == null) {
      throw denied("Init capability not granted");
    }
    if (table.inspect(caller).state() != ProcessState.RUNNING) {
      throw denied("Caller is not running");
    }
  }

  private Object createTerminal(Value request) {
    initAuthorized();
    var shell = snapshot(request);
    rejectLifecycleRedirection(shell);
    var terminal = initKernel.createTerminal(caller, shell);
    return immutable(
        Map.of(
            "terminalId",
            terminal.terminal().id(),
            "rootPid",
            terminal.execution().process().pid()));
  }

  private void awaitShutdown() {
    initAuthorized();
    initKernel.awaitShutdown(caller, closeFuture);
  }

  @Override
  public Object getMember(String name) {
    if (name.equals("createTerminal") && terminalKernel != null) {
      return (ProxyExecutable) a -> {
        if (initKernel != null && a.length == 1) return createTerminal(a[0]);
        if (a.length != 2) throw new IllegalArgumentException("CONFIG_ERROR: expected columns, rows");
        return createGuestTerminal(a[0].asInt(), a[1].asInt());
      };
    }
    if (initKernel != null) {
      if (name.equals("createTerminal")) {
        return (ProxyExecutable)
            a -> {
              requireOne(a);
              return createTerminal(a[0]);
            };
      }
      if (name.equals("awaitShutdown")) {
        return (ProxyExecutable)
            a -> {
              if (a.length != 0) {
                throw new IllegalArgumentException("CONFIG_ERROR: expected no arguments");
              }
              awaitShutdown();
              return null;
            };
      }
    }
    if (shutdownTarget) {
      return name.equals("requestShutdown")
          ? (ProxyExecutable)
              a -> {
                requireNone(a);
                requestShutdown();
                return null;
              }
          : null;
    }
    return switch (name) {
      case "kill" -> (ProxyExecutable) a -> {
        requireOne(a);
        if (!a[0].fitsInLong() || a[0].asLong() <= 0)
          throw new KernelException(ErrorCode.CONFIG_ERROR, "Expected a positive PID");
        return kill(a[0].asLong());
      };
      case "runtimeList" ->
          (ProxyExecutable)
              a -> {
                requireNone(a);
                authorize();
                if (kernel == null) throw denied("Runtime query not granted");
                return runtimeList();
              };
      case "processList" ->
          (ProxyExecutable)
              a -> {
                requireNone(a);
                return processList();
              };
      case "terminalState" ->
          (ProxyExecutable)
              a -> {
                requireNone(a);
                authorize();
                return immutable(Map.of("root", vts.root(terminal) == caller));
              };
      case "tryCloseTerminal" ->
          (ProxyExecutable)
              a -> {
                requireNone(a);
                authorize();
                if (kernel == null) {
                  throw denied("JVK lifecycle not granted");
                }
                return kernel.tryCloseTerminal(caller, terminal);
              };
      case "requestShutdown" ->
          (ProxyExecutable)
              a -> {
                requireNone(a);
                requestShutdown();
                return null;
              };
      case "spawn" ->
          (ProxyExecutable)
              a -> {
                requireOne(a);
                return spawn(snapshot(a[0]));
              };
      case "waitOutcome" ->
          (ProxyExecutable)
              a -> {
                requireOne(a);
                return outcome(waitOutcome(a[0].asLong()));
              };
      case "runApplet" ->
          (ProxyExecutable)
              a -> {
                requireOne(a);
                return runApplet(snapshot(a[0]));
              };
      default -> null;
    };
  }

  @Override
  public Object getMemberKeys() {
    return initKernel != null
        ? new String[] {"createTerminal", "awaitShutdown"}
        : shutdownTarget
            ? new String[] {"requestShutdown"}
            : new String[] {
              "createTerminal",
              "runtimeList",
              "processList",
              "kill",
              "spawn",
              "waitOutcome",
              "runApplet",
              "terminalState",
              "tryCloseTerminal",
              "requestShutdown"
            };
  }

  @Override
  public boolean hasMember(String name) {
    return initKernel != null
        ? Set.of("createTerminal", "awaitShutdown").contains(name)
        : shutdownTarget
            ? name.equals("requestShutdown")
            : Set.of(
                    "createTerminal",
                    "runtimeList",
                    "processList",
                    "kill",
                    "spawn",
                    "waitOutcome",
                    "runApplet",
                    "terminalState",
                    "tryCloseTerminal",
                    "requestShutdown")
                .contains(name);
  }

  @Override
  public void putMember(String key, Value value) {
    throw denied("sys is immutable");
  }

  private static void requireOne(Value[] args) {
    if (args.length != 1) {
      throw new IllegalArgumentException("CONFIG_ERROR: expected one argument");
    }
  }

  private static void requireNone(Value[] args) {
    if (args.length != 0) {
      throw new IllegalArgumentException("CONFIG_ERROR: expected no arguments");
    }
  }

  private void requestShutdown() {
    if (shutdownTarget) {
      if (closed) {
        throw new KernelException(ErrorCode.RESOURCE_CLOSED, "Caller closed");
      }
      if (table.inspect(caller).state() != ProcessState.RUNNING
          || table.inspect(table.inspect(caller).parentPid()).state() != ProcessState.RUNNING
          || vts.foreground(terminal) != table.inspect(caller).parentPid()) {
        throw denied("Caller is not running foreground");
      }
    } else {
      authorize();
    }
    if (kernel == null) {
      throw denied("Shutdown capability not granted");
    }
    kernel.requestShutdown(java.time.Duration.ofSeconds(10));
  }

  private synchronized void authorize() {
    if (closed) {
      throw new KernelException(ErrorCode.RESOURCE_CLOSED, "RESOURCE_CLOSED: caller closed");
    }
    if (!allowed) {
      throw denied("Process capability not granted");
    }
    if (table.inspect(caller).state() != ProcessState.RUNNING) {
      throw denied("Caller is not running");
    }
    if (vts.foreground(terminal) != caller) {
      throw denied("Caller is not foreground");
    }
  }

  private static KernelException denied(String message) {
    return new KernelException(ErrorCode.ACCESS_DENIED, "ACCESS_DENIED: " + message);
  }

  private synchronized boolean kill(long pid) {
    synchronized (table) {
      authorize();
      if (kernel == null) throw denied("Stop capability not granted");
      if (pid == 1) throw denied("Use shutdown for PID 1");
      if (pid == caller) throw denied("Use exit for the current shell");
      return kernel.kill(pid);
    }
  }

  public long spawn(LaunchRequest request) {
    synchronized (this) {
      authorize();
      var prepared = kernel != null ? kernel.prepareLaunch(request)
          : JavaInvocation.prepare(request, this::profile,
              home -> { throw denied("Guest JavaHome selection requires JVK authority"); });
      request = prepared.request();
      rejectLifecycleRedirection(request);
      var packagedShell = kernel == null ? null : kernel.packagedShell(request, shellDispatcher);
      if (packagedShell != null && request.redirectsOutput())
        throw new KernelException(ErrorCode.CONFIG_ERROR,
            "Output redirection is not supported for lifecycle targets");
      synchronized (table) {
        authorize();
        RuntimeProfile profile = prepared.profile();
        String ownerDispatcher = shellDispatcher;
        boolean distribution = kernel != null && !ownerDispatcher.isEmpty()
            && kernel.sameDistribution(shellClasspath, request);
        boolean nested = packagedShell != null
            || distribution && request.argv().get(0).equals(ownerDispatcher + ":esh");
        boolean shutdown = distribution && request.argv().get(0).equals(ownerDispatcher + ":shutdown");
        boolean outputApplet = distribution && request.redirectsOutput()
            && request.argv().get(0).startsWith(ownerDispatcher + ":");
        var child = table.spawn(caller, request);
        boolean foreground = false;
        try {
          // Output inheritance grants neither terminal input nor foreground/control authority.
          var output = vts.processIo(terminal, caller);
          var childApi =
              new JvkProcessApi(table, provider, profiles, vts,
                  nested || shutdown ? terminal : -1, child.pid(), nested);
          if (terminalKernel != null) childApi.forTerminals(terminalKernel);
          if (outputApplet) {
            // The same distribution applet retains its shell's read-only process view.
            // This does not grant terminal input, foreground, spawn, kill or shutdown authority.
            childApi.appletInspection = true;
            childApi.inspectionOwner = caller;
            childApi.kernel = kernel;
          }
          if (nested) childApi.forShell(kernel, ownerDispatcher,
              packagedShell == null ? request.classpath() : packagedShell.classpath());
          if (shutdown) childApi.forShutdown(kernel);
          if (nested) {
            vts.pushForeground(terminal, caller, child.pid());
            foreground = true;
          }
          var execution = provider.start(child, profile,
              nested ? vts.processIo(terminal, child.pid())
                  : ProcessIo.background(output.out(), output.err()), childApi);
          var restored = nested ? execution.cleanupCompletion().whenComplete((unused, failure) -> {
            if (failure == null) vts.foregroundCompleted(terminal, child.pid());
          }) : execution.cleanupCompletion();
          children.put(child.pid(), restored);
          return child.pid();
        } catch (RuntimeException failure) {
          if (foreground) vts.foregroundCompleted(terminal, child.pid());
          if (!child.state().terminal()) {
            child.requestStop();
            child.confirm(ProcessOutcome.failed(ErrorCode.START_FAILED, "Managed start failed"));
          }
          throw failure;
        }
      }
    }
  }

  public ProcessOutcome waitOutcome(long pid) {
    if (!allowed) {
      throw denied("Wait capability not granted");
    }
    var process = table.inspect(pid);
    if (process.parentPid() != caller) {
      throw denied("Not a direct child");
    }
    var completion = children.get(pid);
    if (completion == null) {
      throw denied("Not an accepted application child");
    }
    try {
      try {
        CompletableFuture.anyOf(completion, closeFuture).get();
      } catch (ExecutionException failure) {
        if (closed || closeFuture.isDone()) {
          throw callerStopped();
        }
        throw failure;
      }
      if (closed || closeFuture.isDone()) {
        throw callerStopped();
      }
      return process.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new KernelException(ErrorCode.SOURCE_CLOSED, "Wait interrupted");
    } catch (ExecutionException failure) {
      throw new KernelException(ErrorCode.START_FAILED, "Child cleanup failed for PID " + pid,
          failure.getCause());
    }
  }

  public int runApplet(LaunchRequest request) {
    if (request.redirectsOutput()) {
      throw new KernelException(ErrorCode.CONFIG_ERROR,
          "Output redirection requires a new managed JVP");
    }
    if (JavaInvocation.selectsHome(request)) {
      throw new KernelException(ErrorCode.CONFIG_ERROR,
          "Explicit Guest JavaHome requires a new managed JVP");
    }
    TerminalAppletWorker selected;
    List<String> argv = new ArrayList<>(request.argv());
    String entry = argv.remove(0);
    int colon = entry.indexOf(':');
    if (colon < 1) {
      throw new KernelException(
          ErrorCode.ENTRY_NOT_FOUND, "ENTRY_NOT_FOUND: missing applet target");
    }
    String className = entry.substring(0, colon), target = entry.substring(colon + 1);
    argv.add(0, target);
    if (Set.of("init", "esh", "shutdown").contains(target)) {
      throw denied("Target cannot run in worker");
    }
    synchronized (this) {
      synchronized (table) {
        authorize();
        if (worker == null) {
          if (workerSnapshot != null) {
            throw new KernelException(
                ErrorCode.RESOURCE_CLOSED, "RESOURCE_CLOSED: worker terminated");
          }
          RuntimeProfile profile = profile(request.runtimeId());
          workerSnapshot = request;
          dispatcher = className;
          var process =
              table.spawn(
                  caller,
                  new LaunchRequest(
                      request.runtimeId(),
                      List.of(className),
                      request.cwd(),
                      request.environment(),
                      request.classpath()));
          var execution =
              provider.startAppletWorker(
                  process,
                  profile,
                  vts.processIo(terminal, process.pid()),
                  new JvkProcessApi(table, provider, profiles, vts, terminal, process.pid(), false)
                      .forApplets(kernel));
          worker = new TerminalAppletWorker(vts, terminal, caller, execution);
        } else if (!compatible(request, className)) {
          throw new KernelException(
              ErrorCode.CONFIG_ERROR, "CONFIG_ERROR: worker launch snapshot mismatch");
        }
        selected = worker;
      }
    }
    try {
      return selected.invoke(argv, request.cwd(), request.environment()).get();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new KernelException(ErrorCode.SOURCE_CLOSED, "Applet wait interrupted");
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof KernelException kernelFailure) {
        throw kernelFailure;
      }
      String message = String.valueOf(cause.getMessage());
      ErrorCode code =
          message.contains("ENTRY_NOT_FOUND:") ? ErrorCode.ENTRY_NOT_FOUND : ErrorCode.START_FAILED;
      throw new KernelException(code, code + ": applet invocation failed: " + message);
    }
  }

  private boolean compatible(LaunchRequest r, String entry) {
    return dispatcher.equals(entry)
        && workerSnapshot.runtimeId().equals(r.runtimeId())
        && workerSnapshot.classpath().equals(r.classpath());
  }

  private Object runtimeList() {
    Object[] rows =
        kernel.runtimeProfiles().stream()
            .map(
                p ->
                    immutable(
                        Map.of(
                            "id",
                            p.id(),
                            "version",
                            p.version(),
                            "vendor",
                            p.vendor(),
                            "javaHome",
                            p.javaHome().toString())))
            .toArray();
    return immutable(
        Map.of(
            "hostVersion",
            System.getProperty("java.version"),
            "hostJavaHome",
            System.getProperty("java.home"),
            "espressoVersion",
            kernel.espressoVersion(),
            "runtimes",
            immutableArray(rows)));
  }

  private static ProxyArray immutableArray(Object[] rows) {
    return new ProxyArray() {
      public Object get(long i) {
        return rows[Math.toIntExact(i)];
      }

      public long getSize() {
        return rows.length;
      }

      public void set(long i, Value value) {
        throw denied("Projection is immutable");
      }
    };
  }

  private Object processList() {
    synchronized (this) {
      if (closed || (!allowed && !appletInspection)) {
        throw denied("Process inspection not granted");
      }
      long foreground = inspectionOwner >= 0 ? inspectionOwner : caller;
      long inspectionTerminal = inspectionOwner >= 0
          ? kernel.processTerminal(inspectionOwner) : terminal;
      if (table.inspect(caller).state() != ProcessState.RUNNING
          || inspectionOwner >= 0 && table.inspect(inspectionOwner).state() != ProcessState.RUNNING
          || vts.foreground(inspectionTerminal) != foreground) {
        throw denied("Caller is not running foreground");
      }
    }
    Object[] rows =
        table.records().stream()
            .map(
                p ->
                    immutable(
                        Map.of(
                            "terminalId",
                            kernel == null
                                ? (p.pid() == caller ? terminal : -1L)
                                : kernel.processTerminal(p.pid()),
                            "pid",
                            p.pid(),
                            "parentPid",
                            p.parentPid(),
                            "state",
                            p.state().name(),
                            "runtimeId",
                            p.request().runtimeId(),
                            "command",
                            String.join(" ", p.request().argv()))))
            .toArray();
    return new ProxyArray() {
      public Object get(long i) {
        return rows[Math.toIntExact(i)];
      }

      public long getSize() {
        return rows.length;
      }

      public void set(long i, Value value) {
        throw denied("Projection is immutable");
      }
    };
  }

  private RuntimeProfile profile(String id) {
    RuntimeProfile result = profiles.apply(id);
    if (result == null) {
      throw new KernelException(ErrorCode.RUNTIME_NOT_FOUND, "RUNTIME_NOT_FOUND: " + id);
    }
    return result;
  }

  private static KernelException callerStopped() {
    return new KernelException(ErrorCode.SOURCE_CLOSED, "SOURCE_CLOSED: caller stopped");
  }

  public synchronized long workerPid() {
    return worker == null ? -1 : worker.workerPid();
  }

  public void interruptWorker() {
    TerminalAppletWorker w;
    synchronized (this) {
      w = worker;
    }
    if (w != null) {
      w.interrupt();
    }
  }

  @Override
  public void close() {
    TerminalAppletWorker w;
    synchronized (this) {
      closed = true;
      w = worker;
    }
    // Complete outside the lock, before owned cleanup can block.
    closeFuture.complete(null);
    for (var frontend : frontends) frontend.ownerClosed();
    frontends.clear();
    if (w != null) {
      w.close();
    }
  }

  private static LaunchRequest snapshot(Value value) {
    String id = value.getMember("runtimeId").asString(), cwd = value.getMember("cwd").asString();
    List<String> argv = strings(value.getMember("argv")),
        keys = strings(value.getMember("environmentKeys")),
        values = strings(value.getMember("environmentValues"));
    if (keys.size() != values.size()) {
      throw new IllegalArgumentException("CONFIG_ERROR: environment lengths differ");
    }
    Map<String, String> env = new LinkedHashMap<>();
    for (int i = 0; i < keys.size(); i++) {
      if (env.put(keys.get(i), values.get(i)) != null) {
        throw new IllegalArgumentException("CONFIG_ERROR: duplicate environment key");
      }
    }
    Path stdoutPath = outputPath(value, "stdoutPath"), stderrPath = outputPath(value, "stderrPath");
    boolean stdoutAppend = outputAppend(value, "stdoutAppend"),
        stderrAppend = outputAppend(value, "stderrAppend");
    if (stdoutAppend && stdoutPath == null || stderrAppend && stderrPath == null)
      throw new KernelException(ErrorCode.CONFIG_ERROR, "Append requires an output path");
    return new LaunchRequest(
        id,
        argv,
        Path.of(cwd),
        env,
        strings(value.getMember("classpath")).stream().map(Path::of).toList(),
        stdoutPath, stdoutAppend, stderrPath, stderrAppend);
  }

  private static Path outputPath(Value value, String name) {
    if (!value.hasMember(name)) return null; // Original six-field projections remain valid.
    Value path = value.getMember(name);
    if (path == null || path.isNull()) return null;
    if (!path.isString() || path.asString().isEmpty())
      throw new KernelException(ErrorCode.CONFIG_ERROR, "Invalid output path: " + name);
    try {
      Path result = Path.of(path.asString());
      if (!result.isAbsolute()) throw new IllegalArgumentException("Expected absolute path");
      return result;
    } catch (IllegalArgumentException invalid) {
      throw new KernelException(ErrorCode.CONFIG_ERROR, "Invalid output path: " + name, invalid);
    }
  }

  private static boolean outputAppend(Value value, String name) {
    if (!value.hasMember(name)) return false;
    Value append = value.getMember(name);
    if (append == null || !append.isBoolean())
      throw new KernelException(ErrorCode.CONFIG_ERROR, "Expected boolean: " + name);
    return append.asBoolean();
  }

  private static void rejectLifecycleRedirection(LaunchRequest request) {
    if (!request.redirectsOutput()) return;
    String entry = request.argv().get(0);
    int colon = entry.indexOf(':');
    if (colon > 0 && Set.of("init", "esh", "shutdown").contains(entry.substring(colon + 1)))
      throw new KernelException(ErrorCode.CONFIG_ERROR,
          "Output redirection is not supported for lifecycle targets");
  }

  private static List<String> strings(Value value) {
    if (value == null || !value.hasArrayElements()) {
      throw new IllegalArgumentException("CONFIG_ERROR: expected string array");
    }
    int size = Math.toIntExact(value.getArraySize());
    List<String> result = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      result.add(value.getArrayElement(i).asString());
    }
    return List.copyOf(result);
  }

  private static ProxyObject outcome(ProcessOutcome result) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("state", result.state().name());
    values.put("exitCode", result.exitCode());
    values.put("error", result.error() == null ? "" : result.error().name());
    values.put("message", result.message());
    return immutable(values);
  }

  private static ProxyObject immutable(Map<String, Object> values) {
    return new ProxyObject() {
      public Object getMember(String key) {
        return values.get(key);
      }

      public Object getMemberKeys() {
        return values.keySet().toArray(String[]::new);
      }

      public boolean hasMember(String key) {
        return values.containsKey(key);
      }

      public void putMember(String key, Value v) {
        throw denied("Projection is immutable");
      }
    };
  }
}
