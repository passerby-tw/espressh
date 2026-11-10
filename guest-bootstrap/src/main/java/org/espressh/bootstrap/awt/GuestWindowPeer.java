package org.espressh.bootstrap.awt;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.espressh.bootstrap.graphics.*;

/** Guest-local peers, raster and event pump. Host retains only opaque window resources. */
final class GuestWindowPeer {
  private static final Map<Long, GuestWindowPeer> WINDOWS = new ConcurrentHashMap<>();
  private static final Map<Component, GuestWindowPeer> PEERS = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
  static final boolean TRACE = Boolean.getBoolean("espressh.input.trace");
  private static boolean traceListenerInstalled;
  static void trace(String stage, String metadata) {
    if (TRACE) System.err.println("[INPUT_TRACE] ms=" + System.currentTimeMillis()
        + " stage=" + stage + " " + metadata);
  }
  private static String componentName(Component c) {
    return c == null ? "null" : c.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(c));
  }
  private static GraphicsBridge bridge;
  private static Thread pump;
  private static volatile long pumpEpoch;
  private static long activePumpEpoch;
  final Component target;
  final boolean window;
  long handle, generation, sequence;
  BufferedImage buffer;
  Insets insets = new Insets(0, 0, 0, 0);
  boolean disposed;
  boolean hostFocused;
  private boolean paintPending;
  Component pendingFocus;

  private GuestWindowPeer(Component target) {
    this.target = target;
    window = target instanceof Window;
    if (window) {
      handle = bridge().createWindow(target instanceof Frame f ? f.getTitle() : "",
          Math.max(1, target.getWidth()), Math.max(1, target.getHeight()));
      geometry(); WINDOWS.put(handle, this); startPump();
    }
    PEERS.put(target, this);
  }
  static synchronized GraphicsBridge bridge() {
    if (bridge == null) try { bridge = new GraphicsBridge(); }
    catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
    return bridge;
  }
  static Object create(Component target, Class<?> type) {
    GuestWindowPeer peer = new GuestWindowPeer(target);
    try {
      return Proxy.newProxyInstance(GuestWindowPeer.class.getClassLoader(), new Class<?>[]{type},
          (proxy, method, args) -> peer.invoke(proxy, method.getName(), args));
    } catch (RuntimeException | Error failure) {
      peer.dispose(); throw failure;
    }
  }
  static GuestWindowPeer owner(Component component) {
    for (Component c = component; c != null; c = c.getParent()) {
      GuestWindowPeer peer = PEERS.get(c);
      if (peer != null && peer.window && !peer.disposed) return peer;
    }
    return null;
  }
  private void geometry() {
    String[] v = bridge().windowGeometry(handle).split(";");
    if (v.length != 14 || !v[0].equals("1")) throw new IllegalStateException("Invalid window snapshot");
    long next = Long.parseLong(v[1]);
    int w = Integer.parseInt(v[8]), h = Integer.parseInt(v[9]);
    insets = new Insets(Integer.parseInt(v[10]), Integer.parseInt(v[11]), Integer.parseInt(v[12]), Integer.parseInt(v[13]));
    if (buffer == null || generation != next || buffer.getWidth() != w || buffer.getHeight() != h) {
      if (buffer != null) buffer.flush();
      buffer = GuestSharedRaster.create(Math.max(1,w), Math.max(1,h), Transparency.TRANSLUCENT);
      sequence = 0;
    }
    generation = next;
  }
  private Object invoke(Object proxy, String method, Object[] a) {
    switch (method) {
      case "equals": return proxy == a[0];
      case "hashCode": return System.identityHashCode(proxy);
      case "toString": return "GuestPeer[" + target.getClass().getName() + "]";
      case "getGraphicsConfiguration": return target.getGraphicsConfiguration();
      case "getAppropriateGraphicsConfiguration":
        if (a[0] instanceof GraphicsConfiguration configuration
            && configuration.getDevice().getIDstring().equals("GuestDefaultDisplay")) return configuration;
        throw unsupported(method);
      case "getColorModel": return java.awt.image.ColorModel.getRGBdefault();
      case "getFontMetrics": return ServiceFontMetrics.forFont((Font) a[0]);
      case "getInsets": return (Insets) insets.clone();
      case "getGraphics": return graphics();
      case "dispose": dispose(); return null;
      case "setVisible":
        if (window) { bridge().setVisible(handle, (Boolean)a[0]); if ((Boolean)a[0]) requestPaint(); }
        return null;
      case "setTitle": bridge().setTitle(handle, (String)a[0]); return null;
      case "setBounds": case "setBoundsPrivate":
        if (window) {
          int width = Math.max(1, (Integer)a[2] - insets.left - insets.right);
          int height = Math.max(1, (Integer)a[3] - insets.top - insets.bottom);
          bridge().setBounds(handle, (Integer)a[0], (Integer)a[1], width, height); geometry();
        }
        return null;
      case "getBoundsPrivate": return target.getBounds();
      case "getLocationOnScreen":
        GuestWindowPeer owner = owner(target);
        if (owner == null) throw unsupported(method);
        String[] v = bridge().windowGeometry(owner.handle).split(";");
        Point point = new Point(Integer.parseInt(v[2]), Integer.parseInt(v[3]));
        for (Component c = target; c != owner.target; c = c.getParent()) { point.translate(c.getX(), c.getY()); }
        return point;
      case "getPreferredSize": case "getMinimumSize": return target.getSize();
      case "createImage": return new BufferedImage((Integer)a[0], (Integer)a[1], BufferedImage.TYPE_INT_ARGB);
      case "isFocusable": return target.isFocusable();
      case "handlesWheelScrolling": case "isReparentSupported": case "canDetermineObscurity": return false;
      case "isObscured": throw unsupported(method);
      case "updateGraphicsData": return false; // Same copied default configuration needs no replacement peer.
      case "getState": return Frame.NORMAL;
      case "requestFocus":
        GuestWindowPeer focus = owner((Component)a[0]);
        if (focus == null) return false;
        focus.pendingFocus = (Component)a[0];
        if (focus.hostFocused) { focus.focusClient(); return true; }
        boolean accepted = bridge().requestFocus(focus.handle);
        if (!accepted) focus.pendingFocus = null;
        return accepted;
      case "handleEvent":
        if (a[0] instanceof PaintEvent) requestPaint();
        return null;
      case "paint": target.paint((Graphics)a[0]); return null;
      case "print": target.print((Graphics)a[0]); return null;
      // Guest layout/style are used at paint time; no separate native child/style exists.
      case "setForeground": case "setBackground": case "setFont": case "layout":
      case "beginValidate": case "endValidate": case "beginLayout": case "endLayout":
      case "coalescePaintEvent": return null;
      // No native cursor or shaped-child surface is provided in this first increment.
      case "updateCursorImmediately": case "repositionSecurityWarning": return null;
      case "setZOrder": if (a[0] == null) return null; throw unsupported(method);
      case "applyShape": if (a[0] == null) return null; throw unsupported(method);
      case "setEnabled": if (Boolean.TRUE.equals(a[0])) return null; throw unsupported(method);
      case "setResizable": if (Boolean.TRUE.equals(a[0])) return null; throw unsupported(method);
      case "setState": if ((Integer)a[0] == Frame.NORMAL) return null; throw unsupported(method);
      case "setMenuBar": if (a[0] == null) return null; throw unsupported(method);
      case "setMaximizedBounds": if (a[0] == null) return null; throw unsupported(method);
      case "updateMinimumSize": if (!target.isMinimumSizeSet()) return null; throw unsupported(method);
      case "updateIconImages": if (((Window)target).getIconImages().isEmpty()) return null; throw unsupported(method);
      case "updateFocusableWindowState": if (((Window)target).getFocusableWindowState()) return null; throw unsupported(method);
      case "updateAlwaysOnTopState": if (!((Window)target).isAlwaysOnTop()) return null; throw unsupported(method);
      default: throw unsupported(method);
    }
  }
  private static UnsupportedOperationException unsupported(String method) {
    return new UnsupportedOperationException("AWT_PEER_UNSUPPORTED: " + method);
  }
  private Graphics graphics() {
    if (!EventQueue.isDispatchThread()) throw unsupported("off-EDT getGraphics");
    GuestWindowPeer owner = owner(target);
    if (owner == null) return null;
    Graphics2D g = owner.buffer.createGraphics();
    int x = -owner.insets.left, y = -owner.insets.top;
    for (Component c = target; c != owner.target; c = c.getParent()) { x += c.getX(); y += c.getY(); }
    g.translate(x, y); g.clipRect(0, 0, target.getWidth(), target.getHeight());
    if (target.getFont() != null) g.setFont(target.getFont());
    if (target.getForeground() != null) g.setColor(target.getForeground());
    return new GuestWindowGraphics(g, owner::present);
  }
  private void paint() {
    if (disposed || !target.isVisible()) return;
    long started = TRACE ? System.nanoTime() : 0;
    if (TRACE) trace("guest-paint-begin", "window=" + handle);
    Graphics g = graphics();
    try {
      target.paint(g);
      if(target instanceof Container container) paintHeavyChildren(container,g);
    } catch(RuntimeException | Error failure) {
      // Preserve the paint failure and previous frame; publication must not mask it.
      ((GuestWindowGraphics)g).abortPublication();
      throw failure;
    } finally {
      try { g.dispose(); }
      finally { if (TRACE) trace("guest-paint-end", "window=" + handle
          + " elapsedMs=" + (System.nanoTime()-started)/1_000_000); }
    }
  }
  private synchronized void requestPaint() {
    if(disposed || paintPending)return;
    paintPending=true;
    EventQueue.invokeLater(()->{
      synchronized(GuestWindowPeer.this){paintPending=false;}
      paint();
    });
  }
  private static void paintHeavyChildren(Container container,Graphics graphics) {
    for(Component child:container.getComponents()) {
      if(!child.isVisible())continue;
      Graphics local=graphics.create(child.getX(),child.getY(),child.getWidth(),child.getHeight());
      try {
        // Container.paint visits lightweight children; native peers normally paint the others.
        if(!child.isLightweight() && PEERS.containsKey(child))child.paint(local);
        if(child instanceof Container nested)paintHeavyChildren(nested,local);
      } finally {local.dispose();}
    }
  }
  void present() {
    if (disposed) return;
    if (bridge().generation(handle) != generation) {
      geometry(); target.invalidate();
      EventQueue.invokeLater(() -> { if (!disposed) { target.validate(); requestPaint(); } });
      return;
    }
    long started = TRACE ? System.nanoTime() : 0;
    GuestText.tracePresentation(handle);
    if (TRACE) trace("guest-present-begin", "window=" + handle);
    int w = buffer.getWidth(), h = buffer.getHeight();
    Color background = target.getBackground();
    if (background == null || background.getAlpha() != 255) throw unsupported("non-opaque window background");
    long image = ((GuestSharedRaster)buffer).handle();
    try { bridge().submit(handle, generation, ++sequence,
        new GraphicsWire.Batch().color(background.getRGB()).fill(0,0,w,h).image(image,0,0)); }
    finally { if (TRACE) trace("guest-present-end", "window=" + handle
          + " pixels=" + (long)w*h + " transport=shared elapsedMs=" + (System.nanoTime()-started)/1_000_000); }
    java.awt.im.InputContext context=((Window)target).getInputContext();
    if(context instanceof GuestInputContext input) input.refresh();
  }
  private void dispose() {
    if (disposed) return;
    disposed = true; PEERS.remove(target, this);
    if (window) {
      WINDOWS.remove(handle, this);
      if (buffer != null) { buffer.flush(); buffer = null; }
      bridge().release(handle);
      synchronized (GuestWindowPeer.class) {
        if (WINDOWS.isEmpty()) {
          pumpEpoch++;
          if(pump != null) pump.interrupt();
        }
      }
    }
  }
  private static synchronized void startPump() {
    if (TRACE && !traceListenerInstalled) {
      Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        trace("guest-dispatch", "id=" + event.getID() + " source="
            + componentName(event.getSource() instanceof Component c ? c : null)
            + " focus=" + componentName(focus));
      }, AWTEvent.KEY_EVENT_MASK | AWTEvent.FOCUS_EVENT_MASK | AWTEvent.INPUT_METHOD_EVENT_MASK);
      traceListenerInstalled = true;
    }
    if (pump != null && pump.isAlive() && activePumpEpoch == pumpEpoch) return;
    final long epoch=++pumpEpoch;
    activePumpEpoch=epoch;
    pump = new Thread(() -> {
      while (epoch == pumpEpoch && !Thread.currentThread().isInterrupted()) {
        try {
          for (GraphicsWire.Event event : bridge().awaitEvents(128, 250)) {
            if (TRACE) trace("guest-pump", "window=" + event.window() + " generation=" + event.generation()
                + " type=" + event.type() + " eventNs=" + event.timestampNanos());
            GuestWindowPeer peer = WINDOWS.get(event.window());
            if (peer != null) EventQueue.invokeLater(() -> peer.event(event));
          }
        } catch (RuntimeException failure) {
          // Interrupting the last-window pump may unwind a Host wait through Interop.
          if (epoch != pumpEpoch || Thread.currentThread().isInterrupted()) return;
          throw failure;
        }
      }
    }, "guest-awt-input");
    pump.setDaemon(true); pump.start();
  }
  private void event(GraphicsWire.Event e) {
    if (TRACE) trace("guest-event", "window=" + handle + " type=" + e.type()
        + " eventNs=" + e.timestampNanos() + " generation=" + e.generation()
        + " currentGeneration=" + generation + " disposed=" + disposed
        + " focus=" + componentName(((Window)target).getFocusOwner()));
    if (disposed || e.generation() < generation || (e.type() != GraphicsWire.RESIZE && e.generation() != generation)) return;
    EventQueue queue = Toolkit.getDefaultToolkit().getSystemEventQueue();
    Window w = (Window)target;
    Component focus = w.getFocusOwner();
    if (focus == null) focus = w;
    long when = System.currentTimeMillis();
    switch(e.type()) {
      case GraphicsWire.CLOSE_REQUEST: queue.postEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING)); break;
      case GraphicsWire.REPAINT: requestPaint(); break;
      case GraphicsWire.RESIZE:
        geometry(); target.setSize(buffer.getWidth()+insets.left+insets.right, buffer.getHeight()+insets.top+insets.bottom);
        target.validate(); requestPaint(); break;
      case GraphicsWire.FOCUS_IN:
        hostFocused = true; queue.postEvent(new WindowEvent(w, WindowEvent.WINDOW_GAINED_FOCUS)); focusClient(); break;
      case GraphicsWire.FOCUS_OUT:
        hostFocused = false; pendingFocus = null;
        if(focus!=w) queue.postEvent(new FocusEvent(focus,FocusEvent.FOCUS_LOST,false,null,FocusEvent.Cause.ACTIVATION));
        queue.postEvent(new WindowEvent(w, WindowEvent.WINDOW_LOST_FOCUS)); break;
      case GraphicsWire.KEY_DOWN: case GraphicsWire.KEY_UP:
        queue.postEvent(new KeyEvent(focus, e.type()==GraphicsWire.KEY_DOWN ? KeyEvent.KEY_PRESSED : KeyEvent.KEY_RELEASED,
            when, e.modifiers(), e.arg1(), KeyEvent.CHAR_UNDEFINED, e.arg2())); break;
      case GraphicsWire.TEXT_INPUT:
        for (char ch : e.text().toCharArray()) queue.postEvent(new KeyEvent(focus,KeyEvent.KEY_TYPED,when,e.modifiers(),KeyEvent.VK_UNDEFINED,ch)); break;
      case GraphicsWire.IME_TEXT_CHANGED: case GraphicsWire.IME_CARET_CHANGED:
        var packet=GraphicsImeWire.decodeText(e.text());
        if(e.type()==GraphicsWire.IME_TEXT_CHANGED)
          queue.postEvent(new InputMethodEvent(focus,InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,when,packet.iterator(),packet.committed(),packet.caret(),packet.visible()));
        else queue.postEvent(new InputMethodEvent(focus,InputMethodEvent.CARET_POSITION_CHANGED,when,null,0,packet.caret(),packet.visible()));
        break;
      case GraphicsWire.MOUSE_DOWN: case GraphicsWire.MOUSE_UP: case GraphicsWire.MOUSE_MOVE: case GraphicsWire.MOUSE_WHEEL:
        int x = e.arg1()+insets.left, y = e.arg2()+insets.top;
        Component hit = w.findComponentAt(x,y); if (hit == null) hit = w;
        Point local = javax.swing.SwingUtilities.convertPoint(w,x,y,hit);
        if (e.type()==GraphicsWire.MOUSE_WHEEL) queue.postEvent(new MouseWheelEvent(hit,MouseEvent.MOUSE_WHEEL,when,e.modifiers(),local.x,local.y,x+w.getX(),y+w.getY(),0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,3,(int)Double.parseDouble(e.text()),Double.parseDouble(e.text())));
        else queue.postEvent(new MouseEvent(hit,e.type()==GraphicsWire.MOUSE_DOWN ? MouseEvent.MOUSE_PRESSED : e.type()==GraphicsWire.MOUSE_UP ? MouseEvent.MOUSE_RELEASED :
            (e.modifiers() & (InputEvent.BUTTON1_DOWN_MASK|InputEvent.BUTTON2_DOWN_MASK|InputEvent.BUTTON3_DOWN_MASK))!=0 ? MouseEvent.MOUSE_DRAGGED : MouseEvent.MOUSE_MOVED,
            when,e.modifiers(),local.x,local.y,e.type()==GraphicsWire.MOUSE_MOVE?0:1,false,e.text().isEmpty()?0:Integer.parseInt(e.text())));
        break;
      default: throw unsupported("event " + e.type());
    }
  }
  private void focusClient() {
    Component next = pendingFocus; pendingFocus = null;
    if(next==null || !next.isShowing() || !next.isFocusable()) return;
    Component previous=((Window)target).getFocusOwner();
    EventQueue queue=Toolkit.getDefaultToolkit().getSystemEventQueue();
    if(previous!=null && previous!=next) queue.postEvent(new FocusEvent(previous,FocusEvent.FOCUS_LOST,false,next,FocusEvent.Cause.UNKNOWN));
    if(previous!=next) queue.postEvent(new FocusEvent(next,FocusEvent.FOCUS_GAINED,false,previous,FocusEvent.Cause.UNKNOWN));
  }
}
