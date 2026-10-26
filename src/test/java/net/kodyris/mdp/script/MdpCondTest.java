package net.kodyris.mdp.script;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/** Tests for conditions: expressions, list files, and if/else blocks. Plain main(), no JUnit. */
public final class MdpCondTest {
    private static int failed = 0, passed = 0;

    static void check(String name, boolean ok) {
        if (ok) passed++; else { failed++; System.out.println("FAIL: " + name); }
    }

    // ---------------------------------------------------------------- helpers

    static Map<String, String> vars = new HashMap<>();
    static JsonLists lists;

    static MdpExpr.Context ctx() {
        return new MdpExpr.Context() {
            public String var(String key) { return vars.get(key); }
            public boolean listContains(String path, String value) throws ListException { return lists.contains(path, value); }
        };
    }

    /** Evaluates a condition: "true", "false", or "ERR". */
    static String ev(String cond) {
        try {
            MdpExpr.Result r = MdpExpr.parse(cond).eval(ctx());
            return r.isError() ? "ERR" : String.valueOf(r.value());
        } catch (MdpExpr.ExprException e) {
            return "PARSE";
        }
    }

    static String evErr(String cond) {
        try {
            MdpExpr.Result r = MdpExpr.parse(cond).eval(ctx());
            return r.error();
        } catch (MdpExpr.ExprException e) {
            return "PARSE: " + e.getMessage();
        }
    }

    static final List<String> HDR = List.of("//---MDP CONFIGS---", "cfg.Enabled = true", "cfg.Permission = 0", "//---FUNC BELOW---");

    static MdpFile file(String body) {
        List<String> lines = new ArrayList<>(HDR);
        lines.addAll(Arrays.asList(body.split("\n", -1)));
        return MdpParser.parseFile("t/file", lines);
    }

    static MdpBlock find(MdpFile f, String name) {
        for (MdpBlock b : f.blocks()) if (b.name().equals(name)) return b;
        return null;
    }

    static List<String> run(MdpBlock b) { return b.select(ctx()).lines(); }

