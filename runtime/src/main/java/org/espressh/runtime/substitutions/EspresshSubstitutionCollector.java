package org.espressh.runtime.substitutions;

import com.oracle.truffle.espresso.substitutions.Substitutions;
import com.oracle.truffle.espresso.substitutions.JavaSubstitution;
import java.util.List;

public final class EspresshSubstitutionCollector implements Substitutions.Extension {
    @Override
    public void register(List<JavaSubstitution.Factory> factories) {
        factories.add(org.espressh.runtime.substitutions.EnvSubstitution.NAMED);
        factories.add(org.espressh.runtime.substitutions.EnvSubstitution.ALL);
        factories.add(org.espressh.runtime.substitutions.ProcessHandleSubstitution.CURRENT);
        factories.add(org.espressh.runtime.substitutions.ProcessHandleSubstitution.OF);
        factories.add(org.espressh.runtime.substitutions.ProcessHandleSubstitution.ALL);
        factories.add(org.espressh.runtime.substitutions.HostProcessSubstitution.START);
        factories.add(org.espressh.runtime.substitutions.ChannelInputSubstitution.READ_ALL_BYTES);
        factories.add(org.espressh.runtime.substitutions.FileSystemSubstitution.EXCLUSIVE_CREATE);
        factories.add(org.espressh.runtime.substitutions.NioAccessSubstitution.CHECK_ACCESS);
        factories.add(org.espressh.runtime.substitutions.CanonicalizeSubstitution.CANONICALIZE);
        factories.add(org.espressh.runtime.substitutions.ManagementSubstitution.MEMORY_USAGE);
        factories.add(org.espressh.runtime.substitutions.ManagementSubstitution.GC_COLLECTION_TIME);
        factories.add(org.espressh.runtime.substitutions.ManagementSubstitution.GC_COLLECTION_COUNT);
        factories.add(org.espressh.runtime.substitutions.UnixIdentitySubstitution.UNIX_INFO);
        factories.add(org.espressh.runtime.substitutions.RuntimeMemorySubstitution.TOTAL_MEMORY);
        factories.add(org.espressh.runtime.substitutions.RuntimeMemorySubstitution.FREE_MEMORY);
        factories.add(org.espressh.runtime.substitutions.RuntimeMemorySubstitution.GC);
        factories.add(org.espressh.runtime.substitutions.SerializationSubstitution.INIT_NATIVE);
        factories.add(org.espressh.runtime.substitutions.SerializationSubstitution.HAS_STATIC_INITIALIZER);
        factories.add(org.espressh.runtime.substitutions.FileLengthSubstitution.GET_LENGTH);
        factories.add(org.espressh.runtime.substitutions.awt.AwtFactorySubstitution.ENVIRONMENT);
        factories.add(org.espressh.runtime.substitutions.awt.AwtFactorySubstitution.TOOLKIT);
        factories.add(org.espressh.runtime.substitutions.awt.AwtFontSubstitution.COMPONENT);
        factories.add(org.espressh.runtime.substitutions.awt.AwtSwingFontSubstitution.FACTORY);
        factories.add(org.espressh.runtime.substitutions.awt.AwtFontSubstitution.GRAPHICS);
        factories.add(org.espressh.runtime.substitutions.awt.AwtFontSubstitution.GRAPHICS_DEFAULT);
        factories.add(org.espressh.runtime.substitutions.awt.AwtFocusSubstitution.FACTORY);
        factories.add(org.espressh.runtime.substitutions.awt.AwtImageGraphicsSubstitution.FACTORY);
        factories.add(org.espressh.runtime.substitutions.awt.AwtComponentFactorySubstitution.FACTORY);
        factories.add(org.espressh.runtime.substitutions.awt.AwtInputContextSubstitution.FACTORY);
        for (var factory : org.espressh.runtime.substitutions.awt.AwtTextSubstitution.FACTORIES) factories.add(factory);
        factories.add(org.espressh.runtime.substitutions.awt.UnsupportedFontSubstitution.FACTORY);
        for (var factory : org.espressh.runtime.substitutions.awt.AwtInitialization.FACTORIES) factories.add(factory);
        factories.add(org.espressh.runtime.substitutions.GuestBufferTransferSubstitution.MAX_DIRECT_TRANSFER_SIZE);
        factories.add(org.espressh.runtime.substitutions.GuestBufferTransferSubstitution.TRANSFER_TO);
        factories.add(org.espressh.runtime.substitutions.GuestBufferTransferSubstitution.TRANSFER_FROM);
        factories.add(org.espressh.runtime.substitutions.GuestBufferTransferSubstitution.PWRITE);
        factories.add(org.espressh.runtime.substitutions.FileRenameSubstitution.RENAME);
        factories.add(org.espressh.runtime.substitutions.FileInputSkipSubstitution.SKIP);
        factories.add(org.espressh.runtime.substitutions.ConsoleTtySubstitution.TTY_STATUS);
    }
}
