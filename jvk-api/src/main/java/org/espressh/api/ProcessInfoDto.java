package org.espressh.api;

/** Managed process values, including historical terminal records when returned by JVK. */
public record ProcessInfoDto(long pid, long parentPid, long terminalId,
                             String state, String runtimeId, String command) {}
