package org.espressh.runtime.substitutions.awt;

import com.oracle.truffle.espresso.substitutions.*;

/** Swing overrides Component metrics; retain the same Guest metrics backend. */
public final class AwtSwingFontSubstitution extends JavaSubstitution {
  public static final Factory FACTORY = new Factory(
      "getFontMetrics", "Ljavax/swing/JComponent;", "Ljava/awt/FontMetrics;",
      new String[]{"Ljavax/swing/JComponent;", "Ljava/awt/Font;"}, true,
      LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null, AwtSwingFontSubstitution::new);

  private final AwtFontSubstitution metrics = new AwtFontSubstitution(false);
  public AwtSwingFontSubstitution() {super(FACTORY);}
  public JavaSubstitution split() {return new AwtSwingFontSubstitution();}
  public Object invoke(Object[] args) {return metrics.invoke(args);}
}
