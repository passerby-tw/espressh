package org.espressh.bootstrap.awt;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.ColorModel;
import org.espressh.bootstrap.graphics.GraphicsBridge;

/** Copied default-display values; no platform object leaves Host. */
final class GuestDisplay extends GraphicsDevice {
  final Rectangle bounds;
  final int dpi;
  final double scaleX, scaleY;
  private final GraphicsConfiguration configuration = new Configuration();

  GuestDisplay() {
    try {
      String[] values = new GraphicsBridge().defaultDisplayInfo().split(";");
      if (values.length != 8 || !values[0].equals("1")) throw new IllegalArgumentException("Invalid display snapshot");
      bounds = new Rectangle(Integer.parseInt(values[1]), Integer.parseInt(values[2]),
          Integer.parseInt(values[3]), Integer.parseInt(values[4]));
      dpi = Integer.parseInt(values[5]);
      scaleX = Double.parseDouble(values[6]); scaleY = Double.parseDouble(values[7]);
      if (bounds.width <= 0 || bounds.height <= 0 || dpi <= 0 || !Double.isFinite(scaleX)
          || !Double.isFinite(scaleY) || scaleX <= 0 || scaleY <= 0) throw new IllegalArgumentException("Invalid display snapshot");
    } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
  }
  public int getType() { return TYPE_RASTER_SCREEN; }
  public String getIDstring() { return "GuestDefaultDisplay"; }
  public GraphicsConfiguration[] getConfigurations() { return new GraphicsConfiguration[]{configuration}; }
  public GraphicsConfiguration getDefaultConfiguration() { return configuration; }
  private final class Configuration extends GraphicsConfiguration {
    public GraphicsDevice getDevice() { return GuestDisplay.this; }
    public ColorModel getColorModel() { return ColorModel.getRGBdefault(); }
    public ColorModel getColorModel(int transparency) {
      return switch (transparency) {
        case Transparency.OPAQUE -> new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB).getColorModel();
        case Transparency.TRANSLUCENT -> ColorModel.getRGBdefault();
        default -> throw new UnsupportedOperationException("AWT_COLOR_MODEL_UNSUPPORTED: " + transparency);
      };
    }
    public AffineTransform getDefaultTransform() { return AffineTransform.getScaleInstance(scaleX, scaleY); }
    public AffineTransform getNormalizingTransform() { return AffineTransform.getScaleInstance(dpi / 72.0 / scaleX, dpi / 72.0 / scaleY); }
    public Rectangle getBounds() { return new Rectangle(bounds); }
    public java.awt.image.BufferedImage createCompatibleImage(int width,int height,int transparency) {
      return GuestSharedRaster.create(width,height,transparency);
    }
    public java.awt.image.BufferedImage createCompatibleImage(int width,int height) {
      return createCompatibleImage(width,height,Transparency.OPAQUE);
    }
    public java.awt.image.VolatileImage createCompatibleVolatileImage(int width,int height,int transparency) {
      getColorModel(transparency); // Reject unsupported transparency before allocating.
      return new GuestVolatileImage(this,width,height,transparency);
    }
    public java.awt.image.VolatileImage createCompatibleVolatileImage(int width,int height) {
      return createCompatibleVolatileImage(width,height,Transparency.OPAQUE);
    }
    public java.awt.image.VolatileImage createCompatibleVolatileImage(int width,int height,ImageCapabilities caps,int transparency) throws AWTException {
      if(caps!=null && caps.isAccelerated())throw new AWTException("AWT_ACCELERATED_IMAGE_UNSUPPORTED");
      return createCompatibleVolatileImage(width,height,transparency);
    }
    public java.awt.image.VolatileImage createCompatibleVolatileImage(int width,int height,ImageCapabilities caps) throws AWTException {
      return createCompatibleVolatileImage(width,height,caps,Transparency.OPAQUE);
    }
  }
}
