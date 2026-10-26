package net.kodyris.mdp.runtime;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.kodyris.mdp.MdpMod;
import net.kodyris.mdp.script.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.ServerFunctionManager;
import net.minecraft.commands.CommandFunction;
import net.minecraft.world.scores.Team;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.stream.Stream;

/** Loads .mdpfunc files, validates them, and runs events and functions. Server thread only. */
public final class MdpRuntime {
    public static final String EXT = ".mdpfunc";
    private static final String DUMMY_UUID = "00000000-0000-0000-0000-000000000000";

    private static MdpRegistry registry = new MdpRegistry();
    private static boolean firingLoaded = false;
    /** Parameterless event blocks (tick) are compiled once per reload instead of every call. */
    private static final Map<MdpBlock, CommandFunction> COMPILED = new IdentityHashMap<>();
    private static final Map<UUID, Snapshot> SNAPSHOTS = new HashMap<>();

    private record Snapshot(Set<String> tags, String team) {}

    /** Backs "exists in <file>.json": files live under ModDataPacks and are re-read when they change. */
    private static JsonLists lists;
    /** Condition errors already logged for events, so a tick handler can't flood the log. */
    private static final Set<String> WARNED = new HashSet<>();

    private static JsonLists lists() {
        if (lists == null) lists = new JsonLists(root());
        return lists;
    }

    private static final class CondContext implements MdpExpr.Context {
        private final Map<String, String> vars;
        CondContext(Map<String, String> vars) { this.vars = vars; }
        public String var(String key) { return vars.get(key); }
        public boolean listContains(String path, String value) throws ListException { return lists().contains(path, value); }
    }

    /** Referenced list files that do not exist (yet), with the blocks that use them. */
    public static Map<String, List<MdpBlock>> missingLists() { return missingLists(registry); }

