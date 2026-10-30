package net.kodyris.mdp.script;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conditions for "if <condition> {".  No Minecraft dependencies.
 *
 *   <player.name> == "Steve"            strings   ==  !=
 *   <amount> >= 5      <a> < <b>        numbers   ==  !=  <  <=  >  >=
 *   <flag> == true     <flag>           bools     ==  !=  or a bare value
 *   <player.uuid> exists in vip.json    membership in a .json list file
 *   "vip.json" exists   <file> exists   a file or folder exists under ModDataPacks
 *   <player> has tag "Queued"           the player currently has that tag (also: has tag <tagname>)
 *   <player> has team "Breacher"        the player is currently on that team (also: has team <team>)
 *   count of players tagged "Breacher" > 3     a number: how many online players have that tag
 *   count of players on team "Breacher" >= <n> a number: how many online players are on that team
 *   !(...)   a && b   a || b   ( ... )  logic; && binds tighter than ||
 *
 * Variables are text at run time. They take the type of whatever they are compared with:
 * against a number literal "5" and "5.0" both equal 5; against true/false, "true"/"false" count.
 * == and != never error: a value of the wrong type is simply not equal.
 * Ordering (< <= > >=) needs numbers on both sides; otherwise the condition is an error and the
 * lines it guards (both the if and the else side) are skipped.
 */
public final class MdpExpr {
    private MdpExpr() {}

    public static final class ExprException extends Exception {
        public ExprException(String m) { super(m); }
    }

    /** Outcome of evaluating a condition: true, false, or an error message. */
    public record Result(boolean value, String error) {
        public static Result ok(boolean v) { return new Result(v, null); }
        public static Result err(String m) { return new Result(false, m); }
        public boolean isError() { return error != null; }
    }

    /** What a condition can look at while it runs. */
    public interface Context {
        /** Value of <key> or <key.prop>, or null if there is no such variable. */
        String var(String key);
        boolean listContains(String path, String value) throws ListException;
        /**
         * Whether the player held by variable 'playerVar' (a parameter name, e.g. "p") currently has the tag.
         * Returns null if that player cannot be found (offline).
         */
        /** Whether a file or folder exists at this path under ModDataPacks. Throws if the path is not allowed. */
        default boolean fileExists(String path) throws ListException { throw new ListException("files can't be checked here"); }
        default Boolean hasTag(String playerVar, String tag) { return null; }
        /** Whether the player held by 'playerVar' is currently on the named team. Null if the player cannot be found. */
        default Boolean hasTeam(String playerVar, String team) { return null; }
        /** Number of online players that currently have the tag, or null if players can't be counted. */
        default Integer countTagged(String tag) { return null; }
        /** Number of online players currently on the team, or null if players can't be counted. */
        default Integer countOnTeam(String team) { return null; }
    }

    public abstract static class Node {
        abstract Result eval(Context c);
        /** Collects referenced variable keys and list-file paths. */
        abstract void collect(Set<String> vars, Set<String> paths);
        /** Collects variables that must hold a player (the left side of 'has tag' / 'has team'). */
        void collectPlayers(Set<String> out) {}
    }

    // ------------------------------------------------------------------ values (for "local x = ...")

    /** The text a value expression produced, or an error. */
    public record ValueResult(String text, String error) {
        public boolean isError() { return error != null; }
    }

    /**
     * Something that can be assigned to a local: a string, number or bool literal, a variable,
     * a count, or a whole condition (which gives true or false).
     */
    public abstract static class Value {
        abstract ValueResult eval(Context c);
        abstract void collect(Set<String> vars, Set<String> paths);
        void collectPlayers(Set<String> out) {}
    }

    static final class OperandValue extends Value {
        final Operand o;
        OperandValue(Operand o) { this.o = o; }
        ValueResult eval(Context c) {
            try {
                return new ValueResult(o.val(c).text(), null);
            } catch (EvalFail e) {
                return new ValueResult(null, e.getMessage());
            }
        }
        void collect(Set<String> vars, Set<String> paths) { o.collect(vars); }
    }

