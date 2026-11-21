# ProcessBuilder startup benchmark

The same standard Java source runs on a Host JVM and inside Espressh. It starts
a no-op child using `ProcessBuilder`, the current `java.home`, and the current
classpath. There is no JVK API dependency, shell script, or Python build step.

On the Host, each child is an external JVM. Inside Espressh, each child is a
managed JVP in the same Host JVM.

## Build

From this directory, using a JDK:

```sh
javac --release 17 -d target/classes StartupBenchmark.java
jar --create --file target/startup-benchmark.jar \
  --main-class StartupBenchmark -C target/classes .
```

This is a standalone benchmark; the repository's root Maven build does not
compile or execute it. Generated classes and JARs are ignored by Git.

## Run on the Host

```sh
java -jar target/startup-benchmark.jar host-process 30
```

## Run inside Espressh

Build and start Espressh from the repository root:

```sh
mvn clean package
java -jar runtime/target/espressh.jar
```

Then enter at the `esh>` prompt:

```text
java -jar benchmarks/startup/target/startup-benchmark.jar guest-process 30
```

Use an absolute JAR path if Espressh starts in another working directory.
The managed ProcessBuilder adapter supports Guest JDK 25.0.5.0.3 and
25.0.4.1.1 on Linux x86_64.

The mode argument is optional; `java -jar target/startup-benchmark.jar 30` detects
the environment from `java.vm.name`. The explicit modes reject an unexpected VM
with exit status 2. These labels are descriptive checks, not authorization or
proof of backend identity: standalone Espresso can have the same VM name.

## What is measured

Each sample includes ProcessBuilder construction/start, no-op child execution,
closing child stdin, draining merged stdout/stderr, waiting for exit status 0,
closing all returned streams, and confirming termination. Failure aborts the
benchmark after requesting cleanup; it is not counted as a successful sample.

The output includes the actual VM, Java version/home, classpath, individual
samples, and a summary. Look for `BENCHMARK_COMPLETE`; the outer runtime exiting
does not by itself prove that the Guest benchmark completed.

- Top-level benchmark startup and Espressh boot are excluded.
- First invocation is not a guarantee of a cold filesystem cache, compiler, or
  shared Engine. Earlier Espressh commands may have warmed the Engine.
- The mean excluding the first five requires at least six samples; it alone
  does not establish steady state.
- Compare matching JDKs and record machine load and whether a fresh Espressh
  instance was used. Run multiple instances before drawing performance conclusions.
- External JVM and managed JVP startup have different lifecycle costs. This
  sequential no-op benchmark does not measure application throughput, parallel
  scaling, or memory isolation, and does not assume a particular fork/exec backend.

## Runtime phase diagnostics

To locate managed startup costs, enable Host diagnostics when starting Espressh:

```sh
java -Despressh.runtime.timing=true -jar runtime/target/espressh.jar 2>runtime-timing.log
```

Run the same benchmark at `esh>`. Each completed execution emits one
`JVP_TIMING` line to Host stderr, including logical PID/parent PID, application
or worker mode, lifecycle state, cleanup confirmation and phase durations in
milliseconds. Diagnostics are disabled by default and do not enter child PIPEs
or follow Guest `2>` redirection.

Phases separate worker allocation/scheduling, IO setup, entry resolution, Context
configuration/build, Host binding installation, Java initialization, environment
linking, IO bootstrap lookup/install, application lookup, argv setup, main
invocation, and child/IO/Context/graphics cleanup. Main invocation can include
deferred Guest class initialization. Worker main time includes its command loop
and idle periods; it is not a per-command measurement.

`total_ms` starts when the execution timing record is allocated and ends after
the worker's final lifecycle confirmation. It excludes parent-side ProcessBuilder
validation/bridge calls, later parent stream operations, trace formatting/output
and completion-future notification. These boundaries differ from the benchmark.
Trace output occurs before completion notification, so its overhead can affect
benchmark samples even though it is excluded from the trace total. Use a separate
run with diagnostics disabled for performance comparisons. Long phases identify
where wall time was spent, not whether CPU work, compilation or scheduling caused it.
