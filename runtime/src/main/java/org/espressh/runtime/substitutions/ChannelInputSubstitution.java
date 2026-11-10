package org.espressh.runtime.substitutions;

import com.oracle.truffle.espresso.classfile.descriptors.Name;
import com.oracle.truffle.espresso.classfile.descriptors.Signature;
import com.oracle.truffle.espresso.classfile.descriptors.Symbol;
import com.oracle.truffle.espresso.descriptors.EspressoSymbols;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.substitutions.JavaSubstitution;
import com.oracle.truffle.espresso.substitutions.LanguageFilter;

/** Preserve Guest readAllBytes without the pinned no-native isOther0 fast path. */
public final class ChannelInputSubstitution extends JavaSubstitution {
    private static final Symbol<Name> READ_N_BYTES = EspressoSymbols.SYMBOLS.putName("readNBytes");
    private static final Symbol<Signature> READ_N_BYTES_SIGNATURE = EspressoSymbols.SYMBOLS.putSignature(
        EspressoSymbols.SYMBOLS.putType("[B"), EspressoSymbols.SYMBOLS.putType("I"));

    public static final Factory READ_ALL_BYTES = new Factory(
        "readAllBytes", "Lsun/nio/ch/ChannelInputStream;", "[B",
        new String[]{"Lsun/nio/ch/ChannelInputStream;"}, true,
        LanguageFilter.AlwaysValid.INSTANCE, (byte)0, null,
        ChannelInputSubstitution::new);

    public ChannelInputSubstitution() { super(READ_ALL_BYTES); }

    @Override public JavaSubstitution split() { return new ChannelInputSubstitution(); }

    @Override public Object invoke(Object[] args) {
        var meta = EspressoContext.get(null).getMeta();
        // Invoke the Guest superclass body directly: a virtual readNBytes call
        // would re-enter ChannelInputStream's unsupported metadata fast path.
        // The generic implementation still dispatches ordinary channel reads,
        // preserving byte content, EOF and IOException in the Guest.
        return meta.java_io_InputStream.requireDeclaredMethod(READ_N_BYTES, READ_N_BYTES_SIGNATURE)
            .invokeDirect(args[0], Integer.MAX_VALUE);
    }
}
