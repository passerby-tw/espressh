package org.espressh.core;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** Owned endpoints or non-closing views of borrowed Host streams. */
public record ProcessIo(InputStream in, OutputStream out, OutputStream err) implements AutoCloseable {
    public ProcessIo { Objects.requireNonNull(in); Objects.requireNonNull(out); Objects.requireNonNull(err); }
    public static ProcessIo borrowed(InputStream in, OutputStream out, OutputStream err) {
        return new ProcessIo(new FilterInputStream(in) { @Override public void close() {} }, shield(out), shield(err));
    }
    private static OutputStream shield(OutputStream out) {
        OutputStream pipeWriter = BytePipe.borrowWriter(out);
        if (pipeWriter != null) return pipeWriter;
        return new FilterOutputStream(out) {
            @Override public void write(byte[] b,int off,int len) throws IOException { out.write(b,off,len); }
            @Override public void close() throws IOException { flush(); }
        };
    }
    /** Only release per-child borrowed pipe views; shared output and pending bytes remain. */
    public void unblockBorrowedWriters() {
        BytePipe.closeBorrowedWriter(out);
        BytePipe.closeBorrowedWriter(err);
    }
    public static ProcessIo background(OutputStream out, OutputStream err) { return borrowed(InputStream.nullInputStream(), out, err); }
    /** Opens owned output files; the caller transfers the original endpoints to the result. */
    public ProcessIo redirectOutputs(LaunchRequest request) throws IOException {
        if (!request.redirectsOutput()) return this;
        OutputStream redirectedOut = null, redirectedErr = null;
        try {
            if (request.stdoutPath() != null)
                redirectedOut = open(request.stdoutPath(), request.stdoutAppend());
            if (request.stderrPath() != null)
                redirectedErr = open(request.stderrPath(), request.stderrAppend());
            return new ProcessIo(in,
                    redirectedOut == null ? out : replacement(redirectedOut, out),
                    redirectedErr == null ? err : replacement(redirectedErr, err));
        } catch (IOException | RuntimeException | Error failure) {
            // The original endpoints still belong to the execution; only roll back acquired files.
            for (OutputStream stream : new OutputStream[]{redirectedOut, redirectedErr}) {
                if (stream != null) try { stream.close(); }
                catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            }
            throw failure;
        }
    }
    private static OutputStream open(Path path, boolean append) throws IOException {
        return Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
    }
    private static OutputStream replacement(OutputStream file, OutputStream original) {
        return new FilterOutputStream(file) {
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                out.write(bytes, offset, length);
            }
            @Override public void close() throws IOException {
                // Release both the owned file and the replaced endpoint (borrowed views only flush).
                try (OutputStream ownedFile = out) { original.close(); }
            }
        };
    }
    @Override public void close() throws IOException {
        IOException failure=null;
        for (AutoCloseable stream : new AutoCloseable[]{in,out,err}) {
            try { stream.close(); } catch (Exception e) {
                IOException io = e instanceof IOException x ? x : new IOException(e);
                if (failure==null) failure=io; else failure.addSuppressed(io);
            }
        }
        if (failure!=null) throw failure;
    }
}
