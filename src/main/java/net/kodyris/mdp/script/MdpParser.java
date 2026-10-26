package net.kodyris.mdp.script;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses .mdpfunc files. No Minecraft dependencies.
 *
 * File layout:
 *   L1 //---MDP CONFIGS---          (empty or comment)
 *   L2 cfg.Enabled = true|false
 *   L3 cfg.Permission = 0-4
 *   L4 //---FUNC BELOW---           (empty or comment)
 *   ...blocks...
 *
 * A bad header disables the whole file. A bad block disables only that block.
 */
public final class MdpParser {
    private MdpParser() {}

    public static final int HEADER_LINES = 4;

    private static final Map<String, Integer> ARITY =
        Map.of("loaded", 0, "tick", 0, "playerjoin", 1, "teamjoin", 2, "tagadded", 2);

    private static final Pattern HEADER =
        Pattern.compile("^(evt|func|function)\\s+(\\w+)\\s*(?:\\(([^)]*)\\))?\\s*\\{\\s*(\\})?$");
    private static final Pattern LOOKS_LIKE_HEADER = Pattern.compile("^(evt|func|function)\\b");
    /** Events whose FIRST parameter is the player involved. */
    private static final Set<String> PLAYER_EVENTS = Set.of("playerjoin", "teamjoin", "tagadded");
    private static final Pattern VAR_REF = Pattern.compile("<(\\w+)\\.(\\w+)>");
    private static final Pattern PARAM = Pattern.compile("^<(\\w+)>$");
    private static final Pattern IF_LINE = Pattern.compile("^if\\s+(.+?)\\s*\\{$");
    private static final Pattern IF_WORD = Pattern.compile("^if(\\s|\\{|$)");
    private static final Pattern ELSE_WORD = Pattern.compile("^else(\\s|\\{|$)");
    private static final Pattern ELSE_LINE = Pattern.compile("^\\}\\s*else(?:\\s+if\\s+(.+?))?\\s*\\{$");
    private static final Pattern ANNOTATION = Pattern.compile("^@(\\w+)\\s*=\\s*(\\S+)$");
    private static final Pattern CFG = Pattern.compile("^cfg\\.(\\w+)\\s*=\\s*(\\S+)$");

    /** Annotation registry: adding a new @key is one entry here. */
    private static final Map<String, BiConsumer<MdpBlock, String>> ANNOTATIONS =
        Map.of("perm", MdpParser::applyPerm);

    // ------------------------------------------------------------------ auto header

    /** Written to the top of a file that has no header at all. */
    public static final List<String> DEFAULT_HEADER = List.of(
        "//---MDP CONFIGS---",
        "cfg.Enabled = true    //Configuration Enabled (Allows running)",
        "cfg.Permission = 0    //Configuration Minimum Permission usability (0=any)",
        "//---FUNC BELOW---");

    /**
     * True only when the file has NO header. Looks at the leading region (blank and comment lines)
     * up to the first real line: a header marker comment or a 'cfg.' line there means a header
     * exists, even a broken or misplaced one. A broken header is never rewritten, it is reported
     * as an error instead, so a typo can't make the mod overwrite someone's settings.
     */
    public static boolean needsHeader(List<String> lines) {
        for (String line : lines) {
            if (line.contains("---MDP CONFIGS---") || line.contains("---FUNC BELOW---")) return false;
            String t = stripComment(line.startsWith("\uFEFF") ? line.substring(1) : line).strip();
            if (t.isEmpty()) continue;
            return !t.startsWith("cfg.");
        }
        return true;   // empty, or only blank/comment lines
    }

    /** The default header, a blank separator line, then the original lines (BOM removed). */
    public static List<String> withHeader(List<String> lines) {
        List<String> out = new ArrayList<>(DEFAULT_HEADER);
        List<String> body = new ArrayList<>(lines);
        if (!body.isEmpty() && body.get(0).startsWith("\uFEFF")) body.set(0, body.get(0).substring(1));
        if (body.stream().anyMatch(l -> !l.isBlank())) out.add("");
        out.addAll(body);
        return out;
    }

    // ------------------------------------------------------------------ comments

