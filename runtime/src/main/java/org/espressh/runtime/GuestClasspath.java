package org.espressh.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.espressh.core.ErrorCode;
import org.espressh.core.KernelException;

/** Provider-owned Guest bytes; the original artifact remains the distribution identity. */
final class GuestClasspath implements AutoCloseable {
    private final Path distribution;
    private final Path bootstrap;
    private final Path directory;

    static GuestClasspath open(Path artifact) {
        Path source = artifact.toAbsolutePath();
        if (!Files.isRegularFile(source)) return new GuestClasspath(source, source, null);
        Path directory = null;
        try (var input = new JarFile(source.toFile())) {
            // Preserve the existing missing-bootstrap failure instead of looking elsewhere.
            if (input.getJarEntry("org/espressh/bootstrap/IoBootstrap.class") == null)
                return new GuestClasspath(source, source, null);
            directory = Files.createTempDirectory("espressh-guest-");
            Path output = directory.resolve("guest.jar");
            try (var jar = new JarOutputStream(Files.newOutputStream(output))) {
                var entries = input.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory() || !selected(entry.getName())) continue;
                    var copy = new JarEntry(entry.getName());
                    copy.setTime(0);
                    jar.putNextEntry(copy);
                    try (var bytes = input.getInputStream(entry)) { bytes.transferTo(jar); }
                    jar.closeEntry();
                }
            }
            return new GuestClasspath(source, output, directory);
        } catch (IOException | RuntimeException failure) {
            if (directory != null) {
                try {
                    Files.deleteIfExists(directory.resolve("guest.jar"));
                    Files.deleteIfExists(directory);
                } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw new KernelException(ErrorCode.START_FAILED,
                    "Cannot prepare trusted Guest classpath: " + source, failure);
        }
    }

    private GuestClasspath(Path distribution, Path bootstrap, Path directory) {
        this.distribution = distribution;
        this.bootstrap = bootstrap;
        this.directory = directory;
    }

    private static boolean selected(String name) {
        return name.startsWith("org/espressh/bootstrap/")
                || name.startsWith("org/espressh/api/")
                || name.startsWith("org/espressh/vts/")
                || name.startsWith("org/espressh/jbox/")
                || name.equals("org/espressh/runtime/Espressh.class")
                || name.startsWith("META-INF/etc/")
                || name.equals("META-INF/MANIFEST.MF")
                || name.equals("META-INF/services/org.espressh.api.JvkSystem")
                || name.equals("META-INF/services/org.espressh.vts.TerminalProvider");
    }

    Path bootstrap() { return bootstrap; }
    Path distribution() { return distribution; }

    /** Only trusted shell-role snapshots may substitute the original distribution entry. */
    List<Path> project(List<Path> application, boolean distributionRole) {
        var paths = new ArrayList<Path>();
        paths.add(bootstrap);
        for (Path entry : application) {
            Path path = entry;
            if (distributionRole && sameDistribution(path)) path = bootstrap;
            if (!distributionRole || !path.equals(bootstrap)) paths.add(path);
        }
        return List.copyOf(paths);
    }

    List<Path> distributionSnapshot(List<Path> application) {
        var paths = new LinkedHashSet<Path>();
        for (Path entry : project(application, true)) paths.add(entry.toAbsolutePath().normalize());
        return List.copyOf(paths);
    }

    List<Path> applicationSnapshot(List<Path> application) {
        var paths = new LinkedHashSet<Path>();
        for (Path entry : application) {
            Path path = sameDistribution(entry) ? bootstrap : entry;
            paths.add(path.toAbsolutePath().normalize());
        }
        return List.copyOf(paths);
    }

    private boolean sameDistribution(Path path) {
        if (path.equals(distribution)) return true;
        try { return Files.isSameFile(path, distribution); }
        catch (IOException unavailable) { return false; }
    }

    @Override public void close() {
        if (directory == null) return;
        try {
            Files.deleteIfExists(bootstrap);
            Files.deleteIfExists(directory);
        } catch (IOException failure) {
            throw new KernelException(ErrorCode.RESOURCE_CLOSED,
                    "Cannot remove owned Guest classpath: " + directory, failure);
        }
    }
}