    static final class CondValue extends Value {
        final Node n;
        CondValue(Node n) { this.n = n; }
        ValueResult eval(Context c) {
            Result r = n.eval(c);
            return r.isError() ? new ValueResult(null, r.error()) : new ValueResult(r.value() ? "true" : "false", null);
        }
        void collect(Set<String> vars, Set<String> paths) { n.collect(vars, paths); }
        void collectPlayers(Set<String> out) { n.collectPlayers(out); }
    }

    /** A lone value ("true", 5, "text", <x>, count of ...) or else a condition ("count of ... >= 4"). */
    public static Value parseValue(String text) throws ExprException {
        try {
            Parser p = new Parser(lex(text));
            Operand o = p.operand();
            if (p.peek().type == T.END) return new OperandValue(o);
        } catch (ExprException ignored) {
            // not a lone value: parse it as a condition below, which reports the real problem
        }
        return new CondValue(parse(text));
    }

    /** Always true. Stands in for a condition that failed to parse (its block is already disabled). */
    public static final Node TRUE = new Truthy(new Lit(Kind.BOOL, "true"));

    public static Node parse(String text) throws ExprException {
        Parser p = new Parser(lex(text));
        Node n = p.or();
        Tok extra = p.peek();
        if (extra.type != T.END) throw new ExprException("unexpected '" + extra.text + "' " + at(extra));
        return n;
    }

    // ------------------------------------------------------------------ values

    enum Kind { STR, NUM, BOOL }

    record Val(Kind kind, String text) {}

    private static final class EvalFail extends Exception {
        EvalFail(String m) { super(m, null, false, false); }
    }

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private static BigDecimal num(Val v) {
        String t = v.text().strip();
        return t.length() <= 40 && NUMBER.matcher(t).matches() ? new BigDecimal(t) : null;
    }

