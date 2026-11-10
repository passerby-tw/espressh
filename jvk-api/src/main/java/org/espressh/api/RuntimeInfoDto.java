package org.espressh.api;

/** Immutable values describing a selectable Guest runtime. */
public record RuntimeInfoDto(String id, String version, String vendor, String javaHome) {}
