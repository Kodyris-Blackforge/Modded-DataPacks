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
    }

    public abstract static class Node {
        abstract Result eval(Context c);
        /** Collects referenced variable keys and list-file paths. */
        abstract void collect(Set<String> vars, Set<String> paths);
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
    }

    static final class Not extends Node {
        final Node a;
        Not(Node a) { this.a = a; }
        Result eval(Context c) {
            Result x = a.eval(c);
            return x.isError() ? x : Result.ok(!x.value());
        }
        void collect(Set<String> v, Set<String> p) { a.collect(v, p); }
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

            Operand left = operand();
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

            if (t.type == T.WORD && t.text.equals("exists")) {
                next();
                Tok in = next();
                if (in.type != T.WORD || !in.text.equals("in"))
                    throw new ExprException("expected 'in' after 'exists', as in: <x> exists in list.json " + at(in));
                Tok p = next();
                if (p.type != T.WORD && p.type != T.STR)
                    throw new ExprException("expected a .json file name after 'exists in' " + at(p));
                String bad = JsonLists.checkPath(p.text);
                if (bad != null) throw new ExprException(bad);
                return new Exists(left, p.text);
            }

            boolean boolLit = left instanceof Lit l && l.v.kind() == Kind.BOOL;
            if (!(left instanceof Var) && !boolLit)
                throw new ExprException(left.show() + " is not a condition; compare it with ==, !=, <, <=, >, >= or 'exists in'");
            return new Truthy(left);
        }

        Operand operand() throws ExprException {
            Tok t = next();
            switch (t.type) {
                case VAR: return new Var(t.text);
                case STR: return new Lit(Kind.STR, t.text);
                case WORD:
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
