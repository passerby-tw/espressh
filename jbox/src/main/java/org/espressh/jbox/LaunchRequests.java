package org.espressh.jbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.espressh.api.LaunchRequestDto;

/** Copies shell state into the public, value-only launch projection. */
final class LaunchRequests {
    private LaunchRequests() {}
    static LaunchRequestDto create(String runtimeId,List<String> argv,Path cwd,
                                   Map<String,String> environment,List<Path> classpath) {
        Objects.requireNonNull(runtimeId);Objects.requireNonNull(cwd);
        if(runtimeId.isBlank())throw new IllegalArgumentException("Empty runtime");
        if(argv.isEmpty())throw new IllegalArgumentException("Empty command");
        if(!cwd.isAbsolute())throw new IllegalArgumentException("CWD must be absolute");
        for(Path path:classpath)if(!path.isAbsolute())throw new IllegalArgumentException("Classpath must be resolved");
        String[] keys=environment.keySet().toArray(String[]::new);
        String[] values=new String[keys.length];
        for(int i=0;i<keys.length;i++)values[i]=environment.get(keys[i]);
        String[] paths=new String[classpath.size()];int index=0;
        for(Path path:classpath)paths[index++]=path.toString();
        return new LaunchRequestDto(runtimeId,argv.toArray(String[]::new),cwd.normalize().toString(),keys,values,
            paths);
    }
}
