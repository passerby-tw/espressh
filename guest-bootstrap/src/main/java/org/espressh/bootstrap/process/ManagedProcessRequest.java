package org.espressh.bootstrap.process;

import java.util.Map;

/** Value-only request copied by the caller-bound Host process service. */
public final class ManagedProcessRequest {
    public final String[] command;
    public final String[] environmentKeys;
    public final String[] environmentValues;
    public final String directory;
    public final boolean mergeError;

    ManagedProcessRequest(String[] command, Map<String, String> environment,
            String directory, boolean mergeError) {
        this.command = command.clone();
        if (environment == null) {
            environmentKeys = null;
            environmentValues = null;
        } else {
            environmentKeys = new String[environment.size()];
            environmentValues = new String[environment.size()];
            int index = 0;
            for (Map.Entry<String, String> entry : environment.entrySet()) {
                environmentKeys[index] = entry.getKey();
                environmentValues[index] = entry.getValue();
                index++;
            }
        }
        this.directory = directory;
        this.mergeError = mergeError;
    }
}
