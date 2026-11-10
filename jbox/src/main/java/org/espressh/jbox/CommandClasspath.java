package org.espressh.jbox;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Parses the existing Java classpath value without regex or stream initialization. */
final class CommandClasspath {
    private CommandClasspath() {}
    static List<Path> parse(String value,boolean distinct) {
        // String.split(regex) retains the empty input when no separator matched.
        if(value.isEmpty())return List.of(Path.of("").toAbsolutePath());
        char separator=File.pathSeparatorChar;
        int end=value.length();
        while(end>0&&value.charAt(end-1)==separator)end--;
        if(end==0)return List.of();
        List<Path> paths=new ArrayList<>();
        Set<Path> seen=distinct?new HashSet<>():null;
        int start=0;
        while(start<end) {
            int next=value.indexOf(separator,start);
            if(next<0||next>=end)next=end;
            Path path=Path.of(value.substring(start,next)).toAbsolutePath();
            if(!distinct||seen.add(path))paths.add(path);
            start=next+1;
        }
        return List.copyOf(paths);
    }
}
