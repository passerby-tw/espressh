package org.espressh.jbox;

/** Userland parsing/configuration failure; does not expose Host kernel types. */
final class ShellException extends RuntimeException {
    static final String CONFIG_ERROR="CONFIG_ERROR";
    static final String COMMAND_NOT_AVAILABLE="COMMAND_NOT_AVAILABLE";
    private final String code;
    ShellException(String code,String message) { this(code,message,null); }
    ShellException(String code,String message,Throwable cause) { super(message,cause);this.code=code; }
    String code() { return code; }
}
