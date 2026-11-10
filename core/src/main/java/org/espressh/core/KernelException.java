package org.espressh.core;

/** Host exception; a future guest projection must not expose its Throwable graph. */
public final class KernelException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final ErrorCode code;
    public KernelException(ErrorCode code, String message) { super(message); this.code = code; }
    public KernelException(ErrorCode code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public ErrorCode code() { return code; }
}
