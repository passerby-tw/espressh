package org.espressh.runtime;

import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.espressh.core.*;

/** Host composition root. Never export this object or its components to a Guest. */
public final class JVK implements AutoCloseable {
  private final KernelOwnership ownership;
  private final ProcessTable processes = new ProcessTable();
  private final RuntimeCatalog runtimes;
  private final EspressoRuntimeProvider provider;
  private final VirtualTerminalService terminals;
  private final ShutdownCoordinator shutdown = new ShutdownCoordinator(processes);
  private final Map<Long, EspressoRuntimeProvider.Execution> executions = new ConcurrentHashMap<>();
  private final Map<Long, Long> terminalOwners = new ConcurrentHashMap<>();
  private final Map<Long, VirtualTerminalService.CloseIntent> terminalCloses =
      new ConcurrentHashMap<>();
  private final ExecutorService controls =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "jvk-control");
            t.setDaemon(true);
            return t;
          });
  private final ExecutorService lifetime =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "jvk-shutdown");
            t.setDaemon(true);
            return t;
          });
  private volatile CompletableFuture<ShutdownCoordinator.Result> stopping;
  private final CompletableFuture<Void> initExit = new CompletableFuture<>();
  private final Path bootstrap;
  private final Frontend bootFrontend;
  private boolean initialCreationAttempted;
  private volatile TerminalExecution initialTerminal;

  public record Frontend(
      boolean hostConsole,
      VirtualTerminalService.Size size,
      OutputStream out,
      OutputStream err,
      int inputCapacity) {}

  public JVK(Path hostJavaHome, Path bootstrap) {
    this(hostJavaHome, bootstrap, null);
  }

  public JVK(Path hostJavaHome, Path bootstrap, Frontend frontend) {
    this(hostJavaHome, bootstrap, frontend, 64);
  }

  public JVK(Path hostJavaHome, Path bootstrap, Frontend frontend, int maxTerminals) {
    if (maxTerminals <= 0) throw new IllegalArgumentException("Invalid terminal limit");
    this.bootstrap = bootstrap.toAbsolutePath();
    this.bootFrontend = frontend;
    ownership = KernelOwnership.acquire();
    EspressoRuntimeProvider created = null;
    try {
      runtimes = new RuntimeCatalog(hostJavaHome, p -> validateHome(p.javaHome()));
      runtimes.refresh(
          () ->
              List.of(
                  new RuntimeProfile(
                      "host",
                      hostJavaHome,
                      System.getProperty("java.version"),
                      System.getProperty("java.vendor"),
                      "espresso",
                      Map.of())));
      created = new EspressoRuntimeProvider(bootstrap, this::track);
      provider = created;
      terminals =
          VirtualTerminalService.withInterruptCapture(
              this::captureInterrupt, () -> requestShutdown(Duration.ofSeconds(10)), maxTerminals);
    } catch (RuntimeException | Error failure) {
      if (created != null) {
        created.close();
      }
      controls.shutdownNow();
      lifetime.shutdownNow();
      ownership.close();
      throw failure;
    }
  }

  private void track(EspressoRuntimeProvider.Execution execution) {
    executions.put(execution.process().pid(), execution);
    if (execution.terminalId() >= 0) {
      terminalOwners.put(execution.process().pid(), execution.terminalId());
      execution
          .cleanupCompletion()
          .whenComplete(
              (ignored, failure) -> {
                if (failure == null) {
                  terminalOwners.remove(execution.process().pid(), execution.terminalId());
                }
              });
    }
    if (execution.process().pid() == 1) {
      execution
          .cleanupCompletion()
          .whenComplete((ignored, failure) -> requestShutdown(Duration.ofSeconds(10)));
    }
  }

  private static void validateHome(Path home) {
    if (!java.nio.file.Files.isRegularFile(home.resolve("release"))
        || !java.nio.file.Files.isRegularFile(home.resolve("lib/modules"))) {
      throw new KernelException(ErrorCode.START_FAILED, "Incomplete modular JDK: " + home);
    }
  }

  public VirtualTerminalService terminals() {
    return terminals;
  }

  public List<ProcessRecord> processes() {
    return processes.records();
  }

  public EspressoRuntimeProvider.Execution startInit(LaunchRequest request) {
    synchronized (processes) {
      var profile = runtimes.forLaunch(request.runtimeId());
      var process = processes.createInit(request);
      return provider.start(
          process,
          profile,
          new ProcessIo(
              InputStream.nullInputStream(),
              OutputStream.nullOutputStream(),
              OutputStream.nullOutputStream()),
          bind(process.pid(), -1, false).forInit(this));
    }
  }

  /** Trusted Host assembly entry; not a new sys member or Guest terminal capability. */
  public record TerminalExecution(
      VirtualTerminalService.Terminal terminal, EspressoRuntimeProvider.Execution execution) {}

  public TerminalExecution startTerminal(
      long parent,
      LaunchRequest request,
      boolean hostConsole,
      VirtualTerminalService.Size size,
      OutputStream out,
      OutputStream err,
      int capacity) {
    return startTerminal(parent, request,
        pid -> terminals.attach(pid, hostConsole, size, out, err, capacity));
  }

  /** Trusted assembly of a terminal with VTS-owned frontend streams. No new sys capability. */
  public TerminalExecution startTerminal(
      long parent, LaunchRequest request, VirtualTerminalService.Size size, int capacity) {
    return startTerminal(parent, request, pid -> terminals.attach(pid, size, capacity));
  }

  private TerminalExecution startTerminal(
      long parent, LaunchRequest request,
      java.util.function.LongFunction<VirtualTerminalService.Terminal> attach) {
    synchronized (processes) {
      var profile = runtimes.forLaunch(request.runtimeId());
      var process = processes.spawn(parent, request);
      VirtualTerminalService.Terminal vt = null;
      try {
        vt = attach.apply(process.pid());
        var attached = vt;
        terminalOwners.put(process.pid(), vt.id());
        var execution =
            provider.start(
                process,
                profile,
                terminals.processIo(vt.id(), process.pid()),
                bind(process.pid(), vt.id(), true).forShell(this));
        execution
            .cleanupCompletion()
            .whenComplete(
                (ignored, failure) -> {
                  if (terminalCloses.containsKey(process.pid())) {
                    controls.execute(() -> finishTerminalClose(process.pid(), failure));
                  } else {
                    try {
                      if (!terminals.closing(attached.id())) frontendLost(attached.frontendId());
                    } catch (KernelException gone) {
                      if (gone.code() != ErrorCode.RESOURCE_CLOSED) throw gone;
                    }
                  }
                });
        return new TerminalExecution(vt, execution);
      } catch (RuntimeException failure) {
        if (vt != null) {
          terminals.lost(vt.frontendId());
        }
        terminalOwners.remove(process.pid());
        if (!process.state().terminal()) {
          process.confirm(ProcessOutcome.failed(ErrorCode.START_FAILED, "Terminal start failed"));
        }
        throw failure;
      }
    }
  }

  boolean sameDistribution(List<Path> parentClasspath, LaunchRequest child) {
    return provider.sameDistribution(parentClasspath, child.classpath());
  }

  JarEntryPlan packagedShell(LaunchRequest request, String dispatcher) {
    if (!PackagedShell.DISPATCHER.equals(dispatcher)
        || !PackagedShell.matches(request, bootstrap)) return null;
    return JarEntryPlan.read(request.cwd().resolve(request.argv().get(1)));
  }

  boolean tryCloseTerminal(long caller, long terminal) {
    synchronized (processes) {
      // The inherited Host console cannot return to its parent shell while this
      // JVM lives. Require Guest confirmation even when other frontends exist.
      var initial = initialTerminal;
      if (initial != null
          && initial.terminal().id() == terminal
          && initial.terminal().hostConsole()) {
        if (terminals.root(terminal) != caller || terminals.foreground(terminal) != caller) {
          throw new KernelException(ErrorCode.ACCESS_DENIED, "Foreground terminal root required");
        }
        return false;
      }
      var intent = terminals.tryCloseRoot(terminal, caller);
      if (intent == null) {
        return false;
      }
      terminalCloses.put(caller, intent);
      return true;
    }
  }

  private void finishTerminalClose(long caller, Throwable failure) {
    var intent = terminalCloses.get(caller);
    if (intent == null) {
      return;
    }
    try {
      if (failure != null) {
        throw new KernelException(ErrorCode.START_FAILED, "Terminal-root cleanup failed");
      }
      terminals.completeClose(
          intent,
          pid -> {
            var execution = executions.get(pid);
            return execution != null
                && execution.process().state().terminal()
                && execution.cleanupCompletion().isDone()
                && !execution.cleanupCompletion().isCompletedExceptionally();
          });
      terminalCloses.remove(caller, intent);
      terminalOwners.remove(caller);
    } catch (KernelException error) {
      if (stopping == null) {
        initExit.completeExceptionally(error);
        requestShutdown(Duration.ofSeconds(10));
      }
    }
  }

  public TerminalExecution initialTerminal() {
    return initialTerminal;
  }

  TerminalExecution createTerminal(long caller, LaunchRequest shell) {
    synchronized (processes) {
      var init = processes.inspect(caller);
      if (caller != 1 || init.state() != ProcessState.RUNNING) {
        throw new KernelException(ErrorCode.ACCESS_DENIED, "Terminal creation not granted");
      }
      if (initialCreationAttempted) {
        throw new KernelException(ErrorCode.INVALID_STATE, "Boot terminal already requested");
      }
      if (bootFrontend == null) {
        throw new KernelException(ErrorCode.CONFIG_ERROR, "Host frontend is not configured");
      }
      var request = init.request();
      if (!shell.runtimeId().equals("host")
          || !shell.cwd().equals(request.cwd())
          || !shell.environment().equals(request.environment())
          || !provider.sameBootClasspath(request.classpath(), shell.classpath())
          || !shell.argv().get(0).endsWith(":esh")) {
        throw new KernelException(ErrorCode.CONFIG_ERROR, "Boot shell snapshot mismatch");
      }
      initialCreationAttempted = true;
      initialTerminal =
          startTerminal(
              caller,
              shell,
              bootFrontend.hostConsole(),
              bootFrontend.size(),
              bootFrontend.out(),
              bootFrontend.err(),
              bootFrontend.inputCapacity());
    }
    var terminal = initialTerminal;
    try {
      terminal.execution().readyCompletion().get();
      terminal
          .execution()
          .cleanupCompletion()
          .whenComplete(
              (ignored, failure) -> {
                // A lost frontend closes IO before queuing shutdown; do not mislabel that race as
                // boot failure.
                if (stopping == null
                    && terminals.attachedCount() > 0
                    && (failure != null
                        || terminal.execution().process().state() == ProcessState.FAILED)) {
                  initExit.completeExceptionally(
                      new KernelException(ErrorCode.START_FAILED, "Terminal-root esh failed"));
                  requestShutdown(Duration.ofSeconds(10));
                }
              });
      return terminal;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new KernelException(ErrorCode.SOURCE_CLOSED, "Terminal creation interrupted");
    } catch (ExecutionException failure) {
      terminals.lost(terminal.terminal().frontendId());
      throw new KernelException(ErrorCode.START_FAILED, "Terminal-root esh initialization failed");
    }
  }

  TerminalExecution createGuestTerminal(long caller, int columns, int rows) {
    synchronized (processes) {
      var owner = processes.inspect(caller);
      if (owner.state() != ProcessState.RUNNING) {
        throw new KernelException(ErrorCode.ACCESS_DENIED, "Caller is not running");
      }
      if (initialTerminal == null) {
        throw new KernelException(ErrorCode.INVALID_STATE, "Initial terminal is not ready");
      }
      var original = initialTerminal.execution().process().request();
      var source = owner.request();
      var shell = new LaunchRequest(source.runtimeId(), original.argv(), source.cwd(),
          source.environment(), original.classpath());
      var created = startTerminal(caller, shell, new VirtualTerminalService.Size(columns, rows), 8192);
      created.execution().cleanupCompletion().whenComplete((ignored, failure) -> {
        // Startup/entry failure must not leave a frontend reader blocked forever.
        try {
          if (!terminals.closing(created.terminal().id())) frontendLost(created.terminal().frontendId());
        } catch (KernelException gone) {
          if (gone.code() != ErrorCode.RESOURCE_CLOSED) throw gone;
        }
      });
      return created;
    }
  }

  void frontendLost(long frontend) {
    try {
      for (long pid : terminals.lost(frontend)) controls.execute(() -> stop(pid));
    } catch (KernelException gone) {
      if (gone.code() != ErrorCode.ACCESS_DENIED && gone.code() != ErrorCode.RESOURCE_CLOSED) throw gone;
    }
  }

  void closeFrontend(VirtualTerminalService.CloseIntent intent) {
    for (long pid : intent.foreground()) stop(pid);
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      for (long pid : intent.foreground()) {
        var execution = executions.get(pid);
        if (execution != null) execution.cleanupCompletion().get(
            Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      }
      terminals.completeClose(intent, pid -> {
        var execution = executions.get(pid);
        return execution != null && execution.process().state().terminal()
            && execution.cleanupCompletion().isDone()
            && !execution.cleanupCompletion().isCompletedExceptionally();
      });
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      requestShutdown(Duration.ofSeconds(10));
      throw new KernelException(ErrorCode.SOURCE_CLOSED, "Frontend close interrupted");
    } catch (ExecutionException | TimeoutException failure) {
      throw new KernelException(ErrorCode.TIMEOUT, "Frontend cleanup unconfirmed");
    }
  }

  void awaitShutdown(long caller, CompletableFuture<Void> callerClose) {
    if (caller != 1 || initialTerminal == null) {
      throw new KernelException(ErrorCode.INVALID_STATE, "Init has not created a terminal");
    }
    try {
      CompletableFuture.anyOf(initExit, callerClose).get();
      if (callerClose.isDone()) {
        throw new KernelException(ErrorCode.SOURCE_CLOSED, "Init wait cancelled");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new KernelException(ErrorCode.SOURCE_CLOSED, "Init wait interrupted");
    } catch (ExecutionException failure) {
      if (callerClose.isDone()) {
        throw new KernelException(ErrorCode.SOURCE_CLOSED, "Init wait cancelled");
      }
      throw new KernelException(ErrorCode.START_FAILED, "Init shutdown notification failed");
    }
  }

  List<RuntimeProfile> runtimeProfiles() {
    return runtimes.list();
  }

  JavaInvocation.Prepared prepareLaunch(LaunchRequest request) {
    return JavaInvocation.prepare(request, runtimes::forLaunch, runtimes::forHome);
  }

  long processTerminal(long pid) {
    return terminalOwners.getOrDefault(pid, -1L);
  }

  String espressoVersion() {
    return provider.engineVersion();
  }

  private JvkProcessApi bind(long caller, long terminal, boolean allowed) {
    return new JvkProcessApi(
        processes, provider, runtimes::forLaunch, terminals, terminal, caller, allowed).forTerminals(this);
  }

  private Runnable captureInterrupt(long pid) {
    // Called under VTS: only concurrent-map/volatile identity reads, never ProcessTable or Guest code.
    var execution = executions.get(pid);
    if (execution == null) return () -> {};
    boolean shell = execution.shellCaller();
    if (!shell) {
      Runnable commandInterrupt = execution.appletInterrupt(() -> {
        Long terminal = terminalOwners.get(pid);
        if (terminal == null) return;
        try { terminals.interruptCommandInput(terminal, pid); }
        catch (java.io.IOException | KernelException gone) { /* terminal closed during dispatch */ }
      });
      if (commandInterrupt != null) {
        return () -> controls.execute(commandInterrupt);
      }
      return () -> controls.execute(() -> stop(pid));
    } else {
      return () -> controls.execute(
          () -> {
            Long terminal = terminalOwners.get(pid);
            if (terminal == null) {
              return;
            }
            try {
              terminals.resetInput(terminal, pid);
            } catch (java.io.IOException | KernelException gone) {
              /* terminal closed during dispatch */
            }
          });
    }
  }

  private void stop(long pid) {
    var execution = executions.get(pid);
    if (execution == null) {
      return;
    }
    execution.stopWithIoUnblock(
        () -> {
          Long terminal = terminalOwners.get(pid);
          if (terminal == null) {
            return;
          }
          try {
            terminals.releaseProcessIo(terminal, pid);
          } catch (KernelException gone) {
            if (gone.code() != ErrorCode.RESOURCE_CLOSED) {
              throw gone;
            }
          }
        });
  }

  /** Accept a managed stop request; completion remains observable through process state. */
  boolean kill(long pid) {
    synchronized (processes) {
      if (controls.isShutdown() || stopping != null)
        throw new KernelException(ErrorCode.RESOURCE_CLOSED, "JVK control dispatch closed");
      var process = processes.inspect(pid);
      if (process.state().terminal()) return false;
      try {
        controls.execute(() -> stop(pid));
      } catch (RejectedExecutionException closed) {
        throw new KernelException(ErrorCode.RESOURCE_CLOSED, "JVK control dispatch closed");
      }
      return true;
    }
  }

  /** Owner cleanup must remain possible after shutdown has frozen public control requests. */
  boolean stopOwnedChild(long parent, long pid) {
    synchronized (processes) {
      var child = processes.inspect(pid);
      if (child.parentPid() != parent || pid == 1)
        throw new KernelException(ErrorCode.ACCESS_DENIED, "Not an owned child");
      if (child.state().terminal()) return false;
      try { controls.execute(() -> stop(pid)); }
      catch (RejectedExecutionException closed) {
        throw new KernelException(ErrorCode.RESOURCE_CLOSED, "JVK control dispatch closed", closed);
      }
      return true;
    }
  }

  public CompletableFuture<ShutdownCoordinator.Result> requestShutdown(Duration timeout) {
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("Negative shutdown timeout");
    }
    processes.freezeSpawn();
    synchronized (this) {
      if (stopping == null) {
        stopping = new CompletableFuture<>();
        var result = stopping;
        lifetime.execute(
            () -> {
              try {
                result.complete(shutdown(timeout));
              } catch (Throwable failure) {
                result.completeExceptionally(failure);
              }
            });
      }
      return stopping.copy();
    }
  }

  private ShutdownCoordinator.Result shutdown(Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    try {
      var result = shutdown.stopChildren(timeout, pid -> controls.execute(() -> stop(pid)));
      if (!result.complete()) {
        for (long pid : result.unconfirmed()) {
          var execution = executions.get(pid);
          System.err.println("SHUTDOWN_UNCONFIRMED: PID " + pid);
          if (execution != null && execution.diagnosticCause() != null) {
            execution.diagnosticCause().printStackTrace(System.err);
          }
        }
        return result;
      }
      List<Long> pending = new ArrayList<>();
      for (var execution : executions.values()) {
        if (execution.process().pid() != 1) {
          awaitCleanup(execution, deadline, pending);
        }
      }
      if (!pending.isEmpty()) {
        return new ShutdownCoordinator.Result(pending);
      }
      // Notify userland only after every child cleanup has completed.
      initExit.complete(null);
      var init = executions.get(1L);
      if (init != null) {
        if (initialTerminal == null) {
          controls.execute(() -> stop(1));
        } else {
          // Reserve at least half of the remaining budget for cancellation/confirmation.
          long remaining = Math.max(0, deadline - System.nanoTime());
          long grace = Math.min(TimeUnit.SECONDS.toNanos(1), remaining / 2);
          try {
            init.cleanupCompletion().get(grace, TimeUnit.NANOSECONDS);
          } catch (TimeoutException expired) {
            System.err.println(
                "INIT_SHUTDOWN_ESCALATION: PID 1 grace expired; requesting cancellation");
            controls.execute(() -> stop(1));
          } catch (ExecutionException failed) {
            pending.add(1L);
          }
        }
        if (pending.isEmpty()) {
          awaitCleanup(init, deadline, pending);
        }
      }
      if (!pending.isEmpty()) {
        return new ShutdownCoordinator.Result(pending);
      }
      terminals.shutdownCompleted();
      provider.close();
      ownership.close();
      controls.shutdown();
      lifetime.shutdown();
      return new ShutdownCoordinator.Result(List.of());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new CompletionException(interrupted);
    }
  }

  private static void awaitCleanup(
      EspressoRuntimeProvider.Execution execution, long deadline, List<Long> pending)
      throws InterruptedException {
    try {
      execution
          .cleanupCompletion()
          .get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    } catch (TimeoutException | ExecutionException failure) {
      pending.add(execution.process().pid());
    }
  }

  @Override
  public void close() {
    var result = requestShutdown(Duration.ofSeconds(10)).join();
    if (!result.complete()) {
      throw new KernelException(
          ErrorCode.TIMEOUT, "JVK shutdown unconfirmed PID/Context/IO: " + result.unconfirmed());
    }
  }
}
