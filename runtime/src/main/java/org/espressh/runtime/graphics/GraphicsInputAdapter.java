package org.espressh.runtime.graphics;

import static org.espressh.bootstrap.graphics.GraphicsWire.*;

import java.awt.Canvas;
import java.awt.EventQueue;
import java.awt.event.*;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Host window input only. No VTS routing, signal handling or Guest callbacks. */
final class GraphicsInputAdapter extends KeyAdapter
    implements FocusListener, MouseListener, MouseMotionListener, MouseWheelListener, InputMethodListener {
  @FunctionalInterface
  interface Sink {
    void emit(int type, int arg1, int arg2, int modifiers, String text);
  }

  private record Key(int code, int location) {}

  private final BooleanSupplier active;
  private final Sink sink;
  // Accessed only on the Host EDT. Repeat presses do not create duplicate held keys.
  private final Map<Key, Boolean> pressed = new LinkedHashMap<>();
  private final Set<Key> releasedOnFocusLoss = new HashSet<>();
  private String committed = "";
  private long committedWhen;

  GraphicsInputAdapter(BooleanSupplier active, Sink sink) {
    this.active = active;
    this.sink = sink;
  }

  void install(Canvas canvas) {
    canvas.setFocusTraversalKeysEnabled(false);
    canvas.addKeyListener(this);
    canvas.addFocusListener(this);
    canvas.addMouseListener(this);
    canvas.addMouseMotionListener(this);
    canvas.addMouseWheelListener(this);
    canvas.addInputMethodListener(this);
  }

  private void trace(java.awt.AWTEvent event) {
    if (!InputTrace.ENABLED) return;
    java.awt.Component focus = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
    InputTrace.log("host-listener", "id=" + event.getID() + " active=" + active.getAsBoolean()
        + " focus=" + (focus == null ? "null" : focus.getClass().getName()));
  }

  private boolean live() {
    if (!EventQueue.isDispatchThread())
      throw new IllegalStateException("Graphics input must run on Host EDT");
    if (active.getAsBoolean()) return true;
    pressed.clear();
    releasedOnFocusLoss.clear();
    return false;
  }

  @Override
  public void keyPressed(KeyEvent e) {
    trace(e);
    if (!live()) return;
    Key key = new Key(e.getKeyCode(), e.getKeyLocation());
    releasedOnFocusLoss.remove(key);
    pressed.put(key, Boolean.TRUE);
    sink.emit(KEY_DOWN, e.getKeyCode(), e.getKeyLocation(), e.getModifiersEx(), "");
  }

  @Override
  public void keyReleased(KeyEvent e) {
    trace(e);
    if (!live()) return;
    // A late release after focus loss was already synthesized; do not duplicate it.
    Key key = new Key(e.getKeyCode(), e.getKeyLocation());
    if (pressed.remove(key) == null && releasedOnFocusLoss.remove(key)) return;
    sink.emit(KEY_UP, e.getKeyCode(), e.getKeyLocation(), e.getModifiersEx(), "");
  }

  @Override
  public void keyTyped(KeyEvent e) {
    trace(e);
    if (!live() || e.getKeyChar() == KeyEvent.CHAR_UNDEFINED) return;
    if (e.getWhen()==committedWhen && !committed.isEmpty() && committed.charAt(0)==e.getKeyChar()) {
      committed=committed.substring(1); return;
    }
    committed="";
    sink.emit(TEXT_INPUT, 0, 0, e.getModifiersEx(), String.valueOf(e.getKeyChar()));
  }

  @Override
  public void inputMethodTextChanged(InputMethodEvent e) {
    trace(e);
    if (!live()) return;
    java.text.AttributedCharacterIterator iterator=e.getText();
    StringBuilder text=new StringBuilder();
    java.util.List<org.espressh.bootstrap.graphics.GraphicsImeWire.Highlight> highlights=new java.util.ArrayList<>();
    if(iterator!=null) {
      for(char c=iterator.first();c!=java.text.AttributedCharacterIterator.DONE;c=iterator.next()) text.append(c);
      for(int i=iterator.getBeginIndex();i<iterator.getEndIndex();) {
        iterator.setIndex(i);int end=iterator.getRunLimit(java.awt.font.TextAttribute.INPUT_METHOD_HIGHLIGHT);
        Object value=iterator.getAttribute(java.awt.font.TextAttribute.INPUT_METHOD_HIGHLIGHT);
        if(value instanceof java.text.Annotation annotation) value=annotation.getValue();
        if(value instanceof java.awt.im.InputMethodHighlight h)
          highlights.add(new org.espressh.bootstrap.graphics.GraphicsImeWire.Highlight(i-iterator.getBeginIndex(),end-iterator.getBeginIndex(),h.isSelected(),h.getState(),h.getVariation()));
        i=end;
      }
    }
    var packet=new org.espressh.bootstrap.graphics.GraphicsImeWire.Text(text.toString(),e.getCommittedCharacterCount(),e.getCaret(),e.getVisiblePosition(),highlights);
    committed=text.substring(0,e.getCommittedCharacterCount());committedWhen=e.getWhen();
    composition=text.substring(e.getCommittedCharacterCount());
    sink.emit(IME_TEXT_CHANGED,0,0,0,org.espressh.bootstrap.graphics.GraphicsImeWire.encodeText(packet));
    e.consume();
  }
  @Override
  public void caretPositionChanged(InputMethodEvent e) {
    if(!live())return;
    // Caret indexes refer to the latest uncommitted text, supplied by the listener below.
    sink.emit(IME_CARET_CHANGED,0,0,0,org.espressh.bootstrap.graphics.GraphicsImeWire.encodeText(
        new org.espressh.bootstrap.graphics.GraphicsImeWire.Text(composition,0,e.getCaret(),e.getVisiblePosition(),java.util.List.of())));
    e.consume();
  }
  private String composition = "";

  @Override
  public void focusGained(FocusEvent e) {
    trace(e);
    if (live()) sink.emit(FOCUS_IN, 0, 0, 0, "");
  }

  @Override
  public void focusLost(FocusEvent e) {
    trace(e);
    if (!live()) return;
    // Release before FOCUS_OUT so Guest state cannot retain keys after focus changes.
    for (Key key : pressed.keySet()) {
      sink.emit(KEY_UP, key.code(), key.location(), 0, "");
      releasedOnFocusLoss.add(key);
    }
    pressed.clear();
    sink.emit(FOCUS_OUT, 0, 0, 0, "");
  }

  @Override
  public void mousePressed(MouseEvent e) {
    if (!live()) return;
    e.getComponent().requestFocusInWindow();
    sink.emit(MOUSE_DOWN, e.getX(), e.getY(), e.getModifiersEx(), Integer.toString(e.getButton()));
  }

  @Override
  public void mouseReleased(MouseEvent e) {
    if (live())
      sink.emit(MOUSE_UP, e.getX(), e.getY(), e.getModifiersEx(), Integer.toString(e.getButton()));
  }

  @Override
  public void mouseMoved(MouseEvent e) {
    if (live()) sink.emit(MOUSE_MOVE, e.getX(), e.getY(), e.getModifiersEx(), "");
  }

  @Override
  public void mouseDragged(MouseEvent e) {
    mouseMoved(e);
  }

  @Override
  public void mouseWheelMoved(MouseWheelEvent e) {
    if (live())
      sink.emit(
          MOUSE_WHEEL,
          e.getX(),
          e.getY(),
          e.getModifiersEx(),
          Double.toString(e.getPreciseWheelRotation()));
  }

  @Override
  public void mouseClicked(MouseEvent e) {}

  @Override
  public void mouseEntered(MouseEvent e) {}

  @Override
  public void mouseExited(MouseEvent e) {}
}