    static void write(Path root, String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- main

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("mdp-cond-test");
        lists = new JsonLists(root);
        MdpExpr.Context c0;

        // 1. strings
        vars.clear();
        MdpVars.putPlayer(vars, "p", "Steve", "11111111-2222-3333-4444-555555555555");
        check("str: == literal", ev("<p.name> == \"Steve\"").equals("true"));
        check("str: == different", ev("<p.name> == \"Alex\"").equals("false"));
        check("str: != literal", ev("<p.name> != \"Alex\"").equals("true"));
        check("str: bare <p> is the name", ev("<p> == \"Steve\"").equals("true"));
        check("str: case sensitive", ev("<p.name> == \"steve\"").equals("false"));
        check("str: uuid ==", ev("<p.uuid> == \"11111111-2222-3333-4444-555555555555\"").equals("true"));
        check("str: literal == literal", ev("\"a\" == \"a\"").equals("true"));
        check("str: escaped quote", ev("\"a\\\"b\" == \"a\\\"b\"").equals("true"));
        check("str: escaped backslash", ev("\"a\\\\b\" == \"a\\\\b\"").equals("true"));
        check("str: slashes inside quotes", ev("\"a//b\" == \"a//b\"").equals("true"));
        check("str: var == var", ev("<p.name> == <p>").equals("true"));
        vars.put("t", "red");
        check("str: var != var", ev("<t> != <p.name>").equals("true"));
        check("str: empty string", ev("\"\" == \"\"").equals("true"));

        // 2. numbers
        vars.put("n", "5");
        check("num: == int", ev("<n> == 5").equals("true"));
        check("num: 5.0 == 5", ev("5.0 == <n>").equals("true"));
        check("num: != ", ev("<n> != 6").equals("true"));
        check("num: <", ev("<n> < 6").equals("true"));
        check("num: < false", ev("<n> < 5").equals("false"));
        check("num: <=", ev("<n> <= 5").equals("true"));
        check("num: >", ev("<n> > 4").equals("true"));
        check("num: >=", ev("<n> >= 6").equals("false"));
        check("num: negative literal", ev("-3 < 0").equals("true"));
        check("num: negative var", ev("<neg> < 0").equals("ERR"));   // undefined var is an error
        vars.put("neg", "-2.5");
        check("num: negative decimal var", ev("<neg> < 0").equals("true"));
        check("num: decimals", ev("<neg> == -2.50").equals("true"));
        vars.put("m", "10");
        check("num: var < var is numeric not textual", ev("<n> < <m>").equals("true"));   // "5" < "10" numerically
        vars.put("junk", "abc");
        check("num: == with non-number is false, not error", ev("<junk> == 5").equals("false"));
        check("num: != with non-number is true", ev("<junk> != 5").equals("true"));
        check("num: ordering non-number is an error", ev("<junk> > 5").equals("ERR"));
        check("num: error names the value", evErr("<junk> > 5").contains("'abc'"));
        check("num: ordering a non-number on the right is an error", ev("5 < <junk>").equals("ERR"));
        vars.put("lead", "05");
        check("num: leading zero", ev("<lead> == 5").equals("true"));
        check("num: string literal vs number coerces", ev("\"5\" == 5").equals("true"));

        // 3. bools
        vars.put("yes", "true");
        vars.put("no", "false");
        vars.put("Maybe", "maybe");
        check("bool: bare true var", ev("<yes>").equals("true"));
        check("bool: bare false var", ev("<no>").equals("false"));
        check("bool: bare literal", ev("true").equals("true"));
        check("bool: == true", ev("<yes> == true").equals("true"));
        check("bool: == false", ev("<no> == false").equals("true"));
        check("bool: != ", ev("<yes> != false").equals("true"));
        vars.put("up", "TRUE");
        check("bool: case-insensitive var text", ev("<up> == true").equals("true"));
        check("bool: bare non-bool var is an error", ev("<Maybe>").equals("ERR"));
        check("bool: == with non-bool is false", ev("<Maybe> == true").equals("false"));
        check("bool: ! var", ev("!<no>").equals("true"));
        check("bool: ! of error stays error", ev("!<Maybe>").equals("ERR"));

        // 4. logic and precedence
        check("logic: &&", ev("<yes> && <no>").equals("false"));
        check("logic: ||", ev("<yes> || <no>").equals("true"));
        check("logic: && binds tighter than ||", ev("true || false && false").equals("true"));
        check("logic: parens change grouping", ev("(true || false) && false").equals("false"));
        check("logic: !(...)", ev("!(<n> == 5)").equals("false"));
        check("logic: nested parens", ev("((<n> == 5))").equals("true"));
        check("logic: comparisons joined", ev("<n> >= 1 && <n> <= 10").equals("true"));
        check("logic: mixed types", ev("<p.name> == \"Steve\" && <n> > 3 && <yes>").equals("true"));
        check("logic: false && error short-circuits", ev("false && <junk> > 1").equals("false"));
        check("logic: true || error short-circuits", ev("true || <junk> > 1").equals("true"));
        check("logic: true && error is an error", ev("true && <junk> > 1").equals("ERR"));
        check("logic: !! ", ev("!!true").equals("true"));
        check("logic: whitespace tolerant", ev("  <n>   ==   5  ").equals("true"));

        // 5. syntax errors
        for (String bad : new String[]{
                "", "<n> ==", "== 5", "(<n> == 5", "<n> == 5)", "<n> == 5 &&", "&& true", "<n> = 5", "<n> & 1", "<n> | 1",
                "\"unterminated", "5", "\"x\"", "abc", "<n> == abc", "<n> < \"x\"", "\"x\" > 1", "true < 1",
                "<n> exists", "<n> exists in", "<n> exists in (", "<n> == 5 <m>", "<n> == 5 6", "\"a\\q\" == \"a\"", "#"}) {
            check("syntax: rejects [" + bad + "]", ev(bad).equals("PARSE"));
        }
        check("syntax: message mentions quotes hint", evErr("<n> == abc").contains("quotes"));
        check("syntax: = hint", evErr("<n> = 5").contains("=="));

        // 6. exists in (list files)
        write(root, "vip.json", "[\"11111111-2222-3333-4444-555555555555\", \"abc\", 7, true, 2.5, null, [\"nested\"], {\"x\":1}]");
        write(root, "map.json", "{\"AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE\": {\"rank\": 1}, \"plain\": 2}");
        write(root, "sub/dir/list.json", "[\"deep\"]");
        write(root, "empty.json", "[]");
        write(root, "bom.json", "\uFEFF[\"bom\"]");
        write(root, "bad.json", "[1, 2,");
        write(root, "scalar.json", "42");
        write(root, "notes.txt", "[\"a\"]");

        check("list: uuid in array", ev("<p.uuid> exists in vip.json").equals("true"));
        check("list: string in array", ev("\"abc\" exists in vip.json").equals("true"));
        check("list: not in array", ev("\"zzz\" exists in vip.json").equals("false"));
        check("list: number in array", ev("7 exists in vip.json").equals("true"));
        check("list: decimal number in array", ev("2.5 exists in vip.json").equals("true"));
        check("list: bool in array", ev("true exists in vip.json").equals("true"));
        check("list: nested values are not members", ev("\"nested\" exists in vip.json").equals("false"));
        check("list: null is not a member", ev("\"null\" exists in vip.json").equals("false"));
        check("list: var number against array", ev("<n> exists in vip.json").equals("false"));
        vars.put("seven", "7");
        check("list: var text 7 matches number 7", ev("<seven> exists in vip.json").equals("true"));
        check("list: object keys", ev("\"plain\" exists in map.json").equals("true"));
        check("list: object values are not members", ev("\"rank\" exists in map.json").equals("false"));
        check("list: uuid compare ignores case (list upper, value lower)", ev("\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\" exists in map.json").equals("true"));
        vars.put("UP", "11111111-2222-3333-4444-55555555555A".toUpperCase());
        check("list: non-uuid strings stay case-sensitive", ev("\"ABC\" exists in vip.json").equals("false"));
        check("list: subfolder path", ev("\"deep\" exists in sub/dir/list.json").equals("true"));
        check("list: quoted path", ev("\"deep\" exists in \"sub/dir/list.json\"").equals("true"));
        check("list: empty list", ev("\"a\" exists in empty.json").equals("false"));
        check("list: BOM tolerated", ev("\"bom\" exists in bom.json").equals("true"));
        check("list: ! exists", ev("!(\"zzz\" exists in vip.json)").equals("true"));
        check("list: combined with &&", ev("<p.name> == \"Steve\" && <p.uuid> exists in vip.json").equals("true"));
        check("list: missing file is an error", ev("\"a\" exists in nope.json").equals("ERR"));
        check("list: missing file message", evErr("\"a\" exists in nope.json").contains("not found"));
        check("list: invalid JSON is an error", ev("\"a\" exists in bad.json").equals("ERR"));
        check("list: invalid JSON message has position", evErr("\"a\" exists in bad.json").contains("line 1"));
        check("list: scalar JSON is an error", ev("\"a\" exists in scalar.json").equals("ERR"));
        check("list: missing file under || still short-circuits", ev("true || \"a\" exists in nope.json").equals("true"));

        // path safety is enforced when the condition is parsed
        for (String badPath : new String[]{"../x.json", "a/../../x.json", "/etc/x.json", "C:/x.json", "a\\b.json", "x.txt", "x", "./x.json", "a//b.json", ".json/"}) {
            check("path: rejects [" + badPath + "]", ev("\"a\" exists in \"" + badPath + "\"").equals("PARSE"));
        }
        check("path: .txt file rejected even if present", ev("\"a\" exists in notes.txt").equals("PARSE"));
        check("path: checkPath accepts normal", JsonLists.checkPath("a/b-c_d.json") == null);

        // live editing: a changed file is re-read without any reload
        write(root, "live.json", "[\"one\"]");
        check("live: before edit", ev("\"two\" exists in live.json").equals("false"));
        write(root, "live.json", "[\"one\", \"two\"]");
        check("live: after edit", ev("\"two\" exists in live.json").equals("true"));
        Files.delete(root.resolve("live.json"));
        check("live: deleted file becomes an error", ev("\"two\" exists in live.json").equals("ERR"));

        // symlink escaping the root is refused (skipped quietly where symlinks are unavailable)
        try {
            Path outside = Files.createTempDirectory("mdp-outside");
            Files.writeString(outside.resolve("secret.json"), "[\"s\"]");
            Files.createSymbolicLink(root.resolve("link.json"), outside.resolve("secret.json"));
            check("symlink: escape is refused", ev("\"s\" exists in link.json").equals("ERR"));
        } catch (UnsupportedOperationException | IOException e) {
            System.out.println("(symlink test skipped: " + e.getClass().getSimpleName() + ")");
        }

        // 7. variable collection
        Set<String> vs = new LinkedHashSet<>(), ps = new LinkedHashSet<>();
        MdpExpr.parse("<a.name> == <b> && <c> exists in x/y.json || !(<a.uuid> > 1)").collect(vs, ps);
        check("collect: variables", vs.equals(new LinkedHashSet<>(List.of("a.name", "b", "c", "a.uuid"))));
        check("collect: paths", ps.equals(Set.of("x/y.json")));

        // 8. if / else blocks
        vars.clear();
        MdpVars.putPlayer(vars, "player", "Steve", "11111111-2222-3333-4444-555555555555");
        MdpFile f = file("function Kit(<player>) {\nsay always\nif <player.uuid> exists in vip.json {\nsay vip\n} else {\nsay normal\n}\nsay after\n}");
        MdpBlock kit = find(f, "Kit");
        check("if: parses and is active", kit.isActive() && kit.hasConditions());
        check("if: body keeps every command line, braces excluded", kit.body().equals(List.of("say always", "say vip", "say normal", "say after")));
        check("if: true branch", run(kit).equals(List.of("say always", "say vip", "say after")));
        vars.clear();
        MdpVars.putPlayer(vars, "player", "Alex", "99999999-2222-3333-4444-555555555555");
        check("if: else branch", run(kit).equals(List.of("say always", "say normal", "say after")));
        check("if: line numbers kept", kit.bodyLines().equals(List.of(6, 8, 10, 12)));
        check("if: list path recorded", kit.listPaths().equals(Set.of("vip.json")));

        f = file("function Rank(<player>,<score>) {\nif <score> >= 90 {\nsay gold\n} else if <score> >= 50 {\nsay silver\n} else if <score> >= 10 {\nsay bronze\n} else {\nsay none\n}\n}");
        MdpBlock rank = find(f, "Rank");
        check("chain: parses", rank.isActive());
        for (String[] t : new String[][]{{"95", "say gold"}, {"90", "say gold"}, {"60", "say silver"}, {"10", "say bronze"}, {"9", "say none"}, {"0", "say none"}}) {
            vars.put("score", t[0]);
            check("chain: score " + t[0], run(rank).equals(List.of(t[1])));
        }
        vars.put("score", "abc");
        MdpBlock.Selected sel = rank.select(ctx());
        check("chain: a condition error skips the whole chain, else included", sel.lines().isEmpty());
        check("chain: error reported once with its line", sel.errors().size() == 1 && sel.errors().get(0).startsWith("line 6"));

        f = file("function Nest(<player>,<a>,<b>) {\nif <a> == 1 {\nsay a1\nif <b> == 1 {\nsay a1b1\n} else {\nsay a1b0\n}\n} else {\nsay a0\nif <b> == 1 {\nsay a0b1\n}\n}\nsay end\n}");
        MdpBlock nest = find(f, "Nest");
        check("nest: parses", nest.isActive());
        String[][] nt = {{"1", "1", "say a1,say a1b1,say end"}, {"1", "0", "say a1,say a1b0,say end"}, {"0", "1", "say a0,say a0b1,say end"}, {"0", "0", "say a0,say end"}};
        for (String[] t : nt) {
            vars.put("a", t[0]);
            vars.put("b", t[1]);
            check("nest: a=" + t[0] + " b=" + t[1], String.join(",", run(nest)).equals(t[2]));
        }

        f = file("evt playerjoin(<p>) {\nif <p.name> == \"Steve\" {\nsay hi steve\n}\n}");
        check("evt: if inside an event", find(f, "playerjoin").isActive() && find(f, "playerjoin").hasConditions());
        f = file("evt tick {\nif true {\nsay t\n}\n}");
        check("evt: tick with a constant condition", find(f, "tick").isActive() && find(f, "tick").hasConditions());
        f = file("function Plain() {\nsay a\n}");
        check("plain block has no conditions", !find(f, "Plain").hasConditions());
        f = file("function OnlyIf() {\nif true {\nsay yes\n}\n}");
        check("if without else: true runs", run(find(f, "OnlyIf")).equals(List.of("say yes")));
        f = file("function OnlyIf() {\nif false {\nsay yes\n}\n}");
        check("if without else: false runs nothing", run(find(f, "OnlyIf")).isEmpty());
        f = file("function Empty() {\nif true {\n}\n}");
        check("if with an empty body leaves the block empty", find(f, "Empty").isActive() && find(f, "Empty").body().isEmpty());
        f = file("function Cm(<player>) {\nif <player.name> == \"a//b\" { // trailing comment\nsay x\n} // another\n}");
        check("if: comments around braces and // inside strings", find(f, "Cm").isActive() && find(f, "Cm").body().equals(List.of("say x")));
        f = file("function Pe(<player>) {\nif (<player.name> == \"a\") {\nsay x\n}\n}");
        check("if: parenthesised condition", find(f, "Pe").isActive());

        // 9. structural errors disable only that block
        f = file("function A() {\nif true {\nsay a\n\nfunction B() {\nsay b\n}");
        check("err: unclosed if disables the block", !find(f, "A").isActive() && find(f, "A").disabledReason().contains("unclosed 'if' from line 6"));
        check("err: next block still loads", find(f, "B").isActive());
        f = file("function A() {\nif true {\nsay a\n}\n\nfunction B() {\nsay b\n}");
        check("err: if closed but block not closed disables", !find(f, "A").isActive() && find(f, "A").disabledReason().contains("missing closing"));
        check("err: next block loads after that too", find(f, "B").isActive());
        f = file("function A() {\nif true {\nsay a\n}\nelse {\nsay b\n}\n}\nfunction B() {\nsay b\n}");
        check("err: else on its own line gets a clear message", !find(f, "A").isActive() && find(f, "A").disabledReason().contains("same line as the closing brace"));
        check("err: block after the else mistake still loads", find(f, "B").isActive());
        f = file("function A() {\nsay a\n} else {\nsay b\n}\nfunction B() {\nsay b\n}");
        check("err: else without if disables", !find(f, "A").isActive());
        check("err: block after it unaffected", find(f, "B").isActive());
        f = file("function A() {\nelse {\nsay a\n}\n}");
        check("err: bare else is not a command and disables", !find(f, "A").isActive());
        f = file("function A() {\nif true {\nsay a\n} else {\nsay b\n} else {\nsay c\n}\n}");
        check("err: second else disables", !find(f, "A").isActive() && find(f, "A").disabledReason().contains("nothing can follow"));
        f = file("function A() {\nif true {\nsay a\n} else if false {\nsay b\n} else {\nsay c\n} else if true {\nsay d\n}\n}");
        check("err: else if after else disables", !find(f, "A").isActive());
        f = file("function A() {\nif {\nsay a\n}\n}");
        check("err: if without condition disables", !find(f, "A").isActive() && find(f, "A").disabledReason().contains("malformed 'if'"));
        f = file("function A() {\nif true\nsay a\n}\nfunction B() {\nsay b\n}");
        check("err: if without brace disables only that block", !find(f, "A").isActive() && find(f, "B").isActive());
        f = file("function A() {\nif <x> == 1 {\nsay a\n}\n}");
        check("err: unknown variable in condition", !find(f, "A").isActive() && find(f, "A").disabledReason().contains("unknown variable <x>"));
        check("err: unknown variable names the line", find(f, "A").disabledReason().startsWith("line 6"));
        f = file("function A(<player>,<n>) {\nif <n.uuid> == \"x\" {\nsay a\n}\n}");
        check("err: property on a non-player", !find(f, "A").isActive() && find(f, "A").disabledReason().contains("not a player"));
        f = file("function A(<player>) {\nif <player.foo> == \"x\" {\nsay a\n}\n}");
        check("err: unknown player property", !find(f, "A").isActive() && find(f, "A").disabledReason().contains("unknown player property"));
        f = file("evt tick {\nif <player> == \"x\" {\nsay a\n}\n}");
        check("err: tick has no variables", !find(f, "tick").isActive() && find(f, "tick").disabledReason().contains("no parameters"));
        f = file("function A() {\nif \"a\" exists in ../x.json {\nsay a\n}\n}");
        check("err: unsafe list path disables the block", !find(f, "A").isActive() && find(f, "A").disabledReason().contains("'..'"));
        f = file("function A() {\nif 5 {\nsay a\n}\n}");
        check("err: constant that is not a condition", !find(f, "A").isActive());
        f = file("function A() {\nif true {\nsay a\n}}\nfunction B() {\nsay b\n}");
        check("err: '}}' is not a valid closer", !find(f, "A").isActive() && find(f, "B").isActive());

        // an @perm above a block with conditions still works, and a bad condition does not leak into later blocks
        f = file("@perm=op\nfunction Secret(<player>) {\nif <player.name> == \"x\" {\nsay a\n}\n}\nfunction Other() {\nsay b\n}");
        check("perm: still attaches", find(f, "Secret").perm() != null && find(f, "Secret").perm().serverOp());
        check("perm: does not leak", find(f, "Other").perm() == null);

        // 10. registry: referenced list files
        MdpRegistry reg = new MdpRegistry();
        reg.add(file("function A(<player>) {\nif <player.uuid> exists in vip.json {\nsay a\n}\n}\nfunction B(<player>) {\nif <player.uuid> exists in lists/ban.json {\nsay b\n}\n}"));
        Set<String> used = reg.referencedLists().keySet();
        check("registry: referencedLists", used.equals(Set.of("vip.json", "lists/ban.json")));
        check("registry: referencedLists maps to blocks", reg.referencedLists().get("vip.json").get(0).name().equals("A"));

        System.out.println(passed + " passed, " + failed + " failed");
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        if (failed > 0) System.exit(1);
    }
}
