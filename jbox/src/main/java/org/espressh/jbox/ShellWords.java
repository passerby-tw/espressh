package org.espressh.jbox;

import java.util.ArrayList;
import java.util.List;

/** Small explicit grammar: words, quotes, escapes and output redirection. No substitution. */
final class ShellWords {
    private ShellWords() {}
    public record Redirection(String path, boolean append) {}
    public record Invocation(List<String> words, boolean background, Redirection stdout, Redirection stderr) {
        boolean redirected() { return stdout!=null||stderr!=null; }
    }
    public static List<String> parse(String line) { return parseInvocation(line, false).words(); }
    public static Invocation invocation(String line) { return parseInvocation(line, true); }
    private static Invocation parseInvocation(String line, boolean allowInvocation) {
        var parser=new Parser();char quote=0;boolean escaped=false;
        for(int i=0;i<line.length();i++) {
            char c=line.charAt(i);
            if(escaped) { parser.word.append(c);escaped=false;continue; }
            if(c=='\\' && quote!='\'') { escaped=true;parser.started=true;parser.plain=false;continue; }
            if(quote!=0) {
                if(quote=='"'&&(c=='$'||c=='`'))throw new ShellException(ShellException.COMMAND_NOT_AVAILABLE,"Substitution is not supported");
                if(c==quote)quote=0;else parser.word.append(c);continue;
            }
            if(c=='\''||c=='"') { quote=c;parser.started=true;parser.plain=false;continue; }
            if(Character.isWhitespace(c)) { parser.finishWord();continue; }
            if(c=='#'&&!parser.started)break;
            if(c=='>'&&allowInvocation) {
                boolean append=i+1<line.length()&&line.charAt(i+1)=='>';
                parser.beginRedirection(append);
                if(append)i++;
                if(i+1<line.length()&&line.charAt(i+1)=='&')throw unavailable("File descriptor duplication is not supported");
                continue;
            }
            if(c=='&' && allowInvocation) {
                parser.finishWord();parser.requireFilename();
                String tail=line.substring(i+1).stripLeading();
                if(parser.words.isEmpty() || (!tail.isEmpty() && !tail.startsWith("#")))
                    throw unavailable("Only trailing & is supported");
                return parser.invocation(true);
            }
            if("|;&<>`$".indexOf(c)>=0)throw unavailable("Unsupported shell syntax: "+c);
            parser.word.append(c);parser.started=true;
        }
        if(quote!=0||escaped)throw new ShellException(ShellException.CONFIG_ERROR,"Unterminated quote or escape");
        parser.finishWord();parser.requireFilename();return parser.invocation(false);
    }
    private static final class Parser {
        final List<String> words=new ArrayList<>();
        final StringBuilder word=new StringBuilder();
        boolean started, plain=true, append;
        int descriptor;
        Redirection stdout, stderr;

        void finishWord() {
            if(!started)return;
            String value=word.toString();
            if(descriptor==0)words.add(value);
            else {
                if(value.isEmpty())throw unavailable("Redirection filename must not be empty");
                var redirection=new Redirection(value,append);
                if(descriptor==1)stdout=redirection;else stderr=redirection;
                descriptor=0;
            }
            clearWord();
        }
        void beginRedirection(boolean append) {
            int selected=1;
            if(descriptor==0&&started&&plain&&digits(word)) {
                if(word.toString().equals("1"))selected=1;
                else if(word.toString().equals("2"))selected=2;
                else throw unavailable("Only stdout (1) and stderr (2) redirection are supported");
                clearWord();
            } else finishWord();
            requireFilename();
            if(selected==1&&stdout!=null||selected==2&&stderr!=null)
                throw unavailable("Duplicate redirection for file descriptor "+selected);
            descriptor=selected;this.append=append;
        }
        void requireFilename() {
            if(descriptor!=0)throw unavailable("Missing redirection filename");
        }
        Invocation invocation(boolean background) {
            if(words.isEmpty()&&(stdout!=null||stderr!=null))throw unavailable("Redirection requires a command");
            return new Invocation(List.copyOf(words),background,stdout,stderr);
        }
        private void clearWord() { word.setLength(0);started=false;plain=true; }
        private static boolean digits(CharSequence value) {
            if(value.isEmpty())return false;
            for(int i=0;i<value.length();i++)if(value.charAt(i)<'0'||value.charAt(i)>'9')return false;
            return true;
        }
    }
    private static ShellException unavailable(String message) { return new ShellException(ShellException.COMMAND_NOT_AVAILABLE,message); }
}
