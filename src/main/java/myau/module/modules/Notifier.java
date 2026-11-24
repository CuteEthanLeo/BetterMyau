package myau.module.modules;

import com.mojang.authlib.GameProfile;
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.util.ChatUtil;
import myau.util.TeamUtil;
import net.minecraft.block.BlockTNT;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemFireball;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemTool;
import net.minecraft.network.play.server.S38PacketPlayerListItem;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Chat notes about other players: joins and leaves (tab list), deaths, and players that
 * hold a fireball / TNT or a tool.
 */
public class Notifier extends Module {
    public final BooleanProperty onPlayerJoin = new BooleanProperty("join", true);
    public final BooleanProperty onPlayerLeft = new BooleanProperty("left", true);
    public final BooleanProperty onPlayerDeath = new BooleanProperty("death", true);
    public final BooleanProperty onHeldExplosive = new BooleanProperty("held-explosive", true);
    public final BooleanProperty onPlayerTool = new BooleanProperty("held-tools", false);
    public final IntProperty warnDelay = new IntProperty("warn-delay", 5000, 1000, 50000,
            () -> this.onPlayerDeath.getValue() || this.onHeldExplosive.getValue() || this.onPlayerTool.getValue());

    // Client thread only
    private final Map<UUID, Long> recentlyWarned = new HashMap<>();

    public Notifier() {
        super("Notifier", false);
    }

    @Override
    public void onDisabled() {
        this.recentlyWarned.clear();
    }

    private static void say(String message) {
        ChatUtil.sendFormatted(Myau.clientName + message);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        long now = System.currentTimeMillis();
        for (EntityPlayer entity : mc.theWorld.playerEntities) {
            if (entity == null || entity == mc.thePlayer || entity.getUniqueID().equals(mc.thePlayer.getUniqueID())) continue;
            if (TeamUtil.isBot(entity)) continue;

            Long lastNotified = this.recentlyWarned.get(entity.getUniqueID());
            if (lastNotified != null && now - lastNotified < this.warnDelay.getValue()) continue;

            int distance = Math.round(mc.thePlayer.getDistanceToEntity(entity));
            ItemStack held = entity.getHeldItem();
            Item item = held == null ? null : held.getItem();

            if (this.onPlayerDeath.getValue() && (entity.isDead || !entity.isEntityAlive())) {
                say("&7" + entity.getName() + " has &cdied &a(" + distance + "m)");
                this.recentlyWarned.put(entity.getUniqueID(), now);
            } else if (this.onHeldExplosive.getValue() && item instanceof ItemFireball) {
                say("&7" + entity.getName() + " is holding a &eFireball &a(" + distance + "m)");
                this.recentlyWarned.put(entity.getUniqueID(), now);
            } else if (this.onHeldExplosive.getValue() && item instanceof ItemBlock && ((ItemBlock) item).getBlock() instanceof BlockTNT) {
                say("&7" + entity.getName() + " is holding &eTNT &a(" + distance + "m)");
                this.recentlyWarned.put(entity.getUniqueID(), now);
            } else if (this.onPlayerTool.getValue() && item instanceof ItemTool) {
                say("&7" + entity.getName() + " is holding a &b" + held.getDisplayName() + " &a(" + distance + "m)");
                this.recentlyWarned.put(entity.getUniqueID(), now);
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (!(event.getPacket() instanceof S38PacketPlayerListItem)) return;
        final S38PacketPlayerListItem packet = (S38PacketPlayerListItem) event.getPacket();
        S38PacketPlayerListItem.Action action = packet.getAction();
        if (action != S38PacketPlayerListItem.Action.ADD_PLAYER && action != S38PacketPlayerListItem.Action.REMOVE_PLAYER) return;
        // Network thread: the world is read on the client thread. The task runs before the packet
        // itself is applied, so a leaving player can still be looked up.
        mc.addScheduledTask(() -> this.handlePlayerList(packet));
    }

    private void handlePlayerList(S38PacketPlayerListItem packet) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        // Skip the tab list the server sends right after a join
        if (mc.thePlayer.ticksExisted < 50) return;

        AntiBot antiBot = Myau.moduleManager == null ? null : (AntiBot) Myau.moduleManager.modules.get(AntiBot.class);
        UUID self = mc.thePlayer.getUniqueID();

        if (this.onPlayerJoin.getValue() && packet.getAction() == S38PacketPlayerListItem.Action.ADD_PLAYER) {
            for (S38PacketPlayerListItem.AddPlayerData data : packet.getEntries()) {
                GameProfile profile = data == null ? null : data.getProfile();
                if (profile == null || profile.getId() == null || profile.getName() == null) continue;
                if (profile.getId().equals(self) || antiBot != null && antiBot.isBotId(profile.getId())) continue;
                say("&7" + profile.getName() + " &ajoined the game.");
            }
        }

        if (this.onPlayerLeft.getValue() && packet.getAction() == S38PacketPlayerListItem.Action.REMOVE_PLAYER) {
            for (S38PacketPlayerListItem.AddPlayerData data : packet.getEntries()) {
                GameProfile removed = data == null ? null : data.getProfile();
                if (removed == null || removed.getId() == null) continue;
                // Only players that are loaded in the world: a network-wide tab list would flood the chat.
                EntityPlayer player = mc.theWorld.getPlayerEntityByUUID(removed.getId());
                GameProfile profile = player == null ? null : player.getGameProfile();
                if (profile == null || profile.getId() == null) continue;
                if (profile.getId().equals(self) || antiBot != null && antiBot.isBotId(profile.getId())) continue;
                say("&7" + profile.getName() + " &cleft the game.");
            }
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        if (!this.isEnabled()) return;
        this.recentlyWarned.clear();
    }
}
