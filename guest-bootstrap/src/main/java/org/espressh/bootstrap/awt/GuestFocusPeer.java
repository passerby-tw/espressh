package org.espressh.bootstrap.awt;

import java.awt.Component;
import java.awt.Window;
import java.lang.reflect.Proxy;

/** Guest-owned peer state. Platform focus requests remain explicitly unsupported. */
public final class GuestFocusPeer {
  private Component owner;
  private Window window;
  private static Object peer;
  public static synchronized Object create() throws ClassNotFoundException {
    if (peer != null) return peer;
    Class<?> type = Class.forName("java.awt.peer.KeyboardFocusManagerPeer");
    GuestFocusPeer state = new GuestFocusPeer();
    peer = Proxy.newProxyInstance(GuestFocusPeer.class.getClassLoader(), new Class<?>[]{type},
        (proxy, method, args) -> {
          synchronized (state) {
            switch (method.getName()) {
              case "getCurrentFocusOwner": return state.owner;
              case "getCurrentFocusedWindow": return state.window;
              case "setCurrentFocusOwner": state.owner = (Component) args[0]; return null;
              case "setCurrentFocusedWindow": state.window = (Window) args[0]; return null;
              case "clearGlobalFocusOwner":
                Component previous = state.owner;
                state.owner = null;
                if (previous != null) java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(
                    new java.awt.event.FocusEvent(previous, java.awt.event.FocusEvent.FOCUS_LOST,
                        false, null, java.awt.event.FocusEvent.Cause.CLEAR_GLOBAL_FOCUS_OWNER));
                return null;
              case "hashCode": return System.identityHashCode(proxy);
              case "equals": return proxy == args[0];
              case "toString": return "GuestFocusPeer";
              default: throw new UnsupportedOperationException("AWT_PLATFORM_FOCUS_NOT_CONNECTED: " + method.getName());
            }
          }
        });
    return peer;
  }
}
