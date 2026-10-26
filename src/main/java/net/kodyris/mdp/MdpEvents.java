package net.kodyris.mdp;

import net.kodyris.mdp.runtime.MdpCommand;
import net.kodyris.mdp.runtime.MdpRuntime;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

/** Forge-bus hooks. All logic lives in MdpRuntime. */
@Mod.EventBusSubscriber(modid = MdpMod.MODID)
public final class MdpEvents {
    private MdpEvents() {}

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        MdpCommand.register(e.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent e) {
        MdpRuntime.reload(e.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent e) {
        MdpRuntime.clear();
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) MdpRuntime.tick(server);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) MdpRuntime.playerJoined(p);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        MdpRuntime.playerLeft(e.getEntity().getUUID());
    }
}
