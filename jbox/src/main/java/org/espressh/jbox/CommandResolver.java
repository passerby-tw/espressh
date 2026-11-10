package org.espressh.jbox;

import java.io.File;
import org.espressh.api.LaunchRequestDto;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Parsing only. Never loads dispatcher classes in the caller or starts Host executables. */
final class CommandResolver {
    public record Command(String builtin, LaunchRequestDto launch, List<String> words, boolean background,
                          boolean explicitRuntime) {
        public Command { words=List.copyOf(words); }
        public Command(String builtin, LaunchRequestDto launch, List<String> words) { this(builtin,launch,words,false,false); }
        public Command(String builtin, LaunchRequestDto launch, List<String> words, boolean background) {
            this(builtin,launch,words,background,false);
        }
        String entry() {
            if(launch==null)return "";
            int index=0;
            while(index<launch.argv.length) {
                String option=launch.argv[index];
                if(option.startsWith(JAVA_HOME_OPTION)){index++;continue;}
                if((option.equals("-cp")||option.equals("-classpath"))&&index+1<launch.argv.length){index+=2;continue;}
                return option;
            }
            return "";
        }
        boolean usesAppletWorker() {
            String entry=entry();int colon=entry.indexOf(':');
            return !background&&!explicitRuntime&&colon>0
                &&launch.stdoutPath==null&&launch.stderrPath==null
                &&!Set.of("esh","init","shutdown").contains(entry.substring(colon+1));
        }
    }
    private static final String JAVA_HOME_OPTION="--java.JavaHome=";
    private static final Set<String> BUILTINS=Set.of("cd","set","export","alias","exit","kill");
    public Command resolve(String line,ShellState shell) {
        var invocation=ShellWords.invocation(line);
        var command=resolveWords(invocation.words(),shell);
        if(invocation.background() && command.launch()==null)throw unavailable("Builtin cannot run in background");
        var launch=command.launch();
        if(invocation.redirected()) {
            if(launch==null)throw unavailable("Builtin output redirection is not supported");
            launch=new LaunchRequestDto(launch.runtimeId,launch.argv,launch.cwd,launch.environmentKeys,launch.environmentValues,
                launch.classpath,redirectPath(invocation.stdout(),launch.cwd),
                invocation.stdout()!=null&&invocation.stdout().append(),redirectPath(invocation.stderr(),launch.cwd),
                invocation.stderr()!=null&&invocation.stderr().append());
        }
        var resolved=new Command(command.builtin(),launch,command.words(),invocation.background(),command.explicitRuntime());
        String entry=resolved.entry();int colon=entry.indexOf(':');
        boolean shellJar=(invocation.redirected()||resolved.background())&&packagedShell(launch,shell);
        if(invocation.redirected()&&(shellJar||colon>0&&Set.of("esh","init","shutdown").contains(entry.substring(colon+1))))
            throw unavailable("Terminal lifecycle targets do not support output redirection");
        if(resolved.background()&&(shellJar||colon>0&&entry.substring(colon+1).equals("esh")))
            throw unavailable("Nested esh requires terminal input; background nested esh is not supported");
        return resolved;
    }

