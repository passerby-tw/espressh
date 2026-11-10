package org.espressh.jbox;
import org.espressh.api.JvkSystem;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
/** Guest PID 1. Kernel services remain in the Host. */
public final class Init {
 private Init() {}
 public static void main(String[] args) throws Exception {
  if(args.length!=0)throw new IllegalArgumentException("Unsupported init arguments");
  String source="META-INF/etc/inittab";
  try(InputStream resource=Init.class.getClassLoader().getResourceAsStream(source)) {
   if(resource!=null)StartupFiles.validateInit(source,new String(resource.readAllBytes(),StandardCharsets.UTF_8));
  } catch(IOException failure) {
   throw new ShellException(ShellException.CONFIG_ERROR,source+": Cannot read configuration",failure);
  }
  Path cwd=Path.of(System.getProperty("user.dir"));
  var cp=CommandClasspath.parse(System.getProperty("java.class.path"),false);
  var request=LaunchRequests.create("host",List.of("org.espressh.jbox.JBox:esh"),cwd,System.getenv(),cp);
  JvkSystem.current().createTerminal(request);
  JvkSystem.current().awaitShutdown();
 }
}
