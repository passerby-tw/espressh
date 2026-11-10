package org.espressh.core;

public enum ProcessState {
    NEW, STARTING, RUNNING, STOPPING, EXITED, FAILED, CANCELLED;
    public boolean terminal() { return this == EXITED || this == FAILED || this == CANCELLED; }
    boolean permits(ProcessState next) {
        return switch (this) {
            case NEW -> next == STARTING || next == STOPPING || next == FAILED;
            case STARTING -> next == RUNNING || next == STOPPING || next == FAILED;
            case RUNNING -> next == STOPPING || next == EXITED || next == FAILED || next == CANCELLED;
            case STOPPING -> next == EXITED || next == FAILED || next == CANCELLED;
            default -> false;
        };
    }
}