    private static Boolean bool(Val v) {
        String t = v.text().strip();
        if (t.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (t.equalsIgnoreCase("false")) return Boolean.FALSE;
        return null;
    }

    private static boolean equal(Val a, Val b) {
        if (a.kind() == Kind.NUM || b.kind() == Kind.NUM) {
            BigDecimal x = num(a), y = num(b);
            return x != null && y != null && x.compareTo(y) == 0;
        }
        if (a.kind() == Kind.BOOL || b.kind() == Kind.BOOL) {
            Boolean x = bool(a), y = bool(b);
            return x != null && x.equals(y);
        }
        return a.text().equals(b.text());
    }

    // ------------------------------------------------------------------ operands

    abstract static class Operand {
        abstract Val val(Context c) throws EvalFail;
        abstract void collect(Set<String> vars);
        abstract String show();
    }

    static final class Lit extends Operand {
        final Val v;
        Lit(Kind k, String text) { this.v = new Val(k, text); }
        Val val(Context c) { return v; }
        void collect(Set<String> vars) {}
        String show() { return v.kind() == Kind.STR ? "\"" + v.text() + "\"" : v.text(); }
    }

    static final class Var extends Operand {
        final String key;
        Var(String key) { this.key = key; }
        Val val(Context c) throws EvalFail {
            String s = c.var(key);
            if (s == null) throw new EvalFail("unknown variable <" + key + ">");
            return new Val(Kind.STR, s);
        }
        void collect(Set<String> vars) { vars.add(key); }
        String show() { return "<" + key + ">"; }
    }

    /** "count of players tagged X" / "count of players on team X": a number, read live from the online players. */
    static final class Count extends Operand {
        final boolean byTeam;
        final Operand name;
        Count(boolean byTeam, Operand name) { this.byTeam = byTeam; this.name = name; }
        Val val(Context c) throws EvalFail {
            String n = name.val(c).text();
            Integer r = byTeam ? c.countOnTeam(n) : c.countTagged(n);
            if (r == null) throw new EvalFail("players can't be counted here");
            return new Val(Kind.NUM, Integer.toString(r));
        }
        void collect(Set<String> vars) { name.collect(vars); }
        String show() { return "count of players " + (byTeam ? "on team " : "tagged ") + name.show(); }
    }

    // ------------------------------------------------------------------ nodes

    static final class Or extends Node {
        final Node a, b;
        Or(Node a, Node b) { this.a = a; this.b = b; }
        Result eval(Context c) {
            Result x = a.eval(c);
            if (x.isError()) return x;
            if (x.value()) return x;                 // short-circuit: b is never looked at
            return b.eval(c);
        }
        void collect(Set<String> v, Set<String> p) { a.collect(v, p); b.collect(v, p); }
        void collectPlayers(Set<String> out) { a.collectPlayers(out); b.collectPlayers(out); }
    }

    static final class And extends Node {
        final Node a, b;
        And(Node a, Node b) { this.a = a; this.b = b; }
        Result eval(Context c) {
            Result x = a.eval(c);
            if (x.isError()) return x;
            if (!x.value()) return x;
            return b.eval(c);
        }
        void collect(Set<String> v, Set<String> p) { a.collect(v, p); b.collect(v, p); }
        void collectPlayers(Set<String> out) { a.collectPlayers(out); b.collectPlayers(out); }
    }

    static final class Not extends Node {
        final Node a;
        Not(Node a) { this.a = a; }
        Result eval(Context c) {
            Result x = a.eval(c);
            return x.isError() ? x : Result.ok(!x.value());
        }
        void collect(Set<String> v, Set<String> p) { a.collect(v, p); }
        void collectPlayers(Set<String> out) { a.collectPlayers(out); }
    }

    static final class Truthy extends Node {
        final Operand o;
        Truthy(Operand o) { this.o = o; }
        Result eval(Context c) {
            try {
                Val v = o.val(c);
                Boolean b = bool(v);
                return b == null ? Result.err(o.show() + " is '" + v.text() + "', not true or false") : Result.ok(b);
            } catch (EvalFail e) {
                return Result.err(e.getMessage());
            }
        }
        void collect(Set<String> v, Set<String> p) { o.collect(v); }
    }

    static final class Cmp extends Node {
        final T op;
        final Operand a, b;
        Cmp(T op, Operand a, Operand b) { this.op = op; this.a = a; this.b = b; }
        Result eval(Context c) {
            try {
                Val x = a.val(c), y = b.val(c);
                if (op == T.EQ) return Result.ok(equal(x, y));
                if (op == T.NE) return Result.ok(!equal(x, y));
                BigDecimal p = num(x), q = num(y);
                if (p == null) return Result.err(a.show() + " is '" + x.text() + "', not a number");
                if (q == null) return Result.err(b.show() + " is '" + y.text() + "', not a number");
                int r = p.compareTo(q);
                return Result.ok(switch (op) {
                    case LT -> r < 0;
                    case LE -> r <= 0;
                    case GT -> r > 0;
                    default -> r >= 0;
                });
            } catch (EvalFail e) {
                return Result.err(e.getMessage());
            }
        }
        void collect(Set<String> v, Set<String> p) { a.collect(v); b.collect(v); }
    }

    static final class Exists extends Node {
        final Operand a;
        final String path;
        Exists(Operand a, String path) { this.a = a; this.path = path; }
        Result eval(Context c) {
            try {
                return Result.ok(c.listContains(path, a.val(c).text()));
            } catch (EvalFail e) {
                return Result.err(e.getMessage());
            } catch (ListException e) {
                return Result.err(e.getMessage());
            }
        }
        void collect(Set<String> v, Set<String> p) { a.collect(v); p.add(path); }
    }

    static final class FileExists extends Node {
        final Operand a;
        FileExists(Operand a) { this.a = a; }
        Result eval(Context c) {
            try {
                return Result.ok(c.fileExists(a.val(c).text()));
            } catch (EvalFail e) {
                return Result.err(e.getMessage());
            } catch (ListException e) {
                return Result.err(e.getMessage());
            }
        }
        void collect(Set<String> v, Set<String> p) { a.collect(v); }   // not a list file, so it is not reported as missing
    }

    static final class HasTag extends Node {
        final Var player;
        final Operand tag;
        HasTag(Var player, Operand tag) { this.player = player; this.tag = tag; }
        Result eval(Context c) {
            try {
                String base = MdpVars.baseName(player.key);
                String t = tag.val(c).text();
                Boolean r = c.hasTag(base, t);
                return r == null ? Result.err("player <" + base + "> is not online, so its tags can't be read") : Result.ok(r);
            } catch (EvalFail e) {
                return Result.err(e.getMessage());
            }
        }
        void collect(Set<String> v, Set<String> p) { player.collect(v); tag.collect(v); }
        void collectPlayers(Set<String> out) { out.add(player.key); }
    }

    static final class HasTeam extends Node {
        final Var player;
        final Operand team;
        HasTeam(Var player, Operand team) { this.player = player; this.team = team; }
        Result eval(Context c) {
            try {
                String base = MdpVars.baseName(player.key);
                Boolean r = c.hasTeam(base, team.val(c).text());
                return r == null ? Result.err("player <" + base + "> is not online, so its team can't be read") : Result.ok(r);
            } catch (EvalFail e) {
                return Result.err(e.getMessage());
            }
        }
        void collect(Set<String> v, Set<String> p) { player.collect(v); team.collect(v); }
        void collectPlayers(Set<String> out) { out.add(player.key); }
    }

    // ------------------------------------------------------------------ lexer

    enum T { LP, RP, NOT, AND, OR, EQ, NE, LT, LE, GT, GE, STR, WORD, VAR, END }

    record Tok(T type, String text, int pos) {}

    private static final Pattern VAR_AT = Pattern.compile("<(\\w+(?:\\.\\w+)?)>");
    private static final Pattern WORD_AT = Pattern.compile("[\\w./-]+");

    private static String at(Tok t) { return "(position " + (t.pos + 1) + ")"; }

    private static List<Tok> lex(String s) throws ExprException {
        List<Tok> out = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            switch (c) {
                case '(' -> { out.add(new Tok(T.LP, "(", i)); i++; continue; }
                case ')' -> { out.add(new Tok(T.RP, ")", i)); i++; continue; }
                case '"' -> {
                    StringBuilder sb = new StringBuilder();
                    int j = i + 1;
                    boolean closed = false;
                    while (j < n) {
                        char d = s.charAt(j);
                        if (d == '\\') {
                            if (j + 1 >= n) break;
                            char e = s.charAt(j + 1);
                            if (e != '"' && e != '\\') throw new ExprException("unknown escape '\\" + e + "' in string (position " + (j + 1) + ")");
                            sb.append(e);
                            j += 2;
                        } else if (d == '"') {
                            closed = true;
                            j++;
                            break;
                        } else {
                            sb.append(d);
                            j++;
                        }
                    }
                    if (!closed) throw new ExprException("unterminated string (position " + (i + 1) + ")");
                    out.add(new Tok(T.STR, sb.toString(), i));
                    i = j;
                    continue;
                }
                case '<' -> {
                    Matcher m = VAR_AT.matcher(s);
                    m.region(i, n);
                    if (m.lookingAt()) { out.add(new Tok(T.VAR, m.group(1), i)); i = m.end(); continue; }
                    if (i + 1 < n && s.charAt(i + 1) == '=') { out.add(new Tok(T.LE, "<=", i)); i += 2; }
                    else { out.add(new Tok(T.LT, "<", i)); i++; }
                    continue;
                }
                case '>' -> {
                    if (i + 1 < n && s.charAt(i + 1) == '=') { out.add(new Tok(T.GE, ">=", i)); i += 2; }
                    else { out.add(new Tok(T.GT, ">", i)); i++; }
                    continue;
                }
                case '=' -> {
                    if (i + 1 < n && s.charAt(i + 1) == '=') { out.add(new Tok(T.EQ, "==", i)); i += 2; continue; }
                    throw new ExprException("use '==' to compare (position " + (i + 1) + ")");
                }
                case '!' -> {
                    if (i + 1 < n && s.charAt(i + 1) == '=') { out.add(new Tok(T.NE, "!=", i)); i += 2; }
                    else { out.add(new Tok(T.NOT, "!", i)); i++; }
                    continue;
                }
                case '&' -> {
                    if (i + 1 < n && s.charAt(i + 1) == '&') { out.add(new Tok(T.AND, "&&", i)); i += 2; continue; }
                    throw new ExprException("use '&&' for 'and' (position " + (i + 1) + ")");
                }
                case '|' -> {
                    if (i + 1 < n && s.charAt(i + 1) == '|') { out.add(new Tok(T.OR, "||", i)); i += 2; continue; }
                    throw new ExprException("use '||' for 'or' (position " + (i + 1) + ")");
                }
                default -> { }
            }
            Matcher w = WORD_AT.matcher(s);
            w.region(i, n);
            if (w.lookingAt()) { out.add(new Tok(T.WORD, w.group(), i)); i = w.end(); continue; }
            throw new ExprException("unexpected character '" + c + "' (position " + (i + 1) + ")");
        }
        out.add(new Tok(T.END, "end of condition", n));
        return out;
    }

