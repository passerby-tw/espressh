package org.espressh.bootstrap.awt;

import java.awt.*;
import java.awt.image.*;

/** Unaccelerated Guest-owned Swing back buffer. Never allocates a platform surface. */
final class GuestVolatileImage extends VolatileImage {
  private final GraphicsConfiguration configuration;
  private BufferedImage raster;
  private final int width, height;
  GuestVolatileImage(GraphicsConfiguration configuration, int width, int height, int transparency) {
    if(width<=0 || height<=0)throw new IllegalArgumentException("Invalid image dimensions");
    this.configuration=configuration;this.width=width;this.height=height;this.transparency=transparency;
    restore();
  }
  private void restore() {raster=configuration.createCompatibleImage(width,height,transparency);}
  BufferedImage raster() {if(raster==null)restore();return raster;}
  static Image source(Image image) {return image instanceof GuestVolatileImage own?own.raster():image;}
  public BufferedImage getSnapshot() {
    BufferedImage copy=configuration.createCompatibleImage(width,height,transparency);
    Graphics2D graphics=copy.createGraphics();
    try {graphics.setComposite(AlphaComposite.Src);graphics.drawImage(raster(),0,0,null);}
    finally {graphics.dispose();}
    return copy;
  }
  public int getWidth() {return width;}
  public int getHeight() {return height;}
  public int getWidth(ImageObserver observer) {return width;}
  public int getHeight(ImageObserver observer) {return height;}
  public Object getProperty(String name,ImageObserver observer) {return raster().getProperty(name,observer);}
  public Graphics2D createGraphics() {return new GuestServiceGraphics(raster(),configuration);}
  public int validate(GraphicsConfiguration proposed) {
    if(proposed!=null && proposed!=configuration)return IMAGE_INCOMPATIBLE;
    if(raster==null){restore();return IMAGE_RESTORED;}
    return IMAGE_OK;
  }
  public boolean contentsLost() {return raster==null;}
  public ImageCapabilities getCapabilities() {return new ImageCapabilities(false);}
  @Override public void flush() {if(raster!=null)raster.flush();raster=null;}
}
