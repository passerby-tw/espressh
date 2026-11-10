package org.espressh.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import org.espressh.core.*;

/** Host executable entry. Guest init is always executed by the pinned Espresso provider. */
public final class HostLauncher {
    private HostLauncher() {}
    public record Prepared(BootPlan boot,Path hostJavaHome,String entryClass) {}
    public static Prepared prepare(Path runningArtifact,List<String> argv,Path cwd,Map<String,String> env) throws IOException {
        boolean packaged=false;
        if(Files.isRegularFile(runningArtifact))try(var jar=new JarFile(runningArtifact.toFile())) {
            packaged=jar.getJarEntry("org/espressh/jbox/JBox.class")!=null;
        }
        BootPlan boot=argv.size()>0&&argv.get(0).equals("--boot")?BootPlan.split(argv,cwd,env):
            packaged?BootPlan.packaged(runningArtifact,argv,cwd,env):BootPlan.split(argv,cwd,env);
        String entry=boot.launcher().equals("jbox")?"org.espressh.jbox.JBox":boot.launcher();
        if(!entry.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.]*"))
            throw new KernelException(ErrorCode.ENTRY_NOT_FOUND,"Invalid managed launcher class");
        try(var jar=new JarFile(boot.artifact().toFile())) {
            if(jar.getJarEntry(entry.replace('.','/')+".class")==null)
                throw new KernelException(ErrorCode.ENTRY_NOT_FOUND,"Initial launcher is absent from selected boot artifact: "+boot.launcher());
        }
        Path home=Path.of(System.getProperty("java.home")).toRealPath();
        validateHome(home);
        return new Prepared(boot,home,entry);
    }
    private static void validateHome(Path home) {
        if(!Files.isRegularFile(home.resolve("release"))||!Files.isRegularFile(home.resolve("lib/modules")))
            throw new KernelException(ErrorCode.START_FAILED,"Host java.home is not a complete supported modular JDK: "+home);
    }
    /** Embedding entry with explicit trusted-bootstrap location. No public Guest syscall is added. */
    public static int run(Prepared prepared,Path bootstrap,PrintStream diagnostics) throws Exception {
        try(var resources=new BootResources(prepared.boot().artifact())) {
            if(resources.read("/etc/runtime_dir.json").isPresent())
                throw new KernelException(ErrorCode.CONFIG_ERROR,"runtime_dir.json discovery schema is not implemented");
            try(var kernel=new JVK(prepared.hostJavaHome(),bootstrap,new JVK.Frontend(true,new VirtualTerminalService.Size(80,24),System.out,System.err,8192),terminalLimit())) {
                try(var console=new HostConsole(kernel.terminals(),() -> {var t=kernel.initialTerminal();return t==null?null:t.terminal();},System.in,diagnostics)) {
                console.start();
                var plan=prepared.boot();var argv=new ArrayList<String>();
                argv.add(prepared.entryClass()+":"+plan.target());argv.addAll(plan.arguments());
                var execution=kernel.startInit(new LaunchRequest("host",argv,plan.cwd(),plan.environment(),List.of(plan.artifact())));
                execution.cleanupCompletion().get();
                var outcome=execution.process().await();
                var stopped=kernel.requestShutdown(java.time.Duration.ofSeconds(10)).get();
                if(!stopped.complete())throw new KernelException(ErrorCode.TIMEOUT,"JVK shutdown unconfirmed: "+stopped.unconfirmed());
                if(outcome.state()==ProcessState.EXITED)return outcome.exitCode();
                diagnostics.println("INIT_FAILED: "+outcome.state()+" "+outcome.error()+" "+outcome.message());
                if(execution.diagnosticCause()!=null)diagnostics.println("INIT_CAUSE: "+execution.diagnosticCause());
                return outcome.state()==ProcessState.CANCELLED?137:1;
                }
            }
        }
    }

    private static int terminalLimit() {
        try {
            int limit=Integer.parseInt(System.getProperty("espressh.vts.maxTerminals","64"));
            if(limit<=0)throw new NumberFormatException("must be positive");
            return limit;
        } catch(NumberFormatException invalid) {
            throw new KernelException(ErrorCode.CONFIG_ERROR,"Invalid espressh.vts.maxTerminals");
        }
    }

    public static void main(String[] args) {
        int status;
        try {
            Path artifact=Path.of(HostLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            var prepared=prepare(artifact,List.of(args),Path.of(System.getProperty("user.dir")),System.getenv());
            status=run(prepared,artifact,System.err);
        } catch(Exception failure) {
            String code=failure instanceof KernelException kernel?kernel.code().name():"START_FAILED";
            System.err.println("panic: "+code+": "+failure.getMessage());status=1;
        } catch(LinkageError failure) {System.err.println("panic: START_FAILED: "+failure);status=1;}
        System.exit(status);
    }
}
