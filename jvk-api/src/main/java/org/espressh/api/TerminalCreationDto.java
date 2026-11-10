package org.espressh.api;

/** Values identifying the initial terminal and its root shell. */
public record TerminalCreationDto(long terminalId, long rootPid) {}