    // ------------------------------------------------------------------ parser

    private static final class Parser {
        private final List<Tok> toks;
        private int i = 0;

        Parser(List<Tok> toks) { this.toks = toks; }

        Tok peek() { return toks.get(i); }
        Tok peekAt(int k) { return toks.get(Math.min(i + k, toks.size() - 1)); }

        Tok next() {
            Tok t = toks.get(i);
            if (t.type != T.END) i++;
            return t;
        }

        Node or() throws ExprException {
            Node n = and();
            while (peek().type == T.OR) { next(); n = new Or(n, and()); }
            return n;
        }

        Node and() throws ExprException {
            Node n = not();
            while (peek().type == T.AND) { next(); n = new And(n, not()); }
            return n;
        }

        Node not() throws ExprException {
            if (peek().type == T.NOT) { next(); return new Not(not()); }
            return cmp();
        }

        Node cmp() throws ExprException {
            if (peek().type == T.LP) {
                next();
                Node e = or();
                if (peek().type != T.RP) throw new ExprException("missing ')' " + at(peek()));
                next();
                return e;
            }

            Operand left;
            Tok first = peek();
            if (first.type == T.WORD && peekAt(1).type == T.WORD && peekAt(1).text.equals("exists")
                    && !first.text.equals("true") && !first.text.equals("false") && !NUMBER.matcher(first.text).matches()) {
                next();                                       // a bare file name right before 'exists':  vip.json exists
                left = new Lit(Kind.STR, first.text);
            } else {
                left = operand();
            }
            Tok t = peek();
            switch (t.type) {
                case EQ, NE, LT, LE, GT, GE -> {
                    next();
                    Operand right = operand();
                    if (t.type != T.EQ && t.type != T.NE) {
                        for (Operand o : new Operand[]{left, right})
                            if (o instanceof Lit l && l.v.kind() != Kind.NUM)
                                throw new ExprException("'" + t.text + "' compares numbers, but " + o.show() + " is not a number");
                    }
                    return new Cmp(t.type, left, right);
                }
                default -> { }
            }

            if (t.type == T.WORD && t.text.equals("has")) {
                next();
                if (!(left instanceof Var pv))
                    throw new ExprException("'has tag' / 'has team' needs a player variable on the left, e.g. <player> has tag \"Queued\" " + at(t));
                Tok kw = next();
                if (kw.type != T.WORD || !(kw.text.equals("tag") || kw.text.equals("team")))
                    throw new ExprException("expected 'tag' or 'team' after 'has', as in: <player> has tag \"Queued\" " + at(kw));
                Tok name = next();
                Operand nameOp;
                switch (name.type) {
                    case STR, WORD -> nameOp = new Lit(Kind.STR, name.text);
                    case VAR -> nameOp = new Var(name.text);
                    default -> throw new ExprException("expected a " + kw.text + " name after 'has " + kw.text + "' " + at(name));
                }
                return kw.text.equals("tag") ? new HasTag(pv, nameOp) : new HasTeam(pv, nameOp);
            }

            if (t.type == T.WORD && t.text.equals("exists")) {
                next();
                Tok nxt = peek();
                if (nxt.type == T.WORD && nxt.text.equals("in")) {
                    next();
                    Tok p = next();
                    if (p.type != T.WORD && p.type != T.STR)
                        throw new ExprException("expected a .json file name after 'exists in' " + at(p));
                    String bad = JsonLists.checkPath(p.text);
                    if (bad != null) throw new ExprException(bad);
                    return new Exists(left, p.text);
                }
                // "<file> exists": does that file or folder exist
                boolean strLit = left instanceof Lit l && l.v.kind() == Kind.STR;
                if (!(left instanceof Var) && !strLit)
                    throw new ExprException("'exists' needs a file name in quotes or a variable, e.g. \"vip.json\" exists " + at(t));
                if (left instanceof Lit l) {
                    String bad = JsonLists.checkRelPath(l.v.text(), "file");
                    if (bad != null) throw new ExprException(bad);
                }
                return new FileExists(left);
            }

            boolean boolLit = left instanceof Lit l && l.v.kind() == Kind.BOOL;
            if (!(left instanceof Var) && !boolLit)
                throw new ExprException(left.show() + " is not a condition; compare it with ==, !=, <, <=, >, >= or 'exists in'");
            return new Truthy(left);
        }

