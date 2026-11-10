package org.espressh.bootstrap.awt;

import java.awt.Transparency;
import java.awt.image.*;
import java.lang.ref.Cleaner;
import org.espressh.bootstrap.graphics.*;

/** Adapter-owned Guest image over revocable Graphics Service pixels. */
final class GuestSharedRaster extends BufferedImage {
  private static final Cleaner CLEANER = Cleaner.create();
  private final Pixels pixels;
  private final Cleaner.Cleanable cleanup;
  private long sequence;
  private static final class Resource implements Runnable {
    final GraphicsBridge bridge;
    final long handle;
    private boolean released;
    Resource(GraphicsBridge bridge, long handle) { this.bridge=bridge;this.handle=handle; }
    public synchronized void run() {
      if (released) return;
      released=true;
      // Context cleanup may have already revoked this image's service.
      try { bridge.release(handle); } catch (IllegalStateException alreadyClosed) { }
    }
  }
  private static final class Pixels extends DataBuffer {
    final GraphicsBridge.PixelView view;
    final Resource resource;
    final boolean opaque;
    Pixels(int width,int height,boolean opaque) {
      super(TYPE_INT,Math.multiplyExact(width,height));this.opaque=opaque;
      GraphicsBridge bridge=GuestWindowPeer.bridge();
      long handle=bridge.createSurface(width,height);
      resource=new Resource(bridge,handle);
      try {
        if (opaque) bridge.submit(handle,1,1,new GraphicsWire.Batch().targetOpaque(true).color(0xff000000).fill(0,0,width,height));
        view=bridge.sharedPixels(handle,1);
      }
      catch(RuntimeException|Error failure){resource.run();throw failure;}
    }
    public int getElem(int bank,int index) { if(bank!=0)throw new IndexOutOfBoundsException();return view.read(index); }
    public void setElem(int bank,int index,int value) { if(bank!=0)throw new IndexOutOfBoundsException();view.write(index,opaque?value|0xff000000:value); }
  }
  static GuestSharedRaster create(int width,int height,int transparency) {
    if(width<1||height<1)throw new IllegalArgumentException("Invalid image dimensions");
    if(transparency!=Transparency.OPAQUE&&transparency!=Transparency.TRANSLUCENT)
      throw new UnsupportedOperationException("AWT_SHARED_RASTER_UNSUPPORTED_TRANSPARENCY");
    Pixels pixels=new Pixels(width,height,transparency==Transparency.OPAQUE);
    try{return new GuestSharedRaster(width,height,pixels);}
    catch(RuntimeException|Error failure){pixels.resource.run();throw failure;}
  }
  private GuestSharedRaster(int width,int height,Pixels pixels) {
    super(new DirectColorModel(pixels.opaque?24:32,0xff0000,0xff00,0xff,pixels.opaque?0:0xff000000),
      Raster.createWritableRaster(new SinglePixelPackedSampleModel(DataBuffer.TYPE_INT,width,height,
        pixels.opaque?new int[]{0xff0000,0xff00,0xff}:new int[]{0xff0000,0xff00,0xff,0xff000000}),pixels,null),false,null);
    this.pixels=pixels;cleanup=CLEANER.register(this,pixels.resource);
    sequence=pixels.opaque?1:0;
  }
  @Override public java.awt.Graphics2D createGraphics() {
    return new GuestServiceGraphics(this);
  }
  long handle(){return pixels.resource.handle;}
  synchronized void submit(GraphicsWire.Batch batch){pixels.resource.bridge.submit(handle(),1,++sequence,batch);}
  @Override public void flush(){try{cleanup.clean();}finally{super.flush();}}
}
