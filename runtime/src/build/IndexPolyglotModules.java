import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.attribute.ModulePackagesAttribute;
import java.lang.constant.PackageDesc;
import java.lang.module.FindException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.net.JarURLConnection;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.zip.ZipException;

/** Prepare module descriptors for the exact extension JAR bytes distributed by this SDK. */
public final class IndexPolyglotModules {
    private static final String RESOURCE_MARKER = "com/oracle/truffle/espresso/resources/libs/EspressoLibsResources.class";
    private static final Pattern EXTENSION = Pattern.compile("META-INF/resources/java/espresso-libs/[^/]+/[^/]+/lib/espresso-polyglot\\.jar");

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        var marker = ClassLoader.getSystemResource(RESOURCE_MARKER);
        if (marker == null || !(marker.openConnection() instanceof JarURLConnection resource)) {
            throw new IOException("Expected espresso-libs-resources on the build classpath");
        }
        var entries = new TreeMap<String, String>();
        resource.setUseCaches(false);
        Path temporary = Files.createTempFile("espresso-polyglot-", ".jar");
        try (JarFile sdk = resource.getJarFile()) {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var entry : sdk.stream().filter(e -> EXTENSION.matcher(e.getName()).matches()).toList()) {
                try (InputStream input = sdk.getInputStream(entry)) {
                    Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                }
                String sha = HexFormat.of().formatHex(digest.digest(Files.readAllBytes(temporary)));
                if (!entries.containsKey(sha)) {
                    byte[] descriptor = completeDescriptor(temporary);
                    if (descriptor != null) {
                        entries.put(sha, Base64.getEncoder().encodeToString(descriptor));
                    }
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        Files.createDirectories(output.getParent());
        var index = new StringBuilder();
        entries.forEach((sha, descriptor) -> index.append(sha).append('=').append(descriptor).append('\n'));
        Files.writeString(output, index);
    }

    private static byte[] completeDescriptor(Path file) throws IOException {
        try (JarFile jar = new JarFile(file.toFile())) {
            var entry = jar.getJarEntry("module-info.class");
            if (entry == null || jar.isMultiRelease() || jar.stream().anyMatch(e -> unsupported(e.getName()))) {
                return null;
            }
            byte[] original;
            try (InputStream bytes = jar.getInputStream(entry)) {
                original = bytes.readAllBytes();
            }
            var reference = ModuleFinder.of(file).find("espresso.polyglot");
            if (reference.isEmpty()) {
                return null;
            }
            var expected = reference.get().descriptor();
            var classFile = ClassFile.of();
            var model = classFile.parse(original);
            if (model.findAttribute(Attributes.modulePackages()).isPresent()) {
                return original;
            }
            var packages = expected.packages().stream().sorted().map(PackageDesc::of).toList();
            byte[] complete = classFile.transformClass(model, ClassTransform.endHandler(
                    builder -> builder.with(ModulePackagesAttribute.ofNames(packages))));
            if (!ModuleDescriptor.read(ByteBuffer.wrap(complete)).equals(expected)) {
                throw new IOException("Inconsistent package index for " + expected.name());
            }
            return complete;
        } catch (FindException | IllegalArgumentException | ZipException e) {
            // An unrecognized SDK extension stays on the Guest JDK discovery path.
            return null;
        }
    }

    private static boolean unsupported(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) {
            return false;
        }
        return upper.startsWith("META-INF/VERSIONS/") || upper.startsWith("META-INF/SIG-")
                || upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA")
                || upper.endsWith(".EC");
    }
}
