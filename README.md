# Espressh

A shell for Espresso. [Run multiple Java applications in a single JVM](https://bgplayground.blogspot.com/2026/11/run-multiple-java-applications-in.html).

<p align="left">
  <img src="https://github.com/user-attachments/assets/8e19fe51-0822-4998-9f1a-f164b174db4a"
       alt="espressh"
       width="240">
</p>

Espressh uses GraalVM Espresso to run Java applications and provides an interactive `esh` shell, managed processes, virtual terminals, and basic command utilities. JVK coordinates application startup, input and output, interruption, and shutdown within one host JVM.

**Run existing Java applications without changing their source, recompiling them, or adding a JVK API dependency.** Compatibility depends on the selected guest JDK and the Java APIs supported by Espresso.

## How it works

- **JVK** manages processes, virtual terminals, and resource lifecycles.
- **JVP** is a managed logical process with its own Espresso Context. Its PID identifies a process within Espressh.
- **VTS** provides virtual terminals and manages frontend connections and foreground input.
- **JBox** supplies `init`, `esh`, and commands such as `ls`, `cat`, `ps`, and `top`.

Java applications run in separate JVPs. Some short JBox commands run sequentially in a persistent worker JVP owned by the shell, reducing Context startup overhead.

## Requirements

Use the following build environment:

| Component | Environment |
| --- | --- |
| Operating system | Linux x86_64 |
| Host JDK | GraalVM Community JDK 25.0.5.0.3 |
| Maven | 3.8.7 |
| Espresso | 25.5.5.0.3, built from the source in this repository |
| Truffle / Polyglot SDK | 25.5.5.0.3, downloaded by Maven |

Use a complete JDK and check which Java installation Maven uses:

```sh
java -version
mvn -version
```

The SDK version is pinned in [pom.xml](pom.xml). Maven compiles the local Espresso modules, including the annotation processor, as part of the same build.

## Build

From the repository root:

```sh
mvn clean package
```

The first build needs network access to download Maven dependencies. The resulting executable is:

```text
runtime/target/espressh.jar
```

This single JAR includes Espressh, JBox, and runtime dependencies. No separate boot JAR or dependency directory is required. **A JDK must still be installed.**

Espressh prepares a small private Guest classpath in the host JVM's temporary directory, which must be writable. It keeps the public distribution as one JAR and removes the temporary files after confirmed shutdown.

Stop any Espressh instance using the JAR before rebuilding it, or build in a separate directory to avoid replacing a running application's JAR.

## Start the shell

From the repository root:

```sh
java -jar runtime/target/espressh.jar
```

Startup creates JVK, runs `init`, and opens `esh` on the host console:

```text
esh> pwd
esh> ls
esh> echo hello
hello
esh> java -version
```

You can also copy the JAR to another directory and start it there:

```sh
java -jar espressh.jar
```

Cold startup can currently take more than 30 seconds. Initial prompt latency remains an area for improvement.

## Docker

Build the image from the repository root:

```sh
docker build -t espressh .
```

Start an interactive session:

```sh
docker run --rm -it --init espressh
```

To access applications and files from the current host directory:

```sh
docker run --rm -it --init \
  --mount "type=bind,source=$(pwd),target=/workspace" \
  espressh
```

The container starts `esh` in `/workspace`. It runs as the `espressh` user with UID 1000; mounted files need appropriate read/write permissions for that user. Use `exit` and confirm shutdown to end the session.

The [Dockerfile](Dockerfile) builds the application in a separate stage and uses the matching GraalVM Community JDK in the final image. Maven and project sources remain in the build stage. A complete JDK stays in the runtime image so that Espresso can use its standard library and the integrated JDK tools remain available.

This image provides the CLI. GUI display forwarding is not configured. Keep stdin open with `-it`; closing the console input triggers Espressh's frontend-loss handling.

## Run Java applications

Enter an executable JAR's path directly:

```text
esh> /path/to/application.jar
```

Or use the explicit `java` launcher syntax:

```text
esh> java /path/to/application.jar
esh> java -jar /path/to/application.jar
esh> java -cp /path/to/classes com.example.Main
```

`java` is a **launcher keyword** recognized by `esh`. Espressh parses the launch arguments and asks JVK to execute the application in an Espresso JVP. All three JAR forms use the same managed launch mechanism; when `-jar` is omitted, the `.jar` extension identifies the entry as a JAR. Use `java` with a fully qualified class name to launch a class main method.

Relative paths resolve against the shell's working directory, which you can change with `cd`. File operations use the host filesystem with the current OS user's permissions.

### Select a guest JDK

The default guest JDK comes from the running host JVM's actual `java.home`. To select another compatible, complete JDK for one invocation:

```text
esh> java --java.JavaHome=/path/to/jdk -version
esh> java --java.JavaHome=/path/to/jdk -jar /path/to/application.jar
```

This selects the new process's guest JDK. It does not change the host JVM, the Espresso implementation, or the current shell's runtime.

## Use JDK tools directly

Espressh integrates `javac`, `javap`, `jar`, and `javadoc`. Enter their names directly in `esh`; each tool runs in a managed JVP through the selected guest JDK's `java.util.spi.ToolProvider`.

For example, with a `Hello.java` file containing a main method in the working directory:

```text
esh> javac -d out Hello.java
esh> java -cp out Hello
esh> javap -classpath out Hello
esh> jar --create --file hello.jar --main-class Hello -C out .
esh> hello.jar
esh> javadoc -d api Hello.java
```

The guest JDK's tool implementation handles tool arguments. Support depends on that JDK and Espresso compatibility:

- The selected guest JDK must include the tool and its ToolProvider. Otherwise, the tool is reported as unavailable.
- JVM options handled by standalone tool launchers, such as `-J...`, are unsupported. They do not configure the host JVM or guest Context.
- Only the four tools above are integrated. Commands such as `jps`, `jcmd`, and `jlink` are not integrated.
- Annotation processors, compiler plugins, and doclets run in the guest with the same API and permission limits. Features requiring unsupported native APIs or host process creation cannot be used.

## One JAR, two entry points

Running `java -jar espressh.jar` on the host JVM starts JVK. Running the same JAR inside Espressh starts a nested `esh`:

```text
esh> java runtime/target/espressh.jar
esh> exit
esh>
```

This example assumes startup from the repository root. If you started a copied `espressh.jar`, use `java espressh.jar` instead.

The nested entry must reference the **same physical JAR** used to start the current instance. It accepts no additional application arguments, background execution, or redirection. You can also enter `esh` directly to start a nested shell. Its `exit` command returns to the parent shell.

## Commands

| Command | Purpose |
| --- | --- |
| `pwd`, `cd <directory>` | Show or change the working directory |
| `ls`, `ls -l` | List files |
| `cat <file>` | Print file contents |
| `ps` | List currently active managed processes |
| `top` | Refresh the process view every two seconds; enter `q` followed by Enter to leave |
| `top -b -n 1` | Print one process snapshot |
| `kill <PID>` | Stop a managed process |
| `runat --list` | List available guest runtime selections |
| `exit` | Leave the current shell |
| `shutdown` | Request coordinated shutdown of Espressh and all managed programs |

### Background execution and output redirection

Run an application in the background with `&`:

```text
esh> java -jar /path/to/application.jar &
esh> ps
```

Background processes retain the original terminal's stdout and stderr but do not acquire terminal stdin or foreground ownership. Reads from stdin return EOF. Use `ps` to find a PID, then `kill <PID>` to stop it.

Redirect output with:

```text
esh> java -jar /path/to/application.jar > output.txt 2> error.txt
esh> echo hello >> output.txt
```

Supported operators are `>`, `1>`, and `>>` for stdout, and `2>` and `2>>` for stderr. Shell builtins and lifecycle commands do not support redirection. Host engine logs are independent of guest stderr redirection.

## Exit and shutdown

In a nested shell, `exit` ends that shell and returns to its parent.

In the host console's root shell, `exit` asks:

```text
Shut down Espressh and stop all running programs? [Y/n]
```

Enter `Y` or press Enter to coordinate shutdown of all JVPs and exit Espressh. Enter `N` to keep the current shell running. The `shutdown` command submits a system-wide shutdown request directly.

## Current limitations

- Espressh uses EspressoLibs in no-native mode. Some Java native APIs are not yet supported, and guests cannot create host processes.
- There is no host shell or native command fallback. `esh` does not implement Bash compatibility.
- Pipes, stdin redirection, `2>&1`, `jobs` / `fg` / `bg`, `kill -9`, and arbitrary JVM options are unsupported.
- JVPs share the host JVM's heap and GC. Per-JVP CPU and memory usage is currently unavailable. Espressh does not provide hard resource isolation or a security sandbox for untrusted code.
- Compatibility with other platforms and JDK combinations has not been fully verified.

## Source layout

| Module | Contents |
| --- | --- |
| [jvk-api](jvk-api/) | Public Java API and VTS interfaces for applications |
| [core](core/) | Processes, VTS, runtime policy, and lifecycle management |
| [guest-bootstrap](guest-bootstrap/) | Guest API provider, IO, and AWT adapters |
| [jbox](jbox/) | `init`, `esh`, and command utilities |
| [runtime](runtime/) | Host entry point, Espresso integration, and single-JAR packaging |

Only applications that explicitly use JVK features, such as requesting a VTS virtual terminal, need `jvk-api`. Ordinary Java applications have no such dependency. Use Maven `provided` scope when depending on the API. Espressh supplies the API and provider at runtime; applications should not bundle JVK or Espresso implementations.
