package org.espressh.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Finite Host-side installation validation; does not execute any installation binary. */
record JavaHomeMetadata(Path home,String version,String vendor) {
    private static final Set<String> REQUIRED=Set.of("JAVA_VERSION","IMPLEMENTOR","OS_NAME","OS_ARCH");
    private static final Set<Integer> FEATURES=Set.of(17,21,25);

    static JavaHomeMetadata read(Path path){
        return read(path,System.getProperty("os.name",""),System.getProperty("os.arch",""));
    }

    static JavaHomeMetadata read(Path path,String hostOs,String hostArch){
        if(path==null||path.toString().isBlank()||!path.isAbsolute())
            throw new KernelException(ErrorCode.CONFIG_ERROR,"CONFIG_ERROR: JavaHome must be a non-empty absolute path");
        final Path home;
        try {home=path.toRealPath();}
        catch(NoSuchFileException missing){throw failure(ErrorCode.RUNTIME_NOT_FOUND,"JavaHome does not exist: "+path,missing);}
        catch(IOException failure){throw failure(ErrorCode.START_FAILED,"Cannot resolve JavaHome: "+path,failure);}
        if(!Files.isDirectory(home))throw invalid(home,"JavaHome is not a directory");
        requireFile(home,"release");
        requireFile(home,"lib/modules");
        Map<String,String> fields=new HashMap<>();
        final List<String> lines;
        try {lines=Files.readAllLines(home.resolve("release"));}
        catch(IOException failure){throw failure(ErrorCode.START_FAILED,"Cannot read JavaHome release metadata: "+home,failure);}
        for(String line:lines) {
            int separator=line.indexOf('=');
            if(separator<0)continue;
            String key=line.substring(0,separator).strip();
            if(!REQUIRED.contains(key))continue; // E.g. vendor-specific unquoted COMMIT_INFO is not one of our fields.
            if(fields.putIfAbsent(key,quoted(home,key,line.substring(separator+1).strip()))!=null)
                throw invalid(home,"Duplicate release field: "+key);
        }
        for(String key:REQUIRED)if(!fields.containsKey(key))throw invalid(home,"Missing release field: "+key);
        String version=fields.get("JAVA_VERSION");
        int feature;
        try {feature=Runtime.Version.parse(version).feature();}
        catch(IllegalArgumentException malformed){throw failure(ErrorCode.START_FAILED,"Unsupported or malformed JavaHome JAVA_VERSION: "+version+" at "+home,malformed);}
        if(!FEATURES.contains(feature))throw invalid(home,"Guest JDK feature must be 17, 21 or 25 for this bootstrap/provider; found "+feature);
        String os=normalizeOs(fields.get("OS_NAME")),architecture=normalizeArch(fields.get("OS_ARCH"));
        if(os==null||!os.equals(normalizeOs(hostOs)))throw invalid(home,"JavaHome OS_NAME is incompatible with the Host");
        if(architecture==null||!architecture.equals(normalizeArch(hostArch)))throw invalid(home,"JavaHome OS_ARCH is incompatible with the Host");
        String suffix=os.equals("windows")?".exe":"";
        requireFile(home,"bin/java"+suffix);requireFile(home,"bin/javac"+suffix);
        return new JavaHomeMetadata(home,version,fields.get("IMPLEMENTOR"));
    }

    private static String quoted(Path home,String key,String text){
        if(text.length()<2||text.charAt(0)!='"'||text.charAt(text.length()-1)!='"')
            throw invalid(home,"Release field must be double quoted: "+key);
        StringBuilder value=new StringBuilder();
        for(int i=1;i<text.length()-1;i++) {
            char c=text.charAt(i);
            if(c=='\\') {
                if(++i>=text.length()-1)throw invalid(home,"Invalid release escape: "+key);
                c=text.charAt(i);
                if(c!='\\'&&c!='"')throw invalid(home,"Invalid release escape: "+key);
            } else if(c=='"')throw invalid(home,"Unexpected quote in release field: "+key);
            if(Character.isISOControl(c))throw invalid(home,"Control character in release field: "+key);
            value.append(c);
        }
        if(value.toString().isBlank())throw invalid(home,"Empty release field: "+key);
        return value.toString();
    }

    private static void requireFile(Path home,String relative){
        if(!Files.isRegularFile(home.resolve(relative)))throw invalid(home,"Incomplete JavaHome: missing "+relative);
    }

    private static String normalizeOs(String value){
        String normalized=value.strip().toLowerCase(Locale.ROOT);
        if(normalized.equals("windows")||normalized.startsWith("windows "))return "windows";
        return switch(normalized) {
            case "linux" -> "linux";
            case "darwin","mac os x","macos","macosx" -> "mac";
            default -> null;
        };
    }

    private static String normalizeArch(String value){
        return switch(value.strip().toLowerCase(Locale.ROOT)) {
            case "amd64","x86_64" -> "x86_64";
            case "aarch64","arm64" -> "aarch64";
            default -> null;
        };
    }

    private static KernelException invalid(Path home,String message){
        return new KernelException(ErrorCode.START_FAILED,"START_FAILED: "+message+": "+home);
    }

    private static KernelException failure(ErrorCode code,String message,Throwable detail){
        // Keep the stable classification visible through the shell's existing root-cause
        // presentation, while retaining the original Host detail for diagnostics.
        KernelException failure=new KernelException(code,code+": "+message);
        failure.addSuppressed(detail);return failure;
    }
}