    private static boolean packagedShell(LaunchRequestDto launch,ShellState shell) {
        if(launch==null)return false;
        int index=0;
        while(index<launch.argv.length) {
            String option=launch.argv[index];
            if(option.startsWith(JAVA_HOME_OPTION)){index++;continue;}
            if((option.equals("-cp")||option.equals("-classpath"))&&index+1<launch.argv.length){index+=2;continue;}
            break;
        }
        if(index+1>=launch.argv.length||!launch.argv[index].equals("-jar"))return false;
        try {
            Path jar=Path.of(launch.cwd).resolve(launch.argv[index+1]);
            boolean current=false;
            for(Path entry:shell.classpath()) {
                if(java.nio.file.Files.isSameFile(jar,entry)){current=true;break;}
            }
            if(!current)return false;
            try(var file=new java.util.jar.JarFile(jar.toFile())) {
                var manifest=file.getManifest();
                return manifest!=null
                    &&"org.espressh.runtime.Espressh".equals(manifest.getMainAttributes().getValue("Main-Class"))
                    &&file.getJarEntry("org/espressh/jbox/JBox.class")!=null;
            }
        } catch(java.io.IOException|java.nio.file.InvalidPathException unavailable) {
            // Entry failures are reported by the normal managed launch path.
            return false;
        }
    }
    private Command resolveWords(List<String> words,ShellState shell) {
        List<String> w=new ArrayList<>(words);
        if(w.isEmpty())return new Command("",null,w);
        List<String> expansion=shell.aliases().get(w.get(0));
        if(expansion!=null){w.remove(0);w.addAll(0,expansion);}
        // Recursive alias semantics are deferred; a second expansion is not guessed.
        if(shell.aliases().containsKey(w.get(0)) && expansion!=null)
            throw new ShellException(ShellException.COMMAND_NOT_AVAILABLE,"Recursive/chained alias is not supported");
        if(BUILTINS.contains(w.get(0)))return new Command(w.get(0),null,w);
        String runtime=shell.runtimeId();
        boolean explicitRuntime=false;
        if(w.get(0).equals("runat")) {
            if(w.size()==2&&w.get(1).equals("--list"))return new Command("runat --list",null,w);
            if(w.size()<3)throw unavailable("runat requires runtime and command");
            runtime=w.remove(1);w.remove(0);
            explicitRuntime=true;
            boolean javaForm=w.get(0).equals("java");
            if(javaForm)w.remove(0);
            if(w.isEmpty())throw unavailable("Missing Java entry");
            rejectJavaHomeWithRunat(w);
            // Preserve the existing provider-owned grammar when java is omitted.
            if(!javaForm) {
                expandLauncher(w,shell);
                return managed(runtime,w,shell,shell.classpath(),true,null);
            }
            w.add(0,"java");
        }
        if(Set.of("javac","javap","jar","javadoc").contains(w.get(0)))
            return managed(runtime,w,shell,shell.classpath());
        boolean javaForm=w.get(0).equals("java");
        if(javaForm)w.remove(0);
        if(w.isEmpty())throw unavailable("Missing Java entry");
        List<Path> cp=shell.classpath();String javaHome=null;boolean explicitClasspath=false;
        while(!w.isEmpty()) {
            String option=w.get(0);
            if(javaForm&&(option.startsWith(JAVA_HOME_OPTION)||option.equals("--java.JavaHome"))) {
                if(!option.startsWith(JAVA_HOME_OPTION)||option.length()==JAVA_HOME_OPTION.length())
                    throw config("JavaHome requires --java.JavaHome=<non-empty path>");
                if(javaHome!=null)throw config("Duplicate JavaHome option");
                javaHome=w.remove(0);
            } else if(option.equals("-cp")||option.equals("-classpath")) {
                if(explicitClasspath)throw unavailable("Duplicate classpath option");
                if(w.size()<3)throw unavailable("Missing classpath or entry");
                String value=w.remove(1);w.remove(0);cp=new ArrayList<>();explicitClasspath=true;
                for(String p:value.split(java.util.regex.Pattern.quote(File.pathSeparator),-1))
                    cp.add(shell.cwd().resolve(p.isEmpty()?".":p).normalize());
            } else break;
        }
        if(w.isEmpty())throw unavailable("Missing Java entry");
        if(javaForm&&w.get(0).equals("-version")) {
            if(w.size()!=1)throw config("-version does not accept an application entry or arguments");
            return managed(runtime,w,shell,List.of(),explicitRuntime,javaHome);
        }
        if(w.get(0).startsWith("-")&&!w.get(0).equals("-jar"))
            throw config("Unsupported managed Java option: "+w.get(0));
        if(w.get(0).endsWith(".jar"))w.add(0,"-jar");
        if(w.get(0).equals("-jar")) {
            if(w.size()<2)throw unavailable("Missing JAR path");
            w.set(1,shell.cwd().resolve(w.get(1)).normalize().toString());
            return managed(runtime,w,shell,List.of(),explicitRuntime,javaHome);
        }
        expandLauncher(w,shell);
        boolean classMain=javaForm&&w.get(0).matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.]*");
        if(!classMain&&!w.get(0).matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.]*:[A-Za-z_][A-Za-z0-9_-]*"))
            throw unavailable("No managed command: "+w.get(0));
        return managed(runtime,w,shell,cp,explicitRuntime,javaHome);
    }
    private static void rejectJavaHomeWithRunat(List<String>w) {
        int index=0;
        while(index<w.size()) {
            String option=w.get(index);
            if(option.startsWith(JAVA_HOME_OPTION)||option.equals("--java.JavaHome"))
                throw config("runat and JavaHome cannot select a runtime in the same invocation");
            if((option.equals("-cp")||option.equals("-classpath"))&&index+1<w.size()){index+=2;continue;}
            return;
        }
    }
    private static void expandLauncher(List<String>w,ShellState shell) {
        int colon=w.get(0).indexOf(':');if(colon<1)return;
        String name=w.get(0).substring(0,colon);List<String>a=shell.aliases().get(name);
        if(a==null)return;
        if(a.size()!=1||a.get(0).contains(":"))throw unavailable("Launcher alias must name one class");
        w.set(0,a.get(0)+w.get(0).substring(colon));
    }
    private static Command managed(String runtime,List<String>w,ShellState s,List<Path>cp) {
        return managed(runtime,w,s,cp,false,null);
    }
    private static Command managed(String runtime,List<String>w,ShellState s,List<Path>cp,boolean explicitRuntime,String javaHome) {
        if(javaHome!=null)w.add(0,javaHome);
        return new Command(null,LaunchRequests.create(runtime,w,s.cwd(),s.environment(),cp),w,false,explicitRuntime||javaHome!=null);
    }
    private static String redirectPath(ShellWords.Redirection redirection,String cwd) {
        if(redirection==null)return null;
        try { return Path.of(cwd).resolve(redirection.path()).normalize().toString(); }
        catch(java.nio.file.InvalidPathException invalid) { throw config("Invalid redirection path: "+invalid.getReason()); }
    }
    private static ShellException config(String message){return new ShellException(ShellException.CONFIG_ERROR,ShellException.CONFIG_ERROR+": "+message);}
    private static ShellException unavailable(String message){return new ShellException(ShellException.COMMAND_NOT_AVAILABLE,message);}
}
