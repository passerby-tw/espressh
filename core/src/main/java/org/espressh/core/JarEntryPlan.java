package org.espressh.core;

import java.io.IOException;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.jar.JarFile;

public record JarEntryPlan(String mainClass,List<Path> classpath,List<String> addOpens) {
    public JarEntryPlan {
        classpath=List.copyOf(classpath);
        addOpens=List.copyOf(addOpens);
    }

    private static List<String> manifestOpens(String value) {
        if(value==null)return List.of();
        var opens=new LinkedHashSet<String>();
        // Match LauncherHelper's literal-space parsing, including trailing-slash handling.
        for(String item:value.split(" +")) {
            String[] pair=item.trim().split("/");
            if(pair.length!=2||pair[0].isEmpty()||pair[1].isEmpty())continue;
            String target=pair[0]+"/"+pair[1];
            // These cannot name a module/package. Do not let them inject an Espresso option
            // separator or an explicit target. Unknown ordinary pairs are left to the Guest JDK.
            if(target.indexOf(File.pathSeparatorChar)>=0||target.indexOf('=')>=0
                    ||target.chars().anyMatch(c->c<=32))continue;
            opens.add(target+"=ALL-UNNAMED");
        }
        return List.copyOf(opens);
    }
    public static JarEntryPlan read(Path jarPath) {
        try {
            Path path=jarPath.toRealPath();
            try(JarFile jar=new JarFile(path.toFile())) {
                var manifest=jar.getManifest();
                String main=manifest==null?null:manifest.getMainAttributes().getValue("Main-Class");
                if(main==null||main.isBlank())throw new KernelException(ErrorCode.ENTRY_NOT_FOUND,"Missing Main-Class: "+path);
                List<Path> cp=new ArrayList<>();cp.add(path);
                String dependencies=manifest.getMainAttributes().getValue("Class-Path");
                if(dependencies!=null&&!dependencies.isBlank())for(String item:dependencies.trim().split("\\s+")) {
                    URI resolved=path.toUri().resolve(item);
                    if(!resolved.getScheme().equals("file"))throw new KernelException(ErrorCode.COMMAND_NOT_AVAILABLE,"Remote manifest Class-Path is not implemented: "+item);
                    cp.add(Path.of(resolved).normalize());
                }
                return new JarEntryPlan(main.trim(),cp,
                        manifestOpens(manifest.getMainAttributes().getValue("Add-Opens")));
            }
        }catch(IOException|IllegalArgumentException e){throw new KernelException(ErrorCode.ENTRY_NOT_FOUND,"Cannot resolve JAR: "+jarPath,e);}
    }
}
