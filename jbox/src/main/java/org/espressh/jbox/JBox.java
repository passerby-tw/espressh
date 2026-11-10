package org.espressh.jbox;

import java.nio.file.Path;
import java.util.Arrays;
import org.espressh.api.JvkSystem;

/** Guest-only minimal worker dispatcher. init/esh execute in independent managed JVPs. */
public final class JBox {
    private JBox() {}
    public static void main(String[] args) throws Exception {
        if(args.length==0)throw new IllegalArgumentException("Missing target");
        if(args[0].equals("esh")) {Esh.main(Arrays.copyOfRange(args,1,args.length));return;}
        if(args[0].equals("init")) {Init.main(Arrays.copyOfRange(args,1,args.length));return;}
        if(args[0].equals("shutdown")) {
            JvkSystem.current().requestShutdown();return;
        }
        int code=runApplet(args);
        if(code!=0)System.exit(code);
    }
    public static int runApplet(String[] args) {
        return runApplet(Path.of(System.getProperty("user.dir")),args);
    }
    /** Per-command directory is data, never a mutation of JVM-wide properties. */
    public static int runAppletAt(String[] invocation) {
        if(invocation.length<2)throw new IllegalArgumentException("Missing command directory or applet");
        return runApplet(Path.of(invocation[0]),Arrays.copyOfRange(invocation,1,invocation.length));
    }
    /** Optional runtime dispatcher protocol; these hooks never create or stop a JVP. */
    public static void beginApplet(long generation) { CommandScope.begin(generation); }
    public static boolean interruptApplet(long generation) { return CommandScope.interrupt(generation); }
    public static void endApplet(long generation) { CommandScope.end(generation); }
    private static int runApplet(Path cwd,String[] args) {
        try(var scope=CommandScope.enter()) {
            try {
                scope.checkCancelled();
                return scope.complete(dispatch(cwd,args));
            } catch(CommandScope.Interrupted interrupted) {
                return scope.complete(130);
            }
        }
    }
    private static int dispatch(Path cwd,String[] args) {
        if(args.length==0) throw new IllegalArgumentException("Missing applet");
        switch(args[0]) {
            case "true": return 0;
            case "false": return 1;
            case "echo": System.out.println(String.join(" ",Arrays.copyOfRange(args,1,args.length))); return 0;
            case "pwd":
                if(args.length!=1) {System.err.println("pwd: unsupported arguments");return 2;}
                System.out.println(cwd);return 0;
            case "cat": return Cat.run(cwd,Arrays.copyOfRange(args,1,args.length));
            case "ls": return Listing.run(cwd,Arrays.copyOfRange(args,1,args.length));
            case "ps": case "top": return ProcessView.run(args);
            default: throw new IllegalArgumentException("ENTRY_NOT_FOUND: "+args[0]);
        }
    }
}
