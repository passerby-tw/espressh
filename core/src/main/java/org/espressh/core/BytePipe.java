package org.espressh.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Objects;

/** Bounded ordered byte channel. Closing either end wakes blocked peers. */
public final class BytePipe implements AutoCloseable {
    private final byte[] bytes;
    private int head, size;
    private boolean readerClosed, writerClosed, cancelled, commandInterrupted;
    public BytePipe(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        bytes = new byte[capacity];
    }
    private void awaitChange() throws InterruptedIOException {
        try { wait(); } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("Pipe operation interrupted");
        }
    }
    private void readable() throws IOException {
        if (readerClosed || cancelled) throw new IOException("RESOURCE_CLOSED");
        if (commandInterrupted) throw new IOException("Command input interrupted");
    }
    private void writable() throws IOException { if (readerClosed || writerClosed || cancelled) throw new IOException("RESOURCE_CLOSED"); }
    private void writable(Writer writer) throws IOException {
        writable();
        if (writer.closed) throw new IOException("RESOURCE_CLOSED");
    }
    private synchronized int readBytes(byte[] dst, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, dst.length);
        if (len == 0) return 0;
        readable();
        while (size == 0 && !writerClosed) { awaitChange(); readable(); }
        if (size == 0) return -1;
        int count = Math.min(len, size);
        for (int i=0; i<count; i++) dst[off+i] = bytes[(head+i)%bytes.length];
        head=(head+count)%bytes.length; size-=count; notifyAll(); return count;
    }
    private synchronized void writeBytes(byte[] src, int off, int len, Writer writer) throws IOException {
        Objects.checkFromIndexSize(off, len, src.length); writable(writer);
        for (int i=0; i<len;) {
            while (size == bytes.length) { awaitChange(); writable(writer); }
            writable(writer); int count = Math.min(len-i, bytes.length-size);
            for (int j=0; j<count; j++) bytes[(head+size+j)%bytes.length]=src[off+i+j];
            size+=count; i+=count; notifyAll();
        }
    }
    private final InputStream input = new InputStream() {
        @Override public int read() throws IOException { byte[] b = new byte[1]; return read(b,0,1)<0 ? -1 : b[0]&255; }
        @Override public int read(byte[] b,int off,int len) throws IOException { return readBytes(b,off,len); }
        @Override public int available() throws IOException { synchronized(BytePipe.this) { readable(); return size; } }
        @Override public void close() { synchronized(BytePipe.this) { readerClosed=true; size=0; BytePipe.this.notifyAll(); } }
    };
    private final class Writer extends OutputStream {
        private final boolean root;
        private boolean closed;
        Writer(boolean root) { this.root = root; }
        Writer fork() { return new Writer(false); }
        @Override public void write(int b) throws IOException { write(new byte[]{(byte)b}); }
        @Override public void write(byte[] b,int off,int len) throws IOException { writeBytes(b,off,len,this); }
        @Override public void close() {
            synchronized(BytePipe.this) {
                closed=true;
                if(root) writerClosed=true;
                BytePipe.this.notifyAll();
            }
        }
    }
    private final OutputStream output = new Writer(true);
    /** A child can cancel its blocked inherited write without closing the shared writer. */
    static OutputStream borrowWriter(OutputStream stream) {
        return stream instanceof BytePipe.Writer writer ? writer.fork() : null;
    }
    static void closeBorrowedWriter(OutputStream stream) {
        if (stream instanceof BytePipe.Writer writer && !writer.root) writer.close();
    }
    synchronized void resetForInterrupt() throws IOException {
        writable();head=0;size=1;bytes[0]=3;notifyAll();
    }
    /** Out-of-band, invocation-scoped wakeup; never interrupts a Host thread or closes the channel. */
    synchronized void interruptCommandRead() throws IOException {
        writable();commandInterrupted=true;notifyAll();
    }
    synchronized void clearCommandInterrupt() { commandInterrupted=false; }
    public InputStream input() { return input; }
    public OutputStream output() { return output; }
    @Override public synchronized void close() { cancelled=true; size=0; notifyAll(); }
}
