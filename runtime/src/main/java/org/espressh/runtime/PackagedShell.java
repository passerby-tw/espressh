package org.espressh.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.espressh.core.LaunchRequest;

/** Exact current-artifact recognition, never a search or a manifest-based authority. */
final class PackagedShell {
    static final String DISPATCHER = "org.espressh.jbox.JBox";
    private PackagedShell() {}

    static boolean matches(LaunchRequest request, Path bootstrap) {
        if (!request.argv().get(0).equals("-jar") || request.argv().size() < 2) return false;
        try {
            Path jar = request.cwd().resolve(request.argv().get(1));
            if (!Files.isSameFile(jar, bootstrap)) return false;
            try (var file = new JarFile(jar.toFile())) {
                var manifest = file.getManifest();
                return manifest != null
                        && Espressh.class.getName().equals(manifest.getMainAttributes().getValue("Main-Class"))
                        && file.getJarEntry("org/espressh/jbox/JBox.class") != null;
            }
        } catch (IOException | InvalidPathException unavailable) {
            // Ordinary JAR failures retain their original entry-resolution/cleanup path.
            return false;
        }
    }
}
