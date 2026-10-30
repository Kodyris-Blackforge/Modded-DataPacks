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
        Map.of("init", 0, "loaded", 0, "tick", 0, "playerjoin", 1, "teamjoin", 2, "tagadded", 2);

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
                InlineIf one = inlineIf(t);
                if (one != null) {
                    cur.markConditions();
                    if (one.error() != null) { cur.disable("line " + ln + ": " + one.error()); continue; }
                    openIf(cur, frames, one.cond(), ln);
                    if (!one.body().isEmpty()) addStatement(cur, frames, one.body(), ln);
                    frames.pop();                      // a one-line if opens and closes in the same line
                    continue;
                }
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
                addStatement(cur, frames, t, ln);
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

    private static final Pattern SAY_START = Pattern.compile("^say\\s+[\"{]");
    private static final Pattern SAY_TO_VAR = Pattern.compile("^to\\s+<(\\w+(?:\\.\\w+)?)>$");
    private static final Pattern LOCAL_DECL = Pattern.compile("^local\\s+([A-Za-z_]\\w*)\\s*=(?!=)\\s*(.*)$");
    private static final Pattern LOCAL_WORD = Pattern.compile("^local(\\s|$)");
    private static final Pattern ASSIGN = Pattern.compile("^([A-Za-z_]\\w*)\\s*=(?!=)\\s*(.*)$");
    private static final Set<String> RESERVED_NAMES = Set.of("true", "false");
    private static final Pattern IF_PREFIX = Pattern.compile("^if\\s+");

    /** An "if <condition> { <command> }" written on one line. */
    private record InlineIf(String cond, String body, String error) {}

    /**
     * Splits a one-line if. Returns null when the line is not one (a non-if line, or the block form
     * "if <condition> {"), so the normal handling runs. A malformed one-line if comes back with an error.
     * Braces inside double-quoted strings are ignored and braces in the command (JSON/NBT) must balance.
     */
    private static InlineIf inlineIf(String t) {
        Matcher m = IF_PREFIX.matcher(t);
        if (!m.find()) return null;
        String rest = t.substring(m.end());
        int open = braceOutsideQuotes(rest);
        if (open < 0) return null;
        if (open == rest.length() - 1) return null;                    // "if <condition> {": the block form
        int close = matchingBrace(rest, open);
        if (close < 0) return new InlineIf(null, null, "a one-line 'if' is missing its closing '}'");
        if (close != rest.length() - 1)
            return new InlineIf(null, null, "a one-line 'if' can't be followed by anything, and has no inline 'else'; use the block form: if <condition> { ... } else { ... }");
        String cond = rest.substring(0, open).strip();
        String body = rest.substring(open + 1, close).strip();
        if (cond.isEmpty()) return new InlineIf(null, null, "malformed 'if', expected: if <condition> { <command> }");
        if (IF_WORD.matcher(body).find() || ELSE_WORD.matcher(body).find())
            return new InlineIf(null, null, "a one-line 'if' holds a single command; nest with the block form instead");
        return new InlineIf(cond, body, null);
    }

    private static int braceOutsideQuotes(String s) {
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '\\') i++;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{') return i;
        }
        return -1;
    }

    /** Index of the '}' that closes the '{' at 'open', or -1. */
    private static int matchingBrace(String s, int open) {
        int depth = 0;
        boolean quoted = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '\\') i++;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    /** A body line is a 'local' declaration, an assignment to a local, or a command. */
    private static void addStatement(MdpBlock b, Deque<Frame> frames, String t, int ln) {
        Matcher d = LOCAL_DECL.matcher(t);
        if (d.matches()) { declareLocal(b, frames, d.group(1), d.group(2), ln); return; }
        if (LOCAL_WORD.matcher(t).find()) {
            b.disable("line " + ln + ": malformed 'local', expected: local Name = value");
            return;
        }
        if (SAY_START.matcher(t).find()) { addSay(b, frames, t, ln); return; }
        Matcher a = ASSIGN.matcher(t);
        if (a.matches()) { assignLocal(b, frames, a.group(1), a.group(2), ln); return; }
        b.addBodyLine(t, ln, guard(frames));
    }

    private static final List<String> MC_COLORS = List.of("black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
        "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white");
    private static final Pattern COLOR_KEY = Pattern.compile(
        "([{,]\\s*)(\"?)(MCCOL|hex)(\"?)(\\s*:\\s*)(\"?)(#?)([A-Za-z0-9_]*)(\"?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLOR_TYPO = Pattern.compile("\"\\s*:\\s*(MCCOL|hex)\\s*:", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLOR_LEFTOVER = Pattern.compile("\\b(MCCOL|hex)\\b", Pattern.CASE_INSENSITIVE);

    /** True if a double-quoted string is still open at the end of the text. */
    private static boolean endsInsideString(String s) {
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '\\') i++;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
        }
        return quoted;
    }

    /** true for every character that is inside a double-quoted string (quotes included). */
    private static boolean[] quotedMask(String s) {
        boolean[] mask = new boolean[s.length()];
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                mask[i] = true;
                if (c == '\\') { if (i + 1 < s.length()) mask[++i] = true; }
                else if (c == '"') quoted = false;
            } else if (c == '"') {
                quoted = true;
                mask[i] = true;
            }
        }
        return mask;
    }

    /**
     * Turns the mdp colour keys inside a text component into the real "color" field:
     *   MCCOL:gold   (one of the 16 Minecraft colours)   ->  color:"gold"
     *   hex:ffaa00   (6 hex digits, # optional)          ->  color:"#ffaa00"
     * Only keys written as a field of the component are touched; text inside strings is left alone.
     * Throws IllegalArgumentException with a user-facing message if a key is malformed.
     */
    static String rewriteColors(String comp) {
        boolean[] in = quotedMask(comp);

        Matcher typo = COLOR_TYPO.matcher(comp);
        while (typo.find()) {
            if (!in[comp.indexOf(':', typo.start())])
                throw new IllegalArgumentException("separate fields with a comma, not a colon: {text:\"...\",hex:ffaa00} (found '" + typo.group().strip() + "')");
        }

        Matcher m = COLOR_KEY.matcher(comp);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            if (in[m.start()]) continue;                      // the { or , is inside a string: not a field
            boolean named = m.group(3).equalsIgnoreCase("MCCOL");
            String val = m.group(8);
            if (!m.group(6).equals(m.group(9))) throw new IllegalArgumentException("unbalanced quotes around the " + m.group(3) + " value");
            String color;
            if (named) {
                String name = val.toLowerCase(Locale.ROOT);
                if (!MC_COLORS.contains(name))
                    throw new IllegalArgumentException("MCCOL needs one of the Minecraft colours (" + String.join(", ", MC_COLORS) + "), found '" + val + "'");
                color = "\"" + name + "\"";
            } else {
                if (!val.matches("[0-9A-Fa-f]{6}"))
                    throw new IllegalArgumentException("hex needs 6 hex digits like ffaa00 (a leading # is fine), found '" + m.group(7) + val + "'");
                color = "\"#" + val.toLowerCase(Locale.ROOT) + "\"";
            }
            out.append(comp, last, m.start()).append(m.group(1))
               .append(m.group(2).isEmpty() ? "color" : "\"color\"").append(":").append(color);
            last = m.end();
        }
        out.append(comp.substring(last));

        String result = out.toString();
        boolean[] inResult = quotedMask(result);
        Matcher left = COLOR_LEFTOVER.matcher(result);
        while (left.find()) {
            if (!inResult[left.start()])
                throw new IllegalArgumentException("'" + left.group() + "' must be written as a field, like MCCOL:gold or hex:ffaa00, after a '{' or a ','");
        }
        return result;
    }

    /**
     * say "message"                      plain text
     * say {text:"message",color:gold}    a text component, written the same way as in tellraw
     * say {text:"message",MCCOL:gold}    MCCOL:<colour> and hex:<rrggbb> are shortcuts for the colour field
     * ...either one followed by:   (nothing) = the block's player  |  to <player>  |  to everyone  (also: to all)
     *
     * Both turn into "tellraw <target> <component>". The message / component may use <variables> and locals;
     * their values are escaped, so a quote or backslash in a value can't break the JSON.
     * It is only this effect when the message starts with a double quote or a '{'; anything else after
     * "say" (for example "say hello") stays the vanilla command.
     */
    private static void addSay(MdpBlock b, Deque<Frame> frames, String t, int ln) {
        String after = t.substring(3).stripLeading();
        String component;
        String rest;

        if (after.charAt(0) == '"') {
            StringBuilder msg = new StringBuilder();
            int j = 1;
            boolean closed = false;
            while (j < after.length()) {
                char c = after.charAt(j);
                if (c == '\\' && j + 1 < after.length() && (after.charAt(j + 1) == '"' || after.charAt(j + 1) == '\\')) {
                    msg.append(after.charAt(j + 1));
                    j += 2;
                } else if (c == '"') {
                    closed = true;
                    j++;
                    break;
                } else {
                    msg.append(c);
                    j++;
                }
            }
            if (!closed) { b.disable("line " + ln + ": say \"...\" has an unterminated string"); return; }
            component = "{\"text\":" + MdpBlock.jsonString(msg.toString()) + "}";
            rest = after.substring(j);
        } else {
            int close = matchingBrace(after, 0);
            if (close < 0) {
                b.disable("line " + ln + ": say {...} " + (endsInsideString(after)
                    ? "has an unterminated string (a closing \" is missing)" : "is missing its closing '}'"));
                return;
            }
            try {
                component = rewriteColors(after.substring(0, close + 1));
            } catch (IllegalArgumentException e) {
                b.disable("line " + ln + ": say {...}: " + e.getMessage());
                return;
            }
            rest = after.substring(close + 1);
        }

        rest = rest.strip().replaceAll("\\s+", " ");
        String target;
        if (rest.isEmpty()) {
            if (b.playerParams().isEmpty()) {
                b.disable("line " + ln + ": say has no player to message in this block; use: say ... to everyone");
                return;
            }
            target = b.playerParams().iterator().next();
        } else if (rest.equals("to everyone") || rest.equals("to all")) {
            target = "*";
        } else {
            Matcher m = SAY_TO_VAR.matcher(rest);
            if (!m.matches()) {
                b.disable("line " + ln + ": after the message expected 'to <player>' or 'to everyone', found: " + rest);
                return;
            }
            String key = m.group(1), base = MdpVars.baseName(key);
            if (!b.isPlayerParam(base)) { b.disable("line " + ln + ": 'to <" + key + ">' needs a player, but <" + base + "> is not one"); return; }
            if (key.length() > base.length() && !MdpVars.PLAYER_PROPS.contains(key.substring(base.length() + 1))) {
                b.disable("line " + ln + ": unknown player property in 'to <" + key + ">' (available: name, uuid)");
                return;
            }
            target = key;
        }
        // The stored line is the real command, so the loader checks it with Minecraft's own parser:
        // a bad component or an unknown colour disables just this block at load time.
        b.addSay("tellraw " + ("*".equals(target) ? "@a" : "<" + target + ">") + " " + component, target, guard(frames), ln);
    }

    private static void declareLocal(MdpBlock b, Deque<Frame> frames, String name, String rhs, int ln) {
        b.markConditions();
        if (!frames.isEmpty()) {
            b.disable("line " + ln + ": declare locals at the top level of the block, before any 'if'");
            return;
        }
        if (RESERVED_NAMES.contains(name)) { b.disable("line " + ln + ": '" + name + "' can't be used as a local name"); return; }
        if (b.params().contains(name)) { b.disable("line " + ln + ": '" + name + "' is already a parameter"); return; }
        if (b.locals().contains(name)) { b.disable("line " + ln + ": local '" + name + "' is already declared"); return; }
        MdpExpr.Value v = parseValueText(b, rhs, ln);        // sees only the locals declared before this one
        if (v != null) b.addLocal(name, v, ln);
    }

    private static void assignLocal(MdpBlock b, Deque<Frame> frames, String name, String rhs, int ln) {
        b.markConditions();
        if (!b.locals().contains(name)) {
            b.disable("line " + ln + ": " + (b.params().contains(name)
                ? "'" + name + "' is a parameter and can't be assigned to"
                : "unknown local '" + name + "' (declare it first: local " + name + " = ...)"));
            return;
        }
        MdpExpr.Value v = parseValueText(b, rhs, ln);
        if (v != null) b.addAssign(name, v, guard(frames), ln);
    }

    /** Parses and validates the right-hand side of a local. On any problem the block is disabled and null is returned. */
    private static MdpExpr.Value parseValueText(MdpBlock b, String text, int ln) {
        if (text.isBlank()) {
            b.disable("line " + ln + ": expected a value after '='");
            return null;
        }
        try {
            MdpExpr.Value v = MdpExpr.parseValue(text);
            Set<String> vars = new LinkedHashSet<>(), paths = new LinkedHashSet<>(), players = new LinkedHashSet<>();
            v.collect(vars, paths);
            v.collectPlayers(players);
            String err = checkRefs(b, vars, players);
            if (err != null) { b.disable("line " + ln + ": " + err); return null; }
            b.addListPaths(paths);
            return v;
        } catch (MdpExpr.ExprException e) {
            b.disable("line " + ln + ": " + e.getMessage());
            return null;
        }
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
            Set<String> vars = new LinkedHashSet<>(), paths = new LinkedHashSet<>(), players = new LinkedHashSet<>();
            n.collect(vars, paths);
            n.collectPlayers(players);
            String err = checkRefs(b, vars, players);
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

    /** Variables must exist, properties must be real, and 'has tag' / 'has team' must be given a player. */
    private static String checkRefs(MdpBlock b, Set<String> vars, Set<String> players) {
        String err = checkCondVars(b, vars);
        if (err != null) return err;
        for (String key : players) {
            String base = MdpVars.baseName(key);
            if (!b.isPlayerParam(base)) return "'has tag' / 'has team' needs a player, but <" + base + "> is not one";
        }
        return null;
    }

    /** Unlike command lines, a condition has no plain-text fallback, so every <variable> must be real. */
    private static String checkCondVars(MdpBlock b, Set<String> keys) {
        for (String key : keys) {
            String base = MdpVars.baseName(key);
            boolean isLocal = b.locals().contains(base);
            if (!b.params().contains(base) && !isLocal) {
                StringBuilder have = new StringBuilder();
                for (String p : b.params()) have.append(have.length() > 0 ? ", " : "").append('<').append(p).append('>');
                for (String l : b.locals()) have.append(have.length() > 0 ? ", " : "").append('<').append(l).append('>');
                return "unknown variable <" + key + ">" + (have.length() == 0 ? " (this block has no parameters or locals)" : " (available: " + have + ")");
            }
            if (key.length() > base.length()) {
                String prop = key.substring(base.length() + 1);
                if (isLocal) return "<" + key + ">: '" + base + "' is a local variable and has no properties";
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
                if (b.locals().contains(param)) { b.disable("line " + b.bodyLines().get(i) + ": <" + param + "." + prop + "> - '" + param + "' is a local variable and has no properties"); return; }
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
