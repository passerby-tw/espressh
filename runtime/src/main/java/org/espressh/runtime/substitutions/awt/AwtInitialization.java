package org.espressh.runtime.substitutions.awt;

import com.oracle.truffle.api.nodes.Node.Child;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.substitutions.JavaSubstitution;
import com.oracle.truffle.espresso.substitutions.LanguageFilter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;

/** The finite 39-entry production registry, without loader hooks. */
public final class AwtInitialization extends JavaSubstitution {
  private enum Handler { NO_OP, COMPONENT_METADATA }
  private record Entry(String owner, String name, Handler handler) {
    String key() { return owner + "." + name + "()V"; }
    String ownerDescriptor() { return "L" + owner.replace('.', '/') + ";"; }
  }

  // The original 30-entry ordering is fixed. Toolkit.initIDs is an empty native
  // body; Toolkit.loadLibraries and the three NativeLibLoader helpers stay Java.
  private static final List<Entry> ENTRIES = List.of(
      new Entry("java.awt.Toolkit", "initIDs", Handler.NO_OP),
      new Entry("java.awt.AWTEvent", "initIDs", Handler.NO_OP),
      new Entry("java.awt.Component", "initIDs", Handler.COMPONENT_METADATA),
      new Entry("java.awt.event.InputEvent", "initIDs", Handler.NO_OP),
      new Entry("java.awt.Checkbox", "initIDs", Handler.NO_OP),
      new Entry("java.awt.CheckboxMenuItem", "initIDs", Handler.NO_OP),
      new Entry("java.awt.Choice", "initIDs", Handler.NO_OP),
      new Entry("java.awt.Container", "initIDs", Handler.NO_OP),
      new Entry("java.awt.Cursor", "initIDs", Handler.NO_OP),
      new Entry("java.awt.Dialog", "initIDs", Handler.NO_OP),
      new Entry("java.awt.Dimension", "initIDs", Handler.NO_OP),
      new Entry("java.awt.Event", "initIDs", Handler.NO_OP),
      new Entry("java.awt.Font", "initIDs", Handler.NO_OP),
      new Entry("java.awt.FontMetrics", "initIDs", Handler.NO_OP),
      new Entry("java.awt.Frame", "initIDs", Handler.NO_OP),
      new Entry("java.awt.Insets", "initIDs", Handler.NO_OP),
      new Entry("java.awt.Label", "initIDs", Handler.NO_OP),
      new Entry("java.awt.Menu", "initIDs", Handler.NO_OP),
      new Entry("java.awt.MenuBar", "initIDs", Handler.NO_OP),
      new Entry("java.awt.MenuItem", "initIDs", Handler.NO_OP),
      new Entry("java.awt.ScrollPane", "initIDs", Handler.NO_OP),
      new Entry("java.awt.ScrollPaneAdjustable", "initIDs", Handler.NO_OP),
      new Entry("java.awt.Scrollbar", "initIDs", Handler.NO_OP),
      new Entry("java.awt.TextArea", "initIDs", Handler.NO_OP),
      new Entry("java.awt.TrayIcon", "initIDs", Handler.NO_OP),
      new Entry("java.awt.Window", "initIDs", Handler.NO_OP),
      new Entry("java.awt.event.KeyEvent", "initIDs", Handler.NO_OP),
      new Entry("java.awt.event.MouseEvent", "initIDs", Handler.NO_OP),
      new Entry("sun.awt.FontDescriptor", "initIDs", Handler.NO_OP),
      new Entry("sun.awt.PlatformFont", "initIDs", Handler.NO_OP));

  private static final AtomicIntegerArray HITS = new AtomicIntegerArray(ENTRIES.size());
  private static final List<Factory> CORE_FACTORIES = coreFactories();
  public static final List<Factory> FACTORIES = allFactories();
  private final int index;
  @Child private JavaSubstitution delegate;

  private static List<Factory> coreFactories() {
    var result = new ArrayList<Factory>(ENTRIES.size());
    for (int index = 0; index < ENTRIES.size(); index++) {
      Entry entry = ENTRIES.get(index);
      final int entryIndex = index;
      result.add(new Factory(entry.name(), entry.ownerDescriptor(), "V", new String[0],
          false, LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null,
          () -> new AwtInitialization(entryIndex)));
    }
    return List.copyOf(result);
  }

  private static List<Factory> allFactories() {
    var result = new ArrayList<Factory>(39);
    result.addAll(CORE_FACTORIES);
    result.add(DisposerInitialization.INIT_IDS);
    result.addAll(ImageDataInitialization.FACTORIES);
    result.add(SplashCloseSubstitution.FACTORY);
    var keys = new HashSet<String>(entryKeys());
    if (result.size() != 39 || keys.size() != 39) {
      throw new IllegalStateException("AWT initialization requires 39 distinct exact entries");
    }
    return List.copyOf(result);
  }

  private AwtInitialization(int index) {
    super(CORE_FACTORIES.get(index));
    this.index = index;
    delegate = ENTRIES.get(index).handler() == Handler.COMPONENT_METADATA
        ? new ComponentInitialization() : null;
  }

  @Override public JavaSubstitution split() { return new AwtInitialization(index); }

  @Override public Object invoke(Object[] arguments) {
    Entry entry = ENTRIES.get(index);
    if (delegate == null) AwtRuntimeGuard.requireSupported(EspressoContext.get(null));
    AwtRuntimeGuard.trace("CENTRAL_AWT_HIT=" + entry.key() + " COUNT="
        + HITS.incrementAndGet(index) + " HANDLER=" + entry.handler());
    // Empty native only, or the exact Component metadata delegate. No Java
    // initializer, loader, accessor, headless branch, or Guest failure is skipped.
    return delegate == null ? null : delegate.invoke(arguments);
  }

  public static List<String> entryKeys() {
    var keys = new ArrayList<String>(39);
    for (Entry entry : ENTRIES) keys.add(entry.key());
    keys.add("sun.java2d.Disposer.initIDs()V");
    keys.addAll(ImageDataInitialization.entryKeys());
    keys.add("sun.awt.SunToolkit.closeSplashScreen()V");
    return List.copyOf(keys);
  }

  /** Dispatch diagnostics only, not counts of completed Guest initializers. */
  public static int hitCount(String owner, String name) {
    for (int i = 0; i < ENTRIES.size(); i++) {
      Entry entry = ENTRIES.get(i);
      if (entry.owner().equals(owner) && entry.name().equals(name)) return HITS.get(i);
    }
    if (name.equals("initIDs")) {
      if (owner.equals("sun.java2d.Disposer")) return DisposerInitialization.hitCount();
      if (ImageDataInitialization.entryKeys().contains(owner + ".initIDs()V")) {
        return ImageDataInitialization.hitCount(owner);
      }
    }
    if (owner.equals("sun.awt.SunToolkit") && name.equals("closeSplashScreen")) {
      return SplashCloseSubstitution.hitCount();
    }
    throw new IllegalArgumentException("Unregistered AWT entry: " + owner + "." + name);
  }

  public static String summary() {
    var counts = new ArrayList<String>(39);
    for (String key : entryKeys()) {
      int methodDot = key.lastIndexOf('.', key.indexOf('('));
      String owner = key.substring(0, methodDot);
      String name = key.substring(methodDot + 1, key.indexOf('('));
      counts.add(key + "=" + hitCount(owner, name));
    }
    return String.join(",", counts);
  }
}
