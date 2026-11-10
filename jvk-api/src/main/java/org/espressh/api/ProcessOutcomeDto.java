package org.espressh.api;

/** Confirmed terminal outcome. A failure may have no exit code. */
public record ProcessOutcomeDto(String state, Integer exitCode, String error, String message) {}
