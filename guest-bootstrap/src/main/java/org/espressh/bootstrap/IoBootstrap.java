package org.espressh.bootstrap;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/** TruffleIO carries standard-stream bytes; the Host bridge supplies explicit flush only. */
public final class IoBootstrap {
    public static void install() throws Exception {
        Class<?> polyglot=Class.forName("com.oracle.truffle.espresso.polyglot.Polyglot");
        Class<?> interop=Class.forName("com.oracle.truffle.espresso.polyglot.Interop");
        Method importObject=polyglot.getMethod("importObject",String.class);
        Method invoke=interop.getMethod("invokeMember",Object.class,String.class,Object[].class);
        Object out=importObject.invoke(null,"process_stdout");
        Object err=importObject.invoke(null,"process_stderr");
        // Keep shell reads unbuffered and use generic InputStream convenience
        // methods instead of FileInputStream's regular-file native fast paths.
        // Generic available()==0 and close() preserve the old borrowed-stream semantics.
        var input=new FileInputStream(FileDescriptor.in);
        var guestIn=new InputStream(){
            @Override public int read() throws IOException { return input.read(); }
            @Override public int read(byte[] bytes,int offset,int length) throws IOException {
                return input.read(bytes,offset,length);
            }
            // InputStream.close() leaves the borrowed descriptor open.
        };
        OutputStream guestOut=output(FileDescriptor.out,invoke,out);
        OutputStream guestErr=output(FileDescriptor.err,invoke,err);
        System.setIn(guestIn);
        System.setOut(new PrintStream(guestOut,true,StandardCharsets.UTF_8));
        System.setErr(new PrintStream(guestErr,true,StandardCharsets.UTF_8));
    }

    private static OutputStream output(FileDescriptor descriptor,Method invoke,Object endpoint) {
        // EspressoLibs implements FileOutputStream.writeBytes. Use that entry for
        // single-byte writes too; its default flush does not reach Host buffers.
        return new FileOutputStream(descriptor){
            @Override public void write(int value) throws IOException {
                super.write(new byte[]{(byte)value},0,1);
            }
            @Override public void flush() throws IOException {
                try { invoke.invoke(null,endpoint,"flush",new Object[0]); }
                catch (ReflectiveOperationException e) { throw new IOException(e); }
            }
            @Override public void close() { }
        };
    }
}
