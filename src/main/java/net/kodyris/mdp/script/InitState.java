package net.kodyris.mdp.script;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Remembers which files have already had their "evt init" run, so it runs once per world.
 * Stored as mdp_state.json in the world folder:  {"version":1,"initialized":["last_stand/core"]}
 *
 * If the file exists but cannot be read, error() is set and nothing is ever written over it:
 * running init again by mistake can be destructive (for example it may rebuild teams), so the
 * caller must skip init until the file is fixed or deleted.
 */
public final class InitState {
    private final Path file;
    private final Set<String> done = new TreeSet<>();
    private boolean dirty;
    private String error;

    public InitState(Path file) {
        this.file = file;
        if (!Files.exists(file)) return;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            if (text.isBlank()) throw new ListException("the file is empty");
            Object root = new JsonLists.MiniJson(text).parseRoot();
            if (!(root instanceof Map<?, ?> map)) throw new ListException("the top level must be an object");
            if (!(map.get("initialized") instanceof List<?> list)) throw new ListException("missing the \"initialized\" list");
            for (Object o : list) {
                if (!(o instanceof String s)) throw new ListException("\"initialized\" must contain only strings");
                done.add(s);
            }
        } catch (ListException | IOException e) {
            error = e.getMessage();
            done.clear();
        }
    }

    public Path file() { return file; }
    /** Non-null if the state file exists but is unreadable. */
    public String error() { return error; }
    public boolean isDone(String id) { return done.contains(id); }
    public Set<String> done() { return Collections.unmodifiableSet(done); }

    public void markDone(String id) {
        if (done.add(id)) dirty = true;
    }

    /** "*" clears everything. Returns true if anything was cleared. */
    public boolean reset(String idOrAll) {
        boolean changed = idOrAll.equals("*") ? !done.isEmpty() : done.contains(idOrAll);
        if (idOrAll.equals("*")) done.clear(); else done.remove(idOrAll);
        if (changed) dirty = true;
        return changed;
    }

    public void save() throws IOException {
        if (error != null) throw new IOException("refusing to overwrite an unreadable state file (" + error + ")");
        if (!dirty) return;

        StringBuilder sb = new StringBuilder("{\n  \"version\": 1,\n  \"initialized\": [");
        boolean first = true;
        for (String id : done) {
            sb.append(first ? "\n    " : ",\n    ").append(quote(id));
            first = false;
        }
        sb.append(done.isEmpty() ? "" : "\n  ").append("]\n}\n");

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        dirty = false;
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
