package org.espressh.bootstrap.awt;

import java.awt.*;
import java.awt.event.FocusEvent;
import java.awt.font.TextHitInfo;
import java.awt.im.*;
import java.text.AttributedCharacterIterator;
import java.util.Locale;
import org.espressh.bootstrap.graphics.GraphicsImeWire;

/** Guest API adapter. Platform IME queries read Host-side snapshots only. */
public final class GuestInputContext extends InputContext {
  private Component client;
  private GuestWindowPeer owner;
  private boolean disposed;
  public GuestInputContext() {}
  private static UnsupportedOperationException unsupported() {return new UnsupportedOperationException("AWT_IME_OPERATION_UNSUPPORTED");}
  public boolean selectInputMethod(Locale locale) {throw unsupported();}
  public Locale getLocale() {throw unsupported();}
  public void setCharacterSubsets(Character.Subset[] subsets) {throw unsupported();}
  public void reconvert() {throw unsupported();}
  public Object getInputMethodControlObject() {throw unsupported();}
  public void setCompositionEnabled(boolean value) {live(); throw unsupported();}
  public boolean isCompositionEnabled() {live();throw unsupported();}
  public void dispatchEvent(AWTEvent event) {
    live();
    if(event instanceof FocusEvent f) {
      if(f.getID()==FocusEvent.FOCUS_GAINED) {client=f.getComponent();owner=GuestWindowPeer.owner(client);refresh();}
      else if(f.getID()==FocusEvent.FOCUS_LOST && f.getComponent()==client) {
        clear();
      }
    }
  }
  public void removeNotify(Component component) {if(component==client) clear();}
  public void endComposition() {live();if(owner!=null&&!owner.disposed) GuestWindowPeer.bridge().endComposition(owner.handle);}
  public void dispose() {if(!disposed){clear();disposed=true;}}
  private void live() {if(disposed)throw new IllegalStateException("AWT_INPUT_CONTEXT_DISPOSED");}
  private void clear() {
    if(owner!=null&&!owner.disposed) GuestWindowPeer.bridge().updateInputMethod(owner.handle,
        GraphicsImeWire.encodeSnapshot(new GraphicsImeWire.Snapshot(false,new Rectangle(),"","",0,"")));
    client=null;owner=null;
  }
  void refresh() {
    if(disposed || client==null || owner==null || owner.disposed) return;
    InputMethodRequests requests=client.getInputMethodRequests();
    if(requests==null) {
      GuestWindowPeer.bridge().updateInputMethod(owner.handle,GraphicsImeWire.encodeSnapshot(new GraphicsImeWire.Snapshot(false,new Rectangle(),"","",0,""))); return;
    }
    Rectangle caret=new Rectangle(requests.getTextLocation(TextHitInfo.leading(0)));
    String[] geometry=GuestWindowPeer.bridge().windowGeometry(owner.handle).split(";");
    caret.translate(-Integer.parseInt(geometry[2])-Integer.parseInt(geometry[6]),-Integer.parseInt(geometry[3])-Integer.parseInt(geometry[7]));
    int length=requests.getCommittedTextLength();
    if(length<0 || length>org.espressh.bootstrap.graphics.GraphicsWire.MAX_BYTES)throw new IllegalArgumentException("GRAPHICS_MALFORMED_PAYLOAD");
    String committed=text(requests.getCommittedText(0,length,null));
    if(committed.length()!=length)throw new IllegalArgumentException("GRAPHICS_MALFORMED_PAYLOAD");
    String selected=text(requests.getSelectedText(null));
    Locale locale=client.getLocale();
    GuestWindowPeer.bridge().updateInputMethod(owner.handle,GraphicsImeWire.encodeSnapshot(
        new GraphicsImeWire.Snapshot(true,caret,committed,selected,requests.getInsertPositionOffset(),locale==null?"":locale.toLanguageTag())));
  }
  private static String text(AttributedCharacterIterator iterator) {
    if(iterator==null)return "";
    StringBuilder result=new StringBuilder();for(char c=iterator.first();c!=AttributedCharacterIterator.DONE;c=iterator.next())result.append(c);return result.toString();
  }
}
