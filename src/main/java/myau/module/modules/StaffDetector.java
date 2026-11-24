package myau.module.modules;

import com.mojang.authlib.GameProfile;
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.util.ChatUtil;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S04PacketEntityEquipment;
import net.minecraft.network.play.server.S0CPacketSpawnPlayer;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S19PacketEntityHeadLook;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S1BPacketEntityAttach;
import net.minecraft.network.play.server.S1CPacketEntityMetadata;
import net.minecraft.network.play.server.S1DPacketEntityEffect;
import net.minecraft.network.play.server.S1EPacketRemoveEntityEffect;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.network.play.server.S3EPacketTeams;
import net.minecraft.network.play.server.S49PacketUpdateEntityNBT;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Warns when a known staff member shows up (tab list, entity packets, spectator team) or when the
 * server probes the player (zero-horizontal velocity, vanished tab entries).
 * <p>
 * The staff names come from a local file, {@code ./config/Myau/staff/<server ip>.txt}, one name per
 * line; nothing is downloaded.
 */
public class StaffDetector extends Module {
    private static final File STAFF_DIR = new File("./config/Myau/staff/");
    private static final Pattern COLOR_CODE = Pattern.compile("(?i)§[0-9A-FK-OR]");
    private static final int MAX_PENDING = 1024;

    private static final String[] SERVER_NAMES = {
            "BlocksMC", "CubeCraft", "Gamster", "AgeraPvP", "HypeMC", "Hypixel", "SuperCraft",
            "PikaNetwork", "GommeHD", "CoralMC", "LibreCraft", "Originera", "OC-TC", "AssPixel"
    };
    private static final String[] SERVER_IPS = {
            "blocksmc.com", "cubecraft.net", "gamster.org", "agerapvp.club", "hypemc.pro", "hypixel.net", "supercraft.es",
            "pika-network.net", "gommehd.net", "coralmc.it", "librecraft.com", "mc.orea.asia", "oc.tc", "asspixel.net"
    };

    public final ModeProperty staffMode = new ModeProperty("staff-mode", 0, SERVER_NAMES);
    public final BooleanProperty tab = new BooleanProperty("tab", true);
    public final BooleanProperty packet = new BooleanProperty("packet", true);
    public final BooleanProperty velocity = new BooleanProperty("velocity", true);
    public final BooleanProperty vanish = new BooleanProperty("vanish", true);
    public final ModeProperty autoLeave = new ModeProperty("auto-leave", 0, new String[]{"OFF", "LEAVE", "LOBBY", "QUIT"},
            () -> this.tab.getValue() || this.packet.getValue());
    public final BooleanProperty spectator = new BooleanProperty("staff-spectator", false,
            () -> this.tab.getValue() || this.packet.getValue());
    public final BooleanProperty otherSpectator = new BooleanProperty("other-spectator", false,
            () -> this.tab.getValue() || this.packet.getValue());
    public final BooleanProperty inGame = new BooleanProperty("in-game", true, () -> this.autoLeave.getValue() != 0);
    public final ModeProperty warn = new ModeProperty("warn", 0, new String[]{"CHAT", "NOTIFICATION"});

    // Everything below is only touched on the client thread, except the pending queue.
    private final Set<String> checkedStaff = new HashSet<>();
    private final Set<String> checkedSpectator = new HashSet<>();
    private final Set<String> playersInSpectatorMode = new HashSet<>();
    private final Map<String, Boolean> staffCache = new HashMap<>();
    private final ArrayDeque<Packet<?>> pending = new ArrayDeque<>();
    private final Set<String> hintedIps = new HashSet<>();

    private Set<String> staffNames = Collections.emptySet();
    private String loadedIp;
    private String pendingMessage;
    private boolean attemptLeave;
    private boolean alertClearVanish;

