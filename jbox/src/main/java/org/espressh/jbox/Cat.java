package org.espressh.jbox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class Cat {
  static int run(Path cwd, String[] args) {
    List<String> files = new ArrayList<>();
    boolean options = true;
    for (String arg : args) {
      CommandScope.check();
      if (options && arg.equals("--")) {
        options = false;
        continue;
      }
      if (options && arg.startsWith("-") && !arg.equals("-")) {
        System.err.println("cat: unsupported option: " + arg);
        return 2;
      }
      files.add(arg);
    }
    if (files.isEmpty()) files.add("-");
    int status = 0;
    for (String file : files) {
      CommandScope.check();
      try {
        if (file.equals("-")) copy(CommandScope.input(System.in));
        else
          try (InputStream input = Files.newInputStream(cwd.resolve(file))) {
            copy(input);
          }
      } catch (IOException failure) {
        CommandScope.check();
        System.err.println("cat: " + file + ": " + failure.getMessage());
        status = 1;
      }
    }
    CommandScope.check();
    System.out.flush();
    CommandScope.check();
    return status;
  }

  private static void copy(InputStream input) throws IOException {
    // ChannelInputStream.transferTo probes an unsupported native file type in
    // EspressoLibs. Standard bulk reads preserve streaming without that fast path.
    byte[] buffer = new byte[8192];
    while (true) {
      CommandScope.check();
      int length = input.read(buffer);
      CommandScope.check();
      if (length == -1) return;
      System.out.write(buffer, 0, length);
      CommandScope.check();
    }
  }
}
