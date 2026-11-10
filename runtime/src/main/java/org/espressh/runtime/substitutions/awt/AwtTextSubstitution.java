package org.espressh.runtime.substitutions.awt;

import com.oracle.truffle.espresso.descriptors.EspressoSymbols;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.substitutions.*;

/** The six approved text overloads; forwards only Guest objects to a Guest helper. */
public final class AwtTextSubstitution extends JavaSubstitution {
  private static final String GRAPHICS = "Lsun/java2d/SunGraphics2D;";
  private static final String[][] PARAMETERS = {
    {"Ljava/lang/String;", "I", "I"}, {"Ljava/lang/String;", "F", "F"},
    {"Ljava/text/AttributedCharacterIterator;", "I", "I"}, {"Ljava/text/AttributedCharacterIterator;", "F", "F"},
    {"[C", "I", "I", "I", "I"}, {"[B", "I", "I", "I", "I"}
  };
  public static final Factory[] FACTORIES = createFactories();
  private final int kind;
  private static Factory[] createFactories() {
    Factory[] factories = new Factory[PARAMETERS.length];
    EspressoSymbols.SYMBOLS.putType("Lorg/espressh/bootstrap/awt/GuestText;");
    EspressoSymbols.SYMBOLS.putName("draw");
    EspressoSymbols.SYMBOLS.putName("getDestination");
    AwtImageGraphicsSubstitution.signature("Ljava/lang/Object;");
    for (int i = 0; i < factories.length; i++) {
      int index = i;
      String[] types = new String[PARAMETERS[i].length + 1];
      types[0] = GRAPHICS; System.arraycopy(PARAMETERS[i],0,types,1,PARAMETERS[i].length);
      String[] helper = new String[PARAMETERS[i].length + 2];
      helper[0] = "Ljava/awt/Graphics2D;"; helper[1] = "Ljava/lang/Object;";
      System.arraycopy(PARAMETERS[i],0,helper,2,PARAMETERS[i].length);
      AwtImageGraphicsSubstitution.signature("V", helper);
      factories[i] = new Factory(i < 4 ? "drawString" : i == 4 ? "drawChars" : "drawBytes",
          GRAPHICS,"V",types,true,LanguageFilter.AlwaysValid.INSTANCE,(byte)0,null,
          () -> new AwtTextSubstitution(index));
    }
    return factories;
  }
  private AwtTextSubstitution(int kind) { super(FACTORIES[kind]); this.kind = kind; }
  public JavaSubstitution split() { return new AwtTextSubstitution(kind); }
  public Object invoke(Object[] args) {
    var meta = EspressoContext.get(null).getMeta();
    ObjectKlass graphics = (ObjectKlass)meta.loadKlassOrFail(EspressoSymbols.SYMBOLS.putType(GRAPHICS),StaticObject.NULL,StaticObject.NULL);
    StaticObject data = null;
    for (var field : graphics.getDeclaredFields()) if (field.getNameAsString().equals("surfaceData")) data = (StaticObject)field.getObject((StaticObject)args[0]);
    if (data == null || StaticObject.isNull(data)) throw new IllegalStateException("Pinned graphics surface missing");
    ObjectKlass surface = (ObjectKlass)meta.loadKlassOrFail(EspressoSymbols.SYMBOLS.putType("Lsun/java2d/SurfaceData;"),StaticObject.NULL,StaticObject.NULL);
    Object destination = surface.requireDeclaredMethod(EspressoSymbols.SYMBOLS.putName("getDestination"),AwtImageGraphicsSubstitution.signature("Ljava/lang/Object;")).invokeDirectVirtual(data);
    StaticObject loader = (StaticObject)meta.java_lang_ClassLoader_getSystemClassLoader.invokeDirectStatic();
    ObjectKlass helper = (ObjectKlass)meta.loadKlassOrFail(EspressoSymbols.SYMBOLS.putType("Lorg/espressh/bootstrap/awt/GuestText;"),loader,StaticObject.NULL);
    helper.safeInitialize();
    String[] types = new String[PARAMETERS[kind].length + 2];
    types[0] = "Ljava/awt/Graphics2D;"; types[1] = "Ljava/lang/Object;";
    System.arraycopy(PARAMETERS[kind],0,types,2,PARAMETERS[kind].length);
    Object[] values = new Object[args.length+1]; values[0]=args[0]; values[1]=destination;
    System.arraycopy(args,1,values,2,args.length-1);
    helper.requireDeclaredMethod(EspressoSymbols.SYMBOLS.putName("draw"),AwtImageGraphicsSubstitution.signature("V",types)).invokeDirectStatic(values);
    return null;
  }
}
