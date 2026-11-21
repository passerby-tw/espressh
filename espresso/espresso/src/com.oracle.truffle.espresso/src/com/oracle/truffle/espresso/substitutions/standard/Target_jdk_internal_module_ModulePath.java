/*
 * Copyright (c) 2026, passerby-tw
 * SPDX-License-Identifier: GPL-2.0-only
 */
package com.oracle.truffle.espresso.substitutions.standard;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.io.TruffleIO;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.substitutions.EspressoSubstitutions;
import com.oracle.truffle.espresso.substitutions.Inject;
import com.oracle.truffle.espresso.substitutions.JavaType;
import com.oracle.truffle.espresso.substitutions.Substitution;
import com.oracle.truffle.espresso.substitutions.VersionFilter;

@EspressoSubstitutions
final class Target_jdk_internal_module_ModulePath {
    private static final String PATH = "Ljava/nio/file/Path;";
    private static final String REFERENCE = "Ljava/lang/module/ModuleReference;";
    private static final String ATTRIBUTES = "Ljdk/internal/module/ModuleInfo$Attributes;";
    private static final String PATCHER = "Ljdk/internal/module/ModulePatcher;";

    @Substitution(hasReceiver = true, languageFilter = VersionFilter.Java25OrLater.class)
    @TruffleBoundary
    static @JavaType(ModuleReference.class) StaticObject readJar(
                    @JavaType(internalName = "Ljdk/internal/module/ModulePath;") StaticObject self,
                    @JavaType(Path.class) StaticObject path, @Inject EspressoContext context) {
        Meta meta = context.getMeta();
        if (context.isPolyglotExtensionPath(path) && context.getJavaVersion().featureVersion() == 25 && context.getLanguage().useEspressoLibs()) {
            byte[] descriptor = descriptorFor(context.getTruffleIO(), path);
            if (descriptor != null) {
                StaticObject reference = newJarReference(context, self, path, descriptor);
                if (reference != null) {
                    return reference;
                }
            }
        }
        return (StaticObject) meta.jdk_internal_module_ModulePath.requireDeclaredMethod(context.getNames().getOrCreate("readJar"),
                        context.getSignatures().getOrCreateValidSignature("(" + PATH + ")" + REFERENCE)).getCallTargetNoSubstitution().call(self, path);
    }

    private static StaticObject newJarReference(EspressoContext context, StaticObject self, StaticObject path, byte[] descriptor) {
        Meta meta = context.getMeta();
        Field patcher = self.getKlass().lookupField(context.getNames().getOrCreate("patcher"), context.getTypes().getOrCreateValidType(PATCHER));
        Method read = bootMethod(context, "Ljdk/internal/module/ModuleInfo;", "read", "(Ljava/nio/ByteBuffer;Ljava/util/function/Supplier;)" + ATTRIBUTES);
        Method newJar = bootMethod(context, "Ljdk/internal/module/ModuleReferences;", "newJarModule", "(" + ATTRIBUTES + PATCHER + PATH + ")" + REFERENCE);
        if (patcher == null || patcher.isStatic() || read == null || !read.isStatic() || newJar == null || !newJar.isStatic()) {
            return null;
        }
        StaticObject buffer = (StaticObject) meta.java_nio_ByteBuffer_wrap.invokeDirectStatic(StaticObject.wrap(descriptor, meta));
        StaticObject attrs = (StaticObject) read.invokeDirectStatic(buffer, StaticObject.NULL);
        return (StaticObject) newJar.invokeDirectStatic(attrs, patcher.getObject(self), path);
    }

    private static Method bootMethod(EspressoContext context, String type, String name, String signature) {
        ObjectKlass klass = (ObjectKlass) context.getMeta().loadKlassOrNull(context.getTypes().getOrCreateValidType(type), StaticObject.NULL, StaticObject.NULL);
        return klass == null ? null : klass.lookupDeclaredMethod(context.getNames().getOrCreate(name), context.getSignatures().getOrCreateValidSignature(signature));
    }

    private static byte[] descriptorFor(TruffleIO io, StaticObject path) {
        if (path.getKlass() != io.sun_nio_fs_TrufflePath || Index.DESCRIPTORS.isEmpty()) {
            return null;
        }
        TruffleFile file = (TruffleFile) io.sun_nio_fs_TrufflePath_0file.getHiddenObject(path);
        // Read through the caller's filesystem even on a cache hit. A resource override can
        // replace the JAR without changing its name, size, or modification time.
        try (InputStream in = file.newInputStream()) {
            int limit = 1024 * 1024;
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) {
                return null;
            }
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            String descriptor = Index.DESCRIPTORS.getProperty(digest);
            return descriptor == null ? null : Base64.getDecoder().decode(descriptor);
        } catch (IOException | SecurityException | UnsupportedOperationException failure) {
            // Let the Guest JDK report filesystem and archive errors through its usual path.
            return null;
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static final class Index {
        static final Properties DESCRIPTORS = load();

        private static Properties load() {
            Properties entries = new Properties();
            try (InputStream in = Index.class.getResourceAsStream("/META-INF/espressh/espresso-polyglot-modules.properties")) {
                if (in != null) {
                    entries.load(in);
                }
            } catch (IOException failure) {
                return new Properties();
            }
            return entries;
        }
    }
}
