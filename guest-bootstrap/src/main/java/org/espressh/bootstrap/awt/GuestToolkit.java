package org.espressh.bootstrap.awt;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.font.TextAttribute;
import java.awt.im.InputMethodHighlight;
import java.awt.image.*;
import java.net.URL;
import java.util.Map;
import java.util.Properties;

/** Guest-owned Toolkit and EventQueue; unconnected operations fail explicitly. */
public final class GuestToolkit extends Toolkit {
  private final EventQueue queue;
  public GuestToolkit(EventQueue queue) {this.queue=java.util.Objects.requireNonNull(queue);}
  private static UnsupportedOperationException unsupported() {
    return new UnsupportedOperationException("AWT_TOOLKIT_OPERATION_NOT_CONNECTED");
  }

  public Dimension getScreenSize() { return new GuestDisplay().bounds.getSize(); }
  public int getScreenResolution() { return new GuestDisplay().dpi; }
  public ColorModel getColorModel() { return ColorModel.getRGBdefault(); }
  public String[] getFontList() { throw unsupported(); }
  public FontMetrics getFontMetrics(Font font) { return ServiceFontMetrics.forFont(font); }
  public void sync() { throw unsupported(); }
  public Image getImage(String filename) { throw unsupported(); }
  public Image getImage(URL url) { throw unsupported(); }
  public Image createImage(String filename) { throw unsupported(); }
  public Image createImage(URL url) { throw unsupported(); }
  public Image createImage(ImageProducer producer) { throw unsupported(); }
  public Image createImage(byte[] data, int offset, int length) { throw unsupported(); }
  public boolean prepareImage(Image image, int width, int height, ImageObserver observer) { throw unsupported(); }
  public int checkImage(Image image, int width, int height, ImageObserver observer) { throw unsupported(); }
  public PrintJob getPrintJob(Frame frame, String title, Properties properties) { throw unsupported(); }
  public void beep() { throw unsupported(); }
  public Clipboard getSystemClipboard() { throw unsupported(); }
  protected synchronized EventQueue getSystemEventQueueImpl() {
    return queue;
  }
  public boolean isModalityTypeSupported(Dialog.ModalityType type) { return false; }
  public boolean isModalExclusionTypeSupported(Dialog.ModalExclusionType type) { return false; }
  public Map<TextAttribute, ?> mapInputMethodHighlight(InputMethodHighlight highlight) { throw unsupported(); }
}
