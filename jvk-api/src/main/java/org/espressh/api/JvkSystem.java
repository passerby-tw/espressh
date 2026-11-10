package org.espressh.api;

import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Typed Java binding to the existing caller-bound JVK {@code sys} contract.
 * The runtime supplies the Guest provider; method availability does not grant authority.
 */
public abstract class JvkSystem {
    private static volatile JvkSystem current;

    protected JvkSystem() {}

    /** Returns this Guest Context's provider, or fails when managed Espressh is unavailable. */
    public static JvkSystem current() {
        JvkSystem result = current;
        if (result == null) {
            synchronized (JvkSystem.class) {
                result = current;
                if (result == null) {
                    try {
                        var providers = ServiceLoader.load(JvkSystem.class).iterator();
                        if (!providers.hasNext()) {
                            throw new IllegalStateException(
                                    "JVK_PROVIDER_UNAVAILABLE: managed Espressh required");
                        }
                        result = providers.next();
                    } catch (ServiceConfigurationError failure) {
                        throw new IllegalStateException(
                                "JVK_PROVIDER_UNAVAILABLE: cannot initialize Guest provider", failure);
                    }
                    // Guest static state is local to its Context. Failed lookup is never cached.
                    current = result;
                }
            }
        }
        return result;
    }

    /** PID 1's initial-console operation; general frontend creation uses the VTS API. */
    public abstract TerminalCreationDto createTerminal(LaunchRequestDto request);

    public abstract void awaitShutdown();

    public abstract void requestShutdown();

    public abstract TerminalStateDto terminalState();

    public abstract boolean tryCloseTerminal();

    /** Returns stop acknowledgement, not confirmation that target cleanup has completed. */
    public abstract boolean kill(long pid);

    public abstract RuntimeSnapshotDto runtimeList();

    /** Returns immutable managed-process history; applications choose their display filter. */
    public abstract List<ProcessInfoDto> processList();

    public abstract long spawn(LaunchRequestDto request);

    public abstract ProcessOutcomeDto waitOutcome(long pid);

    public abstract int runApplet(LaunchRequestDto request);
}
