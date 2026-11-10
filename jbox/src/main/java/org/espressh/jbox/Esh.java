package org.espressh.jbox;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.espressh.api.JvkSystem;

/** Guest shell: parsing and shell-local state stay here; execution goes through sys. */
public final class Esh {
    private Esh() {}
    public static void main(String[] args) throws Exception {
        var cp=CommandClasspath.parse(System.getProperty("java.class.path"),true);
        Path cwd=Path.of(System.getProperty("user.dir"));
        var state=new ShellState(cwd,Path.of(System.getenv().getOrDefault("HOME",System.getProperty("user.home"))),System.getenv(),cp,System.getProperty("espressh.runtime"));
        boolean nested=!rootTerminal();
        if(!nested){systemConfig("profile",state);userConfig("esh_profile",state);}
        systemConfig("eshrc",state);userConfig(".eshrc",state);
        var resolver=new CommandResolver();
        var input=new ShellInput(System.in);
        prompt: while(true) {
            System.out.print("esh> ");System.out.flush();
            ByteArrayOutputStream line=new ByteArrayOutputStream();int value;
            while((value=input.read())!=-1&&value!='\n'&&value!='\r') {
                if(value==3){line.reset();System.out.println();System.out.flush();continue prompt;}
                ShellInput.append(line,value);
            }
            if(value==-1)return; // Transport EOF does not define a keyboard exit binding.
            String text=line.toString(StandardCharsets.UTF_8).trim();if(text.isEmpty())continue;
            try {
                var command=resolver.resolve(text,state);
                if(command.launch()==null) {
                    if(command.builtin().isEmpty())continue;
                    if(command.builtin().equals("exit")) {
                        if(!rootTerminal() || JvkSystem.current().tryCloseTerminal())return;
                        if(confirmShutdown(input)){JvkSystem.current().requestShutdown();return;}
                    } else if(command.builtin().equals("kill")) {
                        var words=command.words();
                        long pid;
                        try {
                            if(words.size()!=2 || !words.get(1).matches("[0-9]+"))throw new NumberFormatException();
                            pid=Long.parseLong(words.get(1));
                            if(pid<=0)throw new NumberFormatException();
                        } catch(NumberFormatException invalid) {
                            throw new ShellException(ShellException.CONFIG_ERROR,"CONFIG_ERROR: Usage: kill <positive PID>; signals such as -9 are not supported");
                        }
                        boolean accepted=JvkSystem.current().kill(pid);
                        System.out.println("kill: "+pid+(accepted?" stop request accepted":" process already ended"));
                    } else if(List.of("cd","set","export","alias").contains(command.builtin())) {
                        state.configure(text);
                        if((command.builtin().equals("set") && command.words().size()==1)
                           || (command.builtin().equals("export") && (command.words().size()==1
                               || command.words().size()==2 && command.words().get(1).equals("-p"))))
                            System.out.print(state.displayVariables(command.builtin().equals("export")));
                    }
                    else if(command.builtin().equals("runat --list")) RuntimeView.display(JvkSystem.current().runtimeList(),state.runtimeId());
                    else throw new ShellException(ShellException.COMMAND_NOT_AVAILABLE,"Command not connected: "+text);
                } else {
                    var request=command.launch();
                    if(command.background())JvkSystem.current().spawn(request);
                    else if(command.usesAppletWorker())JvkSystem.current().runApplet(request);
                    else {
                        long pid=JvkSystem.current().spawn(request);
                        var result=JvkSystem.current().waitOutcome(pid);
                        if(result.state().equals("FAILED")) {
                            String error=result.error();
                            String message=result.message();
                            System.err.println("COMMAND_FAILED: "+error+(message.isBlank()?"":": "+message));
                        }
                    }
                }
            } catch(Exception failure) {
                Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();
                if(String.valueOf(cause.getMessage()).contains("SOURCE_CLOSED: caller stopped"))return;
                System.err.println("COMMAND_FAILED: "+cause.getMessage());System.err.flush();
            }
        }
    }
    private static void systemConfig(String name,ShellState state) throws IOException {
        String logicalName="/etc/"+name;
        String source="META-INF"+logicalName;
        try(InputStream resource=Esh.class.getClassLoader().getResourceAsStream(source)) {
            if(resource!=null)StartupFiles.execute(source,
                new String(resource.readAllBytes(),StandardCharsets.UTF_8),state::configure);
        } catch(IOException failure) {
            throw new ShellException(ShellException.CONFIG_ERROR,source+": Cannot read configuration",failure);
        }
    }
    private static void userConfig(String name,ShellState state) throws IOException {
        Path path=state.home().resolve(name);String text;
        try { text=Files.readString(path); }
        catch(NoSuchFileException missing) { return; }
        catch(IOException failure) {
            throw new ShellException(ShellException.CONFIG_ERROR,path+": Cannot read configuration",failure);
        }
        StartupFiles.execute(path.toString(),text,state::configure);
    }
    private static boolean rootTerminal() {
        return JvkSystem.current().terminalState().root();
    }
    private static boolean confirmShutdown(ShellInput input)throws IOException {
        while(true){
            System.out.print("Shut down Espressh and stop all running programs? [Y/n] ");System.out.flush();
            ByteArrayOutputStream reply=new ByteArrayOutputStream();int value;
            while((value=input.read())!=-1&&value!='\n'&&value!='\r'){
                if(value==3){System.out.println();return false;}
                ShellInput.append(reply,value);
            }
            if(value==-1)throw new EOFException("Frontend transport lost during exit confirmation");
            String answer=reply.toString(StandardCharsets.UTF_8).trim();
            if(answer.isEmpty()||answer.equalsIgnoreCase("y"))return true;
            if(answer.equalsIgnoreCase("n"))return false;
        }
    }
}
