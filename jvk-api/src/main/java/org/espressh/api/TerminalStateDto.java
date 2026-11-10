package org.espressh.api;

/** The Host determines whether the caller is its terminal's root shell. */
public record TerminalStateDto(boolean root) {}
