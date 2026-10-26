package net.kodyris.mdp.script;

/** A list file could not be used (missing, invalid JSON, outside ModDataPacks, too big). */
public final class ListException extends Exception {
    public ListException(String message) { super(message); }
}
