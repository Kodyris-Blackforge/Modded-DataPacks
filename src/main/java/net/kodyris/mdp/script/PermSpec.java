package net.kodyris.mdp.script;

/**
 * A permission requirement from an @perm annotation.
 * serverOp = resolve against the server's own op-permission-level when the function is called.
 */
public record PermSpec(String label, int level, boolean serverOp) {
    public static PermSpec fixed(String label, int level) { return new PermSpec(label, level, false); }
    public static PermSpec opTier() { return new PermSpec("op", 0, true); }
}
