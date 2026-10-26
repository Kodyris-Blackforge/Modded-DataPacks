package net.kodyris.mdp.script;

import java.util.ArrayList;
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
    /** True if the block contains any if/else, so its output can differ from call to call. */
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

    /**
     * Decides which body lines run for this call by evaluating the if/else conditions against ctx.
     * A condition that errors (for example comparing "abc" with a number) skips every line it guards,
     * on both the if side and the else side, and is reported in errors.
     */
    public Selected select(MdpExpr.Context ctx) {
        List<String> lines = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<MdpExpr.Node, MdpExpr.Result> memo = new IdentityHashMap<>();
        for (int i = 0; i < body.size(); i++) {
            boolean run = true;
            for (GuardTerm g : bodyGuards.get(i)) {
                MdpExpr.Result r = memo.get(g.expr());
                if (r == null) {
                    r = g.expr().eval(ctx);
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
            if (run) lines.add(body.get(i));
        }
        return new Selected(lines, errors);
    }
}