        private void expectWord(String word, String example) throws ExprException {
            Tok t = next();
            if (t.type != T.WORD || !t.text.equals(word))
                throw new ExprException("expected '" + word + "' in: " + example + " " + at(t));
        }

        /** count of [all] players tagged <tag>   |   count of [all] players on|in team <team> */
        private Operand countPhrase(Tok start) throws ExprException {
            String example = "count of players tagged \"Breacher\"";
            expectWord("of", example);
            if (peek().type == T.WORD && peek().text.equals("all")) next();
            expectWord("players", example);
            Tok kind = next();
            boolean byTeam;
            if (kind.type == T.WORD && kind.text.equals("tagged")) {
                byTeam = false;
            } else if (kind.type == T.WORD && (kind.text.equals("on") || kind.text.equals("in"))) {
                expectWord("team", "count of players on team \"Breacher\"");
                byTeam = true;
            } else {
                throw new ExprException("expected 'tagged <tag>' or 'on team <team>' after 'count of players' " + at(kind));
            }
            Tok name = next();
            Operand nameOp = switch (name.type) {
                case STR, WORD -> new Lit(Kind.STR, name.text);
                case VAR -> new Var(name.text);
                default -> throw new ExprException("expected a " + (byTeam ? "team" : "tag") + " name " + at(name));
            };
            return new Count(byTeam, nameOp);
        }

        Operand operand() throws ExprException {
            Tok t = next();
            switch (t.type) {
                case VAR: return new Var(t.text);
                case STR: return new Lit(Kind.STR, t.text);
                case WORD:
                    if (t.text.equals("count")) return countPhrase(t);
                    if (t.text.equals("true") || t.text.equals("false")) return new Lit(Kind.BOOL, t.text);
                    if (NUMBER.matcher(t.text).matches()) return new Lit(Kind.NUM, t.text);
                    throw new ExprException("unexpected '" + t.text + "' " + at(t) + " - put text in quotes, e.g. \"" + t.text + "\"");
                case END:
                    throw new ExprException("expected a value but the condition ended");
                default:
                    throw new ExprException("expected a value, found '" + t.text + "' " + at(t));
            }
        }
    }
}
