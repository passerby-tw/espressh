package org.espressh.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import org.graalvm.polyglot.HostAccess;

/** Per-JVP borrowed output flush capability. TruffleIO carries bytes; close is never exported. */
public final class IoEndpoint {
    private final OutputStream out;

    IoEndpoint(OutputStream out) { this.out = Objects.requireNonNull(out); }

    @HostAccess.Export public void flush() throws IOException {
        out.flush();
    }
}
