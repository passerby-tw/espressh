package org.espressh.bootstrap.awt;

import java.lang.reflect.Proxy;

/** Caller Guest proxy for the pinned ComponentFactory interface. */
public final class GuestComponentFactory {
  private static Object factory;
  private GuestComponentFactory() {}
  public static synchronized Object create() throws ClassNotFoundException {
    if (factory == null) factory = Proxy.newProxyInstance(GuestComponentFactory.class.getClassLoader(),
        new Class<?>[]{Class.forName("sun.awt.ComponentFactory")}, (proxy, method, args) -> {
          switch (method.getName()) {
            case "createFrame": case "createWindow": case "createDialog":
            case "createComponent": case "createCanvas": case "createPanel":
              return GuestWindowPeer.create((java.awt.Component) args[0], method.getReturnType());
            case "equals": return proxy == args[0];
            case "hashCode": return System.identityHashCode(proxy);
            case "toString": return "GuestComponentFactory";
            default: throw new UnsupportedOperationException("AWT_PEER_NOT_CONNECTED: " + method.getName());
          }
        });
    return factory;
  }
}
