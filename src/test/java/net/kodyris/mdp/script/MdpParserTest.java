package net.kodyris.mdp.script;

import java.util.*;
import java.util.stream.Stream;

/** Plain main()-style tests so they run without JUnit or Minecraft on the classpath. */
public final class MdpParserTest {
    private static int failed = 0, passed = 0;

    private static final List<String> HDR = List.of(
        "//---MDP CONFIGS---",
        "cfg.Enabled = true    //Configuration Enabled (Allows running)",
        "cfg.Permission = 0    //Configuration Minimum Permission usability (0=any)",
        "//---FUNC BELOW---");

    static MdpFile file(String body) {
        List<String> lines = new ArrayList<>(HDR);
        lines.addAll(Arrays.asList(body.split("\n", -1)));
        return MdpParser.parseFile("t/file", lines);
    }

    static MdpFile raw(String... lines) { return MdpParser.parseFile("t/file", Arrays.asList(lines)); }

    static void check(String name, boolean ok) {
        if (ok) passed++; else { failed++; System.out.println("FAIL: " + name); }
    }

    static MdpBlock find(MdpFile f, String name) {
        for (MdpBlock b : f.blocks()) if (b.name().equals(name)) return b;
        return null;
    }

    public static void main(String[] args) {
        // 1. basic parse, line numbers, perm op
        MdpFile f = file("@perm=op\nfunction GiveKit(<player>) {\ngive <player> minecraft:bread 16\n}\n\n"
                       + "evt playerjoin(<player>) {\nmdp run t/file:GiveKit <player>\n}");
        check("basic: 2 blocks", f.blocks().size() == 2);
        MdpBlock gk = find(f, "GiveKit");
        check("basic: GiveKit active", gk != null && gk.isActive());
        check("basic: params", gk.params().equals(List.of("player")));
        check("basic: perm op", gk.perm() != null && gk.perm().serverOp());
        check("basic: body line number", gk.bodyLines().get(0) == 7);
        check("basic: key", gk.key().equals("t/file:GiveKit"));
        check("basic: usable", f.usable());

        // 2. header failures disable the whole file
        check("hdr: L1 text", raw("oops", "cfg.Enabled = true", "cfg.Permission = 0", "//x").disabledReason().startsWith("line 1"));
        check("hdr: L4 text", raw("//x", "cfg.Enabled = true", "cfg.Permission = 0", "junk").disabledReason().startsWith("line 4"));
        check("hdr: perm 9", raw("", "cfg.Enabled = true", "cfg.Permission = 9", "").disabledReason().startsWith("line 3"));
        check("hdr: perm text", raw("", "cfg.Enabled = true", "cfg.Permission = op", "").disabledReason() != null);
        check("hdr: enabled maybe", raw("", "cfg.Enabled = maybe", "cfg.Permission = 0", "").disabledReason().startsWith("line 2"));
        check("hdr: swapped", raw("", "cfg.Permission = 0", "cfg.Enabled = true", "").disabledReason() != null);
        check("hdr: too short", raw("//a", "cfg.Enabled = true").disabledReason() != null);
        check("hdr: empty file", raw().disabledReason() != null);
        check("hdr: blank L1/L4 ok", raw("", "cfg.Enabled = true", "cfg.Permission = 2", "").disabledReason() == null);
        check("hdr: permission value", raw("", "cfg.Enabled = true", "cfg.Permission = 2", "").config().permission() == 2);
        check("hdr: BOM ok", raw("\uFEFF//---MDP CONFIGS---", "cfg.Enabled = true", "cfg.Permission = 0", "//x").disabledReason() == null);
        MdpFile off = raw("", "cfg.Enabled = false", "cfg.Permission = 0", "");
        check("hdr: enabled=false parses but not usable", off.disabledReason() == null && !off.usable());

        // 3. isolation: bad block disables only itself
        f = file("evt bogus() {\nsay x\n}\n\nfunction Good() {\nsay ok\n}\n\nevt tick(<x>) {\nsay y\n}");
        check("iso: unknown event disabled", !find(f, "bogus").isActive());
        check("iso: good function active", find(f, "Good").isActive());
        check("iso: wrong arity disabled", !find(f, "tick").isActive());

        // 4. unclosed block resyncs, and the @perm below still attaches to the next block
        f = file("function Broken() {\nsay hi\n@perm=op\nfunction Secret() {\nsay secret\n}");
        check("resync: Broken disabled", !find(f, "Broken").isActive());
        check("resync: Broken reason", find(f, "Broken").disabledReason().contains("missing closing"));
        check("resync: Secret active", find(f, "Secret").isActive());
        check("resync: Secret keeps @perm=op", find(f, "Secret").perm() != null && find(f, "Secret").perm().serverOp());
        f = file("function A() {\nsay a\nfunction B() {\nsay b\n}");
        check("resync: header inside body", !find(f, "A").isActive() && find(f, "B").isActive());
        f = file("function Last() {\nsay a");
        check("resync: EOF unclosed", !find(f, "Last").isActive());

        // 5. annotations fail closed
        f = file("@prem=4\nfunction Typo() {\nsay a\n}");
        check("ann: unknown key disables", !find(f, "Typo").isActive());
        f = file("@perm=admin\nfunction Bad() {\nsay a\n}");
        check("ann: bad value disables", !find(f, "Bad").isActive());
        f = file("@perm=9\nfunction Bad() {\nsay a\n}");
        check("ann: out of range disables", !find(f, "Bad").isActive());
        f = file("@perm=2\n@perm=3\nfunction Dup() {\nsay a\n}");
        check("ann: duplicate disables", !find(f, "Dup").isActive());
        f = file("@perm\nfunction Mal() {\nsay a\n}");
        check("ann: malformed disables", !find(f, "Mal").isActive());
        f = file("@perm=2\nevt tick {\nsay a\n}");
        check("ann: @perm on evt disables", !find(f, "tick").isActive());
        f = file("@perm=2\n// a comment between\n\nfunction Gap() {\nsay a\n}");
        check("ann: comments/blank between ok", find(f, "Gap").isActive() && find(f, "Gap").perm().level() == 2);
        f = file("@PERM=2\nfunction Case() {\nsay a\n}");
        check("ann: key is case-sensitive (fails closed)", !find(f, "Case").isActive());
        f = file("@perm=OP\nfunction Up() {\nsay a\n}");
        check("ann: value case-insensitive", find(f, "Up").isActive() && find(f, "Up").perm().serverOp());
        f = file("@perm=any\nfunction Any() {\nsay a\n}");
        check("ann: any = 0", find(f, "Any").perm().level() == 0 && !find(f, "Any").perm().serverOp());
        f = file("@perm=2\n");
        check("ann: trailing annotation dropped with warning", f.blocks().isEmpty() && !f.warnings().isEmpty());

        // 6. old closer syntax is rejected, not silently unrestricted
        f = file("function Old() {\nsay a\n} > FuncPerm = 2\n\nfunction After() {\nsay b\n}");
        check("old closer: block disabled", !find(f, "Old").isActive());
        check("old closer: next block unaffected", find(f, "After").isActive());

        // 7. comments
        f = file("// full line comment\nfunction C() {\n// inside body\ntellraw @a {\"text\":\"see http://x.com\"} // trailing\nsay it's fine\n}");
        MdpBlock c = find(f, "C");
        check("comment: body size", c.body().size() == 2);
        check("comment: url kept, trailing stripped", c.body().get(0).equals("tellraw @a {\"text\":\"see http://x.com\"}"));
        check("comment: apostrophe safe", c.body().get(1).equals("say it's fine"));
        check("strip: quoted //", MdpParser.stripComment("say \"a // b\"").equals("say \"a // b\""));
        check("strip: escaped quote", MdpParser.stripComment("say \"a\\\" // b\" // c").equals("say \"a\\\" // b\" "));
        check("strip: no space before //", MdpParser.stripComment("a//b").equals("a//b"));

        // 8. params and aliases
        f = file("function F(player) {\nsay a\n}");
        check("param: missing <> disables", !find(f, "F").isActive());
        f = file("function G(<a>,<a>) {\nsay a\n}");
        check("param: duplicate disables", !find(f, "G").isActive());
        f = file("func Short() {\nsay a\n}");
        check("alias: func works", find(f, "Short") != null && find(f, "Short").isActive() && find(f, "Short").kind() == BlockKind.FUNC);
        f = file("function Spaced( <a> , <b> ) {\nsay a\n}");
        check("param: whitespace tolerated", find(f, "Spaced").isActive() && find(f, "Spaced").params().equals(List.of("a", "b")));
        f = file("evt tick {\nsay a\n}");
        check("evt: tick without parens", find(f, "tick").isActive());
        f = file("evt teamjoin(<p>,<t>) {\nsay a\n}\nevt tagadded(<p>,<t>) {\nsay a\n}");
        check("evt: teamjoin/tagadded", f.blocks().stream().allMatch(MdpBlock::isActive));

        // 9. malformed header text
        f = file("function Broken( {\nsay a\n}\n\nfunction Fine() {\nsay b\n}");
        check("malformed header: skipped with warning", f.blocks().size() == 1 && find(f, "Fine").isActive() && !f.warnings().isEmpty());

        // 10. registry
        MdpRegistry reg = new MdpRegistry();
        reg.add(file("function Dup() {\nsay a\n}\nfunction Dup() {\nsay b\n}\nevt playerjoin(<p>) {\nsay a\n}"));
        check("reg: first dup active, second disabled",
              reg.func("t/file:Dup").isActive() && reg.files().get("t/file").blocks().get(1).disabledReason() != null);
        check("reg: event registered", reg.eventBlocks("playerjoin").size() == 1);
        check("reg: unknown event empty", reg.eventBlocks("nope").isEmpty());
        MdpRegistry reg2 = new MdpRegistry();
        reg2.add(raw("bad", "x", "y", "z"));
        check("reg: header-error file registers nothing", reg2.funcs().isEmpty() && reg2.files().size() == 1);

        // 11. auto-header: only for files with NO header; a broken header is never rewritten
        List<String> noHdr = List.of("function Hi() {", "say hi", "}");
        check("autohdr: headerless needs header", MdpParser.needsHeader(noHdr));
        check("autohdr: empty needs header", MdpParser.needsHeader(List.of()));
        check("autohdr: blank/comment only needs header", MdpParser.needsHeader(List.of("", "// todo", "")));
        check("autohdr: leading comment then content needs header", MdpParser.needsHeader(List.of("// my script", "evt tick {", "say a", "}")));
        check("autohdr: valid header untouched", !MdpParser.needsHeader(HDR));
        check("autohdr: bad value never rewritten", !MdpParser.needsHeader(List.of("", "cfg.Enabled = true", "cfg.Permission = 9", "")));
        check("autohdr: cfg line missing value counts as present", !MdpParser.needsHeader(List.of("cfg.Enabled =", "cfg.Permission = 0")));
        check("autohdr: shifted header counts as present", !MdpParser.needsHeader(
            List.of("", "", "//---MDP CONFIGS---", "cfg.Enabled = true", "cfg.Permission = 0", "//---FUNC BELOW---")));
        check("autohdr: marker comment alone counts as present", !MdpParser.needsHeader(List.of("//---MDP CONFIGS---", "function A() {", "}")));
        check("autohdr: BOM + header untouched", !MdpParser.needsHeader(
            List.of("\uFEFF//---MDP CONFIGS---", "cfg.Enabled = true", "cfg.Permission = 0", "//---FUNC BELOW---")));
        List<String> fixed = MdpParser.withHeader(noHdr);
        MdpFile ff = MdpParser.parseFile("t/new", fixed);
        check("autohdr: result parses with defaults", ff.disabledReason() == null && ff.usable() && ff.config().permission() == 0);
        check("autohdr: block kept active", ff.blocks().size() == 1 && ff.blocks().get(0).isActive());
        check("autohdr: line numbers match the rewritten file", ff.blocks().get(0).bodyLines().get(0) == 7);
        check("autohdr: original lines preserved", fixed.subList(5, fixed.size()).equals(noHdr));
        check("autohdr: idempotent", !MdpParser.needsHeader(fixed));
        check("autohdr: empty file gets header only", MdpParser.withHeader(List.of()).equals(MdpParser.DEFAULT_HEADER));
        check("autohdr: BOM stripped", MdpParser.withHeader(List.of("\uFEFFevt tick {", "}")).get(5).equals("evt tick {"));
        check("autohdr: default header is itself valid", MdpParser.parseFile("t/h", MdpParser.DEFAULT_HEADER).usable());

        // 12. player variables: <p>, <p.name>, <p.uuid>
        f = file("evt playerjoin(<p>) {\nsay <p.uuid>\n}\nevt teamjoin(<p>,<t>) {\nsay <p.name> <t>\n}\n"
               + "function F(<player>,<amount>) {\nsay <player.uuid> <amount>\n}\nfunction G(<target>) {\nsay <target>\n}");
        check("vars: playerjoin first param is a player", find(f, "playerjoin").isPlayerParam("p"));
        check("vars: teamjoin team param is not", !find(f, "teamjoin").isPlayerParam("t") && find(f, "teamjoin").isPlayerParam("p"));
        check("vars: func param named player", find(f, "F").isPlayerParam("player") && !find(f, "F").isPlayerParam("amount"));
        check("vars: func other names are not players", !find(f, "G").isPlayerParam("target"));
        check("vars: valid refs stay active", f.blocks().stream().allMatch(MdpBlock::isActive));
        f = file("function A(<player>) {\nsay <player.foo>\n}");
        check("vars: unknown player property disables", !find(f, "A").isActive() && find(f, "A").disabledReason().contains("unknown player property"));
        check("vars: error names the line", find(f, "A").disabledReason().startsWith("line 6"));
        f = file("function B(<player>,<amount>) {\nsay <amount.uuid>\n}");
        check("vars: property on non-player disables", !find(f, "B").isActive() && find(f, "B").disabledReason().contains("not a player"));
        f = file("function C(<player>) {\nsay <other.thing> and <player>\n}");
        check("vars: dotted text that is not a param is left alone", find(f, "C").isActive());
        f = file("evt tagadded(<who>,<tagname>) {\nsay <who.uuid> <tagname>\n}");
        check("vars: event param can have any name", find(f, "tagadded").isActive() && find(f, "tagadded").isPlayerParam("who"));
        f = file("evt tick {\nsay <x.uuid>\n}");
        check("vars: undeclared <x.uuid> is plain text", find(f, "tick").isActive());

        Map<String, String> vars = new HashMap<>();
        MdpVars.putPlayer(vars, "p", "Steve", "11111111-2222-3333-4444-555555555555");
        vars.put("t", "red");
        check("subst: <p> is the name", MdpVars.substitute("hi <p>", vars).equals("hi Steve"));
        check("subst: <p.name>", MdpVars.substitute("hi <p.name>", vars).equals("hi Steve"));
        check("subst: <p.uuid>", MdpVars.substitute("as <p.uuid>", vars).equals("as 11111111-2222-3333-4444-555555555555"));
        check("subst: mixed", MdpVars.substitute("<p> <t> <p.uuid>", vars).equals("Steve red 11111111-2222-3333-4444-555555555555"));
        check("subst: unknown stays text", MdpVars.substitute("<nope> <p.zzz>", vars).equals("<nope> <p.zzz>"));
        vars.put("t", "<p>");
        check("subst: single pass, values not re-substituted", MdpVars.substitute("<t>", vars).equals("<p>"));
        vars.put("t", "a$1\\b");
        check("subst: regex chars in values are safe", MdpVars.substitute("<t>", vars).equals("a$1\\b"));
        check("subst: json braces untouched", MdpVars.substitute("{\"text\":\"<p>\"}", vars).equals("{\"text\":\"Steve\"}"));
        check("vars: baseName", MdpVars.baseName("p.uuid").equals("p") && MdpVars.baseName("p").equals("p"));

        // 13. evt loaded, and one-line empty blocks
        f = file("evt loaded() {\nsay ready\n}");
        check("loaded: parses and is active", find(f, "loaded").isActive() && find(f, "loaded").params().isEmpty());
        f = file("evt loaded {\nsay ready\n}");
        check("loaded: parens optional", find(f, "loaded").isActive());
        f = file("evt loaded(<x>) {\nsay a\n}");
        check("loaded: takes no parameters", !find(f, "loaded").isActive());
        f = file("evt loaded() {}\n\nfunction After() {\nsay a\n}");
        check("empty block: '{}' is active with no body", find(f, "loaded").isActive() && find(f, "loaded").body().isEmpty());
        check("empty block: next block unaffected", find(f, "After").isActive() && f.warnings().isEmpty());
        f = file("@perm=2\nfunction Nop() {}\nfunction Next() {\nsay a\n}");
        check("empty block: annotation still attaches", find(f, "Nop").isActive() && find(f, "Nop").perm().level() == 2);
        check("empty block: annotation does not leak to the next block", find(f, "Next").perm() == null);
        f = file("evt loaded() { }");
        check("empty block: whitespace inside braces", find(f, "loaded").isActive());
        f = file("evt loaded() {\n}");
        check("empty block: multi-line empty", find(f, "loaded").isActive() && find(f, "loaded").body().isEmpty());
        MdpRegistry r3 = new MdpRegistry();
        r3.add(file("evt loaded() {\nsay a\n}"));
        r3.add(MdpParser.parseFile("t/other", Stream.concat(HDR.stream(), Arrays.stream("evt loaded() {\nsay b\n}".split("\n"))).toList()));
        check("loaded: several handlers across files all register", r3.eventBlocks("loaded").size() == 2);

        // 14. blocks with no data
        MdpRegistry r4 = new MdpRegistry();
        r4.add(file("evt loaded() {}\nfunction Nop() {\n}\nfunction OnlyComments() {\n// nothing here\n\n}\n"
                  + "function Real() {\nsay hi\n}\n@perm=bogus\nfunction BrokenAndEmpty() {}\n"));
        List<String> emptyNames = new ArrayList<>();
        for (MdpBlock b : r4.emptyBlocks()) emptyNames.add(b.name());
        check("empty: finds loaded, Nop and OnlyComments", emptyNames.equals(List.of("loaded", "Nop", "OnlyComments")));
        check("empty: block with a command is not empty", !emptyNames.contains("Real"));
        check("empty: disabled block is not listed as empty", !emptyNames.contains("BrokenAndEmpty"));
        MdpRegistry r5 = new MdpRegistry();
        r5.add(MdpParser.parseFile("off", Arrays.asList("", "cfg.Enabled = false", "cfg.Permission = 0", "", "function Nop() {}")));
        r5.add(raw("bad", "x", "y", "z"));
        check("empty: files that are off or have a bad header are skipped", r5.emptyBlocks().isEmpty());
        check("empty: none when everything has content", new MdpRegistry().emptyBlocks().isEmpty());

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