    public StaffDetector() {
        super("StaffDetector", false);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.staffMode.getModeString()};
    }

    @Override
    public void onEnabled() {
        this.loadedIp = null;
        this.loadStaffList();
    }

    @Override
    public void onDisabled() {
        this.checkedStaff.clear();
        this.checkedSpectator.clear();
        this.playersInSpectatorMode.clear();
        this.clearPending();
        this.attemptLeave = false;
        this.alertClearVanish = false;
    }

    @Override
    public void verifyValue(String name) {
        if ("staff-mode".equals(name) && this.isEnabled()) {
            this.loadStaffList();
        }
    }

    // ── Staff list ──

    private String currentIp() {
        int index = this.staffMode.getValue();
        return index >= 0 && index < SERVER_IPS.length ? SERVER_IPS[index] : SERVER_IPS[0];
    }

    private void loadStaffList() {
        String ip = this.currentIp();
        if (ip.equals(this.loadedIp)) {
            return;
        }
        this.loadedIp = ip;
        this.staffCache.clear();
        this.checkedStaff.clear();
        File file = new File(STAFF_DIR, ip + ".txt");
        if (!file.isFile()) {
            this.staffNames = Collections.emptySet();
            STAFF_DIR.mkdirs();
            if (this.hintedIps.add(ip)) {
                this.say("&cNo staff list for &f" + ip + "&c. Put one name per line in &f" + file.getPath().replace('\\', '/'));
            }
            return;
        }
        Set<String> names = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    names.add(line);
                }
            }
            this.staffNames = names;
            this.say("&aSuccessfully loaded &9" + names.size() + " &astaff names.");
        } catch (Exception e) {
            this.staffNames = Collections.emptySet();
            this.say("&cFailed to load staff list. &9(" + e.getMessage() + ")");
        }
    }

    private boolean isStaff(String player) {
        if (player == null || this.staffNames.isEmpty()) {
            return false;
        }
        Boolean cached = this.staffCache.get(player);
        if (cached == null) {
            cached = Boolean.FALSE;
            for (String staff : this.staffNames) {
                if (player.contains(staff)) {
                    cached = Boolean.TRUE;
                    break;
                }
            }
            if (this.staffCache.size() > 4096) {
                this.staffCache.clear();
            }
            this.staffCache.put(player, cached);
        }
        return cached;
    }

    // ── Output ──

    /** Chat line; kept for the next tick when there is no player yet (module enabled from the config). */
    private void say(String message) {
        if (mc.thePlayer == null) {
            this.pendingMessage = message;
        } else {
            ChatUtil.sendFormatted(Myau.clientName + message);
        }
    }

    private void warn(String message) {
        if (this.warn.getValue() == 0) {
            ChatUtil.sendFormatted(Myau.clientName + message);
        } else {
            String plain = COLOR_CODE.matcher(message.replace('&', '§')).replaceAll("");
            HUD.notify("StaffDetector", plain, false);
        }
    }

    private static String ping(int responseTime) {
        if (responseTime > 0) {
            return "&e(" + responseTime + "ms)";
        }
        return responseTime == 0 ? "&a(Joined)" : "&c(Ping error)";
    }

    // ── Events ──

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        if (!this.isEnabled()) return;
        this.checkedStaff.clear();
        this.checkedSpectator.clear();
        this.playersInSpectatorMode.clear();
        this.alertClearVanish = false;
        this.clearPending();
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        final Packet<?> packet = event.getPacket();

        // This runs on the network thread: only sort the packets here, the checks run on the client thread.
        if (packet instanceof S3EPacketTeams) {
            if (this.spectator.getValue()) {
                final S3EPacketTeams teams = (S3EPacketTeams) packet;
                mc.addScheduledTask(() -> this.handleSpectatorTeam(teams));
            }
        } else if (packet instanceof S12PacketEntityVelocity) {
            if (this.velocity.getValue()) {
                S12PacketEntityVelocity motion = (S12PacketEntityVelocity) packet;
                // A regular knockback always has a horizontal part; a purely vertical nudge is a staff probe.
                if (motion.getEntityID() == mc.thePlayer.getEntityId() && motion.getMotionX() == 0 && motion.getMotionZ() == 0
                        && motion.getMotionY() / 8000.0 > 0.075) {
                    mc.addScheduledTask(this::handleVelocityProbe);
                }
            }
        } else if (packet instanceof S38PacketPlayerListItem) {
            if (this.vanish.getValue()) {
                final S38PacketPlayerListItem list = (S38PacketPlayerListItem) packet;
                mc.addScheduledTask(() -> this.handlePlayerList(list));
            }
        } else if (isEntityPacket(packet)) {
            if (this.tab.getValue() || this.packet.getValue()) {
                synchronized (this.pending) {
                    if (this.pending.size() < MAX_PENDING) {
                        this.pending.add(packet);
                    }
                }
            }
        }
    }

    private static boolean isEntityPacket(Packet<?> packet) {
        return packet instanceof S01PacketJoinGame || packet instanceof S0CPacketSpawnPlayer
                || packet instanceof S18PacketEntityTeleport || packet instanceof S1CPacketEntityMetadata
                || packet instanceof S1DPacketEntityEffect || packet instanceof S1EPacketRemoveEntityEffect
                || packet instanceof S19PacketEntityStatus || packet instanceof S19PacketEntityHeadLook
                || packet instanceof S49PacketUpdateEntityNBT || packet instanceof S1BPacketEntityAttach
                || packet instanceof S04PacketEntityEquipment;
    }

    private Entity resolve(Packet<?> packet) {
        if (packet instanceof S01PacketJoinGame) return mc.theWorld.getEntityByID(((S01PacketJoinGame) packet).getEntityId());
        if (packet instanceof S0CPacketSpawnPlayer) return mc.theWorld.getEntityByID(((S0CPacketSpawnPlayer) packet).getEntityID());
        if (packet instanceof S18PacketEntityTeleport) return mc.theWorld.getEntityByID(((S18PacketEntityTeleport) packet).getEntityId());
        if (packet instanceof S1CPacketEntityMetadata) return mc.theWorld.getEntityByID(((S1CPacketEntityMetadata) packet).getEntityId());
        if (packet instanceof S1DPacketEntityEffect) return mc.theWorld.getEntityByID(((S1DPacketEntityEffect) packet).getEntityId());
        if (packet instanceof S1EPacketRemoveEntityEffect) return mc.theWorld.getEntityByID(((S1EPacketRemoveEntityEffect) packet).getEntityId());
        if (packet instanceof S19PacketEntityStatus) return ((S19PacketEntityStatus) packet).getEntity(mc.theWorld);
        if (packet instanceof S19PacketEntityHeadLook) return ((S19PacketEntityHeadLook) packet).getEntity(mc.theWorld);
        if (packet instanceof S49PacketUpdateEntityNBT) return ((S49PacketUpdateEntityNBT) packet).getEntity(mc.theWorld);
        if (packet instanceof S1BPacketEntityAttach) return mc.theWorld.getEntityByID(((S1BPacketEntityAttach) packet).getEntityId());
        if (packet instanceof S04PacketEntityEquipment) return mc.theWorld.getEntityByID(((S04PacketEntityEquipment) packet).getEntityID());
        return null;
    }

    private void clearPending() {
        synchronized (this.pending) {
            this.pending.clear();
        }
    }

    /** Entity packets of the last tick: one tab scan, then one packet check per entity they referred to. */
    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (this.pendingMessage != null) {
            ChatUtil.sendFormatted(Myau.clientName + this.pendingMessage);
            this.pendingMessage = null;
        }
        List<Packet<?>> packets;
        synchronized (this.pending) {
            if (this.pending.isEmpty()) return;
            packets = new ArrayList<>(this.pending);
            this.pending.clear();
        }
        Set<Entity> entities = Collections.newSetFromMap(new IdentityHashMap<Entity, Boolean>());
        for (Packet<?> packet : packets) {
            Entity entity = this.resolve(packet);
            if (entity != null) {
                entities.add(entity);
            }
        }
        if (entities.isEmpty()) return;
        this.forgetLeftStaff();
        this.notifyStaffTab();
        for (Entity entity : entities) {
            this.notifyStaffPacket(entity);
        }
    }

    // ── Checks (client thread) ──

    /** Staff that left the tab list may be reported again when they come back. */
    private void forgetLeftStaff() {
        if (this.checkedStaff.isEmpty() || mc.getNetHandler() == null) return;
        Set<String> present = new HashSet<>();
        for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            GameProfile profile = info == null ? null : info.getGameProfile();
            if (profile != null && profile.getName() != null) {
                present.add(profile.getName());
            }
        }
        this.checkedStaff.retainAll(present);
    }

    private void notifyStaffTab() {
        if (!this.tab.getValue() || mc.getNetHandler() == null) return;
        Collection<NetworkPlayerInfo> infos = new ArrayList<>(mc.getNetHandler().getPlayerInfoMap());
        for (NetworkPlayerInfo info : infos) {
            GameProfile profile = info == null ? null : info.getGameProfile();
            String player = profile == null ? null : profile.getName();
            if (player == null || this.checkedStaff.contains(player) || !this.isStaff(player)) continue;
            this.warn("&c[STAFF] &d" + player + " &3is a staff &b(TAB) " + ping(info.getResponseTime()));
            this.attemptLeave = false;
            this.checkedStaff.add(player);
            this.autoLeave();
        }
    }

    private void notifyStaffPacket(Entity staff) {
        if (!this.packet.getValue() || !(staff instanceof EntityPlayer) || mc.getNetHandler() == null) return;
        EntityPlayer player = (EntityPlayer) staff;
        GameProfile profile = player.getGameProfile();
        String playerName = profile == null ? null : profile.getName();
        if (playerName == null || this.checkedStaff.contains(playerName) || !this.isStaff(playerName)) return;
        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(player.getUniqueID());
        this.warn("&c[STAFF] &d" + playerName + " &3is a staff &b(Packet) " + ping(info == null ? 0 : info.getResponseTime()));
        this.attemptLeave = false;
        this.checkedStaff.add(playerName);
        this.autoLeave();
    }

    /** BlocksMC puts spectating players (and staff in spectator mode) into the team "Z_Spectator". */
    private void handleSpectatorTeam(S3EPacketTeams packet) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        if (!"Z_Spectator".equalsIgnoreCase(packet.getName())) return;
        Collection<String> players = packet.getPlayers();
        if (players == null) return;

        for (String player : new ArrayList<>(players)) {
            if (this.checkedSpectator.contains(player)) continue;
            if (this.isStaff(player) || this.otherSpectator.getValue()) {
                this.notifySpectator(player);
            }
        }

        // Players that were in the team before and are missing now opened the spectator menu.
        Set<String> left = new HashSet<>(this.playersInSpectatorMode);
        left.removeAll(players);
        for (String player : left) {
            boolean staff = this.isStaff(player);
            if (staff) {
                ChatUtil.sendFormatted(Myau.clientName + "&c[STAFF] &d" + player + " &3is using the spectator menu &e(compass/left)");
            } else if (this.otherSpectator.getValue()) {
                ChatUtil.sendFormatted(Myau.clientName + "&d" + player + " &3is using the spectator menu &e(compass/left)");
            }
            this.checkedSpectator.remove(player);
        }

        this.playersInSpectatorMode.clear();
        this.playersInSpectatorMode.addAll(players);
    }

    private void notifySpectator(String player) {
        boolean staff = this.isStaff(player);
        if (staff) {
            this.warn("&c[STAFF] &d" + player + " &3is a spectator");
        } else if (this.otherSpectator.getValue()) {
            this.warn("&d" + player + " &3is a spectator");
        }
        this.attemptLeave = false;
        this.checkedSpectator.add(player);
        if (staff) {
            this.autoLeave();
        }
    }

    private void handleVelocityProbe() {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        this.attemptLeave = false;
        this.autoLeave();
        this.warn("&3Staff is Watching");
    }

    /** A latency update lists every player the server knows; fewer tab entries on our side means someone is hidden. */
    private void handlePlayerList(S38PacketPlayerListItem packet) {
        if (!this.isEnabled() || !this.vanish.getValue() || mc.thePlayer == null || mc.getNetHandler() == null) return;
        if (packet.getAction() != S38PacketPlayerListItem.Action.UPDATE_LATENCY) return;
        int listed = mc.getNetHandler().getPlayerInfoMap().size();
        if (packet.getEntries().size() != listed) {
            this.warn("&aA player might be vanished.");
            this.alertClearVanish = false;
        } else if (!this.alertClearVanish) {
            this.warn("&cNo players are vanished");
            this.alertClearVanish = true;
        }
    }

    private void autoLeave() {
        if (mc.thePlayer == null || mc.theWorld == null) return;
        ItemStack firstSlot = mc.thePlayer.inventory.mainInventory[0];
        if (firstSlot == null) return;
        // A compass or bow in the first slot means a lobby / waiting room on the supported servers.
        if (this.inGame.getValue() && (firstSlot.getItem() == Items.compass || firstSlot.getItem() == Items.bow)) {
            return;
        }
        if (this.attemptLeave || this.autoLeave.getValue() == 0) return;
        switch (this.autoLeave.getValue()) {
            case 1:
                mc.thePlayer.sendChatMessage("/leave");
                break;
            case 2:
                mc.thePlayer.sendChatMessage("/lobby");
                break;
            case 3:
                mc.theWorld.sendQuittingDisconnectingPacket();
                break;
            default:
                break;
        }
        this.attemptLeave = true;
    }
}
