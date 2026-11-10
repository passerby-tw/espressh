package org.espressh.bootstrap.awt;

/** Window publication around the Guest service adapter; never constructs a Guest renderer. */
final class GuestWindowGraphics extends java.awt.Graphics2D {
  private final java.awt.Graphics2D delegate;
  private static final class Publication {
    final Runnable publish;
    int references = 1;
    boolean failed;
    Publication(Runnable publish) { this.publish = publish; }
  }
  private final Publication publication;
  private boolean disposed;
  GuestWindowGraphics(java.awt.Graphics2D delegate, Runnable publish) { this(delegate, new Publication(publish)); }
  private GuestWindowGraphics(java.awt.Graphics2D delegate, Publication publication) { this.delegate = delegate; this.publication = publication; }
  public java.awt.Graphics create() {
    if (disposed) throw new IllegalStateException("AWT_GRAPHICS_DISPOSED");
    java.awt.Graphics2D copy = (java.awt.Graphics2D) delegate.create();
    publication.references++;
    return new GuestWindowGraphics(copy, publication);
  }
  public void dispose() {
    if (!disposed) {
      disposed = true; delegate.dispose();
      if (--publication.references == 0 && !publication.failed) publication.publish.run();
    }
  }
  void abortPublication() {publication.failed=true;}
  public void drawChars(char[] data, int offset, int length, int x, int y) { delegate.drawChars(data,offset,length,x,y); }
  public void drawBytes(byte[] data, int offset, int length, int x, int y) { delegate.drawBytes(data,offset,length,x,y); }
  public void translate(int p0, int p1) { delegate.translate(p0, p1); }
  public java.awt.Color getColor() { return delegate.getColor(); }
  public void setColor(java.awt.Color p0) { delegate.setColor(p0); }
  public void setPaintMode() { delegate.setPaintMode(); }
  public void setXORMode(java.awt.Color p0) { delegate.setXORMode(p0); }
  public java.awt.Font getFont() { return delegate.getFont(); }
  public void setFont(java.awt.Font p0) { delegate.setFont(p0); }
  public java.awt.FontMetrics getFontMetrics(java.awt.Font p0) { return delegate.getFontMetrics(p0); }
  public java.awt.Rectangle getClipBounds() { return delegate.getClipBounds(); }
  public void clipRect(int p0, int p1, int p2, int p3) { delegate.clipRect(p0, p1, p2, p3); }
  public void setClip(int p0, int p1, int p2, int p3) { delegate.setClip(p0, p1, p2, p3); }
  public java.awt.Shape getClip() { return delegate.getClip(); }
  public void setClip(java.awt.Shape p0) { delegate.setClip(p0); }
  public void copyArea(int p0, int p1, int p2, int p3, int p4, int p5) { delegate.copyArea(p0, p1, p2, p3, p4, p5); }
  public void drawLine(int p0, int p1, int p2, int p3) { delegate.drawLine(p0, p1, p2, p3); }
  public void drawRect(int p0, int p1, int p2, int p3) { delegate.drawRect(p0, p1, p2, p3); }
  public void fillRect(int p0, int p1, int p2, int p3) { delegate.fillRect(p0,p1,p2,p3); }
  public void clearRect(int p0, int p1, int p2, int p3) { delegate.clearRect(p0, p1, p2, p3); }
  public void drawRoundRect(int p0, int p1, int p2, int p3, int p4, int p5) { delegate.drawRoundRect(p0, p1, p2, p3, p4, p5); }
  public void fillRoundRect(int p0, int p1, int p2, int p3, int p4, int p5) { delegate.fillRoundRect(p0, p1, p2, p3, p4, p5); }
  public void drawOval(int p0, int p1, int p2, int p3) { delegate.drawOval(p0, p1, p2, p3); }
  public void fillOval(int p0, int p1, int p2, int p3) { delegate.fillOval(p0, p1, p2, p3); }
  public void drawArc(int p0, int p1, int p2, int p3, int p4, int p5) { delegate.drawArc(p0, p1, p2, p3, p4, p5); }
  public void fillArc(int p0, int p1, int p2, int p3, int p4, int p5) { delegate.fillArc(p0, p1, p2, p3, p4, p5); }
  public void drawPolyline(int[] p0, int[] p1, int p2) { delegate.drawPolyline(p0, p1, p2); }
  public void drawPolygon(int[] p0, int[] p1, int p2) { delegate.drawPolygon(p0, p1, p2); }
  public void fillPolygon(int[] p0, int[] p1, int p2) { delegate.fillPolygon(p0, p1, p2); }
  public void drawString(java.lang.String p0, int p1, int p2) { delegate.drawString(p0, p1, p2); }
  public void drawString(java.text.AttributedCharacterIterator p0, int p1, int p2) { delegate.drawString(p0, p1, p2); }
  public boolean drawImage(java.awt.Image p0, int p1, int p2, java.awt.image.ImageObserver p3) {
    java.awt.Image image=GuestVolatileImage.source(p0);
    return delegate.drawImage(image,p1,p2,p3);
  }
  public boolean drawImage(java.awt.Image p0, int p1, int p2, int p3, int p4, java.awt.image.ImageObserver p5) { return delegate.drawImage(GuestVolatileImage.source(p0), p1, p2, p3, p4, p5); }
  public boolean drawImage(java.awt.Image p0, int p1, int p2, java.awt.Color p3, java.awt.image.ImageObserver p4) { return delegate.drawImage(GuestVolatileImage.source(p0), p1, p2, p3, p4); }
  public boolean drawImage(java.awt.Image p0, int p1, int p2, int p3, int p4, java.awt.Color p5, java.awt.image.ImageObserver p6) { return delegate.drawImage(GuestVolatileImage.source(p0), p1, p2, p3, p4, p5, p6); }
  public boolean drawImage(java.awt.Image p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, java.awt.image.ImageObserver p9) {
    java.awt.Image image=GuestVolatileImage.source(p0);
    return delegate.drawImage(image,p1,p2,p3,p4,p5,p6,p7,p8,p9);
  }
  public boolean drawImage(java.awt.Image p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, java.awt.Color p9, java.awt.image.ImageObserver p10) { return delegate.drawImage(GuestVolatileImage.source(p0), p1, p2, p3, p4, p5, p6, p7, p8, p9, p10); }
  public void draw(java.awt.Shape p0) { delegate.draw(p0); }
  public boolean drawImage(java.awt.Image p0, java.awt.geom.AffineTransform p1, java.awt.image.ImageObserver p2) { return delegate.drawImage(GuestVolatileImage.source(p0), p1, p2); }
  public void drawImage(java.awt.image.BufferedImage p0, java.awt.image.BufferedImageOp p1, int p2, int p3) { delegate.drawImage(p0, p1, p2, p3); }
  public void drawRenderedImage(java.awt.image.RenderedImage p0, java.awt.geom.AffineTransform p1) { delegate.drawRenderedImage(p0, p1); }
  public void drawRenderableImage(java.awt.image.renderable.RenderableImage p0, java.awt.geom.AffineTransform p1) { delegate.drawRenderableImage(p0, p1); }
  public void drawString(java.lang.String p0, float p1, float p2) { delegate.drawString(p0, p1, p2); }
  public void drawString(java.text.AttributedCharacterIterator p0, float p1, float p2) { delegate.drawString(p0, p1, p2); }
  public void drawGlyphVector(java.awt.font.GlyphVector p0, float p1, float p2) { delegate.drawGlyphVector(p0, p1, p2); }
  public void fill(java.awt.Shape p0) { delegate.fill(p0); }
  public boolean hit(java.awt.Rectangle p0, java.awt.Shape p1, boolean p2) { return delegate.hit(p0, p1, p2); }
  public java.awt.GraphicsConfiguration getDeviceConfiguration() { return delegate.getDeviceConfiguration(); }
  public void setComposite(java.awt.Composite p0) { delegate.setComposite(p0); }
  public void setPaint(java.awt.Paint p0) { delegate.setPaint(p0); }
  public void setStroke(java.awt.Stroke p0) { delegate.setStroke(p0); }
  public void setRenderingHint(java.awt.RenderingHints.Key p0, java.lang.Object p1) { delegate.setRenderingHint(p0, p1); }
  public java.lang.Object getRenderingHint(java.awt.RenderingHints.Key p0) { return delegate.getRenderingHint(p0); }
  public void setRenderingHints(java.util.Map<?, ?> p0) { delegate.setRenderingHints(p0); }
  public void addRenderingHints(java.util.Map<?, ?> p0) { delegate.addRenderingHints(p0); }
  public java.awt.RenderingHints getRenderingHints() { return delegate.getRenderingHints(); }
  public void translate(double p0, double p1) { delegate.translate(p0, p1); }
  public void rotate(double p0) { delegate.rotate(p0); }
  public void rotate(double p0, double p1, double p2) { delegate.rotate(p0, p1, p2); }
  public void scale(double p0, double p1) { delegate.scale(p0, p1); }
  public void shear(double p0, double p1) { delegate.shear(p0, p1); }
  public void transform(java.awt.geom.AffineTransform p0) { delegate.transform(p0); }
  public void setTransform(java.awt.geom.AffineTransform p0) { delegate.setTransform(p0); }
  public java.awt.geom.AffineTransform getTransform() { return delegate.getTransform(); }
  public java.awt.Paint getPaint() { return delegate.getPaint(); }
  public java.awt.Composite getComposite() { return delegate.getComposite(); }
  public void setBackground(java.awt.Color p0) { delegate.setBackground(p0); }
  public java.awt.Color getBackground() { return delegate.getBackground(); }
  public java.awt.Stroke getStroke() { return delegate.getStroke(); }
  public void clip(java.awt.Shape p0) { delegate.clip(p0); }
  public java.awt.font.FontRenderContext getFontRenderContext() { return delegate.getFontRenderContext(); }
}
