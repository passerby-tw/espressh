package org.espressh.runtime.graphics;

import java.awt.*;
import java.awt.font.TextHitInfo;
import java.awt.im.InputMethodRequests;
import java.text.*;
import org.espressh.bootstrap.graphics.GraphicsImeWire.Snapshot;

/** Host EDT reads only this copied cache; never calls or waits for Guest. */
final class HostInputMethodRequests implements InputMethodRequests {
  private final Canvas canvas;
  Snapshot snapshot = new Snapshot(false,new Rectangle(),"","",0,"");
  HostInputMethodRequests(Canvas canvas) { this.canvas=canvas; }
  public Rectangle getTextLocation(TextHitInfo offset) {
    Rectangle r=snapshot.caret();Point p=canvas.getLocationOnScreen();r.translate(p.x,p.y);return r;
  }
  public TextHitInfo getLocationOffset(int x,int y) {
    return getTextLocation(null).contains(x,y) ? TextHitInfo.leading(0) : null;
  }
  public int getInsertPositionOffset() {return snapshot.insertPosition();}
  public AttributedCharacterIterator getCommittedText(int begin,int end,AttributedCharacterIterator.Attribute[] attributes) {
    return new AttributedString(snapshot.committed().substring(begin,end)).getIterator();
  }
  public int getCommittedTextLength() {return snapshot.committed().length();}
  public AttributedCharacterIterator cancelLatestCommittedText(AttributedCharacterIterator.Attribute[] attributes) {
    throw new UnsupportedOperationException("AWT_IME_RECONVERSION_UNSUPPORTED");
  }
  public AttributedCharacterIterator getSelectedText(AttributedCharacterIterator.Attribute[] attributes) {
    return new AttributedString(snapshot.selected()).getIterator();
  }
}
