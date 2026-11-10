package org.espressh.core;

import java.io.*;
import java.util.*;

/** VTS-owned byte endpoints. No Context, execution object or guest callback. */
final class TerminalChannels implements AutoCloseable {
    private final OutputStream out,err;
    private final int capacity;
    private final Map<Long,BytePipe> inputs=new HashMap<>();
    private boolean closed,eof;
    private BytePipe frontendOutput;
    private boolean echoAfterCr;
    private int echoAsciiTail;
    private long echoPid;
    private final java.util.concurrent.atomic.AtomicLong echoReset = new java.util.concurrent.atomic.AtomicLong();
    private long echoSeenReset;
    TerminalChannels(OutputStream out,OutputStream err,int capacity) {
        this.out=Objects.requireNonNull(out);this.err=Objects.requireNonNull(err);
        if(capacity<=0)throw new IllegalArgumentException("Invalid input capacity");this.capacity=capacity;
    }
    TerminalChannels(int capacity) {
        this(new BytePipe(capacity), capacity);
    }
    private TerminalChannels(BytePipe output, int capacity) {
        this(output.output(), output.output(), capacity);
        frontendOutput=output;
    }
    InputStream frontendOutput() {
        if(frontendOutput==null)throw new KernelException(ErrorCode.INVALID_STATE,"Frontend uses borrowed Host output");
        return frontendOutput.input();
    }
    private BytePipe pipe(long pid) {
        if(closed)throw new KernelException(ErrorCode.RESOURCE_CLOSED,"Terminal channels closed");
        if(pid<=0)throw new IllegalArgumentException("Invalid attachment");
        return inputs.computeIfAbsent(pid,k->{var pipe=new BytePipe(capacity);if(eof)try{pipe.output().close();}catch(IOException impossible){throw new AssertionError(impossible);}return pipe;});
    }
    synchronized ProcessIo endpoint(long pid) {
        return ProcessIo.borrowed(pipe(pid).input(),output(out),output(err));
    }
    synchronized OutputStream input(long pid) {return pipe(pid).output();}
    /** Display copy only; foreground input remains byte-for-byte unchanged. */
    void echo(long pid, byte[] bytes) throws IOException {
        synchronized(out) {
            long reset = echoReset.get();
            if (echoPid != pid || echoSeenReset != reset) {
                echoAsciiTail = 0;
                echoAfterCr = false;
                echoPid = pid;
                echoSeenReset = reset;
            }
            ByteArrayOutputStream display = new ByteArrayOutputStream();
            for (byte b : bytes) {
                if (b == 8 || b == 127) {
                    if (echoAsciiTail > 0) {
                        display.write(8); display.write(' '); display.write(8);
                        echoAsciiTail--;
                    }
                } else if (b == '\r') { display.write('\r'); display.write('\n'); echoAsciiTail = 0; }
                else if (b == '\n') {
                    if (!echoAfterCr) { display.write('\r'); display.write('\n'); }
                    echoAsciiTail = 0;
                } else {
                    display.write(b);
                    // No Unicode cell-width guessing or canonical input buffering.
                    if (b >= 32 && b < 127) {
                        if (echoAsciiTail < Integer.MAX_VALUE) echoAsciiTail++;
                    } else echoAsciiTail = 0;
                }
                echoAfterCr = b == '\r';
            }
            output(out).write(display.toByteArray());
            out.flush();
        }
    }
    private OutputStream output(OutputStream borrowed) {
        return new OutputStream() {
            private void check() throws IOException { synchronized(TerminalChannels.this){if(closed)throw new IOException("Terminal closed");} }
            @Override public void write(int value) throws IOException {check();synchronized(borrowed){borrowed.write(value);} }
            @Override public void write(byte[] bytes,int off,int len) throws IOException {check();synchronized(borrowed){borrowed.write(bytes,off,len);} }
            @Override public void flush() throws IOException { synchronized(TerminalChannels.this){if(closed)return;} synchronized(borrowed){borrowed.flush();} }
        };
    }
    synchronized void resetForInterrupt(long pid) throws IOException {
        echoReset.incrementAndGet();
        pipe(pid).resetForInterrupt();
    }
    synchronized void interruptCommandRead(long pid) throws IOException {
        pipe(pid).interruptCommandRead();
    }
    synchronized void clearCommandInterrupt(long pid) {
        var input=inputs.get(pid);if(input!=null)input.clearCommandInterrupt();
    }
    synchronized void release(long pid) {var input=inputs.remove(pid);if(input!=null)input.close();}
    synchronized void inputEnded() {
        eof=true;for(var input:inputs.values())try{input.output().close();}catch(IOException impossible){throw new AssertionError(impossible);}
    }
    @Override public synchronized void close() {closed=true;for(var input:inputs.values())input.close();inputs.clear();if(frontendOutput!=null)frontendOutput.close();}
}
