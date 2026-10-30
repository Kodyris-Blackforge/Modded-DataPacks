package net.kodyris.mdp.script;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One evt or function block. A bad block disables only itself. */
public final class MdpBlock {
    private final BlockKind kind;
    private final String name;
    private final String file;
    private final int line;
    private final List<String> params;
    private final List<String> body = new ArrayList<>();
    private final List<Integer> bodyLines = new ArrayList<>();
    /** Parallel to body: the conditions (from enclosing if/else) that must all hold for that line to run. */
    private final List<List<GuardTerm>> bodyGuards = new ArrayList<>();
    private final Set<String> listPaths = new LinkedHashSet<>();
    /** Every statement in order: commands, 'local' declarations and assignments. */
    private final List<Stmt> stmts = new ArrayList<>();
    private final Set<String> locals = new LinkedHashSet<>();
    private boolean hasConditions;
    private final Set<String> playerParams = new LinkedHashSet<>();
    private PermSpec perm;
    private String disabled;

    public MdpBlock(BlockKind kind, String name, List<String> params, String file, int line) {
        this.kind = kind;
        this.name = name;
        this.params = params;
        this.file = file;
        this.line = line;
    }

    public BlockKind kind() { return kind; }
    public String name() { return name; }
    public String file() { return file; }
    public int line() { return line; }
    public List<String> params() { return params; }
    public List<String> body() { return body; }
    public List<Integer> bodyLines() { return bodyLines; }
    public PermSpec perm() { return perm; }
    /** Local variables declared with 'local', in order. */
    public Set<String> locals() { return locals; }
    /** True if the block contains any if/else or local variable, so its output can differ from call to call. */
    public boolean hasConditions() { return hasConditions; }
    /** .json list files used by this block's conditions (paths relative to ModDataPacks). */
    public Set<String> listPaths() { return listPaths; }
    /** Parameters that hold a player (they have .name and .uuid). */
    public Set<String> playerParams() { return playerParams; }
    public boolean isPlayerParam(String param) { return playerParams.contains(param); }
    public String disabledReason() { return disabled; }
    public boolean isActive() { return disabled == null; }
    public String key() { return file + ":" + name; }

    void addBodyLine(String text, int lineNumber) { addBodyLine(text, lineNumber, List.of()); }
    void addBodyLine(String text, int lineNumber, List<GuardTerm> guard) {
        body.add(text);
        bodyLines.add(lineNumber);
        bodyGuards.add(guard);
        stmts.add(new Stmt(StmtKind.COMMAND, text, null, null, guard, lineNumber));
    }
    /**
     * A say effect. 'tellraw' is the complete command with <variables> still in it; it is kept in body so the effect
     * counts as a command (not "no data") and the loader can check it like any other command.
     */
    void addSay(String tellraw, String target, List<GuardTerm> guard, int lineNumber) {
        body.add(tellraw);
        bodyLines.add(lineNumber);
        bodyGuards.add(guard);
        stmts.add(new Stmt(StmtKind.SAY, tellraw, target, null, guard, lineNumber));
    }
    void addLocal(String name, MdpExpr.Value value, int lineNumber) {
        locals.add(name);
        hasConditions = true;
        stmts.add(new Stmt(StmtKind.LOCAL, null, name, value, List.of(), lineNumber));
    }
    void addAssign(String name, MdpExpr.Value value, List<GuardTerm> guard, int lineNumber) {
        hasConditions = true;
        stmts.add(new Stmt(StmtKind.ASSIGN, null, name, value, guard, lineNumber));
    }
    void markConditions() { hasConditions = true; }
    void addListPaths(Set<String> paths) { listPaths.addAll(paths); }
    void setPerm(PermSpec p) { this.perm = p; }
    void addPlayerParam(String p) { playerParams.add(p); }

    /** Keeps the first reason. */
    public void disable(String reason) { if (disabled == null) disabled = reason; }

    /** One condition that guards a line: it must evaluate to 'expected'. 'else' branches use expected=false. */
    public record GuardTerm(MdpExpr.Node expr, boolean expected, int line) {}

