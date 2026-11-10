package org.espressh.jbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Local shell initialization; executors run in that same shell, never a Host shell. */
final class StartupFiles {
    @FunctionalInterface public interface CommandExecutor { int execute(String command) throws Exception; }
    @FunctionalInterface interface ResourceReader { Optional<String> read(String logicalName) throws IOException; }
    private final ResourceReader resources;
    private final Path home;
    public StartupFiles(ResourceReader resources, Path home) { this.resources=resources; this.home=home; }
    public void loadShell(boolean terminalRoot, CommandExecutor executor) {
        if (terminalRoot) { system("/etc/profile",executor); user("esh_profile",executor); }
        system("/etc/eshrc",executor); user(".eshrc",executor);
    }
    static Optional<String> resource(Class<?> owner, String logicalName) throws IOException {
        try (var input=owner.getClassLoader().getResourceAsStream("META-INF"+logicalName)) {
            return input==null?Optional.empty():Optional.of(new String(input.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    private void system(String name, CommandExecutor executor) {
        String source="META-INF"+name;
        try {
            Optional<String> text=resources.read(name);
            if(text.isPresent())execute(source,text.get(),executor);
        }
        catch(IOException e) { throw failure(source,0,"Cannot read configuration",e); }
    }
    private void user(String name, CommandExecutor executor) {
        Path file=home.resolve(name);
        String text;
        try { text=Files.readString(file); }
        catch(NoSuchFileException absent) { return; }
        catch(IOException e) { throw failure(file.toString(),0,"Cannot read configuration",e); }
        execute(file.toString(),text,executor);
    }
    public static void execute(String source, String text, CommandExecutor executor) {
        int start=0,lineNumber=1;
        for(int offset=0;offset<=text.length();offset++) {
            boolean end=offset==text.length();
            char value=end?0:text.charAt(offset);
            if(!end&&!lineBreak(value))continue;
            executeLine(source,text.substring(start,offset).strip(),lineNumber,executor);
            if(end)return;
            // Treat CRLF as one separator, including when the following line is empty.
            if(value=='\r'&&offset+1<text.length()&&text.charAt(offset+1)=='\n')offset++;
            start=offset+1;lineNumber++;
        }
    }
    private static boolean lineBreak(char value) {
        return value=='\n'||value==0x0B||value=='\f'||value=='\r'
            ||value=='\u0085'||value=='\u2028'||value=='\u2029';
    }
    private static void executeLine(String source,String line,int lineNumber,CommandExecutor executor) {
        if(line.isEmpty()||line.startsWith("#"))return;
        try {
            int code=executor.execute(line);
            if(code!=0)throw failure(source,lineNumber,"Command exited with status "+code,null);
        } catch(ShellException e) {
            throw failure(source,lineNumber,e.getMessage(),e);
        } catch(Exception e) { throw failure(source,lineNumber,e.getMessage(),e); }
    }
    /** Baseline supports no optional init actions yet. Nonempty actions fail explicitly. */
    public void loadInit() {
        String source="META-INF/etc/inittab";
        try {
            Optional<String> text=resources.read("/etc/inittab");
            if(text.isPresent())validateInit(source,text.get());
        } catch(IOException e) { throw failure(source,0,"Cannot read configuration",e); }
    }
    static void validateInit(String source,String text) {
        execute(source,text,line->{
            String[] fields=line.split(":",4);
            if(fields.length!=4) throw new IllegalArgumentException("inittab requires id:runlevels:action:process");
            throw new ShellException(ShellException.COMMAND_NOT_AVAILABLE,"Unsupported inittab action: "+fields[2]);
        });
    }
    private static ShellException failure(String source,int line,String reason,Throwable cause) {
        return new ShellException(ShellException.CONFIG_ERROR,source+(line>0?":"+line:"")+": "+reason,cause);
    }
}
