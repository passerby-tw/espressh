package org.espressh.jbox;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Host filesystem metadata read in Guest; relative operands use command CWD. */
final class Listing {
    private record Entry(Path path,BasicFileAttributes basic) {}
    static int run(Path cwd,String[] args) {
        boolean detailed=false,time=false,options=true;
        var operands=new ArrayList<String>();
        for(String arg:args) {
            CommandScope.check();
            if(options && arg.equals("--")){options=false;continue;}
            if(options && arg.startsWith("-") && !arg.equals("-")) {
                for(char option:arg.substring(1).toCharArray()) {
                    if(option=='l')detailed=true;
                    else if(option=='t')time=true;
                    else {System.err.println("ls: unsupported option: -"+option);return 2;}
                }
            } else operands.add(arg);
        }
        if(operands.isEmpty())operands.add(".");
        int status=0;
        for(String operand:operands) {
            CommandScope.check();
            Path path=cwd.resolve(operand).normalize();
            try {
                var entries=new ArrayList<Entry>();
                if(Files.isDirectory(path)) {
                    if(operands.size()>1)System.out.println(operand+":");
                    try(var children=Files.list(path)) {
                        for(Path child:children.toList()) {
                            CommandScope.check();
                            if(child.getFileName().toString().startsWith("."))continue;
                            try {entries.add(read(child));}
                            catch(IOException failure){CommandScope.check();diagnose(child.toString(),failure);status=1;}
                        }
                    }
                } else entries.add(read(path));
                Comparator<Entry> order=Comparator.comparing(e->e.path().getFileName().toString());
                if(time)order=Comparator.<Entry,FileTime>comparing(e->e.basic().lastModifiedTime()).reversed().thenComparing(order);
                entries.sort(order);
                for(Entry entry:entries) {
                    CommandScope.check();
                    try {System.out.println(detailed?longLine(entry):entry.path().getFileName().toString());}
                    catch(IOException failure){CommandScope.check();diagnose(entry.path().toString(),failure);status=1;}
                }
            } catch(IOException failure){CommandScope.check();diagnose(operand,failure);status=1;}
        }
        CommandScope.check();
        return status;
    }
    private static Entry read(Path path)throws IOException {
        return new Entry(path,Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS));
    }
    private static void diagnose(String path,IOException failure) {
        System.err.println("ls: "+(failure instanceof NoSuchFileException?"Not found: ":"Cannot access: ")+path
            +(failure instanceof NoSuchFileException?"":" ("+failure.getMessage()+")"));
    }
    private static String longLine(Entry entry)throws IOException {
        Path path=entry.path();BasicFileAttributes basic=entry.basic();
        char type=basic.isDirectory()?'d':basic.isSymbolicLink()?'l':basic.isRegularFile()?'-':'?';
        String permissions="?????????",owner="?",group="?",links="?";
        var views=path.getFileSystem().supportedFileAttributeViews();
        var posix=views.contains("posix")
            ?Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS):null;
        if(posix!=null) {
            var attributes=posix.readAttributes();permissions=PosixFilePermissions.toString(attributes.permissions());
            owner=attributes.owner().getName();group=attributes.group().getName();
        } else if(views.contains("owner")) {
            var owners=Files.getFileAttributeView(path,FileOwnerAttributeView.class,LinkOption.NOFOLLOW_LINKS);
            if(owners!=null)owner=owners.getOwner().getName();
        }
        if(views.contains("unix"))
            links=String.valueOf(Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS));
        String modified=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
            .format(basic.lastModifiedTime().toInstant());
        String name=path.getFileName().toString();
        if(basic.isSymbolicLink())name+=" -> "+Files.readSymbolicLink(path);
        return type+permissions+" "+links+" "+owner+" "+group+" "+basic.size()+" "+modified+" "+name;
    }
}
