package org.espressh.bootstrap.graphics;

import java.awt.Rectangle;
import java.awt.font.TextHitInfo;
import java.awt.im.InputMethodHighlight;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.util.*;

/** Copied IME payloads shared by Guest adapter and Host backend, never callbacks. */
public final class GraphicsImeWire {
  private GraphicsImeWire() {}
  public record Snapshot(boolean enabled, Rectangle caret, String committed, String selected,
      int insertPosition, String locale) {
    public Snapshot {
      caret = new Rectangle(Objects.requireNonNull(caret));
      Objects.requireNonNull(committed); Objects.requireNonNull(selected); Objects.requireNonNull(locale);
      if (caret.width<0 || caret.height<0 || insertPosition<0 || insertPosition>committed.length()) throw malformed();
    }
    public Rectangle caret() { return new Rectangle(caret); }
  }
  public record Highlight(int start, int end, boolean selected, int state, int variation) {}
  public record Text(String text, int committed, TextHitInfo caret, TextHitInfo visible, List<Highlight> highlights) {
    public Text {
      Objects.requireNonNull(text); highlights = List.copyOf(highlights);
      if (committed<0 || committed>text.length()) throw malformed();
      int length=text.length()-committed;
      for (TextHitInfo hit : new TextHitInfo[]{caret,visible})
        if(hit!=null && (hit.getInsertionIndex()<0 || hit.getInsertionIndex()>length)) throw malformed();
      for(Highlight h:highlights) if(h.start()<0 || h.end()<=h.start() || h.end()>text.length()
          || h.state()<0 || h.state()>1 || h.variation()<0) throw malformed();
    }
    public AttributedCharacterIterator iterator() {
      if(text.isEmpty()) return null;
      AttributedString result=new AttributedString(text);
      for(Highlight h:highlights) result.addAttribute(java.awt.font.TextAttribute.INPUT_METHOD_HIGHLIGHT,
          new InputMethodHighlight(h.selected(),h.state(),h.variation()),h.start(),h.end());
      return result.getIterator();
    }
  }
  public static String encodeSnapshot(Snapshot s) {
    return encode(out->{out.writeInt(1);out.writeBoolean(s.enabled());Rectangle r=s.caret();
      out.writeInt(r.x);out.writeInt(r.y);out.writeInt(r.width);out.writeInt(r.height);
      string(out,s.committed());string(out,s.selected());out.writeInt(s.insertPosition());string(out,s.locale());});
  }
  public static Snapshot decodeSnapshot(String payload) {
    try(DataInputStream in=input(payload)) {
      version(in);
      Snapshot s=new Snapshot(in.readBoolean(),new Rectangle(in.readInt(),in.readInt(),in.readInt(),in.readInt()),
          string(in),string(in),in.readInt(),string(in));
      end(in); return s;
    } catch(IOException | IllegalArgumentException e) { throw malformed(e); }
  }
  public static String encodeText(Text t) {
    String result=encode(out->{out.writeInt(1);string(out,t.text());out.writeInt(t.committed());hit(out,t.caret());hit(out,t.visible());
      out.writeInt(t.highlights().size());for(Highlight h:t.highlights()){out.writeInt(h.start());out.writeInt(h.end());out.writeBoolean(h.selected());out.writeInt(h.state());out.writeInt(h.variation());}});
    // experimental-1 Event.text uses writeUTF; reject before queue insertion, never truncate.
    if(result.length()>65535) throw malformed();
    return result;
  }
  public static Text decodeText(String payload) {
    try(DataInputStream in=input(payload)) {
      version(in);String text=string(in);int committed=in.readInt();TextHitInfo caret=hit(in),visible=hit(in);
      int count=in.readInt();if(count<0 || count>text.length() || count>in.available()/17) throw malformed();
      List<Highlight> ranges=new ArrayList<>();
      for(int i=0;i<count;i++) ranges.add(new Highlight(in.readInt(),in.readInt(),in.readBoolean(),in.readInt(),in.readInt()));
      end(in); return new Text(text,committed,caret,visible,ranges);
    } catch(IOException | IllegalArgumentException e) { throw malformed(e); }
  }
  private static void hit(DataOutputStream out,TextHitInfo h)throws IOException {out.writeBoolean(h!=null);if(h!=null){out.writeInt(h.getCharIndex());out.writeBoolean(h.isLeadingEdge());}}
  private static TextHitInfo hit(DataInputStream in)throws IOException {
    if(!in.readBoolean()) return null;
    int index=in.readInt(); boolean leading=in.readBoolean();
    return leading ? TextHitInfo.leading(index) : TextHitInfo.trailing(index);
  }
  private interface Encoder {void write(DataOutputStream out)throws IOException;}
  private static String encode(Encoder encoder) {
    try {ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(bytes)){encoder.write(out);}
      if(bytes.size()>GraphicsWire.MAX_BYTES)throw malformed();return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }catch(IOException e){throw malformed(e);}
  }
  private static DataInputStream input(String payload) {
    if(payload==null || payload.length()>(GraphicsWire.MAX_BYTES+2L)/3*4)throw malformed();
    byte[] bytes=Base64.getDecoder().decode(payload);if(bytes.length>GraphicsWire.MAX_BYTES)throw malformed();
    return new DataInputStream(new ByteArrayInputStream(bytes));
  }
  private static void string(DataOutputStream out,String s)throws IOException {byte[] b=s.getBytes(StandardCharsets.UTF_8);if(b.length>GraphicsWire.MAX_BYTES)throw malformed();out.writeInt(b.length);out.write(b);}
  private static String string(DataInputStream in)throws IOException {int size=in.readInt();if(size<0 || size>in.available())throw malformed();byte[] bytes=in.readNBytes(size);
    try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}catch(java.nio.charset.CharacterCodingException e){throw malformed(e);}}
  private static void version(DataInputStream in)throws IOException {if(in.readInt()!=1)throw malformed();}
  private static void end(DataInputStream in)throws IOException {if(in.read()!=-1)throw malformed();}
  private static IllegalArgumentException malformed() {return new IllegalArgumentException("GRAPHICS_MALFORMED_PAYLOAD");}
  private static IllegalArgumentException malformed(Throwable e) {return new IllegalArgumentException("GRAPHICS_MALFORMED_PAYLOAD",e);}
}
