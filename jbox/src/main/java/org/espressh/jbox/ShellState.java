package org.espressh.jbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-shell state; never modifies System properties or Host environment. */
final class ShellState {
    private Path cwd;
    private final Path home;
    private final Map<String,String> variables;
    private final java.util.Set<String> exported=new java.util.LinkedHashSet<>();
    private final Map<String,List<String>> aliases=new LinkedHashMap<>();
    private final List<Path> classpath;
    private String runtimeId;
    public ShellState(Path cwd,Path home,Map<String,String> environment,List<Path> classpath,String runtimeId) {
        this.cwd=cwd.toAbsolutePath().normalize();this.home=home.toAbsolutePath().normalize();
        this.variables=new LinkedHashMap<>(environment);this.exported.addAll(environment.keySet());this.classpath=List.copyOf(classpath);this.runtimeId=runtimeId;
    }
    public Path cwd(){return cwd;}
    public Path home(){return home;}
    public String runtimeId(){return runtimeId;}
    public List<Path> classpath(){return classpath;}
    public Map<String,String> environment(){
        var env=new LinkedHashMap<String,String>();
        for(String key:exported)if(variables.containsKey(key))env.put(key,variables.get(key));
        return Map.copyOf(env);
    }
    public Map<String,String> variables(){return Map.copyOf(variables);}
    public String displayVariables(boolean onlyExported) {
        var values=new java.util.TreeMap<>(onlyExported?environment():variables());
        var text=new StringBuilder();
        values.forEach((key,value)->text.append(onlyExported?"export ":"").append(key).append("='")
            .append(value.replace("'","'\\''")).append("'\n"));
        return text.toString();
    }
    public Map<String,List<String>> aliases(){return Map.copyOf(aliases);}
    public void selectRuntime(String validatedId){if(validatedId==null||validatedId.isBlank())throw new IllegalArgumentException();runtimeId=validatedId;}
    public void alias(String name,List<String> expansion){
        if(!validName(name,true)||expansion.isEmpty())throw new IllegalArgumentException("Invalid alias");
        aliases.put(name,List.copyOf(expansion));
    }
    /** Minimum startup commands; unsupported commands are errors, never silently discarded. */
    public int configure(String line) {
        List<String> w=ShellWords.parse(line);if(w.isEmpty())return 0;
        switch(w.get(0)) {
            case "alias" -> {
                require(w.size()==2,"alias NAME='managed invocation'");
                int eq=w.get(1).indexOf('=');require(eq>0,"alias requires NAME=value");
                alias(w.get(1).substring(0,eq),ShellWords.parse(w.get(1).substring(eq+1)));
            }
            case "set", "export" -> {
                boolean export=w.get(0).equals("export");
                if(w.size()==1 || (export && w.size()==2 && w.get(1).equals("-p")))return 0;
                // Validate the entire command before changing shell state.
                for(int i=1;i<w.size();i++) {
                    String item=w.get(i);int eq=item.indexOf('=');
                    require(export || eq>0,"set requires NAME=value");
                    String name=eq<0?item:item.substring(0,eq);
                    require(validName(name,false),"Invalid variable name: "+name);
                }
                for(int i=1;i<w.size();i++) {
                    String item=w.get(i);int eq=item.indexOf('=');String name=eq<0?item:item.substring(0,eq);
                    if(eq>=0)variables.put(name,item.substring(eq+1));
                    else variables.putIfAbsent(name,"");
                    if(export)exported.add(name);
                }
            }
            case "cd" -> {
                require(w.size()<=2,"cd [directory]");String dest=w.size()==1?"~":w.get(1);
                Path next=dest.equals("~")?home:cwd.resolve(dest).normalize();
                require(Files.isDirectory(next),"Not a directory: "+next);cwd=next;
            }
            default -> throw new ShellException(ShellException.COMMAND_NOT_AVAILABLE,"Unsupported startup command: "+w.get(0));
        }
        return 0;
    }
    private static boolean validName(String name,boolean alias) {
        if(name.isEmpty()||!nameStart(name.charAt(0)))return false;
        for(int i=1;i<name.length();i++) {
            char value=name.charAt(i);
            if(!nameStart(value)&&!(value>='0'&&value<='9')&&!(alias&&value=='-'))return false;
        }
        return true;
    }
    private static boolean nameStart(char value) {
        return value>='A'&&value<='Z'||value>='a'&&value<='z'||value=='_';
    }
    private static void require(boolean valid,String message){if(!valid)throw new ShellException(ShellException.CONFIG_ERROR,message);}
}
