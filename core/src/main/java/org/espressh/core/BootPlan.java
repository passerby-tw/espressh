package org.espressh.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Explicit inputs only; no PATH, registry, sibling-JAR or global classpath lookup. */
public record BootPlan(Path artifact, String launcher, String target, List<String> arguments,
                       Path cwd, Map<String,String> environment) {
    public BootPlan {
        artifact=artifact.toAbsolutePath().normalize(); cwd=cwd.toAbsolutePath().normalize();
        arguments=List.copyOf(arguments); environment=Map.copyOf(environment);
        if (launcher.isBlank() || target.isBlank()) throw new KernelException(ErrorCode.ENTRY_NOT_FOUND,"Missing init entry");
    }
    public static BootPlan split(List<String> argv, Path cwd, Map<String,String> environment) {
        if (argv.size()<3 || !argv.get(0).equals("--boot"))
            throw new KernelException(ErrorCode.ENTRY_NOT_FOUND,"panic: init requires --boot <artifact.jar> <launcher>:<target>");
        return resolve(cwd.resolve(argv.get(1)), argv.subList(2,argv.size()),cwd,environment);
    }
    public static BootPlan packaged(Path runningJar, List<String> argv, Path cwd, Map<String,String> environment) {
        return resolve(runningJar,argv.isEmpty()?List.of("jbox:init"):argv,cwd,environment);
    }
    private static BootPlan resolve(Path artifact, List<String> argv, Path cwd, Map<String,String> environment) {
        String entry=argv.get(0); int colon=entry.indexOf(':');
        if (colon<=0 || colon==entry.length()-1 || entry.indexOf(':',colon+1)>=0)
            throw new KernelException(ErrorCode.ENTRY_NOT_FOUND,"Invalid initial entry: "+entry);
        try {
            artifact=artifact.toRealPath();
            if (!Files.isRegularFile(artifact)) throw new IOException("Not an artifact file");
        } catch(IOException e) { throw new KernelException(ErrorCode.START_FAILED,"Cannot read boot artifact: "+artifact,e); }
        return new BootPlan(artifact,entry.substring(0,colon),entry.substring(colon+1),argv.subList(1,argv.size()),cwd,environment);
    }
}
