package org.espressh.runtime;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.espressh.core.ErrorCode;
import org.espressh.core.KernelException;
import org.espressh.core.LaunchRequest;
import org.espressh.core.RuntimeProfile;

/** Host-only launcher normalization, before process identity or terminal authority is assigned. */
final class JavaInvocation {
  private static final String HOME = "--java.JavaHome=";
  private JavaInvocation() {}

  record Prepared(LaunchRequest request, RuntimeProfile profile) {}
  private record Parsed(String home, List<String> argv, List<Path> classpath) {}

  static Prepared prepare(LaunchRequest request,
      Function<String, RuntimeProfile> profiles, Function<Path, RuntimeProfile> homes) {
    Parsed parsed = parse(request);
    RuntimeProfile profile;
    if (parsed.home() == null) {
      profile = profiles.apply(request.runtimeId());
    } else {
      Path path;
      try {
        path = request.cwd().resolve(Path.of(parsed.home()));
      } catch (InvalidPathException invalid) {
        throw config("Invalid Guest JavaHome path", invalid);
      }
      profile = homes.apply(path);
    }
    var normalized = new LaunchRequest(profile.id(), parsed.argv(), request.cwd(),
        request.environment(), parsed.classpath(), request.stdoutPath(), request.stdoutAppend(),
        request.stderrPath(), request.stderrAppend());
    return new Prepared(normalized, profile);
  }

  static boolean selectsHome(LaunchRequest request) {
    return parse(request).home() != null;
  }

  private static Parsed parse(LaunchRequest request) {
    List<String> words = request.argv();
    List<Path> classpath = request.classpath();
    String home = null;
    boolean classpathOption = false;
    int index = 0;
    while (index < words.size()) {
      String word = words.get(index);
      if (word.startsWith(HOME)) {
        if (home != null) throw config("Duplicate --java.JavaHome option", null);
        home = word.substring(HOME.length());
        if (home.isEmpty()) throw config("Guest JavaHome path is empty", null);
        index++;
      } else if (word.equals("--java.JavaHome")) {
        throw config("Use --java.JavaHome=<path>", null);
      } else if (word.equals("-cp") || word.equals("-classpath")) {
        if (classpathOption || index + 1 >= words.size())
          throw config("Expected one classpath option and its value", null);
        classpathOption = true;
        var resolved = new ArrayList<Path>();
        for (String part : words.get(index + 1).split(java.util.regex.Pattern.quote(File.pathSeparator), -1)) {
          try {
            resolved.add(request.cwd().resolve(part.isEmpty() ? "." : part).normalize());
          } catch (InvalidPathException invalid) {
            throw config("Invalid managed classpath", invalid);
          }
        }
        classpath = List.copyOf(resolved);
        index += 2;
      } else break;
    }
    if (index == words.size()) throw config("Missing Java entry or -version", null);
    String entry = words.get(index);
    if (entry.equals("-version") && words.size() != index + 1)
      throw config("-version does not accept an application or arguments", null);
    if (entry.startsWith("-")
        && !entry.equals("-version") && !entry.equals("-jar"))
      throw config("Unsupported managed Java option: " + entry, null);
    return new Parsed(home, List.copyOf(words.subList(index, words.size())), classpath);
  }

  private static KernelException config(String message, Throwable cause) {
    var failure = new KernelException(ErrorCode.CONFIG_ERROR, "CONFIG_ERROR: " + message);
    if (cause != null) failure.addSuppressed(cause);
    return failure;
  }
}
