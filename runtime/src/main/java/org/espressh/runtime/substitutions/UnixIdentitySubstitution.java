package org.espressh.runtime.substitutions;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.shared.lookup.LookupMode;
import com.oracle.truffle.espresso.substitutions.JavaSubstitution;
import com.oracle.truffle.espresso.substitutions.LanguageFilter;
import com.sun.security.auth.module.UnixSystem;
import org.graalvm.polyglot.Context;

/** Copy Host Unix identity into the caller's original Guest object. */
public final class UnixIdentitySubstitution extends JavaSubstitution {
    private static final String OWNER = "Lcom/sun/security/auth/module/UnixSystem;";

    public static final Factory UNIX_INFO = new Factory("getUnixInfo", OWNER, "V",
            new String[]{OWNER}, true, LanguageFilter.AlwaysValid.INSTANCE, (byte) 0, null,
            UnixIdentitySubstitution::new);

    public UnixIdentitySubstitution() { super(UNIX_INFO); }

    @Override public JavaSubstitution split() { return new UnixIdentitySubstitution(); }

    @Override @TruffleBoundary public Object invoke(Object[] arguments) {
        EspressoContext context = EspressoContext.get(null);
        Meta meta = context.getMeta();
        requireRuntime(context);
        StaticObject receiver = (StaticObject) arguments[0];
        if (StaticObject.isNull(receiver)) throw meta.throwNullPointerException();

        // A subclass can hide protected fields. Always write the original owner,
        // whose private native method is being invoked by its unchanged constructor.
        Klass owner = receiver.getKlass();
        while (owner != null && !OWNER.equals(owner.getType().toString())) {
            owner = owner.getSuperKlass();
        }
        if (owner == null) {
            throw meta.throwExceptionWithMessage(meta.java_lang_NoSuchFieldError,
                    "com.sun.security.auth.module.UnixSystem");
        }
        Field username = field(context, owner, "username", "Ljava/lang/String;");
        Field uid = field(context, owner, "uid", "J");
        Field gid = field(context, owner, "gid", "J");
        Field groups = field(context, owner, "groups", "[J");

        HostIdentity identity = readHostIdentity(meta);
        StaticObject guestName = meta.toGuestString(identity.username());
        StaticObject guestGroups = identity.groups() == null ? StaticObject.NULL
                : StaticObject.wrap(identity.groups().clone(), meta);
        groups.setObject(receiver, guestGroups);
        uid.setLong(receiver, identity.uid());
        gid.setLong(receiver, identity.gid());
        username.setObject(receiver, guestName);
        return StaticObject.NULL;
    }

    private static Field field(EspressoContext context, Klass owner,
            String name, String descriptor) {
        Field field = owner.lookupField(context.getNames().getOrCreate(name),
                context.getTypes().getOrCreateValidType(descriptor), LookupMode.INSTANCE_ONLY);
        if (field == null || field.isStatic() || field.getDeclaringKlass() != owner) {
            throw context.getMeta().throwExceptionWithMessage(
                    context.getMeta().java_lang_NoSuchFieldError, name);
        }
        return field;
    }

    private static HostIdentity readHostIdentity(Meta meta) {
        try {
            // This exported Host JDK API obtains the OS identity; neither Guest
            // properties nor environment variables participate in the query.
            UnixSystem host = new UnixSystem();
            return new HostIdentity(host.getUsername(), host.getUid(), host.getGid(),
                    host.getGroups());
        } catch (UnsatisfiedLinkError failure) {
            // Let the unchanged Guest UnixLoginModule catch Guest linkage failure.
            throw meta.throwExceptionWithMessage(meta.java_lang_UnsatisfiedLinkError,
                    failure.getMessage());
        } catch (SecurityException failure) {
            throw meta.throwExceptionWithMessage(meta.java_lang_SecurityException,
                    failure.getMessage());
        } catch (OutOfMemoryError failure) {
            throw meta.throwExceptionWithMessage(meta.java_lang_OutOfMemoryError,
                    failure.getMessage());
        }
    }

    static void requireLibraryRuntime(EspressoContext context) {
        if (context == null) throw new IllegalArgumentException("Context ownership required");
        if (!"Linux".equals(System.getProperty("os.name")) || Runtime.version().feature() != 25
                || !"25.4.4.1.1".equals(Context.getCurrent().getEngine().getVersion())
                || context.getEnv().isNativeAccessAllowed()
                || !"no-native".equals(context.getLanguage().nativeBackendId())
                || !context.getLanguage().useEspressoLibs()) {
            throw new IllegalStateException("Unix identity requires Linux Host JDK25"
                    + " / same Engine25.4.4.1.1 / native=false / no-native / EspressoLibs");
        }
    }

    private static void requireRuntime(EspressoContext context) {
        requireLibraryRuntime(context);
        Meta meta = context.getMeta();
        String guestVersion = property(meta, "java.version");
        String guestOs = property(meta, "os.name");
        String guestArch = property(meta, "os.arch");
        if (!"25.0.4.1.1".equals(guestVersion) || !"Linux".equals(guestOs)
                || !("amd64".equals(guestArch) || "x86_64".equals(guestArch))) {
            throw meta.throwExceptionWithMessage(meta.java_lang_UnsupportedOperationException,
                    "UNIX_IDENTITY_UNSUPPORTED_RUNTIME: requires Linux x86_64 Guest JDK25.0.4.1.1");
        }
    }

    private static String property(Meta meta, String key) {
        return meta.toHostString((StaticObject) meta.java_lang_System_getProperty
                .invokeDirectStatic(meta.toGuestString(key)));
    }

    private record HostIdentity(String username, long uid, long gid, long[] groups) {}
}
