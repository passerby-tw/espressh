package org.espressh.runtime;

import org.espressh.api.JvkSystem;
import org.espressh.jbox.JBox;

/** One executable entry for Host boot and an existing managed Guest shell. */
public final class Espressh {
    private Espressh() {}

    public static void main(String[] args) throws Exception {
        if (guestVm()) {
            // A broken or unmanaged Guest must fail here, never bootstrap a Host kernel.
            JvkSystem.current();
            if (args.length != 0)
                throw new IllegalArgumentException("CONFIG_ERROR: Guest espressh.jar accepts no arguments");
            JBox.main(new String[]{"esh"});
        } else {
            HostLauncher.main(args);
        }
    }

    private static boolean guestVm() {
        try {
            Class.forName("com.oracle.truffle.espresso.polyglot.Polyglot", false,
                    Espressh.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException hostVm) {
            return false;
        }
    }
}