    /**
     * Removes a trailing or full-line '//' comment. '//' only counts when it is outside a
     * double-quoted string and at the start of the line or preceded by whitespace, so URLs
     * like http://x are safe. Only double quotes are tracked, because a lone apostrophe
     * in plain text (say it's fine) would otherwise swallow the rest of the line.
     */
    public static String stripComment(String s) {
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == '\\') i++;
                else if (c == '"') inQuote = false;
            } else if (c == '"') {
                inQuote = true;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/'
                    && (i == 0 || Character.isWhitespace(s.charAt(i - 1)))) {
                return s.substring(0, i);
            }
        }
        return s;
    }

    // ------------------------------------------------------------------ file + header

    public static MdpFile parseFile(String id, List<String> rawLines) {
        MdpFile f = new MdpFile(id);
        List<String> lines = new ArrayList<>(rawLines);
        if (!lines.isEmpty() && lines.get(0).startsWith("\uFEFF")) {
            lines.set(0, lines.get(0).substring(1));
        }
        try {
            f.setConfig(parseHeader(lines));
        } catch (HeaderException e) {
            f.disable("line " + e.line + ": " + e.getMessage());
            return f;
        }
        parseBlocks(f, lines.subList(HEADER_LINES, lines.size()), HEADER_LINES);
        return f;
    }

    static final class HeaderException extends Exception {
        final int line;
        HeaderException(int line, String msg) { super(msg); this.line = line; }
    }

    static FileConfig parseHeader(List<String> lines) throws HeaderException {
        if (lines.size() < HEADER_LINES)
            throw new HeaderException(Math.max(1, lines.size()), "file has fewer than the 4 required header lines");
        for (int i : new int[]{0, 3})
            if (!stripComment(lines.get(i)).isBlank())
                throw new HeaderException(i + 1, "must be empty or a '//' comment");

        boolean enabled;
        Matcher m2 = CFG.matcher(stripComment(lines.get(1)).strip());
        if (!m2.matches() || !m2.group(1).equals("Enabled"))
            throw new HeaderException(2, "expected 'cfg.Enabled = true|false'");
        switch (m2.group(2)) {
            case "true" -> enabled = true;
            case "false" -> enabled = false;
            default -> throw new HeaderException(2, "cfg.Enabled must be true or false");
        }

        Matcher m3 = CFG.matcher(stripComment(lines.get(2)).strip());
        if (!m3.matches() || !m3.group(1).equals("Permission"))
            throw new HeaderException(3, "expected 'cfg.Permission = 0-4'");
        int perm;
        try {
            perm = Integer.parseInt(m3.group(2));
            if (perm < 0 || perm > 4) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            throw new HeaderException(3, "cfg.Permission must be an integer from 0 to 4");
        }
        return new FileConfig(enabled, perm);
    }

    // ------------------------------------------------------------------ blocks

    /** One open if/else chain while parsing a body. */
    private static final class Frame {
        final List<MdpBlock.GuardTerm> base;     // guards of the enclosing scope
        final int line;
        final List<MdpBlock.GuardTerm> prior = new ArrayList<>();   // the if / else-if conditions so far (expected=true)
        List<MdpBlock.GuardTerm> active;         // guards for lines in the branch being read
        boolean elseSeen;
        Frame(List<MdpBlock.GuardTerm> base, int line) { this.base = base; this.line = line; this.active = base; }
    }

    private static List<MdpBlock.GuardTerm> guard(Deque<Frame> frames) {
        return frames.isEmpty() ? List.of() : frames.peek().active;
    }

    private static String unclosed(Deque<Frame> frames) {
        return frames.isEmpty() ? "missing closing '}'" : "unclosed 'if' from line " + frames.peek().line + " (missing '}')";
    }

    /** lineOffset = number of lines before 'lines' in the real file, so reported numbers match. */
    static void parseBlocks(MdpFile f, List<String> lines, int lineOffset) {
        String file = f.id();
        MdpBlock cur = null;
        boolean skipping = false;                 // swallowing the body of an unusable header
        Deque<Frame> frames = new ArrayDeque<>();
        Map<String, String> pending = new LinkedHashMap<>();
        List<String> pendingErrs = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            int ln = i + 1 + lineOffset;
            String t = stripComment(lines.get(i)).strip();
            if (t.isEmpty()) continue;

            // Annotations. Also ends an unclosed block, so the @perm below it
            // still attaches to the next block instead of being swallowed.
            if (t.startsWith("@")) {
                if (cur != null) { cur.disable(unclosed(frames)); cur = null; }
                frames.clear();
                skipping = false;
                Matcher a2 = ANNOTATION.matcher(t);
                if (!a2.matches()) pendingErrs.add("malformed annotation '" + t + "'");
                else if (pending.putIfAbsent(a2.group(1), a2.group(2)) != null)
                    pendingErrs.add("duplicate annotation @" + a2.group(1));
                continue;
            }

            Matcher m = HEADER.matcher(t);
            if (m.matches()) {
                if (cur != null) cur.disable(unclosed(frames));
                frames.clear();
                skipping = false;
                cur = open(file, ln, m);
                attach(cur, pending, pendingErrs);
                pending.clear();
                pendingErrs.clear();
                f.blocks().add(cur);
                if (m.group(4) != null) cur = null;     // '{}' on one line: an empty block, already closed
                continue;
            }

            if (cur != null) {
                Matcher ifm = IF_LINE.matcher(t);
                if (ifm.matches()) { openIf(cur, frames, ifm.group(1), ln); continue; }
                if (IF_WORD.matcher(t).find()) {
                    cur.disable("line " + ln + ": malformed 'if', expected: if <condition> {");
                    if (t.endsWith("{")) frames.push(new Frame(guard(frames), ln));   // keep braces balanced
                    continue;
                }
                if (ELSE_WORD.matcher(t).find()) {
                    cur.disable("line " + ln + ": 'else' must be on the same line as the closing brace: } else {");
                    if (t.endsWith("{")) frames.push(new Frame(guard(frames), ln));   // keep braces balanced
                    continue;
                }
                Matcher els = ELSE_LINE.matcher(t);
                if (els.matches()) { elseBranch(cur, frames, els.group(1), ln); continue; }
                if (t.startsWith("}")) {
                    if (!t.equals("}"))
                        cur.disable("closing line must be just '}' (set permissions with @perm above the block)");
                    if (!frames.isEmpty()) frames.pop(); else cur = null;
                    continue;
                }
                cur.addBodyLine(t, ln, guard(frames));
                continue;
            }

            if (t.startsWith("}")) {
                if (skipping) skipping = false;
                else f.warnings().add(file + ":" + ln + ": stray '}'");
                continue;
            }

            if (!skipping) {
                if (LOOKS_LIKE_HEADER.matcher(t).find()) {
                    f.warnings().add(file + ":" + ln + ": malformed block header, skipping its body");
                    skipping = true;
                } else {
                    f.warnings().add(file + ":" + ln + ": line outside any block ignored");
                }
                if (!pending.isEmpty() || !pendingErrs.isEmpty()) {
                    f.warnings().add(file + ":" + ln + ": annotations dropped, no block follows them");
                    pending.clear();
                    pendingErrs.clear();
                }
            }
        }
        if (cur != null) cur.disable(unclosed(frames));
        if (!pending.isEmpty() || !pendingErrs.isEmpty())
            f.warnings().add(file + ": annotations at end of file dropped, no block follows them");
        for (MdpBlock b : f.blocks()) checkVarRefs(b);
    }

    private static void openIf(MdpBlock b, Deque<Frame> frames, String text, int ln) {
        b.markConditions();
        MdpExpr.Node e = parseCond(b, text, ln);
        Frame f = new Frame(guard(frames), ln);
        MdpBlock.GuardTerm term = new MdpBlock.GuardTerm(e, true, ln);
        f.prior.add(term);
        f.active = concat(f.base, List.of(term));
        frames.push(f);
    }

    /** "} else {" (text == null) or "} else if <cond> {". Closes the branch above and opens the next one. */
    private static void elseBranch(MdpBlock b, Deque<Frame> frames, String text, int ln) {
        b.markConditions();
        if (frames.isEmpty()) {
            b.disable("line " + ln + ": 'else' without a matching 'if'");
            Frame dead = new Frame(List.of(), ln);
            dead.elseSeen = true;
            frames.push(dead);                       // keeps later '}' balanced
            return;
        }
        Frame f = frames.peek();
        if (f.elseSeen) {
            b.disable("line " + ln + ": nothing can follow 'else' in the same if");
            return;
        }
        List<MdpBlock.GuardTerm> g = new ArrayList<>(f.base);
        for (MdpBlock.GuardTerm p : f.prior) g.add(new MdpBlock.GuardTerm(p.expr(), false, p.line()));
        if (text == null) {
            f.elseSeen = true;
        } else {
            MdpExpr.Node e = parseCond(b, text, ln);
            MdpBlock.GuardTerm term = new MdpBlock.GuardTerm(e, true, ln);
            g.add(term);
            f.prior.add(term);
        }
        f.active = g;
    }

    private static List<MdpBlock.GuardTerm> concat(List<MdpBlock.GuardTerm> a, List<MdpBlock.GuardTerm> b) {
        List<MdpBlock.GuardTerm> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** Parses and validates one condition. On any problem the block is disabled and a placeholder is returned. */
    private static MdpExpr.Node parseCond(MdpBlock b, String text, int ln) {
        try {
            MdpExpr.Node n = MdpExpr.parse(text);
            Set<String> vars = new LinkedHashSet<>();
            Set<String> paths = new LinkedHashSet<>();
            n.collect(vars, paths);
            String err = checkCondVars(b, vars);
            if (err != null) {
                b.disable("line " + ln + ": " + err);
                return MdpExpr.TRUE;
            }
            b.addListPaths(paths);
            return n;
        } catch (MdpExpr.ExprException e) {
            b.disable("line " + ln + ": " + e.getMessage());
            return MdpExpr.TRUE;
        }
    }

    /** Unlike command lines, a condition has no plain-text fallback, so every <variable> must be real. */
    private static String checkCondVars(MdpBlock b, Set<String> keys) {
        for (String key : keys) {
            String base = MdpVars.baseName(key);
            if (!b.params().contains(base)) {
                StringBuilder have = new StringBuilder();
                for (String p : b.params()) have.append(have.length() > 0 ? ", " : "").append('<').append(p).append('>');
                return "unknown variable <" + key + ">" + (b.params().isEmpty() ? " (this block has no parameters)" : " (parameters: " + have + ")");
            }
            if (key.length() > base.length()) {
                String prop = key.substring(base.length() + 1);
                if (!b.isPlayerParam(base)) return "<" + key + ">: '" + base + "' is not a player, so it has no properties";
                if (!MdpVars.PLAYER_PROPS.contains(prop)) return "<" + key + ">: unknown player property '" + prop + "' (available: name, uuid)";
            }
        }
        return null;
    }

    /** <p.prop> must name a real property of a player parameter, otherwise it would reach a command as junk text. */
    private static void checkVarRefs(MdpBlock b) {
        if (!b.isActive()) return;
        for (int i = 0; i < b.body().size(); i++) {
            Matcher m = VAR_REF.matcher(b.body().get(i));
            while (m.find()) {
                String param = m.group(1), prop = m.group(2);
                if (!b.params().contains(param)) continue;      // not one of this block's variables: plain text
                String at = "line " + b.bodyLines().get(i) + ": <" + param + "." + prop + ">";
                if (!b.isPlayerParam(param)) { b.disable(at + " - '" + param + "' is not a player, so it has no properties"); return; }
                if (!MdpVars.PLAYER_PROPS.contains(prop)) { b.disable(at + " - unknown player property '" + prop + "' (available: name, uuid)"); return; }
            }
        }
    }

    private static MdpBlock open(String file, int ln, Matcher m) {
        BlockKind kind = m.group(1).equals("evt") ? BlockKind.EVT : BlockKind.FUNC;
        String name = m.group(2);

        List<String> params = new ArrayList<>();
        String badParam = null;
        String raw = m.group(3);
        if (raw != null && !raw.isBlank()) {
            for (String p : raw.split(",", -1)) {
                Matcher pm = PARAM.matcher(p.strip());
                if (pm.matches()) params.add(pm.group(1));
                else if (badParam == null) badParam = p.strip();
            }
        }

        MdpBlock b = new MdpBlock(kind, name, params, file, ln);
        if (kind == BlockKind.EVT && !params.isEmpty() && PLAYER_EVENTS.contains(name)) b.addPlayerParam(params.get(0));
        else if (kind == BlockKind.FUNC && params.contains("player")) b.addPlayerParam("player");
        if (badParam != null) b.disable("malformed parameter '" + badParam + "', expected <name>");
        else if (params.size() != new HashSet<>(params).size()) b.disable("duplicate parameter name");
        else if (kind == BlockKind.EVT) {
            Integer n = ARITY.get(name);
            if (n == null) b.disable("unknown event '" + name + "'");
            else if (params.size() != n) b.disable("event '" + name + "' takes " + n + " parameter(s)");
        }
        return b;
    }

    private static void attach(MdpBlock b, Map<String, String> ann, List<String> errs) {
        if (!errs.isEmpty()) { b.disable(errs.get(0)); return; }
        for (Map.Entry<String, String> e : ann.entrySet()) {
            BiConsumer<MdpBlock, String> handler = ANNOTATIONS.get(e.getKey());
            if (handler == null) { b.disable("unknown annotation @" + e.getKey()); return; }   // fail closed
            handler.accept(b, e.getValue());
            if (!b.isActive()) return;
        }
    }

    private static void applyPerm(MdpBlock b, String raw) {
        if (b.kind() != BlockKind.FUNC) { b.disable("@perm is only valid on function blocks"); return; }
        String key = raw.toLowerCase(Locale.ROOT);
        if (key.equals("any")) { b.setPerm(PermSpec.fixed("any", 0)); return; }
        if (key.equals("op")) { b.setPerm(PermSpec.opTier()); return; }
        try {
            int v = Integer.parseInt(key);
            if (v < 0 || v > 4) throw new NumberFormatException();
            b.setPerm(PermSpec.fixed(key, v));
        } catch (NumberFormatException e) {
            b.disable("@perm must be 0-4 or one of: any, op");
        }
    }
}
