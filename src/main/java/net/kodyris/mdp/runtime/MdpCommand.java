package net.kodyris.mdp.runtime;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.kodyris.mdp.script.*;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * /mdp run <file>:<Func> [args...]   anyone who meets the function's permission
 * /mdp reload                        level 2
 * /mdp status                        level 2
 */
public final class MdpCommand {
    private MdpCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("mdp")
            .then(Commands.literal("reload").requires(s -> s.hasPermission(2))
                .executes(c -> reload(c.getSource())))
            .then(Commands.literal("status").requires(s -> s.hasPermission(2))
                .executes(c -> status(c.getSource())))
            .then(Commands.literal("init").requires(s -> s.hasPermission(2))
                .then(Commands.literal("run")
                    .then(Commands.argument("file", StringArgumentType.greedyString())
                        .suggests((c, sb) -> SharedSuggestionProvider.suggest(MdpRuntime.filesWithInit().stream(), sb))
                        .executes(c -> initRun(c.getSource(), StringArgumentType.getString(c, "file")))))
                .then(Commands.literal("reset")
                    .then(Commands.argument("file", StringArgumentType.greedyString())
                        .suggests((c, sb) -> SharedSuggestionProvider.suggest(
                            Stream.concat(Stream.of("*"), MdpRuntime.filesWithInit().stream()), sb))
                        .executes(c -> initReset(c.getSource(), StringArgumentType.getString(c, "file"))))))
            .then(Commands.literal("run")
                .then(Commands.argument("call", StringArgumentType.greedyString())
                    .suggests(MdpCommand::suggest)
                    .executes(c -> run(c.getSource(), StringArgumentType.getString(c, "call"))))));
    }

    private static CommandSyntaxException err(String msg) {
        return new SimpleCommandExceptionType(Component.literal(msg)).create();
    }

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggest(
            CommandContext<CommandSourceStack> c, SuggestionsBuilder sb) {
        CommandSourceStack src = c.getSource();
        return SharedSuggestionProvider.suggest(
            MdpRuntime.registry().funcs().stream()
                .filter(MdpBlock::isActive)
                .filter(f -> MdpRuntime.registry().file(f.file()).usable())
                .filter(f -> src.hasPermission(MdpRuntime.requiredPerm(src.getServer(), f)))
                .map(MdpBlock::key), sb);
    }

    private static int run(CommandSourceStack src, String input) throws CommandSyntaxException {
        String[] tok = input.trim().split("\\s+");
        MdpBlock f = MdpRuntime.registry().func(tok[0]);
        if (f == null) throw err("Unknown mdpfunc '" + tok[0] + "'");

        MdpFile file = MdpRuntime.registry().file(f.file());
        if (!file.usable()) throw err("'" + f.file() + "' is turned off (cfg.Enabled = false)");
        if (!f.isActive()) throw err("mdpfunc '" + tok[0] + "' is disabled: " + f.disabledReason());

        int need = MdpRuntime.requiredPerm(src.getServer(), f);
        if (!src.hasPermission(need)) {
            String label = f.perm() != null && !f.perm().label().matches("\\d") ? " (" + f.perm().label() + ")" : "";
            throw err("'" + tok[0] + "' requires permission level " + need + label);
        }

        if (tok.length - 1 != f.params().size())
            throw err("Usage: /mdp run " + tok[0] + f.params().stream().map(p -> " <" + p + ">").collect(Collectors.joining()));

        Map<String, String> vars = new HashMap<>();
        for (int i = 0; i < f.params().size(); i++) {
            String param = f.params().get(i);
            String raw = tok[i + 1];
            if (f.isPlayerParam(param)) {
                // A parameter named "player" must resolve to exactly one online player.
                StringReader reader = new StringReader(raw);
                var selector = EntityArgument.player().parse(reader);
                if (reader.canRead()) throw err("Invalid player argument '" + raw + "'");
                ServerPlayer p = selector.findSinglePlayer(src);
                MdpRuntime.putPlayer(vars, param, p);    // <player>, <player.name>, <player.uuid>
            } else {
                vars.put(param, raw);
            }
        }

        MdpRuntime.execute(src.getServer(), src, f, vars);
        return 1;
    }

    private static int initRun(CommandSourceStack src, String file) throws CommandSyntaxException {
        try {
            int n = MdpRuntime.runInitNow(src.getServer(), file.trim());
            src.sendSuccess(() -> Component.literal("[mdp] ran init for " + file.trim() + " (" + n + " block(s))"), true);
            return n;
        } catch (IllegalArgumentException e) {
            throw err(e.getMessage());
        }
    }

    private static int initReset(CommandSourceStack src, String file) throws CommandSyntaxException {
        try {
            boolean changed = MdpRuntime.resetInit(src.getServer(), file.trim());
            String msg = changed
                ? "[mdp] init will run again for " + (file.trim().equals("*") ? "every file" : file.trim()) + " on the next /mdp reload"
                : "[mdp] nothing to reset: " + file.trim() + " is not marked as initialized";
            src.sendSuccess(() -> Component.literal(msg), true);
            return changed ? 1 : 0;
        } catch (IllegalArgumentException e) {
            throw err(e.getMessage());
        }
    }

    private static int reload(CommandSourceStack src) {
        String summary = MdpRuntime.reload(src.getServer());
        src.sendSuccess(() -> Component.literal("[mdp] reloaded: " + summary), true);

        if (!MdpRuntime.lastInitRan().isEmpty())
            send(src, ChatFormatting.GREEN, "Init ran for: " + String.join(", ", MdpRuntime.lastInitRan()));

        List<MdpBlock> empty = MdpRuntime.registry().emptyBlocks();
        if (!empty.isEmpty()) {
            send(src, ChatFormatting.YELLOW, "Blocks with no data: " + empty.size());
            for (MdpBlock b : empty)
                send(src, ChatFormatting.GRAY, "  " + b.file() + ":" + b.line() + " " + b.kind() + " " + b.name());
        }

        Map<String, List<MdpBlock>> missing = MdpRuntime.missingLists();
        if (!missing.isEmpty()) {
            send(src, ChatFormatting.YELLOW, "Missing list files: " + missing.size());
            for (Map.Entry<String, List<MdpBlock>> e : missing.entrySet())
                send(src, ChatFormatting.GRAY, "  " + e.getKey() + " (used by "
                    + e.getValue().stream().map(MdpBlock::key).distinct().collect(Collectors.joining(", ")) + ")");
        }
        return 1;
    }

    private static int status(CommandSourceStack src) {
        MdpRegistry r = MdpRuntime.registry();
        src.sendSuccess(() -> Component.literal("[mdp] " + r.files().size() + " file(s) in " + MdpRuntime.root().getFileName()), false);
        for (MdpFile f : r.files().values()) {
            if (f.disabledReason() != null) {
                send(src, ChatFormatting.RED, f.id() + " - header error: " + f.disabledReason());
                continue;
            }
            send(src, f.usable() ? ChatFormatting.GREEN : ChatFormatting.YELLOW,
                f.id() + " enabled=" + f.config().enabled() + " perm=" + f.config().permission() + " blocks=" + f.blocks().size()
                + (MdpRuntime.initStatus(src.getServer(), f.id()) != null ? " init=" + MdpRuntime.initStatus(src.getServer(), f.id()) : ""));
            for (MdpBlock b : f.blocks()) {
                if (!b.isActive()) {
                    send(src, ChatFormatting.RED, "  " + b.file() + ":" + b.line() + " " + b.kind() + " " + b.name() + " - " + b.disabledReason());
                } else if (b.body().isEmpty()) {
                    send(src, ChatFormatting.YELLOW, "  " + b.file() + ":" + b.line() + " " + b.kind() + " " + b.name() + " - no data");
                } else if (b.kind() == BlockKind.FUNC) {
                    int lvl = MdpRuntime.requiredPerm(src.getServer(), b);
                    String label = b.perm() != null && b.perm().serverOp() ? "op (" + lvl + ")" : String.valueOf(lvl);
                    send(src, ChatFormatting.GRAY, "  " + b.key() + " perm=" + label);
                }
            }
        }
        return 1;
    }

    private static void send(CommandSourceStack src, ChatFormatting color, String text) {
        src.sendSuccess(() -> Component.literal(text).withStyle(color), false);
    }
}
