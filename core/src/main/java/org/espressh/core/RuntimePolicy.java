package org.espressh.core;

/** Semantic requirements, not a claim that Context.Builder booleans enforce every requirement. */
public enum RuntimePolicy {
    BASELINE;
    public String thread(){return "ALLOW_OWNED";}
    public String io(){return "ALLOW";}
    public String polyglot(){return "ALLOW_WITHIN_CONTEXT";}
    public String hostAccess(){return "EXPLICIT_SYS_ONLY";}
    public String hostClass(){return "DENY";}
    /** Guest Java runs through Espresso's no-native backend and bundled library replacements. */
    public String nativeAccess(){return "NO_NATIVE";}
    /** Additional method-level interception is separate from the selected no-native backend. */
    public String nativeInterception(){return "DEFERRED";}
    public String hostProcess(){return "DENY";}
    public boolean allowValueSharing(){return false;}
}
