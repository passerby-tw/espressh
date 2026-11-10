package org.espressh.runtime;

import java.io.IOException;
import java.util.*;
import org.espressh.core.*;
import org.espressh.vts.FrontendIo;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.*;

/** Opaque caller-bound projection. Never accepts another terminal's identity. */
final class GuestTerminalEndpoint implements ProxyObject {
  private static final Set<String> MEMBERS = Set.of("available", "read", "write", "resize", "interrupt", "inputEnded", "outputClosed", "requestClose", "decideClose");
  private final JvkProcessApi owner;
  private final JVK kernel;
  private final VirtualTerminalService vts;
  private final VirtualTerminalService.Terminal terminal;
  private final FrontendIo io;
  private volatile boolean closed;
  private VirtualTerminalService.CloseIntent intent;

  GuestTerminalEndpoint(JvkProcessApi owner, JVK kernel, VirtualTerminalService vts,
      VirtualTerminalService.Terminal terminal) {
    this.owner = owner; this.kernel = kernel; this.vts = vts; this.terminal = terminal;
    this.io = vts.frontendIo(terminal.frontendId());
  }
  private void check() {
    owner.terminalCallerAuthorized();
    if (closed) throw new KernelException(ErrorCode.RESOURCE_CLOSED, "Frontend closed");
  }
  void ownerClosed() { closed = true; kernel.frontendLost(terminal.frontendId()); }
  @Override public Object getMember(String member) {
    if (!MEMBERS.contains(member)) return null;
    return (ProxyExecutable) args -> {
      check();
      try {
        return switch (member) {
          case "available" -> { count(args,0); yield io.output().available(); }
          case "read" -> {
            count(args, 1); int max = args[0].asInt();
            if (max <= 0 || max > 32768) throw new IllegalArgumentException("CONFIG_ERROR: read size");
            byte[] data = new byte[max]; int n = io.output().read(data);
            yield n < 0 ? "" : Base64.getEncoder().encodeToString(Arrays.copyOf(data, n));
          }
          case "write" -> {
            count(args, 1); String text = args[0].asString();
            if (text.length() > 43692) throw new IllegalArgumentException("CONFIG_ERROR: write size");
            byte[] data = Base64.getDecoder().decode(text);
            if (data.length > 32768) throw new IllegalArgumentException("CONFIG_ERROR: write size");
            io.input().write(data); yield null;
          }
          case "resize" -> { count(args, 2); io.resize(args[0].asInt(), args[1].asInt()); yield null; }
          case "interrupt" -> { count(args, 0); io.interrupt(); yield null; }
          case "outputClosed" -> { count(args, 0); ownerClosed(); yield null; }
          case "inputEnded" -> { count(args, 0); io.input().close(); yield null; }
          case "requestClose" -> {
            count(args, 0);
            synchronized (this) {
              intent = vts.requestClose(terminal.frontendId());
              if (intent.result() == VirtualTerminalService.CloseResult.STOP_FOREGROUND) {
                kernel.closeFrontend(intent); closed = true; yield 0L;
              }
              yield intent.token();
            }
          }
          case "decideClose" -> {
            count(args, 2);
            synchronized (this) {
              if (intent == null || intent.token() != args[0].asLong())
                throw new KernelException(ErrorCode.ACCESS_DENIED, "Stale close token");
              boolean shutdown = args[1].asBoolean();
              vts.decide(intent, shutdown ? VirtualTerminalService.Decision.SHUTDOWN : VirtualTerminalService.Decision.CONTINUE);
              intent = null; yield null;
            }
          }
          default -> throw new AssertionError(member);
        };
      } catch (IOException failure) {
        throw new KernelException(ErrorCode.RESOURCE_CLOSED, "Frontend IO closed");
      }
    };
  }
  private static void count(Value[] args, int expected) {
    if (args.length != expected) throw new IllegalArgumentException("CONFIG_ERROR: argument count");
  }
  @Override public Object getMemberKeys() { return MEMBERS.toArray(String[]::new); }
  @Override public boolean hasMember(String name) { return MEMBERS.contains(name); }
  @Override public void putMember(String name, Value value) { throw new KernelException(ErrorCode.ACCESS_DENIED, "Immutable endpoint"); }
}