    /** The lines to run for one call, and any condition errors met while deciding. */
    public record Selected(List<String> lines, List<String> errors) {}

    public enum StmtKind { COMMAND, LOCAL, ASSIGN, SAY }

    /**
     * One statement. COMMAND carries its text; LOCAL / ASSIGN carry the local's name and the value to store;
     * SAY carries the whole tellraw command as a template in 'text' and its target in 'name' (a player variable key,
     * or "*" for everyone).
     */
    public record Stmt(StmtKind kind, String text, String name, MdpExpr.Value value, List<GuardTerm> guard, int line) {}

    /**
     * Runs the block's logic for one call and returns the commands that should actually be executed.
     *
     * Statements are interpreted in order, once, before any command runs: if/else conditions are decided,
     * 'local' declarations and assignments update the locals, and each command line has its <variables>
     * (parameters and locals) filled in with their values at that point. Because the commands run afterwards,
     * a command can't change a local; only a later assignment can.
     *
     * An if/else chain's condition is evaluated the first time it is reached and that answer is kept, so the
     * else side never flips because the if side changed a local.
     * A condition or assignment that errors (for example comparing "abc" with a number) skips what it guards,
     * on both the if and the else side, and is reported in errors.
     */
    public Selected select(MdpExpr.Context ctx) {
        List<String> lines = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, String> vals = new HashMap<>();                 // current values of the locals
        MdpExpr.Context view = new MdpExpr.Context() {
            public String var(String key) {
                String v = vals.get(key);
                return v != null ? v : ctx.var(key);
            }
            public boolean listContains(String path, String value) throws ListException { return ctx.listContains(path, value); }
            public boolean fileExists(String path) throws ListException { return ctx.fileExists(path); }
            public Boolean hasTag(String playerVar, String tag) { return ctx.hasTag(playerVar, tag); }
            public Boolean hasTeam(String playerVar, String team) { return ctx.hasTeam(playerVar, team); }
            public Integer countTagged(String tag) { return ctx.countTagged(tag); }
            public Integer countOnTeam(String team) { return ctx.countOnTeam(team); }
        };
        Map<MdpExpr.Node, MdpExpr.Result> memo = new IdentityHashMap<>();

        for (Stmt st : stmts) {
            boolean run = true;
            for (GuardTerm g : st.guard()) {
                MdpExpr.Result r = memo.get(g.expr());
                if (r == null) {
                    r = g.expr().eval(view);
                    memo.put(g.expr(), r);
                }
                if (r.isError()) {
                    String msg = "line " + g.line() + ": " + r.error();
                    if (!errors.contains(msg)) errors.add(msg);
                    run = false;
                    break;
                }
                if (r.value() != g.expected()) { run = false; break; }
            }
            if (!run) continue;

            if (st.kind() == StmtKind.COMMAND) {
                lines.add(MdpVars.substitute(st.text(), view::var));
            } else if (st.kind() == StmtKind.SAY) {
                if (!"*".equals(st.name()) && view.var(st.name()) == null) {
                    String msg = "line " + st.line() + ": player <" + st.name() + "> isn't available";
                    if (!errors.contains(msg)) errors.add(msg);
                } else {
                    // st.text() is the whole tellraw command. Values go in JSON-escaped, so they can't break out.
                    lines.add(MdpVars.substitute(st.text(), key -> {
                        String v = view.var(key);
                        return v == null ? null : jsonInner(v);
                    }));
                }
            } else {
                MdpExpr.ValueResult v = st.value().eval(view);
                if (v.isError()) {
                    String msg = "line " + st.line() + ": " + v.error();
                    if (!errors.contains(msg)) errors.add(msg);
                } else {
                    vals.put(st.name(), v.text());
                }
            }
        }
        return new Selected(lines, errors);
    }

    /** A JSON string literal. Values that were filled into a message can't break out of it. */
    static String jsonString(String text) {
        return "\"" + jsonInner(text) + "\"";
    }

    /** The inside of a JSON string: quotes, backslashes and control characters escaped. */
    static String jsonInner(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
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
        return sb.toString();
    }
}