    private static Map<String, List<MdpBlock>> missingLists(MdpRegistry r) {
        Map<String, List<MdpBlock>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<MdpBlock>> e : r.referencedLists().entrySet())
            if (!Files.isRegularFile(root().resolve(e.getKey()))) out.put(e.getKey(), e.getValue());
        return out;
    }

    private MdpRuntime() {}

    public static MdpRegistry registry() { return registry; }

    /** <minecraft root>/ModDataPacks */
    public static Path root() { return FMLPaths.GAMEDIR.get().resolve("ModDataPacks"); }

    public static void clear() {
        registry = new MdpRegistry();
        COMPILED.clear();
        SNAPSHOTS.clear();
        WARNED.clear();
        lists = null;
    }

    // ------------------------------------------------------------------ loading

    /** Returns a one-line summary. Per-block problems go to the log and /mdp status. */
    public static String reload(MinecraftServer server) {
        MdpRegistry fresh = new MdpRegistry();
        Path root = root();
        try {
            Files.createDirectories(root);
            try (Stream<Path> walk = Files.walk(root)) {
                List<Path> found = walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(EXT))   // case-sensitive
                        .sorted().toList();
                for (Path p : found) fresh.add(load(root, p));
            }
        } catch (IOException e) {
            MdpMod.LOGGER.error("[mdp] could not scan {}", root, e);
        }

        for (MdpFile f : fresh.files().values())
            if (f.usable()) for (MdpBlock b : f.blocks()) validate(server, b);

        registry = fresh;
        COMPILED.clear();
        SNAPSHOTS.clear();
        WARNED.clear();
        lists = new JsonLists(root());
        for (ServerPlayer p : server.getPlayerList().getPlayers()) SNAPSHOTS.put(p.getUUID(), snapshot(p));
        String summary = report(fresh);

        // 'loaded' runs once the new scripts are live: at server start and after every /mdp reload.
        // Guard against a loaded handler that itself calls /mdp reload.
        if (!firingLoaded) {
            firingLoaded = true;
            try { fire(server, "loaded", null); } finally { firingLoaded = false; }
        }
        return summary;
    }

    private static MdpFile load(Path root, Path p) {
        String rel = root.relativize(p).toString().replace('\\', '/');
        String id = rel.substring(0, rel.length() - EXT.length());
        try {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            List<String> lines = text.lines().toList();

            if (MdpParser.needsHeader(lines)) {
                lines = MdpParser.withHeader(lines);
                String nl = text.contains("\r\n") ? "\r\n" : "\n";
                try {
                    writeAtomically(p, String.join(nl, lines) + nl);
                    MdpMod.LOGGER.info("[mdp] added the default header to '{}'", id);
                } catch (IOException e) {
                    // Fail closed: don't run a file whose on-disk text differs from what was parsed.
                    MdpFile f = new MdpFile(id);
                    f.disable("missing header, and it could not be added: " + e.getMessage());
                    return f;
                }
            }
            return MdpParser.parseFile(id, lines);
        } catch (IOException e) {
            MdpFile f = new MdpFile(id);
            f.disable("could not read file: " + e.getMessage());
            return f;
        }
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");   // not picked up by the scan
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String report(MdpRegistry r) {
        int files = 0, headerErrors = 0, off = 0, active = 0, disabled = 0;
        for (MdpFile f : r.files().values()) {
            files++;
            for (String w : f.warnings()) MdpMod.LOGGER.warn("[mdp] {}", w);
            if (f.disabledReason() != null) {
                headerErrors++;
                MdpMod.LOGGER.warn("[mdp] file '{}' disabled (header): {}", f.id(), f.disabledReason());
                continue;
            }
            if (!f.usable()) { off++; MdpMod.LOGGER.info("[mdp] file '{}' is off (cfg.Enabled = false)", f.id()); continue; }
            for (MdpBlock b : f.blocks()) {
                if (b.isActive()) active++;
                else {
                    disabled++;
                    MdpMod.LOGGER.warn("[mdp] disabled {} '{}' ({}:{}): {}", b.kind(), b.name(), b.file(), b.line(), b.disabledReason());
                }
            }
        }
        for (Map.Entry<String, List<MdpBlock>> e : missingLists(r).entrySet()) {
            String users = e.getValue().stream().map(MdpBlock::key).distinct().collect(java.util.stream.Collectors.joining(", "));
            MdpMod.LOGGER.warn("[mdp] list file '{}' not found (used by {})", e.getKey(), users);
        }
        List<MdpBlock> empty = r.emptyBlocks();
        active -= empty.size();        // empty blocks are reported on their own line
        if (!empty.isEmpty()) {
            MdpMod.LOGGER.info("[mdp] Blocks with no data: {}", empty.size());
            for (MdpBlock b : empty)
                MdpMod.LOGGER.info("[mdp]   no data: {} '{}' ({}:{})", b.kind(), b.name(), b.file(), b.line());
        }
        String s = files + " file(s) (" + headerErrors + " header error, " + off + " off), "
                + active + " active block(s), " + disabled + " disabled";
        MdpMod.LOGGER.info("[mdp] loaded {}", s);
        return s;
    }

    // ------------------------------------------------------------------ validation

    /** Dummy variable values so lines can be syntax-checked at load time. */
    private static Map<String, String> dummyArgs(MdpBlock b) {
        Map<String, String> m = new HashMap<>();
        if (b.kind() == BlockKind.EVT) {
            String[] extra = switch (b.name()) {
                case "teamjoin" -> new String[]{"mdp_team"};
                case "tagadded" -> new String[]{"mdp_tag"};
                default -> new String[0];
            };
            for (String pp : b.playerParams()) MdpVars.putPlayer(m, pp, "Dev", DUMMY_UUID);
            for (int i = 0; i < extra.length && i + 1 < b.params().size(); i++) m.put(b.params().get(i + 1), extra[i]);
        } else {
            for (String pp : b.playerParams()) MdpVars.putPlayer(m, pp, "Dev", DUMMY_UUID);
        }
        return m;
    }

    private static void validate(MinecraftServer server, MdpBlock b) {
        if (!b.isActive()) return;
        CommandDispatcher<CommandSourceStack> d = server.getCommands().getDispatcher();
        CommandSourceStack src = server.createCommandSourceStack();
        Map<String, String> dummy = dummyArgs(b);
        for (int i = 0; i < b.body().size(); i++) {
            String line = b.body().get(i);
            if (usesUntypedParam(line, b, dummy)) continue;   // value type unknown until run time
            ParseResults<CommandSourceStack> r = d.parse(MdpVars.substitute(line, dummy), src);
            CommandSyntaxException ex = Commands.getParseException(r);
            if (ex != null) {
                b.disable("line " + b.bodyLines().get(i) + ": " + ex.getMessage());
                return;
            }
        }
    }

    private static boolean usesUntypedParam(String line, MdpBlock b, Map<String, String> dummy) {
        Matcher m = MdpVars.VAR.matcher(line);
        while (m.find()) {
            String key = m.group(1);
            if (b.params().contains(MdpVars.baseName(key)) && !dummy.containsKey(key)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ execution

    /** vars holds every <param>, plus <param.name> and <param.uuid> for player parameters. */
    public static void execute(MinecraftServer server, CommandSourceStack src, MdpBlock b, Map<String, String> vars) {
        MdpFile f = registry.file(b.file());
        if (f == null || !f.usable() || !b.isActive() || b.body().isEmpty()) return;

        try {
            CommandFunction fn = COMPILED.get(b);
            if (fn == null) {
                // if/else conditions are decided per call, from this call's variables and the list files.
                MdpBlock.Selected sel = b.select(new CondContext(vars));
                for (String problem : sel.errors()) {
                    if (b.kind() == BlockKind.FUNC) {
                        src.sendFailure(Component.literal("[mdp] " + b.key() + " - condition error, " + problem));
                    } else if (WARNED.add(b.key() + "|" + problem)) {     // once per reload, not once per tick
                        MdpMod.LOGGER.warn("[mdp] {} '{}' ({}:{}) - condition error, {}", b.kind(), b.name(), b.file(), b.line(), problem);
                    }
                }
                if (sel.lines().isEmpty()) return;
                List<String> lines = sel.lines().stream().map(l -> MdpVars.substitute(l, vars)).toList();
                fn = CommandFunction.fromLines(
                        new ResourceLocation(MdpMod.MODID, "blk_" + Integer.toHexString((b.file() + ":" + b.line()).hashCode())),
                        server.getCommands().getDispatcher(), src, lines);
                // Only a block whose output can never change may be compiled once.
                if (b.kind() == BlockKind.EVT && b.params().isEmpty() && !b.hasConditions()) COMPILED.put(b, fn);
            }
            ServerFunctionManager fm = server.getFunctions();
            fm.execute(fn, src);
        } catch (IllegalArgumentException e) {
            if (b.kind() == BlockKind.EVT) {
                // Events run at level 4 with fixed arguments, so a failure here is a real bad line:
                // disable just this block.
                b.disable("failed at run time: " + e.getMessage());
                MdpMod.LOGGER.warn("[mdp] disabled evt '{}' ({}:{}): {}", b.name(), b.file(), b.line(), b.disabledReason());
            } else {
                // Functions parse with the caller's own permissions and caller-supplied arguments, so a
                // failure can be a caller problem (missing permission, bad value). Report it to the
                // caller; never disable the function for everyone because of it.
                src.sendFailure(Component.literal("[mdp] " + b.key() + " could not run: " + e.getMessage()));
            }
        } catch (Exception e) {
            MdpMod.LOGGER.warn("[mdp] error running '{}' ({}:{}): {}", b.name(), b.file(), b.line(), e.toString());
        }
    }

    /**
     * Events run at permission level 4 with the triggering player (if any) as the source.
     * The player fills the event's first parameter; extra fills the rest in order (team, tag name).
     */
    public static void fire(MinecraftServer server, String event, ServerPlayer player /* null = server source */, String... extra) {
        List<MdpBlock> blocks = registry.eventBlocks(event);
        if (blocks.isEmpty()) return;
        CommandSourceStack src = (player != null ? player.createCommandSourceStack() : server.createCommandSourceStack())
                .withPermission(4);
        for (MdpBlock b : blocks) {
            Map<String, String> vars = new HashMap<>();
            int next = 0;
            if (player != null && !b.params().isEmpty()) {
                putPlayer(vars, b.params().get(0), player);
                next = 1;
            }
            for (int i = 0; i < extra.length && next + i < b.params().size(); i++) vars.put(b.params().get(next + i), extra[i]);
            execute(server, src, b, vars);
        }
    }

    /** Puts <param>, <param.name> and <param.uuid>. */
    public static void putPlayer(Map<String, String> vars, String param, ServerPlayer p) {
        MdpVars.putPlayer(vars, param, p.getGameProfile().getName(), p.getUUID().toString());
    }

    /** Effective level to call a function: max(cfg.Permission, @perm). 'op' follows server.properties. */
    public static int requiredPerm(MinecraftServer server, MdpBlock b) {
        MdpFile f = registry.file(b.file());
        int base = f == null || f.config() == null ? 4 : f.config().permission();
        PermSpec p = b.perm();
        if (p == null) return base;
        int lvl = p.serverOp() ? Math.max(1, server.getOperatorUserPermissionLevel()) : p.level();
        return Math.max(base, lvl);
    }

    // ------------------------------------------------------------------ event sources

    public static void tick(MinecraftServer server) {
        fire(server, "tick", null);

        // teamjoin / tagadded have no Forge event, so changes are detected by diffing each tick.
        boolean wantTags = !registry.eventBlocks("tagadded").isEmpty();
        boolean wantTeams = !registry.eventBlocks("teamjoin").isEmpty();
        if (!wantTags && !wantTeams) return;

        for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) {
            Snapshot prev = SNAPSHOTS.get(p.getUUID());
            Snapshot now = snapshot(p);
            SNAPSHOTS.put(p.getUUID(), now);
            if (prev == null) continue;
            if (wantTags)
                for (String t : now.tags()) if (!prev.tags().contains(t)) fire(server, "tagadded", p, t);
            if (wantTeams && now.team() != null && !now.team().equals(prev.team()))
                fire(server, "teamjoin", p, now.team());
        }
    }

    /** Baseline first, so tags/team the player already had don't count as new. */
    public static void playerJoined(ServerPlayer p) {
        SNAPSHOTS.put(p.getUUID(), snapshot(p));
        fire(p.server, "playerjoin", p);
    }

    public static void playerLeft(UUID id) { SNAPSHOTS.remove(id); }

    private static Snapshot snapshot(ServerPlayer p) {
        Team team = p.getTeam();
        return new Snapshot(new TreeSet<>(p.getTags()), team == null ? null : team.getName());
    }
}
