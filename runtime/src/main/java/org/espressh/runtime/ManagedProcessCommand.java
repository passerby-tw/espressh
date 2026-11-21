package org.espressh.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.espressh.core.LaunchRequest;
import org.espressh.core.RuntimeProfile;
import org.graalvm.polyglot.Value;

/** Validate the finite standard-Java launcher before allocating a PID or any pipe. */
final class ManagedProcessCommand {
    private static final List<String> SUPPORTED_GUEST_VERSIONS = List.of("25.0.5.0.3", "25.0.4.1.1");

    private ManagedProcessCommand() {}

    static LaunchRequest parse(Value value, LaunchRequest caller, RuntimeProfile runtime,
                               Map<String,String> inheritedEnvironment) throws IOException {
        try {
            requireSupported(runtime);
            List<String> command = strings(value.getMember("command"));
            if (command.size() < 2) throw new IOException("Expected java and an application entry");
            String executable = command.get(0);
            if (!executable.equals("java")) {
                Path selected = runtime.javaHome().resolve("bin/java").toRealPath();
                Path requested = caller.cwd().resolve(executable);
                if (!requested.isAbsolute() || !requested.toRealPath().equals(selected))
                    throw new IOException("Only the selected Guest Java launcher is supported");
            }
            Value directory = value.getMember("directory");
            Path cwd = directory == null || directory.isNull() ? caller.cwd()
                    : caller.cwd().resolve(directory.asString()).normalize();
            if (!Files.isDirectory(cwd)) throw new IOException("Not a directory: " + cwd);
            Map<String,String> environment;
            Value keys = value.getMember("environmentKeys"), values = value.getMember("environmentValues");
            boolean nullKeys = keys == null || keys.isNull(), nullValues = values == null || values.isNull();
            if (nullKeys != nullValues) throw new IOException("Environment arrays must both be null or present");
            if (nullKeys) environment = Map.copyOf(inheritedEnvironment);
            else {
                List<String> names = strings(keys), entries = strings(values);
                if (names.size() != entries.size()) throw new IOException("Environment lengths differ");
                environment = new LinkedHashMap<>();
                for (int i = 0; i < names.size(); i++) {
                    String name = names.get(i), entry = entries.get(i);
                    if (name.isEmpty() || name.indexOf('=') >= 0 || name.indexOf('\0') >= 0
                            || entry.indexOf('\0') >= 0 || environment.put(name, entry) != null)
                        throw new IOException("Invalid or duplicate environment entry");
                }
            }
            List<String> words = List.copyOf(command.subList(1, command.size()));
            int index = 0;
            if (words.get(0).equals("-cp") || words.get(0).equals("-classpath")) {
                if (words.size() < 3) throw new IOException("Missing classpath or main class");
                index = 2;
            }
            String entry = words.get(index);
            if (entry.startsWith("-") && !entry.equals("-jar") && !entry.equals("-version"))
                throw new IOException("Unsupported managed Java option: " + entry);
            if (entry.equals("-jar") && words.size() <= index + 1)
                throw new IOException("Missing JAR path");
            if (!entry.startsWith("-") && (entry.isEmpty() || entry.indexOf(':') >= 0))
                throw new IOException("Expected a Java main class");
            // No CLASSPATH/PATH search. Like a Java launcher without -cp, the default is CWD.
            LaunchRequest request = new LaunchRequest(runtime.id(), words, cwd, environment, List.of(cwd));
            return JavaInvocation.prepare(request, id -> runtime,
                    home -> { throw new IllegalArgumentException("Guest JavaHome selection is unsupported"); })
                    .request();
        } catch (IllegalArgumentException | org.espressh.core.KernelException invalid) {
            throw new IOException(invalid.getMessage(), invalid);
        }
    }

    private static List<String> strings(Value value) throws IOException {
        if (value == null || !value.hasArrayElements() || value.getArraySize() > Integer.MAX_VALUE)
            throw new IOException("Expected a string array");
        var result = new ArrayList<String>();
        for (long i = 0; i < value.getArraySize(); i++) {
            Value item = value.getArrayElement(i);
            if (item == null || !item.isString() || item.asString().indexOf('\0') >= 0)
                throw new IOException("Invalid command/environment string");
            result.add(item.asString());
        }
        return result;
    }

    private static void requireSupported(RuntimeProfile runtime) throws IOException {
        if (!System.getProperty("os.name").equals("Linux")
                || !List.of("amd64", "x86_64").contains(System.getProperty("os.arch"))
                || !SUPPORTED_GUEST_VERSIONS.contains(runtime.version()))
            throw new IOException("Managed ProcessBuilder requires Linux x86_64 / Guest JDK "
                    + String.join(" or ", SUPPORTED_GUEST_VERSIONS));
    }
}
