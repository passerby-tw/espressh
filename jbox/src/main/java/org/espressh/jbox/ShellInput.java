package org.espressh.jbox;

import java.io.IOException;
import java.io.InputStream;

/** Accept CR immediately, consuming a following LF on the next read without lookahead. */
final class ShellInput {
    private final InputStream input;
    private boolean afterCr;
    ShellInput(InputStream input) { this.input = input; }
    int read() throws IOException {
        int value = input.read();
        if (afterCr && value == '\n') value = input.read();
        afterCr = value == '\r';
        return value;
    }
    /** Baseline erase is limited to printable ASCII; never split a UTF-8 sequence. */
    static void append(java.io.ByteArrayOutputStream line, int value) {
        if (value == 8 || value == 127) {
            byte[] bytes = line.toByteArray();
            if (bytes.length > 0 && bytes[bytes.length - 1] >= 32
                    && bytes[bytes.length - 1] < 127) {
                line.reset();
                line.write(bytes, 0, bytes.length - 1);
            }
        } else line.write(value);
    }
}
