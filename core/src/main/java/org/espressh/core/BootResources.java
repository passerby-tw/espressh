package org.espressh.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;

/** Explicit resource reads from the selected artifact, not a filesystem overlay. */
public final class BootResources implements AutoCloseable {
    private static final Set<String> CONFIGS=Set.of("/etc/inittab","/etc/profile","/etc/eshrc","/etc/runtime_dir.json");
    private final JarFile jar;
    public BootResources(Path artifact) throws IOException { jar=new JarFile(artifact.toFile()); }
    public Optional<String> read(String logicalName) throws IOException {
        if (!CONFIGS.contains(logicalName)) throw new IllegalArgumentException("Unknown configuration: "+logicalName);
        var entry=jar.getJarEntry("META-INF"+logicalName);
        if (entry==null) return Optional.empty();
        if (entry.isDirectory()) throw new IOException("Configuration is a directory: "+logicalName);
        try (var in=jar.getInputStream(entry)) {
            return Optional.of(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(in.readAllBytes())).toString());
        }
    }
    @Override public void close() throws IOException { jar.close(); }
}
