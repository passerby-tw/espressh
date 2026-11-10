package org.espressh.bootstrap.vts;

import java.io.*;
import java.lang.reflect.*;
import java.util.Base64;
import org.espressh.vts.*;

/** Guest-local streams over caller-bound sys projections. No Host implementation reference. */
public final class GuestTerminalProvider implements TerminalProvider {
  private final Object sys;
  private final Method invoke, asString, asLong;
  public GuestTerminalProvider() {
    try {
      Class<?> polyglot = Class.forName("com.oracle.truffle.espresso.polyglot.Polyglot");
      Class<?> interop = Class.forName("com.oracle.truffle.espresso.polyglot.Interop");
      sys = polyglot.getMethod("importObject", String.class).invoke(null, "sys");
      invoke = interop.getMethod("invokeMember", Object.class, String.class, Object[].class);
      asString = interop.getMethod("asString", Object.class);
      asLong = interop.getMethod("asLong", Object.class);
    } catch (ReflectiveOperationException failure) { throw new IllegalStateException("VTS_PROVIDER_UNAVAILABLE", failure); }
  }
  private Object call(Object target, String member, Object... args) {
    try { return invoke.invoke(null, target, member, args); }
    catch (ReflectiveOperationException failure) { throw new IllegalStateException("VTS operation failed: " + member, failure); }
  }
  private String text(Object target, String member, Object... args) {
    try { return (String) asString.invoke(null, call(target, member, args)); }
    catch (ReflectiveOperationException failure) { throw new IllegalStateException("VTS result conversion", failure); }
  }
  private long number(Object target, String member, Object... args) {
    try { return (Long) asLong.invoke(null, call(target, member, args)); }
    catch (ReflectiveOperationException failure) { throw new IllegalStateException("VTS result conversion", failure); }
  }
  @Override public TerminalEndpoint createTerminal(int columns, int rows) {
    if (columns <= 0 || rows <= 0) throw new IllegalArgumentException("Invalid terminal size");
    Object endpoint = call(sys, "createTerminal", columns, rows);
    return new TerminalEndpoint() {
      private final InputStream output = new InputStream() {
        private volatile boolean closed;
        @Override public int read() throws IOException { byte[] b = new byte[1]; return read(b,0,1)<0 ? -1 : b[0]&255; }
        @Override public synchronized int read(byte[] bytes, int offset, int length) throws IOException {
          java.util.Objects.checkFromIndexSize(offset,length,bytes.length);
          if (closed) throw new IOException("Output stream closed");
          if (length == 0) return 0;
          try {
            byte[] data = Base64.getDecoder().decode(text(endpoint,"read",Math.min(length,32768)));
            if (data.length == 0) return -1;
            System.arraycopy(data,0,bytes,offset,data.length); return data.length;
          } catch (RuntimeException failure) { throw new IOException("VTS read failed", failure); }
        }
        @Override public int available() throws IOException {
          if(closed)throw new IOException("Output stream closed");
          try{return Math.toIntExact(number(endpoint,"available"));}
          catch(RuntimeException failure){throw new IOException("VTS available failed",failure);}
        }
        @Override public void close() throws IOException {
          if (!closed) {
            closed = true;
            try { call(endpoint,"outputClosed"); }
            catch(RuntimeException failure) { throw new IOException("VTS output close failed",failure); }
          }
        }
      };
      private final OutputStream input = new OutputStream() {
        private boolean closed;
        @Override public void write(int value) throws IOException { write(new byte[]{(byte)value}); }
        @Override public synchronized void write(byte[] bytes,int offset,int length) throws IOException {
          java.util.Objects.checkFromIndexSize(offset,length,bytes.length);
          if (closed) throw new IOException("Input stream closed");
          try {
            for(int done=0;done<length;) {
              int n=Math.min(32768,length-done);
              call(endpoint,"write",Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(bytes,offset+done,offset+done+n)));
              done+=n;
            }
          } catch (RuntimeException failure) { throw new IOException("VTS write failed", failure); }
        }
        @Override public synchronized void close() throws IOException {
          if(!closed) {
            closed=true;
            try { call(endpoint,"inputEnded"); }
            catch(RuntimeException failure) { throw new IOException("VTS input close failed",failure); }
          }
        }
      };
      @Override public InputStream output() { return output; }
      @Override public OutputStream input() { return input; }
      @Override public void resize(int columns,int rows) { call(endpoint,"resize",columns,rows); }
      @Override public void interrupt() { call(endpoint,"interrupt"); }
      @Override public long requestClose() { return number(endpoint,"requestClose"); }
      @Override public void decideClose(long token,boolean shutdown) { call(endpoint,"decideClose",token,shutdown); }
    };
  }
}
